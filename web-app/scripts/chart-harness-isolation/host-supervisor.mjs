#!/usr/bin/env node

import { spawn } from "node:child_process";
import { Buffer } from "node:buffer";
import { randomUUID } from "node:crypto";
import { mkdir, readFile, rename, unlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { clearTimeout, setTimeout } from "node:timers";
import { fileURLToPath } from "node:url";
import { buildDockerRunInvocation } from "./docker-arguments.mjs";
import {
  IPC_ONE_MIB_BYTES,
  IPC_TEN_MIB_BYTES,
  ISOLATION_LIMITS,
  ISOLATION_SCHEMA_VERSION,
  PLAYWRIGHT_IMAGE,
  SCENARIO_DEADLINES_MS,
  redactRequestForDisplay,
  sha256Hex,
  validateContainerRunnerResult,
  validateRunRequest,
  validateSuccessfulExperimentArtifact,
} from "./protocol.mjs";

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const CONTAINER_RUNNER_PATH = path.join(SCRIPT_DIRECTORY, "container-runner.mjs");
const PROTOCOL_PATH = path.join(SCRIPT_DIRECTORY, "protocol.mjs");
const SECCOMP_PROFILE_PATH = path.join(SCRIPT_DIRECTORY, "seccomp-profile.json");
const ECHARTS_BUNDLE_PATH = path.resolve(SCRIPT_DIRECTORY, "../../node_modules/echarts/dist/echarts.min.js");
const DEFAULT_RESULT_PATH = path.join(SCRIPT_DIRECTORY, "isolation-experiment-result.json");
const CONTAINER_BOOTSTRAP_DEADLINE_MS = 30_000;
const COMMAND_CAPTURE_LIMIT_BYTES = 2 * 1024 * 1024;

const REPRESENTATIVE_RENDER_SOURCE = `
  chart.setOption({
    animation: false,
    backgroundColor: "#ffffff",
    title: { text: "FY " + context.parameters.fiscalYear + " Revenue", left: "center" },
    tooltip: { trigger: "axis" },
    legend: { data: ["Revenue", "Target"], top: 36 },
    dataset: { source: context.dataset },
    xAxis: { type: "category" },
    yAxis: { type: "value" },
    series: [
      { name: "Revenue", type: "bar", encode: { x: "month", y: "revenue" }, itemStyle: { color: "#2466c3" } },
      { name: "Target", type: "line", encode: { x: "month", y: "target" }, lineStyle: { color: "#00a67e", width: 3 } }
    ]
  });
`;

export class SupervisorError extends Error {
  constructor(code, message, detail) {
    super(message);
    this.name = "SupervisorError";
    this.code = code;
    this.detail = detail;
  }
}

function isoNow() {
  return new Date().toISOString();
}

function boundedAppend(currentValue, appendedValue, maximumBytes) {
  const combinedValue = currentValue + appendedValue;
  if (Buffer.byteLength(combinedValue, "utf8") <= maximumBytes) return combinedValue;
  return combinedValue.slice(-maximumBytes);
}

export async function runCommandCapture(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout = boundedAppend(stdout, chunk, COMMAND_CAPTURE_LIMIT_BYTES);
    });
    child.stderr.on("data", (chunk) => {
      stderr = boundedAppend(stderr, chunk, COMMAND_CAPTURE_LIMIT_BYTES);
    });
    child.once("error", reject);
    child.once("close", (exitCode, signal) => resolve({ exitCode, signal, stdout, stderr }));
  });
}

function parseJsonOutput(commandResult, errorCode, message) {
  if (commandResult.exitCode !== 0) {
    throw new SupervisorError(errorCode, message, commandResult.stderr.trim() || commandResult.stdout.trim());
  }
  try {
    return JSON.parse(commandResult.stdout);
  } catch (error) {
    throw new SupervisorError(errorCode, message, error instanceof Error ? error.message : String(error));
  }
}

export async function inspectDockerBootstrap(dockerBinary, image) {
  let dockerVersionResult;
  try {
    dockerVersionResult = await runCommandCapture(dockerBinary, ["version", "--format", "{{json .Server}}"]);
  } catch (error) {
    throw new SupervisorError(
      "DOCKER_UNAVAILABLE",
      "Docker CLI could not be started",
      error instanceof Error ? error.message : String(error),
    );
  }
  const server = parseJsonOutput(dockerVersionResult, "DOCKER_DAEMON_UNAVAILABLE", "Docker daemon is not available");
  const imageInspectResult = await runCommandCapture(dockerBinary, ["image", "inspect", image]);
  if (imageInspectResult.exitCode !== 0) {
    throw new SupervisorError(
      "IMAGE_NOT_AVAILABLE",
      `Pinned image ${image} is not available locally; no experiment was run and no result was persisted`,
      `Bootstrap explicitly with: docker pull ${image}`,
    );
  }
  const inspectedImages = parseJsonOutput(imageInspectResult, "IMAGE_INSPECT_FAILED", "Pinned image inspection failed");
  if (!Array.isArray(inspectedImages) || inspectedImages.length !== 1) {
    throw new SupervisorError("IMAGE_INSPECT_FAILED", "Pinned image inspection returned an unexpected result", imageInspectResult.stdout);
  }
  const inspectedImage = inspectedImages[0];
  return {
    server,
    image: {
      reference: image,
      id: inspectedImage.Id,
      repoDigests: inspectedImage.RepoDigests ?? [],
      architecture: inspectedImage.Architecture,
      os: inspectedImage.Os,
      inspectedAt: isoNow(),
    },
  };
}

function generationRequest(generationId, scenario) {
  return validateRunRequest({ schemaVersion: ISOLATION_SCHEMA_VERSION, generationId, scenario });
}

export function buildExperimentRequests(runId) {
  const prefix = `isolation-${runId}`;
  return [
    generationRequest(`${prefix}-render`, { kind: "render", renderSource: REPRESENTATIVE_RENDER_SOURCE }),
    generationRequest(`${prefix}-infinite-loop`, { kind: "infinite_loop", renderSource: "for (;;) {}" }),
    generationRequest(`${prefix}-memory-oom`, { kind: "memory_oom" }),
    generationRequest(`${prefix}-crash`, { kind: "crash" }),
    generationRequest(`${prefix}-recovery`, { kind: "render", renderSource: REPRESENTATIVE_RENDER_SOURCE }),
    generationRequest(`${prefix}-ipc-1mib`, {
      kind: "ipc",
      renderSource: REPRESENTATIVE_RENDER_SOURCE,
      payload: "a".repeat(IPC_ONE_MIB_BYTES),
      expectedPayloadBytes: IPC_ONE_MIB_BYTES,
    }),
    generationRequest(`${prefix}-ipc-10mib`, {
      kind: "ipc",
      renderSource: REPRESENTATIVE_RENDER_SOURCE,
      payload: "b".repeat(IPC_TEN_MIB_BYTES),
      expectedPayloadBytes: IPC_TEN_MIB_BYTES,
    }),
  ];
}

function buildSandboxProbeRequest(runId) {
  return generationRequest(`isolation-${runId}-sandbox-probe`, {
    kind: "render",
    renderSource: REPRESENTATIVE_RENDER_SOURCE,
  });
}

function containerNameForRequest(request) {
  const generationFragment = request.generationId.toLowerCase().replace(/[^a-z0-9_.-]/g, "-").slice(0, 72);
  return `monomer-chart-${generationFragment}-${randomUUID().slice(0, 8)}`;
}

async function inspectContainer(dockerBinary, containerName) {
  const result = await runCommandCapture(dockerBinary, ["container", "inspect", containerName]);
  if (result.exitCode !== 0) return null;
  const inspectedContainers = JSON.parse(result.stdout);
  if (!Array.isArray(inspectedContainers) || inspectedContainers.length !== 1) return null;
  return inspectedContainers[0];
}

async function teardownContainer(dockerBinary, containerName) {
  const teardownStartedAt = isoNow();
  const removeResult = await runCommandCapture(dockerBinary, ["container", "rm", "--force", containerName]);
  const afterRemoval = await inspectContainer(dockerBinary, containerName);
  return {
    startedAt: teardownStartedAt,
    completedAt: isoNow(),
    removeExitCode: removeResult.exitCode,
    removeDetail: (removeResult.stderr || removeResult.stdout).trim(),
    verifiedAbsent: afterRemoval === null,
  };
}

function dockerStateEvidence(inspectedContainer) {
  if (!inspectedContainer) return null;
  return {
    status: inspectedContainer.State.Status,
    running: inspectedContainer.State.Running,
    oomKilled: inspectedContainer.State.OOMKilled,
    dead: inspectedContainer.State.Dead,
    exitCode: inspectedContainer.State.ExitCode,
    error: inspectedContainer.State.Error,
    startedAt: inspectedContainer.State.StartedAt,
    finishedAt: inspectedContainer.State.FinishedAt,
    configuredUser: inspectedContainer.Config.User,
    networkMode: inspectedContainer.HostConfig.NetworkMode,
    readonlyRootfs: inspectedContainer.HostConfig.ReadonlyRootfs,
    capDrop: inspectedContainer.HostConfig.CapDrop,
    securityOpt: inspectedContainer.HostConfig.SecurityOpt,
    pidsLimit: inspectedContainer.HostConfig.PidsLimit,
    memoryBytes: inspectedContainer.HostConfig.Memory,
    memorySwapBytes: inspectedContainer.HostConfig.MemorySwap,
    nanoCpus: inspectedContainer.HostConfig.NanoCpus,
    shmBytes: inspectedContainer.HostConfig.ShmSize,
    init: inspectedContainer.HostConfig.Init,
    mounts: inspectedContainer.Mounts.map((mount) => ({
      type: mount.Type,
      source: mount.Source,
      destination: mount.Destination,
      readWrite: mount.RW,
    })),
  };
}

function parseContainerMessages(stdout) {
  return stdout.split("\n").map((line) => line.trim()).filter(Boolean).map((line) => {
    try {
      return JSON.parse(line);
    } catch {
      return { kind: "unparseable_container_output", line };
    }
  });
}

function outcomeSuccess(request, deadline, dockerRunExitCode, state, runnerResult) {
  if (!state) return { success: false, evidence: "Container state was unavailable" };
  if (request.scenario.kind === "infinite_loop") {
    const success = deadline.phase === "generation" && deadline.exceeded && deadline.killExitCode === 0
      && state.exitCode === 137 && state.oomKilled === false;
    return { success, evidence: success ? "Synchronous loop exceeded its generation deadline and the whole container was killed" : "Expected whole-container deadline kill was not observed" };
  }
  if (request.scenario.kind === "memory_oom") {
    const success = deadline.phase === "generation" && deadline.exceeded === false
      && state.oomKilled === true && state.exitCode === 137;
    return { success, evidence: success ? "Docker reported State.OOMKilled=true under the configured cgroup memory limit" : "Docker did not report the expected cgroup OOM kill" };
  }
  if (request.scenario.kind === "crash") {
    const success = deadline.phase === "generation" && deadline.exceeded === false
      && state.oomKilled === false && state.exitCode === 137;
    return { success, evidence: success ? `Container runner crashed with exit code ${state.exitCode}` : "Expected isolated runner crash was not observed" };
  }
  if (!runnerResult) return { success: false, evidence: "Completed container result was not emitted" };
  const probesDenied = Object.values(runnerResult.denialProbes).every((probe) => probe.attempted && probe.denied);
  const success = deadline.phase === "generation" && deadline.exceeded === false
    && dockerRunExitCode === 0 && state.exitCode === 0 && probesDenied;
  return { success, evidence: success ? "Paint, PNG, denial probes, and clean exit were observed" : "Completed generation evidence did not satisfy all gates" };
}

export async function runGeneration(input) {
  const request = validateRunRequest(input.request);
  const containerName = containerNameForRequest(request);
  const invocation = buildDockerRunInvocation({
    dockerBinary: input.dockerBinary,
    image: input.image,
    containerName,
    runnerPath: input.runnerPath,
    protocolPath: input.protocolPath,
    echartsPath: input.echartsPath,
    seccompProfilePath: input.seccompProfilePath,
    chromiumSandboxEnabled: input.chromiumSandboxEnabled,
    limits: input.limits,
  });
  const timestamps = {
    hostAcceptedAt: isoNow(),
    dockerSpawnedAt: null,
    containerReadyAt: null,
    deadlineExceededAt: null,
    killRequestedAt: null,
    dockerExitedAt: null,
    inspectCompletedAt: null,
    hostCompletedAt: null,
  };
  const deadline = { phase: "bootstrap", exceeded: false, killExitCode: null, killDetail: "" };
  let stdout = "";
  let stderr = "";
  let pendingLine = "";
  let deadlineTimer;
  let fallbackClientKillTimer;
  let killPromise = Promise.resolve();
  const child = spawn(invocation.command, invocation.args, { stdio: ["pipe", "pipe", "pipe"] });
  timestamps.dockerSpawnedAt = isoNow();

  const requestContainerKill = () => {
    if (deadline.exceeded) return;
    deadline.exceeded = true;
    timestamps.deadlineExceededAt = isoNow();
    killPromise = runCommandCapture(input.dockerBinary, ["container", "kill", "--signal", "KILL", containerName])
      .then((killResult) => {
        deadline.killExitCode = killResult.exitCode;
        deadline.killDetail = (killResult.stderr || killResult.stdout).trim();
        timestamps.killRequestedAt = isoNow();
      });
    fallbackClientKillTimer = setTimeout(() => child.kill("SIGKILL"), 5_000);
  };
  const armDeadline = (phase, deadlineMs) => {
    clearTimeout(deadlineTimer);
    deadline.phase = phase;
    deadlineTimer = setTimeout(requestContainerKill, deadlineMs);
  };
  armDeadline("bootstrap", CONTAINER_BOOTSTRAP_DEADLINE_MS);

  const observeLine = (line) => {
    if (!line.trim()) return;
    try {
      const message = JSON.parse(line);
      if (message.kind === "container_generation_ready" && message.generationId === request.generationId) {
        timestamps.containerReadyAt = message.at;
        armDeadline("generation", SCENARIO_DEADLINES_MS[request.scenario.kind]);
      }
    } catch {
      return;
    }
  };

  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    stdout = boundedAppend(stdout, chunk, COMMAND_CAPTURE_LIMIT_BYTES);
    pendingLine += chunk;
    const lines = pendingLine.split("\n");
    pendingLine = lines.pop() ?? "";
    for (const line of lines) observeLine(line);
  });
  child.stderr.on("data", (chunk) => {
    stderr = boundedAppend(stderr, chunk, COMMAND_CAPTURE_LIMIT_BYTES);
  });
  child.stdin.on("error", () => undefined);
  child.stdin.end(JSON.stringify(request));

  const dockerExit = await new Promise((resolve) => {
    child.once("error", (error) => resolve({ exitCode: null, signal: null, spawnError: error.message }));
    child.once("close", (exitCode, signal) => resolve({ exitCode, signal, spawnError: null }));
  });
  clearTimeout(deadlineTimer);
  clearTimeout(fallbackClientKillTimer);
  await killPromise;
  if (pendingLine) observeLine(pendingLine);
  timestamps.dockerExitedAt = isoNow();
  const inspectedContainer = await inspectContainer(input.dockerBinary, containerName);
  timestamps.inspectCompletedAt = isoNow();
  const state = dockerStateEvidence(inspectedContainer);
  const messages = parseContainerMessages(stdout);
  const completedMessage = messages.findLast((message) => message.kind === "container_generation_result");
  let runnerResult = null;
  let runnerValidationError = null;
  if (completedMessage) {
    try {
      runnerResult = validateContainerRunnerResult(completedMessage, request);
    } catch (error) {
      runnerValidationError = error instanceof Error ? error.message : String(error);
    }
  }
  const observedOutcome = outcomeSuccess(request, deadline, dockerExit.exitCode, state, runnerResult);
  const teardown = await teardownContainer(input.dockerBinary, containerName);
  timestamps.hostCompletedAt = isoNow();
  return {
    actualRun: true,
    generationId: request.generationId,
    scenarioKind: request.scenario.kind,
    request: redactRequestForDisplay(request),
    success: observedOutcome.success && teardown.verifiedAbsent,
    evidence: observedOutcome.evidence,
    timestamps,
    deadline,
    dockerRun: {
      command: invocation.command,
      args: invocation.args,
      exitCode: dockerExit.exitCode,
      signal: dockerExit.signal,
      spawnError: dockerExit.spawnError,
      stderr: stderr.trim(),
    },
    containerState: state,
    runnerResult,
    runnerValidationError,
    runnerMessages: messages.filter((message) => message.kind !== "container_generation_result"),
    teardown,
  };
}

function buildChecks(generations) {
  const byScenario = (scenarioKind) => generations.filter((generation) => generation.scenarioKind === scenarioKind);
  const renders = byScenario("render");
  const baseline = renders[0];
  const recovery = renders[1];
  const infiniteLoop = byScenario("infinite_loop")[0];
  const memoryOom = byScenario("memory_oom")[0];
  const crash = byScenario("crash")[0];
  const ipcGenerations = byScenario("ipc");
  const baselineRender = baseline?.runnerResult?.render;
  const denialProbeValues = Object.values(baseline?.runnerResult?.denialProbes ?? {});
  const paintPass = baseline?.success === true && baselineRender?.paintReadyMs >= 0
    && /^[a-f0-9]{64}$/.test(baselineRender?.pngSha256 ?? "") && baselineRender?.pngBytes > 0;
  const sandbox = baseline?.runnerResult?.chromium?.sandbox;
  const sandboxPass = baseline?.success === true && sandbox?.commandLineDisabled === false && sandbox?.chromiumSandbox === true;
  const denialPass = denialProbeValues.length === 5 && denialProbeValues.every((probe) => probe.attempted && probe.denied);
  const ipcPass = ipcGenerations.length === 2 && ipcGenerations.every((generation) => generation.success)
    && new Set(ipcGenerations.map((generation) => generation.runnerResult?.ipc?.payloadBytes)).size === 2
    && ipcGenerations.some((generation) => generation.runnerResult?.ipc?.payloadBytes === IPC_ONE_MIB_BYTES)
    && ipcGenerations.some((generation) => generation.runnerResult?.ipc?.payloadBytes === IPC_TEN_MIB_BYTES);
  const teardownPass = generations.every((generation) => generation.teardown.verifiedAbsent);
  return {
    chromiumSandbox: {
      status: sandboxPass ? "pass" : "fail",
      chromiumSandbox: sandbox?.chromiumSandbox ?? false,
      commandLineDisabled: sandbox?.commandLineDisabled ?? null,
      namespaceSandboxEnabled: sandbox?.namespaceSandboxEnabled ?? null,
      seccompBpfSandboxEnabled: sandbox?.seccompBpfSandboxEnabled ?? null,
      statusText: sandbox?.statusText ?? null,
    },
    paintReadyAndPngHash: {
      status: paintPass ? "pass" : "fail",
      paintReadyMs: baselineRender?.paintReadyMs ?? null,
      pngSha256: baselineRender?.pngSha256 ?? null,
      pngBytes: baselineRender?.pngBytes ?? null,
    },
    denialProbes: {
      status: denialPass ? "pass" : "fail",
      probes: baseline?.runnerResult?.denialProbes ?? null,
    },
    infiniteLoopWholeContainerKill: {
      status: infiniteLoop?.success ? "pass" : "fail",
      deadline: infiniteLoop?.deadline ?? null,
      containerState: infiniteLoop?.containerState ?? null,
    },
    cgroupMemoryOom: {
      status: memoryOom?.success ? "pass" : "fail",
      oomKilled: memoryOom?.containerState?.oomKilled ?? null,
      memoryBytes: memoryOom?.containerState?.memoryBytes ?? null,
    },
    crashRecovery: {
      status: crash?.success && recovery?.success ? "pass" : "fail",
      crashExitCode: crash?.containerState?.exitCode ?? null,
      recoveryPngSha256: recovery?.runnerResult?.render?.pngSha256 ?? null,
    },
    ipcBoundaries: {
      status: ipcPass ? "pass" : "fail",
      payloads: ipcGenerations.map((generation) => generation.runnerResult?.ipc ?? null),
    },
    teardown: {
      status: teardownPass ? "pass" : "fail",
      generationsVerifiedAbsent: generations.filter((generation) => generation.teardown.verifiedAbsent).length,
      totalGenerations: generations.length,
    },
  };
}

export async function runExperimentSuite(input) {
  const startedAt = isoNow();
  const bootstrap = await inspectDockerBootstrap(input.dockerBinary, input.image);
  const requests = buildExperimentRequests(input.runId);
  const sandboxAttempt = await runGeneration({
    request: buildSandboxProbeRequest(input.runId),
    dockerBinary: input.dockerBinary,
    image: input.image,
    runnerPath: input.runnerPath,
    protocolPath: input.protocolPath,
    echartsPath: input.echartsPath,
    seccompProfilePath: input.seccompProfilePath,
    chromiumSandboxEnabled: true,
    limits: input.limits,
  });
  const chromiumSandboxEnabled = sandboxAttempt.success
    && sandboxAttempt.runnerResult?.chromium?.sandbox?.chromiumSandbox === true;
  const generations = [];
  for (const request of requests) {
    generations.push(await runGeneration({
      request,
      dockerBinary: input.dockerBinary,
      image: input.image,
      runnerPath: input.runnerPath,
      protocolPath: input.protocolPath,
      echartsPath: input.echartsPath,
      seccompProfilePath: input.seccompProfilePath,
      chromiumSandboxEnabled,
      limits: input.limits,
    }));
  }
  const checks = buildChecks(generations);
  const seccompProfile = await readFile(input.seccompProfilePath);
  const executionChecks = Object.entries(checks).filter(([checkName]) => checkName !== "chromiumSandbox");
  const executionStatus = executionChecks.every(([, check]) => check.status === "pass")
    && generations.every((generation) => generation.success) ? "pass" : "fail";
  const publicationStatus = checks.chromiumSandbox.status === "pass" ? "eligible" : "veto";
  const artifact = {
    schemaVersion: ISOLATION_SCHEMA_VERSION,
    kind: "chart_harness_isolation_experiment",
    actualRun: true,
    executionStatus,
    publicationStatus,
    overallStatus: executionStatus === "fail" ? "fail" : publicationStatus === "eligible" ? "pass" : "veto",
    startedAt,
    completedAt: isoNow(),
    image: bootstrap.image,
    host: {
      platform: os.platform(),
      architecture: os.arch(),
      nodeVersion: process.version,
      dockerServer: bootstrap.server,
      seccompProfile: {
        source: "microsoft/playwright@v1.59.1:utils/docker/seccomp_profile.json",
        sha256: sha256Hex(seccompProfile),
      },
    },
    limits: input.limits,
    sandboxAttempt,
    generations,
    checks,
  };
  return artifact;
}

export function buildDryRunPlan(input) {
  const requests = buildExperimentRequests(input.runId);
  const sandboxAttemptRequest = buildSandboxProbeRequest(input.runId);
  const sandboxAttemptInvocation = buildDockerRunInvocation({
    dockerBinary: input.dockerBinary,
    image: input.image,
    containerName: containerNameForRequest(sandboxAttemptRequest),
    runnerPath: input.runnerPath,
    protocolPath: input.protocolPath,
    echartsPath: input.echartsPath,
    seccompProfilePath: input.seccompProfilePath,
    chromiumSandboxEnabled: true,
    limits: input.limits,
  });
  return {
    schemaVersion: ISOLATION_SCHEMA_VERSION,
    kind: "chart_harness_isolation_dry_run",
    actualRun: false,
    image: input.image,
    limits: input.limits,
    sandboxAttempt: {
      request: redactRequestForDisplay(sandboxAttemptRequest),
      deadlineMs: SCENARIO_DEADLINES_MS.render,
      invocation: sandboxAttemptInvocation,
    },
    fallbackPolicy: "If and only if the sandbox-on probe fails, run measurement generations with chromiumSandbox=false and publicationStatus=veto",
    generations: requests.map((request) => {
      const invocation = buildDockerRunInvocation({
        dockerBinary: input.dockerBinary,
        image: input.image,
        containerName: containerNameForRequest(request),
        runnerPath: input.runnerPath,
        protocolPath: input.protocolPath,
        echartsPath: input.echartsPath,
        seccompProfilePath: input.seccompProfilePath,
        chromiumSandboxEnabled: true,
        limits: input.limits,
      });
      return {
        request: redactRequestForDisplay(request),
        deadlineMs: SCENARIO_DEADLINES_MS[request.scenario.kind],
        invocation,
      };
    }),
  };
}

export async function persistSuccessfulArtifact(artifact, outputPath) {
  validateSuccessfulExperimentArtifact(artifact);
  const absoluteOutputPath = path.resolve(outputPath);
  const temporaryPath = `${absoluteOutputPath}.${randomUUID()}.tmp`;
  await mkdir(path.dirname(absoluteOutputPath), { recursive: true });
  try {
    await writeFile(temporaryPath, `${JSON.stringify(artifact, null, 2)}\n`, { encoding: "utf8", flag: "wx", mode: 0o600 });
    await rename(temporaryPath, absoluteOutputPath);
  } catch (error) {
    await unlink(temporaryPath).catch(() => undefined);
    throw error;
  }
  return absoluteOutputPath;
}

export function parseCliArguments(argv) {
  if (argv.length === 0 || !["suite", "dry-run"].includes(argv[0])) {
    throw new SupervisorError("USAGE", "First argument must be suite or dry-run", "Use: node host-supervisor.mjs suite [--output PATH]");
  }
  const options = {
    command: argv[0],
    dockerBinary: "docker",
    image: PLAYWRIGHT_IMAGE,
    outputPath: DEFAULT_RESULT_PATH,
  };
  for (let index = 1; index < argv.length; index += 1) {
    const argument = argv[index];
    const value = argv[index + 1];
    if (argument === "--docker" || argument === "--image" || argument === "--output") {
      if (!value || value.startsWith("--")) throw new SupervisorError("USAGE", `${argument} requires a value`, "");
      if (argument === "--docker") options.dockerBinary = value;
      if (argument === "--image") options.image = value;
      if (argument === "--output") options.outputPath = value;
      index += 1;
    } else {
      throw new SupervisorError("USAGE", `Unknown argument ${argument}`, "");
    }
  }
  return options;
}

async function main() {
  try {
    const options = parseCliArguments(process.argv.slice(2));
    const runId = `${Date.now()}-${randomUUID().slice(0, 8)}`;
    const commonInput = {
      runId,
      dockerBinary: options.dockerBinary,
      image: options.image,
      runnerPath: CONTAINER_RUNNER_PATH,
      protocolPath: PROTOCOL_PATH,
      echartsPath: ECHARTS_BUNDLE_PATH,
      seccompProfilePath: SECCOMP_PROFILE_PATH,
      limits: ISOLATION_LIMITS,
    };
    if (options.command === "dry-run") {
      process.stdout.write(`${JSON.stringify(buildDryRunPlan(commonInput), null, 2)}\n`);
      return;
    }
    const artifact = await runExperimentSuite(commonInput);
    if (artifact.executionStatus !== "pass") {
      throw new SupervisorError(
        "EXPERIMENT_FAILED",
        "At least one actual isolation generation failed; no result was persisted",
        JSON.stringify(artifact.checks),
      );
    }
    const persistedPath = await persistSuccessfulArtifact(artifact, options.outputPath);
    process.stdout.write(`${JSON.stringify({
      status: artifact.overallStatus,
      publicationStatus: artifact.publicationStatus,
      chromiumSandbox: artifact.checks.chromiumSandbox.chromiumSandbox,
      actualRun: true,
      persistedPath,
      image: artifact.image,
      checks: artifact.checks,
    }, null, 2)}\n`);
    if (artifact.overallStatus === "veto") process.exitCode = 2;
  } catch (error) {
    const failure = error instanceof SupervisorError ? error : new SupervisorError(
      "UNEXPECTED_ERROR",
      error instanceof Error ? error.message : String(error),
      "",
    );
    process.stderr.write(`${JSON.stringify({ status: "error", code: failure.code, message: failure.message, detail: failure.detail }, null, 2)}\n`);
    process.exitCode = 1;
  }
}

const directExecutionPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (directExecutionPath === fileURLToPath(import.meta.url)) await main();
