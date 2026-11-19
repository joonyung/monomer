import { spawn } from "node:child_process";
import { Buffer } from "node:buffer";
import { createHash } from "node:crypto";
import { Resolver } from "node:dns/promises";
import { accessSync, constants, readFileSync, readdirSync } from "node:fs";
import process from "node:process";
import { clearTimeout, setTimeout } from "node:timers";
import {
  ISOLATION_SCHEMA_VERSION,
  MAX_RUN_REQUEST_BYTES,
  sha256Hex,
  validateRunRequest,
} from "./protocol.mjs";

const RUNNER_BROWSER_DEADLINE_MS = 15_000;
const CDP_COMMAND_DEADLINE_MS = 15_000;
const SOURCE_COMMAND_DEADLINE_MS = 120_000;
const PROBE_DEADLINE_MS = 1_200;

function isoNow() {
  return new Date().toISOString();
}

function elapsedMs(startedAtNanoseconds) {
  return Number(process.hrtime.bigint() - startedAtNanoseconds) / 1_000_000;
}

async function readStandardInput() {
  const chunks = [];
  let totalBytes = 0;
  for await (const chunk of process.stdin) {
    totalBytes += chunk.length;
    if (totalBytes > MAX_RUN_REQUEST_BYTES) throw new Error(`Input exceeds ${MAX_RUN_REQUEST_BYTES} bytes`);
    chunks.push(chunk);
  }
  if (totalBytes === 0) throw new Error("A generation request is required on stdin");
  return Buffer.concat(chunks).toString("utf8");
}

function collectChromiumCandidates(directoryPath, remainingDepth, candidates) {
  if (remainingDepth < 0) return;
  let entries;
  try {
    entries = readdirSync(directoryPath, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    const entryPath = `${directoryPath}/${entry.name}`;
    if (entry.isDirectory()) {
      collectChromiumCandidates(entryPath, remainingDepth - 1, candidates);
    } else if (entry.isFile() && (entry.name === "chrome" || entry.name === "headless_shell")) {
      candidates.push(entryPath);
    }
  }
}

function findChromiumExecutable() {
  const candidates = [];
  if (process.env.CHROMIUM_EXECUTABLE_PATH) candidates.push(process.env.CHROMIUM_EXECUTABLE_PATH);
  candidates.push("/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome");
  collectChromiumCandidates("/ms-playwright", 6, candidates);
  const rankedCandidates = [...new Set(candidates)].sort((left, right) => {
    const rank = (candidate) => candidate.includes("chrome-linux/chrome") ? 0 : candidate.includes("headless_shell") ? 1 : 2;
    return rank(left) - rank(right) || left.localeCompare(right);
  });
  for (const candidate of rankedCandidates) {
    try {
      accessSync(candidate, constants.X_OK);
      return candidate;
    } catch {
      continue;
    }
  }
  throw new Error("Chromium executable was not found in the pinned Playwright image");
}

function startChromium(executable, generationId) {
  if (!new Set(["enabled", "disabled"]).has(process.env.MONOMER_CHROMIUM_SANDBOX)) {
    throw new Error("MONOMER_CHROMIUM_SANDBOX must explicitly select enabled or disabled");
  }
  const commandLineSandboxDisabled = process.env.MONOMER_CHROMIUM_SANDBOX === "disabled";
  const stderrLines = [];
  const chromiumArguments = [
    "--headless=new",
    "--disable-background-networking",
    "--disable-breakpad",
    "--disable-component-update",
    "--disable-crash-reporter",
    "--disable-default-apps",
    "--disable-extensions",
    "--disable-features=Translate,MediaRouter,OptimizationHints,AutofillServerCommunication",
    "--disable-sync",
    "--hide-scrollbars",
    "--metrics-recording-only",
    "--mute-audio",
    "--no-default-browser-check",
    "--no-first-run",
    "--password-store=basic",
    "--remote-debugging-address=127.0.0.1",
    "--remote-debugging-port=9222",
    `--user-data-dir=/tmp/chromium-${generationId}`,
    "--window-size=800,600",
    "about:blank",
  ];
  if (commandLineSandboxDisabled) chromiumArguments.splice(1, 0, "--no-sandbox", "--disable-setuid-sandbox");
  const chromium = spawn(executable, chromiumArguments, { stdio: ["ignore", "ignore", "pipe"] });
  chromium.stderr.setEncoding("utf8");
  chromium.stderr.on("data", (chunk) => {
    stderrLines.push(chunk);
    if (stderrLines.length > 32) stderrLines.shift();
  });
  return { chromium, stderrLines, commandLineSandboxDisabled };
}

async function fetchJson(url, requestInit) {
  const response = await globalThis.fetch(url, requestInit);
  if (!response.ok) throw new Error(`${requestInit.method} ${url} returned ${response.status}`);
  return response.json();
}

async function waitForBrowserTarget(chromium, stderrLines) {
  const deadlineAt = Date.now() + RUNNER_BROWSER_DEADLINE_MS;
  while (Date.now() < deadlineAt) {
    if (chromium.exitCode !== null) {
      throw new Error(`Chromium exited before CDP became ready: ${stderrLines.join("").slice(-2000)}`);
    }
    try {
      const version = await fetchJson("http://127.0.0.1:9222/json/version", { method: "GET" });
      let targets = await fetchJson("http://127.0.0.1:9222/json/list", { method: "GET" });
      let target = targets.find((candidate) => candidate.type === "page" && candidate.webSocketDebuggerUrl);
      if (!target) {
        await fetchJson("http://127.0.0.1:9222/json/new?about:blank", { method: "PUT" });
        targets = await fetchJson("http://127.0.0.1:9222/json/list", { method: "GET" });
        target = targets.find((candidate) => candidate.type === "page" && candidate.webSocketDebuggerUrl);
      }
      if (target) return { version, target };
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 75));
    }
  }
  throw new Error(`Chromium CDP did not become ready: ${stderrLines.join("").slice(-2000)}`);
}

class CdpClient {
  constructor(webSocketUrl) {
    if (typeof globalThis.WebSocket !== "function") throw new Error("The pinned image Node runtime does not expose WebSocket");
    this.webSocket = new globalThis.WebSocket(webSocketUrl);
    this.nextCommandId = 1;
    this.pendingCommands = new Map();
  }

  async connect() {
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("CDP WebSocket connection timed out")), CDP_COMMAND_DEADLINE_MS);
      this.webSocket.addEventListener("open", () => {
        clearTimeout(timeout);
        resolve();
      }, { once: true });
      this.webSocket.addEventListener("error", () => {
        clearTimeout(timeout);
        reject(new Error("CDP WebSocket connection failed"));
      }, { once: true });
    });
    this.webSocket.addEventListener("message", (event) => {
      const message = JSON.parse(String(event.data));
      if (!message.id) return;
      const pending = this.pendingCommands.get(message.id);
      if (!pending) return;
      this.pendingCommands.delete(message.id);
      clearTimeout(pending.timeout);
      if (message.error) pending.reject(new Error(`${pending.method}: ${message.error.message}`));
      else if (message.result?.exceptionDetails) pending.reject(new Error(`${pending.method}: ${message.result.exceptionDetails.text}`));
      else pending.resolve(message.result);
    });
    this.webSocket.addEventListener("close", () => {
      for (const pending of this.pendingCommands.values()) {
        clearTimeout(pending.timeout);
        pending.reject(new Error(`CDP closed while waiting for ${pending.method}`));
      }
      this.pendingCommands.clear();
    });
  }

  send(method, params, deadlineMs) {
    const id = this.nextCommandId;
    this.nextCommandId += 1;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pendingCommands.delete(id);
        reject(new Error(`${method} exceeded ${deadlineMs}ms`));
      }, deadlineMs);
      this.pendingCommands.set(id, { method, resolve, reject, timeout });
      this.webSocket.send(JSON.stringify({ id, method, params }));
    });
  }

  close() {
    this.webSocket.close();
  }
}

async function evaluateValue(cdpClient, expression, deadlineMs) {
  const response = await cdpClient.send("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
    userGesture: false,
  }, deadlineMs);
  if (response.exceptionDetails) {
    const description = response.exceptionDetails.exception?.description ?? response.exceptionDetails.text;
    throw new Error(`Browser evaluation failed: ${description}`);
  }
  return response.result?.value;
}

async function readChromiumSandboxStatus(cdpClient, commandLineSandboxDisabled) {
  await cdpClient.send("Page.navigate", { url: "chrome://sandbox/" }, CDP_COMMAND_DEADLINE_MS);
  let statusText = "";
  const deadlineAt = Date.now() + 5_000;
  while (Date.now() < deadlineAt) {
    statusText = await evaluateValue(
      cdpClient,
      "document.body ? document.body.innerText : ''",
      CDP_COMMAND_DEADLINE_MS,
    );
    if (typeof statusText === "string" && statusText.trim()) break;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  const normalizedStatus = statusText.toLowerCase();
  const sandbox = {
    commandLineDisabled: commandLineSandboxDisabled,
    chromiumSandbox: !commandLineSandboxDisabled && normalizedStatus.includes("you are adequately sandboxed"),
    namespaceSandboxEnabled: /namespace[^\n]*(yes|enabled)/i.test(statusText),
    seccompBpfSandboxEnabled: /seccomp-bpf[^\n]*(yes|enabled)/i.test(statusText),
    statusText: statusText.trim().slice(0, 4096),
  };
  await cdpClient.send("Page.navigate", { url: "about:blank" }, CDP_COMMAND_DEADLINE_MS);
  const navigationDeadlineAt = Date.now() + 5_000;
  while (Date.now() < navigationDeadlineAt) {
    const location = await evaluateValue(cdpClient, "location.href", CDP_COMMAND_DEADLINE_MS);
    if (location === "about:blank") return sandbox;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("Chromium did not leave the internal sandbox status page");
}

function renderExpression(renderSource) {
  return `
    (async () => {
      const executionStartedAt = new Date().toISOString();
      const executionStarted = performance.now();
      const chartElement = document.getElementById("chart");
      const chart = echarts.init(chartElement, null, { renderer: "canvas", width: 800, height: 600 });
      let finishedHandler;
      const finished = new Promise((resolve) => {
        finishedHandler = () => {
          chart.off("finished", finishedHandler);
          resolve({ paintReadyAt: new Date().toISOString(), paintReadyPerformance: performance.now() });
        };
        chart.on("finished", finishedHandler);
      });
      const renderFunction = new Function("chart", "echarts", "context", ${JSON.stringify(renderSource)});
      await renderFunction(chart, echarts, {
        dataset: [
          { month: "2026-01", revenue: 120, target: 100 },
          { month: "2026-02", revenue: 180, target: 145 },
          { month: "2026-03", revenue: 150, target: 165 },
          { month: "2026-04", revenue: 240, target: 210 }
        ],
        parameters: { fiscalYear: 2026 },
        variables: { segment: "Enterprise", revision: 7 }
      });
      const paint = await Promise.race([
        finished,
        new Promise((_, reject) => setTimeout(() => reject(new Error("ECharts finished event deadline exceeded")), 10000))
      ]);
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const canvas = chartElement.querySelector("canvas");
      if (!canvas) throw new Error("ECharts did not create a canvas");
      const context2d = canvas.getContext("2d", { willReadFrequently: true });
      const pixels = context2d.getImageData(0, 0, canvas.width, canvas.height).data;
      let nonTransparentSampleCount = 0;
      const sampledColors = new Set();
      for (let pixelIndex = 0; pixelIndex < pixels.length; pixelIndex += 64) {
        if (pixels[pixelIndex + 3] > 0) {
          nonTransparentSampleCount += 1;
          sampledColors.add(pixels[pixelIndex] + ":" + pixels[pixelIndex + 1] + ":" + pixels[pixelIndex + 2] + ":" + pixels[pixelIndex + 3]);
        }
      }
      if (nonTransparentSampleCount === 0 || sampledColors.size < 2) throw new Error("ECharts canvas did not contain painted chart pixels");
      return {
        executionStartedAt,
        paintReadyAt: paint.paintReadyAt,
        paintReadyMs: paint.paintReadyPerformance - executionStarted,
        canvasWidth: canvas.width,
        canvasHeight: canvas.height,
        nonTransparentSampleCount,
        distinctSampledColors: sampledColors.size
      };
    })()
  `;
}

function browserDenialProbeExpression(probeDeadlineMs) {
  return `
    (async () => {
      const deadlineMs = ${probeDeadlineMs};
      const measure = async (operation) => {
        const startedAt = performance.now();
        try {
          const outcome = await operation();
          return { attempted: true, denied: false, detail: String(outcome), elapsedMs: performance.now() - startedAt };
        } catch (error) {
          return { attempted: true, denied: true, detail: error instanceof Error ? error.name + ": " + error.message : String(error), elapsedMs: performance.now() - startedAt };
        }
      };
      const timeout = (name) => new Promise((_, reject) => setTimeout(() => reject(new Error(name + " deadline exceeded")), deadlineMs));
      const network = measure(async () => {
        const controller = new AbortController();
        const abort = setTimeout(() => controller.abort(), deadlineMs);
        try {
          const response = await fetch("https://example.com/monomer-network-probe", { cache: "no-store", signal: controller.signal });
          return "fetch succeeded with status " + response.status;
        } finally {
          clearTimeout(abort);
        }
      });
      const websocket = measure(() => Promise.race([
        new Promise((resolve, reject) => {
          const socket = new WebSocket("wss://example.com/monomer-websocket-probe");
          socket.addEventListener("open", () => { socket.close(); resolve("WebSocket opened"); }, { once: true });
          socket.addEventListener("error", () => reject(new Error("WebSocket connection rejected")), { once: true });
        }),
        timeout("WebSocket")
      ]));
      const dynamicImport = measure(() => Promise.race([
        import("https://example.com/monomer-dynamic-import-probe.mjs").then(() => "dynamic import succeeded"),
        timeout("dynamic import")
      ]));
      const image = measure(() => Promise.race([
        new Promise((resolve, reject) => {
          const imageElement = new Image();
          imageElement.addEventListener("load", () => resolve("image loaded"), { once: true });
          imageElement.addEventListener("error", () => reject(new Error("image load rejected")), { once: true });
          imageElement.src = "https://example.com/monomer-image-probe.png";
        }),
        timeout("image")
      ]));
      const [networkResult, websocketResult, dynamicImportResult, imageResult] = await Promise.all([network, websocket, dynamicImport, image]);
      return { network: networkResult, websocket: websocketResult, dynamic_import: dynamicImportResult, image: imageResult };
    })()
  `;
}

async function runDnsDenialProbe() {
  const startedAt = process.hrtime.bigint();
  const resolver = new Resolver();
  resolver.setServers(["8.8.8.8"]);
  let timeout;
  try {
    await Promise.race([
      resolver.resolve4("example.com").then((addresses) => {
        throw new Error(`DNS resolved ${addresses.join(",")}`);
      }),
      new Promise((_, reject) => {
        timeout = setTimeout(() => {
          resolver.cancel();
          reject(new Error("DNS deadline exceeded"));
        }, PROBE_DEADLINE_MS);
      }),
    ]);
    return { attempted: true, denied: false, detail: "DNS unexpectedly succeeded", elapsedMs: elapsedMs(startedAt) };
  } catch (error) {
    const detail = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
    const unexpectedlyResolved = detail.startsWith("Error: DNS resolved ");
    return { attempted: true, denied: !unexpectedlyResolved, detail, elapsedMs: elapsedMs(startedAt) };
  } finally {
    clearTimeout(timeout);
  }
}

async function terminateChromium(chromium) {
  if (chromium.exitCode !== null) return;
  chromium.kill("SIGTERM");
  const exited = await Promise.race([
    new Promise((resolve) => chromium.once("exit", () => resolve(true))),
    new Promise((resolve) => setTimeout(() => resolve(false), 750)),
  ]);
  if (!exited) chromium.kill("SIGKILL");
}

async function runCompletedGeneration(request, chromiumContext, timestamps) {
  const { chromium, executable, version, target } = chromiumContext;
  const cdpClient = new CdpClient(target.webSocketDebuggerUrl);
  await cdpClient.connect();
  timestamps.browserConnectedAt = isoNow();
  try {
    await cdpClient.send("Page.enable", {}, CDP_COMMAND_DEADLINE_MS);
    await cdpClient.send("Runtime.enable", {}, CDP_COMMAND_DEADLINE_MS);
    const sandbox = await readChromiumSandboxStatus(cdpClient, chromiumContext.commandLineSandboxDisabled);
    await cdpClient.send("Emulation.setDeviceMetricsOverride", {
      width: 800,
      height: 600,
      deviceScaleFactor: 1,
      mobile: false,
    }, CDP_COMMAND_DEADLINE_MS);
    const frameTree = await cdpClient.send("Page.getFrameTree", {}, CDP_COMMAND_DEADLINE_MS);
    await cdpClient.send("Page.setDocumentContent", {
      frameId: frameTree.frameTree.frame.id,
      html: "<!doctype html><html><head><meta charset=\"utf-8\"><style>html,body,#chart{width:800px;height:600px;margin:0;overflow:hidden;background:#fff}</style></head><body><div id=\"chart\"></div></body></html>",
    }, CDP_COMMAND_DEADLINE_MS);
    const echartsBundle = readFileSync("/runner/echarts.min.js", "utf8");
    await evaluateValue(cdpClient, echartsBundle, CDP_COMMAND_DEADLINE_MS);
    const browserRender = await evaluateValue(cdpClient, renderExpression(request.scenario.renderSource), SOURCE_COMMAND_DEADLINE_MS);
    timestamps.executionStartedAt = browserRender.executionStartedAt;
    timestamps.paintReadyAt = browserRender.paintReadyAt;
    const screenshot = await cdpClient.send("Page.captureScreenshot", {
      format: "png",
      fromSurface: true,
      captureBeyondViewport: false,
    }, CDP_COMMAND_DEADLINE_MS);
    timestamps.screenshotCapturedAt = isoNow();
    const screenshotBuffer = Buffer.from(screenshot.data, "base64");
    const browserProbes = await evaluateValue(
      cdpClient,
      browserDenialProbeExpression(PROBE_DEADLINE_MS),
      CDP_COMMAND_DEADLINE_MS,
    );
    const dnsProbe = await runDnsDenialProbe();
    const ipc = request.scenario.kind === "ipc" ? {
      payloadBytes: Buffer.byteLength(request.scenario.payload, "utf8"),
      payloadSha256: sha256Hex(request.scenario.payload),
    } : null;
    timestamps.containerCompletedAt = isoNow();
    return {
      schemaVersion: ISOLATION_SCHEMA_VERSION,
      kind: "container_generation_result",
      generationId: request.generationId,
      scenarioKind: request.scenario.kind,
      status: "completed",
      timestamps,
      chromium: {
        executable,
        version: version.Browser,
        pid: chromium.pid,
        runningAsUid: process.getuid(),
        sandbox,
      },
      render: {
        paintReadyMs: browserRender.paintReadyMs,
        pngSha256: createHash("sha256").update(screenshotBuffer).digest("hex"),
        pngBytes: screenshotBuffer.length,
        canvasWidth: browserRender.canvasWidth,
        canvasHeight: browserRender.canvasHeight,
        nonTransparentSampleCount: browserRender.nonTransparentSampleCount,
        distinctSampledColors: browserRender.distinctSampledColors,
      },
      denialProbes: { ...browserProbes, dns: dnsProbe },
      ipc,
    };
  } finally {
    cdpClient.close();
  }
}

function exhaustCgroupMemory() {
  const retainedChunks = [];
  for (;;) {
    const chunk = Buffer.allocUnsafe(8 * 1024 * 1024);
    chunk.fill(0xa5);
    retainedChunks.push(chunk);
  }
}

async function main() {
  const timestamps = { containerStartedAt: isoNow() };
  let chromium;
  try {
    const requestText = await readStandardInput();
    const request = validateRunRequest(JSON.parse(requestText));
    const executable = findChromiumExecutable();
    const started = startChromium(executable, request.generationId);
    chromium = started.chromium;
    timestamps.chromiumStartedAt = isoNow();
    const { version, target } = await waitForBrowserTarget(chromium, started.stderrLines);
    const chromiumContext = {
      chromium,
      executable,
      version,
      target,
      commandLineSandboxDisabled: started.commandLineSandboxDisabled,
    };
    process.stdout.write(`${JSON.stringify({
      schemaVersion: ISOLATION_SCHEMA_VERSION,
      kind: "container_generation_ready",
      generationId: request.generationId,
      at: isoNow(),
    })}\n`);
    if (request.scenario.kind === "memory_oom") exhaustCgroupMemory();
    if (request.scenario.kind === "crash") process.kill(process.pid, "SIGKILL");
    const result = await runCompletedGeneration(request, chromiumContext, timestamps);
    process.stdout.write(`${JSON.stringify(result)}\n`);
  } catch (error) {
    process.stdout.write(`${JSON.stringify({
      schemaVersion: ISOLATION_SCHEMA_VERSION,
      kind: "container_generation_error",
      status: "failed",
      message: error instanceof Error ? error.message : String(error),
      timestamps: { ...timestamps, containerFailedAt: isoNow() },
    })}\n`);
    process.exitCode = 1;
  } finally {
    if (chromium) await terminateChromium(chromium);
  }
}

await main();
