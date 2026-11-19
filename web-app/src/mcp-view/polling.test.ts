import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPoller, POLL_FAILURE_LIMIT, POLL_IDLE_LIMIT_MS, POLL_INTERVAL_MS, type PollOutcome, type PollingPhase } from "./polling";

const clock = {
  now: () => Date.now(),
  setTimeout: (callback: () => void, delayMs: number) => setTimeout(callback, delayMs),
  clearTimeout: (handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

function poller(poll: () => Promise<PollOutcome>, active: () => boolean = () => true) {
  const phases: PollingPhase[] = [];
  const instance = createPoller({
    intervalMs: POLL_INTERVAL_MS,
    idleLimitMs: POLL_IDLE_LIMIT_MS,
    failureLimit: POLL_FAILURE_LIMIT,
    clock,
    isActive: active,
    onPhaseChange: (phase) => phases.push(phase),
    poll,
  });
  return { instance, phases };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe("polling", () => {
  it("polls every two seconds after starting", async () => {
    const poll = vi.fn(async (): Promise<PollOutcome> => "changed");
    const { instance } = poller(poll);
    instance.start();
    expect(poll).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(poll).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    expect(poll).toHaveBeenCalledTimes(4);
  });

  it("does not poll while the view is hidden or out of the viewport", async () => {
    let active = false;
    const poll = vi.fn(async (): Promise<PollOutcome> => "unchanged");
    const { instance } = poller(poll, () => active);
    instance.start();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(poll).not.toHaveBeenCalled();
    active = true;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(poll).toHaveBeenCalledTimes(1);
  });

  it("never overlaps polls while one is in flight", async () => {
    let finish: (outcome: PollOutcome) => void = () => undefined;
    const poll = vi.fn(() => new Promise<PollOutcome>((resolve) => { finish = resolve; }));
    const { instance } = poller(poll);
    instance.start();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 10);
    expect(poll).toHaveBeenCalledTimes(1);
    finish("unchanged");
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(poll).toHaveBeenCalledTimes(2);
  });

  it("pauses after ten observed minutes without change and resumes on request", async () => {
    const poll = vi.fn(async (): Promise<PollOutcome> => "unchanged");
    const { instance, phases } = poller(poll);
    instance.start();
    await vi.advanceTimersByTimeAsync(POLL_IDLE_LIMIT_MS - POLL_INTERVAL_MS);
    expect(instance.phase).toBe("running");
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2);
    expect(instance.phase).toBe("paused");
    const pausedAt = poll.mock.calls.length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 10);
    expect(poll).toHaveBeenCalledTimes(pausedAt);
    instance.resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(poll).toHaveBeenCalledTimes(pausedAt + 1);
    expect(phases).toEqual(["running", "paused", "running"]);
  });

  it("counts idle time only while the view is active", async () => {
    let active = true;
    const poll = vi.fn(async (): Promise<PollOutcome> => "unchanged");
    const { instance } = poller(poll, () => active);
    instance.start();
    await vi.advanceTimersByTimeAsync(POLL_IDLE_LIMIT_MS / 2);
    active = false;
    await vi.advanceTimersByTimeAsync(POLL_IDLE_LIMIT_MS);
    active = true;
    await vi.advanceTimersByTimeAsync(POLL_IDLE_LIMIT_MS / 2 - POLL_INTERVAL_MS * 4);
    expect(instance.phase).toBe("running");
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 6);
    expect(instance.phase).toBe("paused");
  });

  it("restarts the idle window on every observed change", async () => {
    let outcome: PollOutcome = "unchanged";
    const { instance } = poller(async () => outcome);
    instance.start();
    await vi.advanceTimersByTimeAsync(POLL_IDLE_LIMIT_MS - POLL_INTERVAL_MS);
    outcome = "changed";
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    outcome = "unchanged";
    await vi.advanceTimersByTimeAsync(POLL_IDLE_LIMIT_MS - POLL_INTERVAL_MS);
    expect(instance.phase).toBe("running");
  });

  it("stops after three consecutive failures and retries on request", async () => {
    let fail = true;
    const poll = vi.fn(async (): Promise<PollOutcome> => {
      if (fail) throw new Error("unavailable");
      return "unchanged";
    });
    const { instance } = poller(poll);
    instance.start();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2);
    expect(instance.phase).toBe("running");
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(instance.phase).toBe("failed");
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(poll).toHaveBeenCalledTimes(POLL_FAILURE_LIMIT);
    fail = false;
    instance.resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(instance.phase).toBe("running");
    expect(poll).toHaveBeenCalledTimes(POLL_FAILURE_LIMIT + 1);
  });

  it("resets the failure count after a successful poll", async () => {
    const outcomes = ["fail", "fail", "ok", "fail", "fail", "ok"];
    const { instance } = poller(async () => {
      if (outcomes.shift() === "fail") throw new Error("unavailable");
      return "unchanged";
    });
    instance.start();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 6);
    expect(instance.phase).toBe("running");
  });

  it("ends when the source is complete and when stopped", async () => {
    const complete = poller(async () => "complete");
    complete.instance.start();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(complete.instance.phase).toBe("complete");

    const poll = vi.fn(async (): Promise<PollOutcome> => "changed");
    const stopped = poller(poll);
    stopped.instance.start();
    stopped.instance.stop();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    expect(poll).not.toHaveBeenCalled();
    expect(stopped.instance.phase).toBe("stopped");
  });
});
