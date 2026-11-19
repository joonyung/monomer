import { spawnSync } from "node:child_process";
import console from "node:console";
import { readdirSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, URL } from "node:url";

export function checkPatches(webDirectory) {
  const patchDirectory = join(webDirectory, "patches");
  const patchNames = readdirSync(patchDirectory).filter((name) => name.endsWith(".patch")).sort();
  for (const patchName of patchNames) {
    const result = spawnSync("patch", ["-R", "--dry-run", "-p1", "-f", "-s"], {
      cwd: webDirectory,
      input: readFileSync(join(patchDirectory, patchName)),
      encoding: "utf8",
    });
    if (result.error || result.status !== 0) {
      throw new Error(
        `Required dependency patch ${patchName} is not applied or cannot be verified. `
        + `Reinstall Web dependencies with 'cd ${webDirectory} && npm ci', then run 'npm run check:patches'.`
        + (result.error ? ` ${result.error.message}` : ""),
      );
    }
  }
  return patchNames.length;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const count = checkPatches(fileURLToPath(new URL("..", import.meta.url)));
    console.log(`PASS required dependency patches applied (${count}).`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
