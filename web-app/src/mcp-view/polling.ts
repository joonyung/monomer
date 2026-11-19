export const POLL_INTERVAL_MS = 2_000;
export const POLL_IDLE_LIMIT_MS = 10 * 60_000;
export const POLL_FAILURE_LIMIT = 3;

export type PollOutcome = "changed" | "unchanged" | "complete";
export type PollingPhase = "idle" | "running" | "paused" | "failed" | "complete" | "stopped";

export interface PollingClock {
  now(): number;
  setTimeout(callback: () => void, delayMs: number): unknown;
  clearTimeout(handle: unknown): void;
}

export interface PollingOptions {
  intervalMs: number;
  idleLimitMs: number;
  failureLimit: number;
  poll(): Promise<PollOutcome>;
  isActive(): boolean;
  onPhaseChange(phase: PollingPhase): void;
  clock: PollingClock;
}

export interface Poller {
  readonly phase: PollingPhase;
  start(): void;
  resume(): void;
  stop(): void;
}

// One poll at a time: the next tick is scheduled only after the previous poll settles. Idle time is
// counted only while the view is active, so a hidden view does not pause itself.
export function createPoller(options: PollingOptions): Poller {
  let phase: PollingPhase = "idle";
  let timer: unknown = null;
  let inFlight = false;
  let consecutiveFailures = 0;
  let idleMs = 0;
  let observedAt: number | null = null;

  const setPhase = (next: PollingPhase) => {
    if (phase === next) return;
    phase = next;
    options.onPhaseChange(next);
  };
  const clearTimer = () => {
    if (timer !== null) options.clock.clearTimeout(timer);
    timer = null;
  };
  const schedule = () => {
    clearTimer();
    timer = options.clock.setTimeout(() => void tick(), options.intervalMs);
  };

  async function tick(): Promise<void> {
    timer = null;
    if (phase !== "running" || inFlight) return;
    if (!options.isActive()) {
      observedAt = null;
      schedule();
      return;
    }
    inFlight = true;
    if (observedAt === null) observedAt = options.clock.now();
    let outcome: PollOutcome | "failed";
    try {
      outcome = await options.poll();
    } catch {
      outcome = "failed";
    }
    inFlight = false;
    if (phase !== "running") return;
    const settledAt = options.clock.now();
    if (outcome === "failed") {
      consecutiveFailures += 1;
      if (consecutiveFailures >= options.failureLimit) setPhase("failed");
      else schedule();
      return;
    }
    consecutiveFailures = 0;
    if (outcome === "complete") {
      setPhase("complete");
      return;
    }
    if (outcome === "changed") idleMs = 0;
    else idleMs += settledAt - (observedAt ?? settledAt);
    observedAt = settledAt;
    if (idleMs >= options.idleLimitMs) setPhase("paused");
    else schedule();
  }

  return {
    get phase() {
      return phase;
    },
    start() {
      if (phase !== "idle") return;
      setPhase("running");
      schedule();
    },
    resume() {
      if (phase !== "paused" && phase !== "failed") return;
      consecutiveFailures = 0;
      idleMs = 0;
      observedAt = null;
      setPhase("running");
      if (!inFlight) {
        clearTimer();
        void tick();
      }
    },
    stop() {
      clearTimer();
      setPhase("stopped");
    },
  };
}
