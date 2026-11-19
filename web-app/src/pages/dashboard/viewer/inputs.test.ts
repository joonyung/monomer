import { describe, expect, it } from "vitest";
import { dashboardResultsStatus, fittingInputChipCount, scheduledRunTime } from "./inputs";

describe("Inputs bar chip overflow", () => {
  it("shows every chip when they fit without reserving the overflow control", () => {
    expect(fittingInputChipCount([80, 90, 100], 286, 8, 48)).toBe(3);
  });

  it("keeps leading chips and reserves room for the +N control", () => {
    expect(fittingInputChipCount([80, 90, 100], 285, 8, 48)).toBe(2);
    expect(fittingInputChipCount([80, 90, 100], 180, 8, 48)).toBe(1);
  });

  it("collapses every chip when not even one fits beside +N", () => {
    expect(fittingInputChipCount([200, 90], 120, 8, 48)).toBe(0);
    expect(fittingInputChipCount([], 0, 8, 48)).toBe(0);
  });
});

describe("Inputs bar results status", () => {
  it("prefers the running signal over the time of the results it will replace", () => {
    expect(dashboardResultsStatus("2026-10-01T09:02:00Z", true)).toEqual({ kind: "running" });
  });

  it("shows the latest displayed result time, or nothing before any result", () => {
    expect(dashboardResultsStatus("2026-10-01T09:02:00Z", false)).toEqual({ kind: "results", at: "2026-10-01T09:02:00Z" });
    expect(dashboardResultsStatus(null, false)).toEqual({ kind: "none" });
  });
});

describe("Scheduled run time", () => {
  it("joins a weekday date and the local time, adding the year only for another year", () => {
    const now = Date.parse("2026-10-01T12:00:00");
    const sameYear = scheduledRunTime(new Date(2026, 9, 1, 9, 0).toISOString(), now);
    expect(sameYear).toMatch(/ · /);
    expect(sameYear).not.toContain("2026");
    expect(scheduledRunTime(new Date(2025, 9, 1, 9, 0).toISOString(), now)).toContain("2025");
    expect(scheduledRunTime("not-a-time", now)).toBe("not-a-time");
  });
});
