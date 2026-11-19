import { describe, expect, it } from "vitest";
import { buildDockerRunInvocation } from "./docker-arguments.mjs";
import { ISOLATION_LIMITS, PLAYWRIGHT_IMAGE } from "./protocol.mjs";

function invocation() {
  return buildDockerRunInvocation({
    dockerBinary: "docker",
    image: PLAYWRIGHT_IMAGE,
    containerName: "monomer-chart-test-generation",
    runnerPath: "/workspace/scripts/container-runner.mjs",
    protocolPath: "/workspace/scripts/protocol.mjs",
    echartsPath: "/workspace/node_modules/echarts.min.js",
    seccompProfilePath: "/workspace/scripts/seccomp-profile.json",
    chromiumSandboxEnabled: true,
    limits: ISOLATION_LIMITS,
  });
}

describe("chart isolation Docker boundary", () => {
  it("pins all requested containment controls and a non-root init process", () => {
    const { args } = invocation();
    expect(args).toContain(PLAYWRIGHT_IMAGE);
    expect(args).toEqual(expect.arrayContaining([
      "--network", "none",
      "--read-only",
      "--cap-drop", "ALL",
      "--security-opt", "no-new-privileges:true",
      "--security-opt", "seccomp=/workspace/scripts/seccomp-profile.json",
      "--pids-limit", "128",
      "--memory", "536870912b",
      "--memory-swap", "536870912b",
      "--cpus", "1",
      "--shm-size", "134217728b",
      "--user", "1001:1001",
      "--init",
    ]));
    expect(args).not.toContain("--rm");
    expect(args).not.toContain("--privileged");
    expect(args).toContain("--interactive");
    expect(args).toEqual(expect.arrayContaining(["--env", "MONOMER_CHROMIUM_SANDBOX=enabled"]));
  });

  it("mounts only the three minimum read-only runner assets", () => {
    const { args } = invocation();
    const mounts = args.filter((argument) => argument.startsWith("type=bind"));
    expect(mounts).toEqual([
      "type=bind,source=/workspace/scripts/container-runner.mjs,target=/runner/container-runner.mjs,readonly",
      "type=bind,source=/workspace/scripts/protocol.mjs,target=/runner/protocol.mjs,readonly",
      "type=bind,source=/workspace/node_modules/echarts.min.js,target=/runner/echarts.min.js,readonly",
    ]);
    expect(args.some((argument) => argument.includes("docker.sock"))).toBe(false);
  });

  it("uses read-only rootfs plus bounded tmpfs mounts", () => {
    const { args } = invocation();
    const tmpfsValues = args.filter((argument) => argument.startsWith("/tmp:") || argument.startsWith("/home/pwuser:"));
    expect(tmpfsValues).toEqual([
      "/tmp:rw,nosuid,noexec,size=134217728,uid=1001,gid=1001",
      "/home/pwuser:rw,nosuid,noexec,size=16777216,uid=1001,gid=1001",
    ]);
  });
});
