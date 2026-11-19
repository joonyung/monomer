import { mkdtemp, readFile, stat } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import {
  buildDryRunPlan,
  buildExperimentRequests,
  parseCliArguments,
  persistSuccessfulArtifact,
} from "./host-supervisor.mjs";
import { ISOLATION_LIMITS, ISOLATION_SCHEMA_VERSION, PLAYWRIGHT_IMAGE } from "./protocol.mjs";

function successfulArtifact(actualRun, overallStatus) {
  const timestamp = "2026-07-18T01:00:00.000Z";
  const checkNames = [
    "chromiumSandbox",
    "paintReadyAndPngHash",
    "denialProbes",
    "infiniteLoopWholeContainerKill",
    "cgroupMemoryOom",
    "crashRecovery",
    "ipcBoundaries",
    "teardown",
  ];
  return {
    schemaVersion: ISOLATION_SCHEMA_VERSION,
    kind: "chart_harness_isolation_experiment",
    actualRun,
    executionStatus: overallStatus === "pass" ? "pass" : "fail",
    publicationStatus: "eligible",
    overallStatus,
    startedAt: timestamp,
    completedAt: timestamp,
    image: {},
    host: {},
    limits: ISOLATION_LIMITS,
    sandboxAttempt: { actualRun, teardown: { verifiedAbsent: true } },
    generations: Array.from({ length: 7 }, (_, index) => ({
      actualRun,
      generationId: `generation-${index}`,
      success: true,
      teardown: { verifiedAbsent: true },
    })),
    checks: Object.fromEntries(checkNames.map((checkName) => [checkName, { status: "pass" }])),
  };
}

describe("chart isolation host supervisor without Docker", () => {
  it("plans seven fresh-container generations including crash recovery and both IPC boundaries", () => {
    const requests = buildExperimentRequests("unit");
    expect(requests.map((request) => request.scenario.kind)).toEqual([
      "render", "infinite_loop", "memory_oom", "crash", "render", "ipc", "ipc",
    ]);
    expect(new Set(requests.map((request) => request.generationId)).size).toBe(7);
    expect(requests.filter((request) => request.scenario.kind === "ipc").map((request) => request.scenario.expectedPayloadBytes))
      .toEqual([1024 * 1024, 10 * 1024 * 1024]);
  });

  it("produces a redacted dry-run with one secured Docker invocation per generation", () => {
    const plan = buildDryRunPlan({
      runId: "unit",
      dockerBinary: "docker",
      image: PLAYWRIGHT_IMAGE,
      runnerPath: "/workspace/container-runner.mjs",
      protocolPath: "/workspace/protocol.mjs",
      echartsPath: "/workspace/echarts.min.js",
      seccompProfilePath: "/workspace/seccomp-profile.json",
      limits: ISOLATION_LIMITS,
    });
    expect(plan.actualRun).toBe(false);
    expect(plan.generations).toHaveLength(7);
    expect(new Set(plan.generations.map((generation) => {
      const nameIndex = generation.invocation.args.indexOf("--name");
      return generation.invocation.args[nameIndex + 1];
    })).size).toBe(7);
    const ipcPlans = plan.generations.filter((generation) => generation.request.scenario.kind === "ipc");
    expect(ipcPlans.every((generation) => generation.request.scenario.payload.startsWith("<"))).toBe(true);
    expect(JSON.stringify(plan).includes("a".repeat(1024))).toBe(false);
  });

  it("never persists a dry-run or failed artifact and atomically persists a successful actual artifact", async () => {
    const temporaryDirectory = await mkdtemp(path.join(os.tmpdir(), "monomer-isolation-test-"));
    const outputPath = path.join(temporaryDirectory, "result.json");
    await expect(persistSuccessfulArtifact(successfulArtifact(false, "pass"), outputPath)).rejects.toThrow("actual run");
    await expect(stat(outputPath)).rejects.toThrow();
    await expect(persistSuccessfulArtifact(successfulArtifact(true, "fail"), outputPath)).rejects.toThrow("successful generations");
    await expect(stat(outputPath)).rejects.toThrow();
    await expect(persistSuccessfulArtifact(successfulArtifact(true, "pass"), outputPath)).resolves.toBe(outputPath);
    expect(JSON.parse(await readFile(outputPath, "utf8")).actualRun).toBe(true);
  });

  it("parses the explicit suite and dry-run commands without accepting ambiguous modes", () => {
    expect(parseCliArguments(["suite", "--image", PLAYWRIGHT_IMAGE, "--output", "/tmp/result.json"])).toMatchObject({
      command: "suite",
      image: PLAYWRIGHT_IMAGE,
      outputPath: "/tmp/result.json",
    });
    expect(parseCliArguments(["dry-run"]).command).toBe("dry-run");
    expect(() => parseCliArguments([])).toThrow("First argument must be suite or dry-run");
    expect(() => parseCliArguments(["suite", "--fallback", "iframe"])).toThrow("Unknown argument --fallback");
  });
});
