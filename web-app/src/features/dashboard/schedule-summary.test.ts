import { describe, expect, it } from "vitest";
import {
  CUSTOM_SCHEDULE,
  formatReaderTime,
  formatScheduleTime,
  relativeTime,
  scheduleListLabel,
  scheduleRunPresentation,
  scheduleSummary,
} from "./schedule-summary";

describe("scheduleSummary", () => {
  it.each([
    ["0 9 * * *", "Daily · 9:00 AM"],
    ["30 17 * * *", "Daily · 5:30 PM"],
    ["0 0 * * *", "Daily · 12:00 AM"],
    ["0 12 * * *", "Daily · 12:00 PM"],
    ["0 9,17 * * *", "Daily · 9:00 AM, 5:00 PM"],
    ["0 8 * * 1-5", "Weekdays · 8:00 AM"],
    ["0 8 * * MON-FRI", "Weekdays · 8:00 AM"],
    ["0 8 * * mon", "Mondays · 8:00 AM"],
    ["0 8 * * 1", "Mondays · 8:00 AM"],
    ["0 8 * * 0", "Sundays · 8:00 AM"],
    ["0 8 * * 7", "Sundays · 8:00 AM"],
    ["0 10 * * 0,6", "Weekends · 10:00 AM"],
    ["0 10 * * 6,7", "Weekends · 10:00 AM"],
    ["0 8 * * 1,3,5", "Mon, Wed, Fri · 8:00 AM"],
    ["0 8 * * 0-6", "Daily · 8:00 AM"],
    ["0 6 1 * *", "Monthly on the 1st · 6:00 AM"],
    ["0 6 22 * *", "Monthly on the 22nd · 6:00 AM"],
    ["0 6 13 * *", "Monthly on the 13th · 6:00 AM"],
    ["0 * * * *", "Hourly"],
    ["15 * * * *", "Hourly at :15"],
    ["*/15 * * * *", "Every 15 minutes"],
    ["0 */6 * * *", "Every 6 hours"],
    ["* * * * *", "Every minute"],
  ])("reads %s as %s", (cron, label) => {
    expect(scheduleSummary(cron)).toBe(label);
  });

  it.each([
    "0 9 * 1 *",
    "0 9 1 * 1",
    "0 9-17 * * *",
    "0,30 9 * * *",
    "0 9 * * 5-1",
    "0 25 * * *",
    "0 9 * *",
    "@daily",
    "0 9 L * *",
    "",
  ])("falls back to a custom schedule for %s", (cron) => {
    expect(scheduleSummary(cron)).toBe(CUSTOM_SCHEDULE);
  });
});

describe("scheduleListLabel", () => {
  const at = new Date("2026-10-01T00:00:00Z");

  it("summarizes none, one, and several schedules", () => {
    expect(scheduleListLabel([], "Asia/Seoul", at)).toBe("—");
    expect(scheduleListLabel([{ cron: "0 9 * * *", timeZone: "Asia/Seoul" }], "Asia/Seoul", at)).toBe("Daily · 9:00 AM");
    expect(scheduleListLabel([{ cron: "0 9 * * *", timeZone: "UTC" }, { cron: "0 8 * * 1", timeZone: "UTC" }], "UTC", at)).toBe("2 schedules");
    expect(scheduleListLabel([{ cron: "0 9 L * *", timeZone: "UTC" }], "UTC", at)).toBe(CUSTOM_SCHEDULE);
  });

  it("names the schedule's zone only when a wall-clock time differs from the reader's zone", () => {
    expect(scheduleListLabel([{ cron: "0 9 * * *", timeZone: "America/New_York" }], "Asia/Seoul", at)).toBe("Daily · 9:00 AM EDT");
    expect(scheduleListLabel([{ cron: "0 * * * *", timeZone: "America/New_York" }], "Asia/Seoul", at)).toBe("Hourly");
  });
});

describe("schedule times", () => {
  const now = new Date("2026-10-01T12:00:00Z");

  it("formats an instant in the schedule's zone and adds the year only when it differs", () => {
    expect(formatScheduleTime("2026-10-02T00:00:00Z", "Asia/Seoul", now)).toBe("Fri, Oct 2 · 9:00 AM");
    expect(formatScheduleTime("2027-01-04T00:00:00Z", "Asia/Seoul", now)).toBe("Mon, Jan 4, 2027 · 9:00 AM");
  });

  it("adds the reader's clock only when it reads differently", () => {
    expect(formatReaderTime("2026-10-02T00:00:00Z", "Asia/Seoul", "UTC", now)).toBe("Fri, Oct 2 · 12:00 AM");
    expect(formatReaderTime("2026-10-02T00:00:00Z", "Asia/Seoul", "Asia/Tokyo", now)).toBeNull();
  });

  it("describes the distance to an instant", () => {
    expect(relativeTime("2026-10-02T00:00:00Z", now)).toBe("in 12 hours");
    expect(relativeTime("2026-10-01T12:20:00Z", now)).toBe("in 20 minutes");
    expect(relativeTime("2026-10-04T12:00:00Z", now)).toBe("in 3 days");
    expect(relativeTime("2026-10-01T10:00:00Z", now)).toBe("2h ago");
    expect(relativeTime("2026-10-01T11:59:30Z", now)).toBe("Just now");
    expect(relativeTime("2026-10-01T12:00:30Z", now)).toBe("in 1 minute");
  });
});

describe("scheduleRunPresentation", () => {
  it("reads an accepted run through its result state in the shared status vocabulary", () => {
    expect(scheduleRunPresentation({ outcome: "ACCEPTED", reason: null, resultState: "READY" })).toEqual({ state: "COMPLETED", reason: null });
    expect(scheduleRunPresentation({ outcome: "ACCEPTED", reason: null, resultState: "PARTIAL_FAILURE" })).toEqual({ state: "PARTIAL_FAILURE", reason: null });
    expect(scheduleRunPresentation({ outcome: "ACCEPTED", reason: null, resultState: "RUNNING" })).toEqual({ state: "RUNNING", reason: null });
    expect(scheduleRunPresentation({ outcome: "ACCEPTED", reason: null, resultState: "UNAVAILABLE" })).toEqual({ state: "UNAVAILABLE", reason: null });
  });

  it("explains skipped runs in words, including a workspace at its run limit", () => {
    expect(scheduleRunPresentation({ outcome: "SKIPPED", reason: "LATE", resultState: null })).toEqual({ state: "SKIPPED", reason: "started more than 15 minutes late" });
    expect(scheduleRunPresentation({ outcome: "SKIPPED", reason: "OVERLAP", resultState: null })).toEqual({ state: "SKIPPED", reason: "the previous run was still going" });
    expect(scheduleRunPresentation({ outcome: "ADMISSION_FAILED", reason: "EXECUTION_ADMISSION_REJECTED", resultState: null })).toEqual({ state: "SKIPPED", reason: "workspace at its run limit" });
  });

  it("keeps definition failures as failures and never shows an unknown raw reason", () => {
    expect(scheduleRunPresentation({ outcome: "ADMISSION_FAILED", reason: "INVALID_SCHEDULE_PARAMETERS", resultState: null })).toEqual({ state: "ADMISSION_FAILED", reason: "inputs couldn't be calculated" });
    expect(scheduleRunPresentation({ outcome: "ADMISSION_FAILED", reason: "PLANNING_TIMEOUT", resultState: null })).toEqual({ state: "ADMISSION_FAILED", reason: "query planning timed out" });
    expect(scheduleRunPresentation({ outcome: "ADMISSION_FAILED", reason: "secret warehouse error", resultState: null })).toEqual({ state: "ADMISSION_FAILED", reason: "couldn't start" });
  });
});
