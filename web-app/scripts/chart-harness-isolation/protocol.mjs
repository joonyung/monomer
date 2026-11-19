import { Buffer } from "node:buffer";
import { createHash } from "node:crypto";

export const ISOLATION_SCHEMA_VERSION = 1;
export const PLAYWRIGHT_IMAGE = "mcr.microsoft.com/playwright:v1.59.1-noble-arm64";
export const IPC_ONE_MIB_BYTES = 1024 * 1024;
export const IPC_TEN_MIB_BYTES = 10 * 1024 * 1024;
export const MAX_RUN_REQUEST_BYTES = 12 * 1024 * 1024;

export const ISOLATION_LIMITS = Object.freeze({
  pids: 128,
  memoryBytes: 512 * 1024 * 1024,
  memorySwapBytes: 512 * 1024 * 1024,
  cpus: 1,
  shmBytes: 128 * 1024 * 1024,
  tmpBytes: 128 * 1024 * 1024,
  homeBytes: 16 * 1024 * 1024,
});

export const SCENARIO_DEADLINES_MS = Object.freeze({
  render: 20_000,
  infinite_loop: 2_500,
  memory_oom: 30_000,
  crash: 10_000,
  ipc: 30_000,
});

const SCENARIO_KINDS = new Set(Object.keys(SCENARIO_DEADLINES_MS));
const REQUIRED_DENIAL_PROBES = ["network", "dns", "websocket", "dynamic_import", "image"];

export class ProtocolValidationError extends Error {
  constructor(message) {
    super(message);
    this.name = "ProtocolValidationError";
  }
}

function requirePlainObject(value, path) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ProtocolValidationError(`${path} must be an object`);
  }
  return value;
}

function requireExactKeys(value, requiredKeys, optionalKeys, path) {
  const allowedKeys = new Set([...requiredKeys, ...optionalKeys]);
  for (const key of Object.keys(value)) {
    if (!allowedKeys.has(key)) throw new ProtocolValidationError(`${path}.${key} is not allowed`);
  }
  for (const key of requiredKeys) {
    if (!(key in value)) throw new ProtocolValidationError(`${path}.${key} is required`);
  }
}

function requireString(value, path, maximumLength) {
  if (typeof value !== "string" || value.length === 0 || value.length > maximumLength) {
    throw new ProtocolValidationError(`${path} must be a non-empty string of at most ${maximumLength} characters`);
  }
  return value;
}

function requireFiniteNonNegativeNumber(value, path) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new ProtocolValidationError(`${path} must be a finite non-negative number`);
  }
  return value;
}

function requirePositiveInteger(value, path) {
  if (!Number.isInteger(value) || value <= 0) {
    throw new ProtocolValidationError(`${path} must be a positive integer`);
  }
  return value;
}

function requireBoolean(value, path) {
  if (typeof value !== "boolean") throw new ProtocolValidationError(`${path} must be a boolean`);
  return value;
}

function requireIsoTimestamp(value, path) {
  requireString(value, path, 64);
  if (!Number.isFinite(Date.parse(value))) throw new ProtocolValidationError(`${path} must be an ISO timestamp`);
  return value;
}

function validateGenerationId(value) {
  requireString(value, "request.generationId", 128);
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(value)) {
    throw new ProtocolValidationError("request.generationId contains unsupported characters");
  }
  return value;
}

function validateRenderSource(value, path) {
  requireString(value, path, 1024 * 1024);
  if (Buffer.byteLength(value, "utf8") > 1024 * 1024) {
    throw new ProtocolValidationError(`${path} exceeds 1 MiB`);
  }
  return value;
}

export function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}

export function validateRunRequest(input) {
  const request = requirePlainObject(input, "request");
  requireExactKeys(request, ["schemaVersion", "generationId", "scenario"], [], "request");
  if (request.schemaVersion !== ISOLATION_SCHEMA_VERSION) {
    throw new ProtocolValidationError(`request.schemaVersion must be ${ISOLATION_SCHEMA_VERSION}`);
  }
  validateGenerationId(request.generationId);
  const scenario = requirePlainObject(request.scenario, "request.scenario");
  requireString(scenario.kind, "request.scenario.kind", 32);
  if (!SCENARIO_KINDS.has(scenario.kind)) {
    throw new ProtocolValidationError(`request.scenario.kind ${scenario.kind} is not supported`);
  }

  if (scenario.kind === "render" || scenario.kind === "infinite_loop") {
    requireExactKeys(scenario, ["kind", "renderSource"], [], "request.scenario");
    validateRenderSource(scenario.renderSource, "request.scenario.renderSource");
  } else if (scenario.kind === "ipc") {
    requireExactKeys(scenario, ["kind", "renderSource", "payload", "expectedPayloadBytes"], [], "request.scenario");
    validateRenderSource(scenario.renderSource, "request.scenario.renderSource");
    if (typeof scenario.payload !== "string") throw new ProtocolValidationError("request.scenario.payload must be a string");
    requirePositiveInteger(scenario.expectedPayloadBytes, "request.scenario.expectedPayloadBytes");
    const payloadBytes = Buffer.byteLength(scenario.payload, "utf8");
    if (payloadBytes !== scenario.expectedPayloadBytes) {
      throw new ProtocolValidationError(`request.scenario.payload has ${payloadBytes} bytes, expected ${scenario.expectedPayloadBytes}`);
    }
    if (![IPC_ONE_MIB_BYTES, IPC_TEN_MIB_BYTES].includes(payloadBytes)) {
      throw new ProtocolValidationError("request.scenario.payload must be exactly 1 MiB or 10 MiB");
    }
  } else {
    requireExactKeys(scenario, ["kind"], [], "request.scenario");
  }

  const serializedRequestBytes = Buffer.byteLength(JSON.stringify(request), "utf8");
  if (serializedRequestBytes > MAX_RUN_REQUEST_BYTES) {
    throw new ProtocolValidationError(`request exceeds ${MAX_RUN_REQUEST_BYTES} bytes`);
  }
  return request;
}

function validateDenialProbe(input, path) {
  const probe = requirePlainObject(input, path);
  requireExactKeys(probe, ["attempted", "denied", "detail", "elapsedMs"], [], path);
  requireBoolean(probe.attempted, `${path}.attempted`);
  requireBoolean(probe.denied, `${path}.denied`);
  requireString(probe.detail, `${path}.detail`, 512);
  requireFiniteNonNegativeNumber(probe.elapsedMs, `${path}.elapsedMs`);
}

export function validateContainerRunnerResult(input, request) {
  const validatedRequest = validateRunRequest(request);
  const result = requirePlainObject(input, "result");
  requireExactKeys(
    result,
    ["schemaVersion", "kind", "generationId", "scenarioKind", "status", "timestamps", "chromium", "render", "denialProbes", "ipc"],
    [],
    "result",
  );
  if (result.schemaVersion !== ISOLATION_SCHEMA_VERSION) throw new ProtocolValidationError("result.schemaVersion is invalid");
  if (result.kind !== "container_generation_result") throw new ProtocolValidationError("result.kind is invalid");
  if (result.generationId !== validatedRequest.generationId) throw new ProtocolValidationError("result.generationId does not match request");
  if (result.scenarioKind !== validatedRequest.scenario.kind) throw new ProtocolValidationError("result.scenarioKind does not match request");
  if (result.status !== "completed") throw new ProtocolValidationError("result.status must be completed");

  const timestamps = requirePlainObject(result.timestamps, "result.timestamps");
  requireExactKeys(
    timestamps,
    ["containerStartedAt", "chromiumStartedAt", "browserConnectedAt", "executionStartedAt", "paintReadyAt", "screenshotCapturedAt", "containerCompletedAt"],
    [],
    "result.timestamps",
  );
  for (const [key, value] of Object.entries(timestamps)) requireIsoTimestamp(value, `result.timestamps.${key}`);

  const chromium = requirePlainObject(result.chromium, "result.chromium");
  requireExactKeys(chromium, ["executable", "version", "pid", "runningAsUid", "sandbox"], [], "result.chromium");
  requireString(chromium.executable, "result.chromium.executable", 1024);
  requireString(chromium.version, "result.chromium.version", 256);
  requirePositiveInteger(chromium.pid, "result.chromium.pid");
  if (!Number.isInteger(chromium.runningAsUid) || chromium.runningAsUid < 0) {
    throw new ProtocolValidationError("result.chromium.runningAsUid must be a non-negative integer");
  }
  if (chromium.runningAsUid === 0) throw new ProtocolValidationError("Chromium must not run as root");
  const sandbox = requirePlainObject(chromium.sandbox, "result.chromium.sandbox");
  requireExactKeys(
    sandbox,
    ["commandLineDisabled", "chromiumSandbox", "namespaceSandboxEnabled", "seccompBpfSandboxEnabled", "statusText"],
    [],
    "result.chromium.sandbox",
  );
  requireBoolean(sandbox.commandLineDisabled, "result.chromium.sandbox.commandLineDisabled");
  requireBoolean(sandbox.chromiumSandbox, "result.chromium.sandbox.chromiumSandbox");
  requireBoolean(sandbox.namespaceSandboxEnabled, "result.chromium.sandbox.namespaceSandboxEnabled");
  requireBoolean(sandbox.seccompBpfSandboxEnabled, "result.chromium.sandbox.seccompBpfSandboxEnabled");
  requireString(sandbox.statusText, "result.chromium.sandbox.statusText", 4096);
  if (sandbox.commandLineDisabled === sandbox.chromiumSandbox) {
    throw new ProtocolValidationError("Chromium sandbox flags are internally inconsistent");
  }

  const render = requirePlainObject(result.render, "result.render");
  requireExactKeys(
    render,
    ["paintReadyMs", "pngSha256", "pngBytes", "canvasWidth", "canvasHeight", "nonTransparentSampleCount", "distinctSampledColors"],
    [],
    "result.render",
  );
  requireFiniteNonNegativeNumber(render.paintReadyMs, "result.render.paintReadyMs");
  requireString(render.pngSha256, "result.render.pngSha256", 64);
  if (!/^[a-f0-9]{64}$/.test(render.pngSha256)) throw new ProtocolValidationError("result.render.pngSha256 must be SHA-256 hex");
  requirePositiveInteger(render.pngBytes, "result.render.pngBytes");
  requirePositiveInteger(render.canvasWidth, "result.render.canvasWidth");
  requirePositiveInteger(render.canvasHeight, "result.render.canvasHeight");
  requirePositiveInteger(render.nonTransparentSampleCount, "result.render.nonTransparentSampleCount");
  requirePositiveInteger(render.distinctSampledColors, "result.render.distinctSampledColors");

  const denialProbes = requirePlainObject(result.denialProbes, "result.denialProbes");
  requireExactKeys(denialProbes, REQUIRED_DENIAL_PROBES, [], "result.denialProbes");
  for (const probeName of REQUIRED_DENIAL_PROBES) validateDenialProbe(denialProbes[probeName], `result.denialProbes.${probeName}`);

  if (result.ipc !== null) {
    const ipc = requirePlainObject(result.ipc, "result.ipc");
    requireExactKeys(ipc, ["payloadBytes", "payloadSha256"], [], "result.ipc");
    requirePositiveInteger(ipc.payloadBytes, "result.ipc.payloadBytes");
    requireString(ipc.payloadSha256, "result.ipc.payloadSha256", 64);
    if (!/^[a-f0-9]{64}$/.test(ipc.payloadSha256)) throw new ProtocolValidationError("result.ipc.payloadSha256 must be SHA-256 hex");
  }
  if (validatedRequest.scenario.kind === "ipc") {
    if (result.ipc === null) throw new ProtocolValidationError("IPC result is required for IPC scenario");
    if (result.ipc.payloadBytes !== validatedRequest.scenario.expectedPayloadBytes) {
      throw new ProtocolValidationError("IPC result payloadBytes does not match request");
    }
    if (result.ipc.payloadSha256 !== sha256Hex(validatedRequest.scenario.payload)) {
      throw new ProtocolValidationError("IPC result payloadSha256 does not match request");
    }
  } else if (result.ipc !== null) {
    throw new ProtocolValidationError("IPC result is only allowed for IPC scenario");
  }
  return result;
}

function requireCheckPass(checks, checkName) {
  const check = requirePlainObject(checks[checkName], `artifact.checks.${checkName}`);
  if (check.status !== "pass") throw new ProtocolValidationError(`artifact.checks.${checkName}.status must be pass`);
}

export function validateSuccessfulExperimentArtifact(input) {
  const artifact = requirePlainObject(input, "artifact");
  requireExactKeys(
    artifact,
    ["schemaVersion", "kind", "actualRun", "executionStatus", "publicationStatus", "overallStatus", "startedAt", "completedAt", "image", "host", "limits", "sandboxAttempt", "generations", "checks"],
    [],
    "artifact",
  );
  if (artifact.schemaVersion !== ISOLATION_SCHEMA_VERSION) throw new ProtocolValidationError("artifact.schemaVersion is invalid");
  if (artifact.kind !== "chart_harness_isolation_experiment") throw new ProtocolValidationError("artifact.kind is invalid");
  if (artifact.actualRun !== true) throw new ProtocolValidationError("Only an actual run can be persisted");
  if (artifact.executionStatus !== "pass") throw new ProtocolValidationError("Only completed successful generations can be persisted");
  if (!new Set(["eligible", "veto"]).has(artifact.publicationStatus)) throw new ProtocolValidationError("artifact.publicationStatus is invalid");
  if (!new Set(["pass", "veto"]).has(artifact.overallStatus)) throw new ProtocolValidationError("Only a pass or explicit security veto can be persisted");
  requireIsoTimestamp(artifact.startedAt, "artifact.startedAt");
  requireIsoTimestamp(artifact.completedAt, "artifact.completedAt");
  requirePlainObject(artifact.sandboxAttempt, "artifact.sandboxAttempt");
  if (artifact.sandboxAttempt.actualRun !== true || artifact.sandboxAttempt.teardown?.verifiedAbsent !== true) {
    throw new ProtocolValidationError("artifact.sandboxAttempt must be an actual torn-down container run");
  }
  if (!Array.isArray(artifact.generations) || artifact.generations.length !== 7) {
    throw new ProtocolValidationError("artifact.generations must contain exactly seven actual generations");
  }
  for (const [index, generation] of artifact.generations.entries()) {
    requirePlainObject(generation, `artifact.generations[${index}]`);
    if (generation.actualRun !== true || generation.success !== true) {
      throw new ProtocolValidationError(`artifact.generations[${index}] is not a successful actual run`);
    }
    if (generation.teardown?.verifiedAbsent !== true) {
      throw new ProtocolValidationError(`artifact.generations[${index}] did not verify teardown`);
    }
  }
  const checks = requirePlainObject(artifact.checks, "artifact.checks");
  requireExactKeys(
    checks,
    ["chromiumSandbox", "paintReadyAndPngHash", "denialProbes", "infiniteLoopWholeContainerKill", "cgroupMemoryOom", "crashRecovery", "ipcBoundaries", "teardown"],
    [],
    "artifact.checks",
  );
  for (const checkName of Object.keys(checks)) {
    if (checkName !== "chromiumSandbox") requireCheckPass(checks, checkName);
  }
  if (artifact.overallStatus === "pass") {
    requireCheckPass(checks, "chromiumSandbox");
    if (artifact.publicationStatus !== "eligible") throw new ProtocolValidationError("Passing artifact must be publication eligible");
  } else {
    if (checks.chromiumSandbox.status !== "fail" || artifact.publicationStatus !== "veto") {
      throw new ProtocolValidationError("Veto artifact must explicitly fail the Chromium sandbox gate");
    }
  }
  return artifact;
}

export function redactRequestForDisplay(request) {
  const validatedRequest = validateRunRequest(request);
  if (validatedRequest.scenario.kind !== "ipc") return validatedRequest;
  return {
    ...validatedRequest,
    scenario: {
      ...validatedRequest.scenario,
      payload: `<${validatedRequest.scenario.expectedPayloadBytes} bytes; sha256=${sha256Hex(validatedRequest.scenario.payload)}>`,
    },
  };
}
