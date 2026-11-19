import { describe, expect, it, vi } from "vitest";
import { restoreLatestSession } from "./latest-session-restore";

describe("initial latest Session restore", () => {
  it("adopts the first Session after an initially empty History poll", () => {
    const latestRestoreAttempt = { current: null as string | null };
    const selectSession = vi.fn();

    restoreLatestSession(latestRestoreAttempt, "workspace:dashboard", undefined, selectSession);
    restoreLatestSession(latestRestoreAttempt, "workspace:dashboard", undefined, selectSession);
    expect(selectSession).not.toHaveBeenCalled();
    expect(latestRestoreAttempt.current).toBeNull();

    restoreLatestSession(latestRestoreAttempt, "workspace:dashboard", "first-session", selectSession);
    expect(selectSession).toHaveBeenCalledExactlyOnceWith("first-session");

    restoreLatestSession(latestRestoreAttempt, "workspace:dashboard", "newer-session", selectSession);
    expect(selectSession).toHaveBeenCalledTimes(1);
  });

  it("restores again on a new bare entry", () => {
    const latestRestoreAttempt = { current: null as string | null };
    const selectSession = vi.fn();
    restoreLatestSession(latestRestoreAttempt, "workspace:dashboard", "first-session", selectSession);

    latestRestoreAttempt.current = null;
    restoreLatestSession(latestRestoreAttempt, "workspace:dashboard", "newer-session", selectSession);
    expect(selectSession.mock.calls).toEqual([["first-session"], ["newer-session"]]);
  });
});
