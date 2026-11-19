import { describe, expect, it } from "vitest";
import { sessionCreatedLabel, sessionExactTime } from "./session-time";

describe("session time presentation", () => {
  it("uses calendar days and includes a different year", () => {
    const now = new Date(2026, 0, 1, 0, 10).getTime();
    expect(sessionCreatedLabel(new Date(2026, 0, 1, 0, 5).toISOString(), now)).toMatch(/^Today,/);
    expect(sessionCreatedLabel(new Date(2025, 11, 31, 23, 55).toISOString(), now)).toMatch(/^Yesterday,/);
    expect(sessionCreatedLabel(new Date(2025, 10, 1).toISOString(), now)).toContain("2025");
  });
  it("retains exact timestamps", () => {
    const value = new Date(2026, 8, 7, 16, 39, 25).toISOString();
    expect(sessionExactTime(value)).toContain("2026");
    expect(sessionExactTime(value)).toMatch(/^Sep 7, 2026, 4:39:25\sPM/);
  });
});
