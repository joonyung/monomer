import { describe, expect, it } from "vitest";
import { formatExactTime, formatResultBytes, runDurationSeconds, runStartedLabel } from "./clock";
import type { RunPresentation } from "./types";

const runningRun: RunPresentation = {
  id: "run-1",
  title: "Revenue",
  subtitle: "SQL · Warehouse",
  state: "RUNNING",
  createdAt: "2026-08-24T00:00:00.000Z",
  updatedAt: "2026-08-24T00:00:00.000Z",
  resultAvailable: false,
  failureMessage: null,
  isCurrentTarget: false,
};

describe("run presentation clock", () => {
  it("calculates elapsed time from the local observation clock for running runs", () => {
    expect(runDurationSeconds(runningRun, Date.parse("2026-08-24T00:01:07.000Z"))).toBe(67);
  });

  it("uses the presentation fallback for demo timestamps without a date", () => {
    expect(runDurationSeconds({ ...runningRun, createdAt: "Aug 24, 09:00", elapsedSeconds: 12 })).toBe(12);
    expect(runStartedLabel({ ...runningRun, createdAt: "Aug 24, 09:00" })).toBe("Aug 24, 09:00");
  });

  it("formats exact run times in English", () => {
    expect(formatExactTime(new Date(2026, 8, 7, 16, 39).toISOString())).toMatch(/^Sep 7, 04:39\sPM$/);
  });
});

describe("result byte size", () => {
  it.each([["0", "0 B"], ["546", "546 B"], ["1200000", "1.2 MB"]])("formats %s bytes as %s", (byteSize, formatted) => {
    expect(formatResultBytes(byteSize)).toBe(formatted);
  });
});
