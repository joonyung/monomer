import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it, vi } from "vitest";
import globalSetup, { acquireRunLock, type RunLockOptions, type RunLockOwner } from "./e2e/support/run-lock";

vi.mock("node:child_process", () => ({ execFileSync: vi.fn() }));

const roots: string[] = [];

afterEach(() => {
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true });
});

function lockDirectory(): string {
  const root = mkdtempSync(join(tmpdir(), "playwright-run-lock-"));
  roots.push(root);
  return join(root, "playwright-run");
}

function owner(pid: number): RunLockOwner {
  return { pid, worktree: `/worktrees/${pid}`, startedAt: "2026-10-08T05:00:00.000Z" };
}

function options(directory: string, overrides: Partial<RunLockOptions>): RunLockOptions {
  let clock = 0;
  return {
    lockDirectory: directory,
    owner: owner(100),
    pollIntervalMs: 5_000,
    maxWaitMs: 60_000,
    ownerlessStaleAfterMs: 60_000,
    isProcessAlive: () => true,
    log: () => {},
    sleep: async (milliseconds) => {
      clock += milliseconds;
    },
    now: () => clock,
    ...overrides,
  };
}

function hold(directory: string, holder: RunLockOwner): void {
  mkdirSync(directory);
  writeFileSync(join(directory, "owner.json"), JSON.stringify(holder));
}

describe("Playwright machine-wide run lock", () => {
  it("rejects missing dependency patches before taking the machine lock", async () => {
    vi.mocked(execFileSync).mockImplementationOnce(() => { throw new Error("Required dependency patch is missing. Run npm ci."); });
    await expect(globalSetup()).rejects.toThrow("Required dependency patch is missing. Run npm ci.");
    expect(execFileSync).toHaveBeenCalledWith("npm", ["run", "check:patches"], {
      cwd: fileURLToPath(new URL("..", import.meta.url)),
      stdio: "inherit",
    });
  });

  it("records its owner while held and removes the lock on release", async () => {
    const directory = lockDirectory();
    const release = await acquireRunLock(options(directory, {}));
    expect(JSON.parse(readFileSync(join(directory, "owner.json"), "utf8"))).toEqual(owner(100));
    release();
    release();
    expect(existsSync(directory)).toBe(false);
  });

  it("waits for a live run and acquires once that run releases", async () => {
    const directory = lockDirectory();
    hold(directory, owner(200));
    const messages: string[] = [];
    let sleeps = 0;
    const release = await acquireRunLock(options(directory, {
      log: (message) => messages.push(message),
      sleep: async () => {
        sleeps += 1;
        if (sleeps === 2) rmSync(directory, { recursive: true });
      },
    }));
    expect(sleeps).toBe(2);
    expect(messages).toEqual(["Waiting for the Playwright run in /worktrees/200 (pid 200, started 2026-10-08T05:00:00.000Z) to finish."]);
    expect(JSON.parse(readFileSync(join(directory, "owner.json"), "utf8")).pid).toBe(100);
    release();
  });

  it("reclaims a lock whose owner process is gone", async () => {
    const directory = lockDirectory();
    hold(directory, owner(300));
    const messages: string[] = [];
    const release = await acquireRunLock(options(directory, {
      isProcessAlive: (pid) => pid !== 300,
      log: (message) => messages.push(message),
    }));
    expect(messages).toEqual(["Removing a stale Playwright run lock from /worktrees/300 (pid 300, started 2026-10-08T05:00:00.000Z)."]);
    expect(JSON.parse(readFileSync(join(directory, "owner.json"), "utf8")).pid).toBe(100);
    release();
  });

  it("reclaims an ownerless lock only after it is old enough", async () => {
    const directory = lockDirectory();
    mkdirSync(directory);
    const created = new Date("2026-10-08T05:00:00.000Z");
    utimesSync(directory, created, created);
    let clock = created.getTime();
    const sleeps: number[] = [];
    const release = await acquireRunLock(options(directory, {
      now: () => clock,
      sleep: async (milliseconds) => {
        sleeps.push(milliseconds);
        clock += milliseconds;
      },
      maxWaitMs: 10 * 60_000,
    }));
    expect(sleeps.length).toBe(13);
    expect(JSON.parse(readFileSync(join(directory, "owner.json"), "utf8")).pid).toBe(100);
    release();
  });

  it("stops waiting after the maximum wait and names the holder", async () => {
    const directory = lockDirectory();
    hold(directory, owner(400));
    await expect(acquireRunLock(options(directory, { maxWaitMs: 120_000 }))).rejects.toThrow(
      "Another Playwright run has held "
        + `${directory} for over 2 minutes: /worktrees/400 (pid 400, started 2026-10-08T05:00:00.000Z). `
        + "Stop that run, or remove the directory if it is stale.",
    );
    expect(JSON.parse(readFileSync(join(directory, "owner.json"), "utf8")).pid).toBe(400);
  });

  it("does not remove a lock another run took after this one released it", async () => {
    const directory = lockDirectory();
    const release = await acquireRunLock(options(directory, {}));
    rmSync(directory, { recursive: true });
    hold(directory, owner(500));
    release();
    expect(JSON.parse(readFileSync(join(directory, "owner.json"), "utf8")).pid).toBe(500);
  });
});
