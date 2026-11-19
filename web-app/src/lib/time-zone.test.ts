import { describe, expect, it } from "vitest";
import {
  browserKnowsTimeZone,
  timeZoneOffsetLabel,
  timeZoneOffsetMinutes,
  timestampToZonedWallClock,
  zonedWallClockToTimestamp,
} from "./time-zone";

describe("time zone wall clocks", () => {
  it("names a zone's offset at an instant", () => {
    const summer = new Date("2026-07-01T00:00:00Z");
    expect(timeZoneOffsetLabel("Asia/Seoul", summer)).toBe("GMT+9");
    expect(timeZoneOffsetLabel("UTC", summer)).toBe("GMT+0");
    expect(timeZoneOffsetLabel("America/New_York", summer)).toBe("GMT-4");
    expect(timeZoneOffsetLabel("America/New_York", new Date("2026-01-15T00:00:00Z"))).toBe("GMT-5");
    expect(timeZoneOffsetMinutes("Asia/Kolkata", summer)).toBe(330);
    expect(timeZoneOffsetMinutes("America/St_Johns", summer)).toBe(-150);
  });

  it("treats a zone the browser doesn't know as unavailable rather than UTC", () => {
    expect(browserKnowsTimeZone("Asia/Seoul")).toBe(true);
    expect(browserKnowsTimeZone("Mars/Olympus_Mons")).toBe(false);
    expect(timeZoneOffsetLabel("Mars/Olympus_Mons", new Date())).toBeNull();
    expect(zonedWallClockToTimestamp("2026-08-20T09:30", "Mars/Olympus_Mons")).toBeNull();
  });

  it("writes an entered wall-clock time with the zone's offset", () => {
    expect(zonedWallClockToTimestamp("2026-08-20T09:30", "Asia/Seoul")).toBe("2026-08-20T09:30:00+09:00");
    expect(zonedWallClockToTimestamp("2026-08-20T09:30:05", "UTC")).toBe("2026-08-20T09:30:05Z");
    expect(zonedWallClockToTimestamp("2026-08-20T09:30:05.25", "Asia/Kolkata")).toBe("2026-08-20T09:30:05.25+05:30");
    expect(zonedWallClockToTimestamp("2026-01-15T12:00", "America/New_York")).toBe("2026-01-15T12:00:00-05:00");
    expect(zonedWallClockToTimestamp("not a time", "UTC")).toBeNull();
  });

  it("reads a repeated hour as its first occurrence and moves a skipped hour forward", () => {
    // New York repeats 01:00–02:00 on 2026-11-01 and skips 02:00–03:00 on 2026-03-08.
    expect(zonedWallClockToTimestamp("2026-11-01T01:30", "America/New_York")).toBe("2026-11-01T01:30:00-04:00");
    expect(zonedWallClockToTimestamp("2026-03-08T02:30", "America/New_York")).toBe("2026-03-08T02:30:00-05:00");
    expect(timestampToZonedWallClock("2026-03-08T02:30:00-05:00", "America/New_York")).toBe("2026-03-08T03:30:00");
    // Berlin repeats 02:00–03:00 on 2026-10-25.
    expect(zonedWallClockToTimestamp("2026-10-25T02:30", "Europe/Berlin")).toBe("2026-10-25T02:30:00+02:00");
  });

  it("reads an instant as the zone's wall-clock time", () => {
    expect(timestampToZonedWallClock("2026-08-20T00:30:00Z", "Asia/Seoul")).toBe("2026-08-20T09:30:00");
    expect(timestampToZonedWallClock("2026-08-20T09:30:00+09:00", "UTC")).toBe("2026-08-20T00:30:00");
    expect(timestampToZonedWallClock("garbage", "UTC")).toBe("");
  });
});
