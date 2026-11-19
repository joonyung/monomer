import { describe, expect, it } from "vitest";
import {
  dashboardValuesEqual,
  isCanonicalDecimal,
  isDashboardControlValueValid,
  isDashboardValueShapeValid,
  parseEditorValue,
  dateTimeLocalToTimestamp,
  timestampToDateTimeLocal,
} from "@/features/dashboard/dashboard-value";

describe("dashboard value contract", () => {
  it("covers all eleven stored value types", () => {
    expect(isDashboardValueShapeValid("TEXT", "North")).toBe(true);
    expect(isDashboardValueShapeValid("NUMBER", "123.45")).toBe(true);
    expect(isDashboardValueShapeValid("BOOLEAN", false)).toBe(true);
    expect(isDashboardValueShapeValid("DATE", "2026-08-25")).toBe(true);
    expect(isDashboardValueShapeValid("TIMESTAMP", "2026-08-25T09:00:00Z")).toBe(true);
    expect(isDashboardValueShapeValid("TEXT_SET", ["North", "South"])).toBe(true);
    expect(isDashboardValueShapeValid("NUMBER_SET", ["1", "2.5"])).toBe(true);
    expect(isDashboardValueShapeValid("DATE_SET", ["2026-08-01", "2026-08-25"])).toBe(true);
    expect(isDashboardValueShapeValid("TIMESTAMP_SET", ["2026-08-01T00:00:00Z"])).toBe(true);
    expect(isDashboardValueShapeValid("DATE_RANGE", { start: "2026-08-01", end: "2026-08-25" })).toBe(true);
    expect(isDashboardValueShapeValid("TIMESTAMP_RANGE", { start: "2026-08-01T00:00:00Z", end: "2026-08-25T00:00:00Z" })).toBe(true);
  });

  it("keeps invalid intermediate drafts out of commits", () => {
    expect(isCanonicalDecimal("-")).toBe(false);
    expect(isCanonicalDecimal("1.")).toBe(false);
    expect(parseEditorValue("NUMBER", "-")).toBeUndefined();
    expect(parseEditorValue("NUMBER", "1.")).toBeUndefined();
    expect(isDashboardValueShapeValid("NUMBER", "123.45")).toBe(true);
    expect(isDashboardValueShapeValid("NUMBER", "1.")).toBe(false);
    expect(isDashboardValueShapeValid("DATE", "2026-02-30")).toBe(false);
    expect(isDashboardValueShapeValid("TIMESTAMP", "2026-02-30T09:00:00Z")).toBe(false);
    expect(isDashboardValueShapeValid("TIMESTAMP", "2026-08-25T24:00:00Z")).toBe(false);
  });

  it("validates nullable, duplicate, ordered range, and exact shapes", () => {
    expect(isDashboardValueShapeValid("DATE", null)).toBe(true);
    expect(isDashboardValueShapeValid("TEXT_SET", ["North", "South"])).toBe(true);
    expect(isDashboardValueShapeValid("TEXT_SET", ["North", "North"])).toBe(false);
    expect(isDashboardValueShapeValid("TEXT_SET", [])).toBe(false);
    expect(isDashboardValueShapeValid("TEXT_SET", [null])).toBe(false);
    expect(isDashboardValueShapeValid("DATE_RANGE", null)).toBe(true);
    expect(isDashboardValueShapeValid("DATE_RANGE", { start: "2026-01-01" })).toBe(false);
    expect(isDashboardValueShapeValid("DATE_RANGE", { start: "2026-01-01", end: null })).toBe(false);
    expect(isDashboardValueShapeValid("DATE_RANGE", { start: "2026-01-01", end: 20260110 })).toBe(false);
    expect(isDashboardValueShapeValid("DATE_RANGE", { start: "2026-01-11", end: "2026-01-10" })).toBe(false);
    expect(isDashboardValueShapeValid("DATE_RANGE", { start: "2026-01-01", end: "2026-01-10", extra: true })).toBe(false);
  });

  it("applies typed options and bounds only to user input", () => {
    const textControl = { options: [{ value: "north", label: "North" }, { value: "south", label: "South" }] };
    expect(isDashboardControlValueValid("TEXT", "north", textControl)).toBe(true);
    expect(isDashboardControlValueValid("TEXT", "west", textControl)).toBe(false);
    expect(isDashboardControlValueValid("NUMBER", "10", { min: "0", max: "10" })).toBe(true);
    expect(isDashboardControlValueValid("NUMBER", "10.1", { min: "0", max: "10" })).toBe(false);
    expect(isDashboardValueShapeValid("TEXT", "west")).toBe(true);
    expect(isDashboardValueShapeValid("NUMBER", "10.1")).toBe(true);
    expect(isDashboardValueShapeValid("TEXT_SET", ["south", "west", "north"])).toBe(true);
  });

  it("compares number and timestamp values by their typed identity", () => {
    expect(dashboardValuesEqual("NUMBER", "1", "1.0")).toBe(true);
    expect(dashboardValuesEqual("TIMESTAMP", "2026-08-25T09:00:00Z", "2026-08-25T18:00:00+09:00")).toBe(true);
    expect(dashboardValuesEqual("TIMESTAMP", "2026-08-25T09:00:00.000000001Z", "2026-08-25T09:00:00.000000002Z")).toBe(false);
    expect(dashboardValuesEqual("TIMESTAMP", "2026-08-25T09:00:00.000000001Z", "2026-08-25T18:00:00.000000001+09:00")).toBe(true);
    expect(isDashboardControlValueValid("TIMESTAMP", "2026-08-25T09:00:00.0000000015Z", {
      min: "2026-08-25T09:00:00.000000001Z",
      max: "2026-08-25T09:00:00.000000002Z",
    })).toBe(true);
    expect(isDashboardControlValueValid("TIMESTAMP", "2026-08-25T09:00:00.000000003Z", {
      min: "2026-08-25T09:00:00.000000001Z",
      max: "2026-08-25T09:00:00.000000002Z",
    })).toBe(false);
  });

  it("parses multiple free tokens and shows timestamp controls in UTC when the zone is unknown", () => {
    expect(parseEditorValue("TEXT_SET", "North, South")).toEqual(["North", "South"]);
    expect(parseEditorValue("NUMBER_SET", "1, 2.5")).toEqual(["1", "2.5"]);
    expect(timestampToDateTimeLocal("2026-08-25T18:00:00+09:00", "UTC")).toBe("2026-08-25T09:00:00");
    expect(timestampToDateTimeLocal("2026-08-25T09:00:00.000000001Z", "UTC")).toBe("2026-08-25T09:00:00.000000001");
    expect(timestampToDateTimeLocal("2026-08-25T18:00:00.000000001+09:00", "UTC")).toBe("2026-08-25T09:00:00.000000001");
    expect(dateTimeLocalToTimestamp("2026-08-25T09:00", "UTC")).toBe("2026-08-25T09:00:00Z");
    expect(dateTimeLocalToTimestamp("2026-02-30T09:00", "UTC")).toBeUndefined();
  });

  it("reads and shows timestamp controls as the Reporting time zone's wall clock when it is known", () => {
    expect(dateTimeLocalToTimestamp("2026-08-25T14:00", "Asia/Seoul")).toBe("2026-08-25T14:00:00+09:00");
    expect(dateTimeLocalToTimestamp("2026-08-25T14:00:05.000000001", "Asia/Seoul")).toBe("2026-08-25T14:00:05.000000001+09:00");
    expect(timestampToDateTimeLocal("2026-08-25T05:00:00Z", "Asia/Seoul")).toBe("2026-08-25T14:00:00");
    expect(timestampToDateTimeLocal("2026-08-25T05:00:00.000000001Z", "Asia/Seoul")).toBe("2026-08-25T14:00:00.000000001");
    expect(dateTimeLocalToTimestamp("2026-02-30T09:00", "Asia/Seoul")).toBeUndefined();
  });

  it("reads a repeated hour as its first occurrence", () => {
    // New York repeats 01:00–02:00 on 2026-11-01: 01:30 first falls at 05:30Z, then again at 06:30Z.
    expect(dateTimeLocalToTimestamp("2026-11-01T01:30", "America/New_York")).toBe("2026-11-01T01:30:00-04:00");
    expect(timestampToDateTimeLocal("2026-11-01T05:30:00Z", "America/New_York")).toBe("2026-11-01T01:30:00");
    expect(timestampToDateTimeLocal("2026-11-01T06:30:00Z", "America/New_York")).toBe("2026-11-01T01:30:00");
  });
});
