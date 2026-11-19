import path from "node:path";
import { ISOLATION_LIMITS } from "./protocol.mjs";

function requireAbsolutePath(filePath, fieldName) {
  if (typeof filePath !== "string" || !path.isAbsolute(filePath)) throw new Error(`${fieldName} must be an absolute path`);
  if (filePath.includes(",")) throw new Error(`${fieldName} cannot contain a comma`);
  return filePath;
}

function dockerByteValue(bytes) {
  if (!Number.isInteger(bytes) || bytes <= 0) throw new Error("Docker byte values must be positive integers");
  return `${bytes}b`;
}

export function buildDockerRunInvocation(input) {
  const dockerBinary = input.dockerBinary;
  const image = input.image;
  const containerName = input.containerName;
  const runnerPath = requireAbsolutePath(input.runnerPath, "runnerPath");
  const protocolPath = requireAbsolutePath(input.protocolPath, "protocolPath");
  const echartsPath = requireAbsolutePath(input.echartsPath, "echartsPath");
  const seccompProfilePath = requireAbsolutePath(input.seccompProfilePath, "seccompProfilePath");
  const limits = input.limits;
  if (typeof dockerBinary !== "string" || dockerBinary.length === 0) throw new Error("dockerBinary is required");
  if (typeof image !== "string" || image.length === 0) throw new Error("image is required");
  if (!/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/.test(containerName)) throw new Error("containerName is invalid");
  for (const limitName of Object.keys(ISOLATION_LIMITS)) {
    if (!Number.isFinite(limits[limitName]) || limits[limitName] <= 0) throw new Error(`limits.${limitName} is invalid`);
  }
  if (typeof input.chromiumSandboxEnabled !== "boolean") throw new Error("chromiumSandboxEnabled must be a boolean");

  const args = [
    "run",
    "--interactive",
    "--name", containerName,
    "--label", "monomer.chart-harness-isolation=true",
    "--network", "none",
    "--read-only",
    "--cap-drop", "ALL",
    "--security-opt", "no-new-privileges:true",
    "--security-opt", `seccomp=${seccompProfilePath}`,
    "--pids-limit", String(limits.pids),
    "--memory", dockerByteValue(limits.memoryBytes),
    "--memory-swap", dockerByteValue(limits.memorySwapBytes),
    "--cpus", String(limits.cpus),
    "--shm-size", dockerByteValue(limits.shmBytes),
    "--user", "1001:1001",
    "--init",
    "--tmpfs", `/tmp:rw,nosuid,noexec,size=${limits.tmpBytes},uid=1001,gid=1001`,
    "--tmpfs", `/home/pwuser:rw,nosuid,noexec,size=${limits.homeBytes},uid=1001,gid=1001`,
    "--env", "HOME=/home/pwuser",
    "--env", "XDG_CONFIG_HOME=/tmp/xdg-config",
    "--env", "XDG_CACHE_HOME=/tmp/xdg-cache",
    "--env", `MONOMER_CHROMIUM_SANDBOX=${input.chromiumSandboxEnabled ? "enabled" : "disabled"}`,
    "--mount", `type=bind,source=${runnerPath},target=/runner/container-runner.mjs,readonly`,
    "--mount", `type=bind,source=${protocolPath},target=/runner/protocol.mjs,readonly`,
    "--mount", `type=bind,source=${echartsPath},target=/runner/echarts.min.js,readonly`,
    image,
    "node",
    "/runner/container-runner.mjs",
  ];
  return { command: dockerBinary, args };
}
