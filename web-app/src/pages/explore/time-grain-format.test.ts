import { describe, expect, it } from "vitest";
import { formatMetricTimeRange, formatTimeGrainValue, timeGrainLabels } from "./time-grain-format";

describe("time grain values", () => {
  it("reads each calendar grain without shifting the date", () => {
    expect(formatTimeGrainValue("2026-06-01", "DAY", "en-US")).toBe("Jun 1, 2026");
    expect(formatTimeGrainValue("2026-06-01", "WEEK", "en-US")).toBe("Week of Jun 1");
    expect(formatTimeGrainValue("2026-06-01", "MONTH", "en-US")).toBe("Jun 2026");
    expect(formatTimeGrainValue("2026-05-01", "QUARTER", "en-US")).toBe("Q2 2026");
    expect(formatTimeGrainValue("2026-12-01", "QUARTER", "en-US")).toBe("Q4 2026");
    expect(formatTimeGrainValue("2026-01-01", "YEAR", "en-US")).toBe("2026");
    expect(formatTimeGrainValue("2026-01-01T00:00:00.000000Z", "DAY", "en-US")).toBe("Jan 1, 2026");
  });

  it("keeps date and time for finer grains and marks UTC instants", () => {
    expect(formatTimeGrainValue("2026-06-01T14:00:00.000000", "HOUR", "en-US")).toBe("Jun 1, 2026, 14:00");
    expect(formatTimeGrainValue("2026-06-01T14:05:00.000000Z", "MINUTE", "en-US")).toBe("Jun 1, 2026, 14:05 UTC");
    expect(formatTimeGrainValue("2026-06-01T14:05:09.000000", "SECOND", "en-US")).toBe("Jun 1, 2026, 14:05:09");
    expect(formatTimeGrainValue("2026-06-01T14:05:09.123000", "MILLISECOND", "en-US")).toBe("2026-06-01T14:05:09.123000");
  });

  it("leaves values that are not ISO dates as returned", () => {
    expect(formatTimeGrainValue("June", "MONTH", "en-US")).toBe("June");
    expect(formatTimeGrainValue("2026-13-45", "DAY", "en-US")).toBe("2026-13-45");
  });

  it("falls back to returned values when two values would share an axis label", () => {
    expect(timeGrainLabels(["2026-06-01", "2026-07-01", "2026-06-01"], "MONTH", "en-US"))
      .toEqual({ "2026-06-01": "Jun 2026", "2026-07-01": "Jul 2026" });
    expect(timeGrainLabels(["2025-06-02", "2026-06-02"], "WEEK", "en-US"))
      .toEqual({ "2025-06-02": "2025-06-02", "2026-06-02": "2026-06-02" });
  });
});

describe("metric time ranges", () => {
  const range = (start: string, end: string, kind: "DATE" | "TIMESTAMP" = "DATE") => ({ start: { kind, value: start }, end: { kind, value: end } });

  it("reads dates as a range that shares its year once", () => {
    expect(formatMetricTimeRange(range("2024-09-01", "2024-09-30"), "en-US")).toBe("Sep 1 – Sep 30, 2024");
    expect(formatMetricTimeRange(range("2023-12-01", "2024-01-31"), "en-US")).toBe("Dec 1, 2023 – Jan 31, 2024");
  });

  it("drops midnight times and keeps other times with their zone", () => {
    expect(formatMetricTimeRange(range("2024-09-01T00:00:00Z", "2024-10-01T00:00:00Z", "TIMESTAMP"), "en-US")).toBe("Sep 1 – Oct 1, 2024");
    expect(formatMetricTimeRange(range("2024-09-01T09:30:00Z", "2024-09-02T00:00:00Z", "TIMESTAMP"), "en-US")).toBe("Sep 1, 2024, 09:30 UTC – Sep 2, 2024");
  });

  it("keeps values that aren't ISO dates as written", () => {
    expect(formatMetricTimeRange(range("last month", "2024-09-30"), "en-US")).toBe("last month – 2024-09-30");
  });
});
