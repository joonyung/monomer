import { mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Worktrees share one machine. Overlapping suites, each using half the CPU cores, push 5-second
// assertions into load-induced timeouts, so every Playwright run first takes a machine-wide lock.
// mkdir is the atomic acquire; the owner's pid lets a waiter reclaim a lock left by a dead run.

export interface RunLockOwner {
  pid: number;
  worktree: string;
  startedAt: string;
}

export interface RunLockOptions {
  lockDirectory: string;
  owner: RunLockOwner;
  pollIntervalMs: number;
  maxWaitMs: number;
  // An owner directory without owner.json older than this is a crash between mkdir and write.
  ownerlessStaleAfterMs: number;
  isProcessAlive: (pid: number) => boolean;
  log: (message: string) => void;
  sleep: (milliseconds: number) => Promise<void>;
  now: () => number;
}

function readOwner(ownerFile: string): RunLockOwner | null {
  try {
    const owner = JSON.parse(readFileSync(ownerFile, "utf8")) as Partial<RunLockOwner>;
    return typeof owner.pid === "number" && typeof owner.worktree === "string" && typeof owner.startedAt === "string"
      ? { pid: owner.pid, worktree: owner.worktree, startedAt: owner.startedAt }
      : null;
  } catch {
    return null;
  }
}

function describeOwner(owner: RunLockOwner): string {
  return `${owner.worktree} (pid ${owner.pid}, started ${owner.startedAt})`;
}

export async function acquireRunLock(options: RunLockOptions): Promise<() => void> {
  const ownerFile = join(options.lockDirectory, "owner.json");
  const deadline = options.now() + options.maxWaitMs;
  let reportedOwner: string | null = null;
  for (;;) {
    try {
      mkdirSync(options.lockDirectory);
      writeFileSync(ownerFile, JSON.stringify(options.owner));
      let released = false;
      return () => {
        if (released) return;
        released = true;
        if (readOwner(ownerFile)?.pid === options.owner.pid) rmSync(options.lockDirectory, { recursive: true, force: true });
      };
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
    }

    const current = readOwner(ownerFile);
    if (current === null) {
      let createdAt: number | null = null;
      try {
        createdAt = statSync(options.lockDirectory).mtimeMs;
      } catch {
        continue;
      }
      if (options.now() - createdAt > options.ownerlessStaleAfterMs) {
        options.log(`Removing an ownerless Playwright run lock at ${options.lockDirectory}.`);
        rmSync(options.lockDirectory, { recursive: true, force: true });
        continue;
      }
    } else if (!options.isProcessAlive(current.pid)) {
      // Re-read right before removal so a lock another waiter just took is left alone.
      if (readOwner(ownerFile)?.pid === current.pid) {
        options.log(`Removing a stale Playwright run lock from ${describeOwner(current)}.`);
        rmSync(options.lockDirectory, { recursive: true, force: true });
      }
      continue;
    } else if (describeOwner(current) !== reportedOwner) {
      reportedOwner = describeOwner(current);
      options.log(`Waiting for the Playwright run in ${reportedOwner} to finish.`);
    }

    if (options.now() >= deadline) {
      throw new Error(
        `Another Playwright run has held ${options.lockDirectory} for over ${Math.round(options.maxWaitMs / 60_000)} minutes`
          + `${current ? `: ${describeOwner(current)}` : ""}. Stop that run, or remove the directory if it is stale.`,
      );
    }
    await options.sleep(options.pollIntervalMs);
  }
}

function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === "EPERM";
  }
}

export default async function globalSetup(): Promise<() => void> {
  execFileSync("npm", ["run", "check:patches"], {
    cwd: fileURLToPath(new URL("../../..", import.meta.url)),
    stdio: "inherit",
  });
  const lockDirectory = join(homedir(), ".monomer", "locks", "playwright-run");
  mkdirSync(dirname(lockDirectory), { recursive: true });
  const release = await acquireRunLock({
    lockDirectory,
    owner: {
      pid: process.pid,
      worktree: fileURLToPath(new URL("../../../..", import.meta.url)),
      startedAt: new Date().toISOString(),
    },
    pollIntervalMs: 5_000,
    maxWaitMs: 60 * 60_000,
    ownerlessStaleAfterMs: 60_000,
    isProcessAlive,
    log: (message) => console.log(message),
    sleep: (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
    now: () => Date.now(),
  });
  process.once("exit", release);
  return release;
}
