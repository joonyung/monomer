import { describe, expect, it } from "vitest";
import {
  IPC_ONE_MIB_BYTES,
  IPC_TEN_MIB_BYTES,
  ISOLATION_SCHEMA_VERSION,
  ProtocolValidationError,
  sha256Hex,
  validateContainerRunnerResult,
  validateRunRequest,
} from "./protocol.mjs";

function requestForScenario(scenario) {
  return { schemaVersion: ISOLATION_SCHEMA_VERSION, generationId: "generation-1", scenario };
}

function completedResult(request) {
  const timestamp = "2026-07-18T01:00:00.000Z";
  const ipc = request.scenario.kind === "ipc" ? {
    payloadBytes: request.scenario.expectedPayloadBytes,
    payloadSha256: sha256Hex(request.scenario.payload),
  } : null;
  return {
    schemaVersion: ISOLATION_SCHEMA_VERSION,
    kind: "container_generation_result",
    generationId: request.generationId,
    scenarioKind: request.scenario.kind,
    status: "completed",
    timestamps: {
      containerStartedAt: timestamp,
      chromiumStartedAt: timestamp,
      browserConnectedAt: timestamp,
      executionStartedAt: timestamp,
      paintReadyAt: timestamp,
      screenshotCapturedAt: timestamp,
      containerCompletedAt: timestamp,
    },
    chromium: {
      executable: "/ms-playwright/chromium/chrome",
      version: "Chrome/140.0.0.0",
      pid: 42,
      runningAsUid: 1001,
      sandbox: {
        commandLineDisabled: false,
        chromiumSandbox: true,
        namespaceSandboxEnabled: true,
        seccompBpfSandboxEnabled: true,
        statusText: "You are adequately sandboxed.",
      },
    },
    render: {
      paintReadyMs: 12.5,
      pngSha256: "a".repeat(64),
      pngBytes: 4096,
      canvasWidth: 800,
      canvasHeight: 600,
      nonTransparentSampleCount: 100,
      distinctSampledColors: 8,
    },
    denialProbes: Object.fromEntries(
      ["network", "dns", "websocket", "dynamic_import", "image"].map((probeName) => [probeName, {
        attempted: true,
        denied: true,
        detail: `${probeName} denied`,
        elapsedMs: 1,
      }]),
    ),
    ipc,
  };
}

describe("chart isolation protocol", () => {
  it("accepts only the exact render, termination, and IPC request shapes", () => {
    expect(validateRunRequest(requestForScenario({ kind: "render", renderSource: "chart.setOption({});" }))).toBeTruthy();
    expect(validateRunRequest(requestForScenario({ kind: "infinite_loop", renderSource: "for (;;) {}" }))).toBeTruthy();
    expect(validateRunRequest(requestForScenario({ kind: "memory_oom" }))).toBeTruthy();
    expect(validateRunRequest(requestForScenario({ kind: "crash" }))).toBeTruthy();
    expect(validateRunRequest(requestForScenario({
      kind: "ipc",
      renderSource: "chart.setOption({});",
      payload: "a".repeat(IPC_ONE_MIB_BYTES),
      expectedPayloadBytes: IPC_ONE_MIB_BYTES,
    }))).toBeTruthy();
    expect(() => validateRunRequest(requestForScenario({ kind: "render", renderSource: "x", fallback: "iframe" })))
      .toThrow(ProtocolValidationError);
    expect(() => validateRunRequest(requestForScenario({ kind: "ipc", renderSource: "x", payload: "small", expectedPayloadBytes: 5 })))
      .toThrow("must be exactly 1 MiB or 10 MiB");
  });

  it("accepts both required IPC boundaries and rejects a byte mismatch", () => {
    for (const payloadBytes of [IPC_ONE_MIB_BYTES, IPC_TEN_MIB_BYTES]) {
      const payload = "x".repeat(payloadBytes);
      const request = requestForScenario({ kind: "ipc", renderSource: "chart.setOption({});", payload, expectedPayloadBytes: payloadBytes });
      expect(validateContainerRunnerResult(completedResult(request), request).ipc.payloadBytes).toBe(payloadBytes);
      expect(() => validateContainerRunnerResult({
        ...completedResult(request),
        ipc: { payloadBytes, payloadSha256: "0".repeat(64) },
      }, request)).toThrow("payloadSha256 does not match request");
    }
  });

  it("requires paint evidence, a non-root Chromium, and all denial probes", () => {
    const request = requestForScenario({ kind: "render", renderSource: "chart.setOption({});" });
    const validResult = completedResult(request);
    expect(validateContainerRunnerResult(validResult, request).render.pngBytes).toBe(4096);
    expect(() => validateContainerRunnerResult({
      ...validResult,
      chromium: { ...validResult.chromium, runningAsUid: 0 },
    }, request)).toThrow("must not run as root");
    expect(() => validateContainerRunnerResult({
      ...validResult,
      chromium: {
        ...validResult.chromium,
        sandbox: { ...validResult.chromium.sandbox, chromiumSandbox: false },
      },
    }, request)).toThrow("internally inconsistent");
    expect(validateContainerRunnerResult({
      ...validResult,
      chromium: {
        ...validResult.chromium,
        sandbox: { ...validResult.chromium.sandbox, commandLineDisabled: true, chromiumSandbox: false },
      },
    }, request).chromium.sandbox.chromiumSandbox).toBe(false);
    const missingImageProbe = { ...validResult.denialProbes };
    delete missingImageProbe.image;
    expect(() => validateContainerRunnerResult({ ...validResult, denialProbes: missingImageProbe }, request))
      .toThrow("result.denialProbes.image is required");
  });
});
