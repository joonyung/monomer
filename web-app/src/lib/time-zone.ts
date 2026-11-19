// Wall-clock arithmetic in an IANA time zone, using the browser's time zone data.

const WALL_CLOCK = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(\.\d{1,9})?)?$/;
const LONG_OFFSET = /^GMT(?:([+-])(\d{1,2})(?::(\d{2}))?(?::\d{2})?)?$/;

function offsetName(timeZone: string, at: Date, style: "shortOffset" | "longOffset"): string | null {
  try {
    return new Intl.DateTimeFormat("en-US", { timeZone, timeZoneName: style }).formatToParts(at)
      .find((part) => part.type === "timeZoneName")?.value ?? null;
  } catch {
    return null;
  }
}

/** Whether this browser has data for the zone; a zone newer than its time zone data does not. */
export function browserKnowsTimeZone(timeZone: string): boolean {
  return offsetName(timeZone, new Date(0), "shortOffset") !== null;
}

/** The zone's offset from UTC at an instant, as "GMT+9", "GMT-3:30", or "GMT+0"; null when the browser lacks the zone. */
export function timeZoneOffsetLabel(timeZone: string, at: Date): string | null {
  const name = offsetName(timeZone, at, "shortOffset");
  // ICU versions write a zero offset as either "GMT" or "GMT+0".
  return name === "GMT" ? "GMT+0" : name;
}

/** Minutes the zone is ahead of UTC at an instant; null when the browser lacks the zone. */
export function timeZoneOffsetMinutes(timeZone: string, at: Date): number | null {
  const match = LONG_OFFSET.exec(offsetName(timeZone, at, "longOffset") ?? "");
  if (!match) return null;
  if (!match[1]) return 0;
  const minutes = Number(match[2]) * 60 + Number(match[3] ?? 0);
  return match[1] === "-" ? -minutes : minutes;
}

/** "+09:00", "-04:00", or "Z" for a zero offset. */
export function offsetSuffix(minutes: number): string {
  if (minutes === 0) return "Z";
  const sign = minutes < 0 ? "-" : "+";
  const absolute = Math.abs(minutes);
  return `${sign}${String(Math.floor(absolute / 60)).padStart(2, "0")}:${String(absolute % 60).padStart(2, "0")}`;
}

const DAY_MS = 86_400_000;

/**
 * The zone's offset, in minutes ahead of UTC, for a wall-clock time given as the milliseconds it would be
 * in UTC. An hour repeated by a daylight saving change takes its first occurrence, and an hour the change
 * skips keeps the offset from before it, so it lands an hour later. Null when the browser lacks the zone.
 */
export function wallClockOffsetMinutes(wallClockAsUtcMs: number, timeZone: string): number | null {
  // Offset changes are months apart, so the offsets a day either side are the only candidates.
  const before = timeZoneOffsetMinutes(timeZone, new Date(wallClockAsUtcMs - DAY_MS));
  const after = timeZoneOffsetMinutes(timeZone, new Date(wallClockAsUtcMs + DAY_MS));
  if (before === null || after === null) return null;
  const fitting = [before, after].filter((offset) => timeZoneOffsetMinutes(timeZone, new Date(wallClockAsUtcMs - offset * 60_000)) === offset);
  return fitting.length > 0 ? Math.max(...fitting) : before;
}

/**
 * The instant a wall-clock time ("2026-08-20T09:30" or with seconds) names in a zone, written as that
 * wall-clock time with the zone's offset: "2026-08-20T09:30:00+09:00", or "…Z" where the offset is zero.
 * Repeated and skipped hours resolve as `wallClockOffsetMinutes` does. Null when the text isn't a
 * wall-clock time or the browser lacks the zone.
 */
export function zonedWallClockToTimestamp(wallClock: string, timeZone: string): string | null {
  const match = WALL_CLOCK.exec(wallClock);
  if (!match) return null;
  const [, year, month, day, hour, minute, second, fraction] = match;
  const offset = wallClockOffsetMinutes(Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute), Number(second ?? 0)), timeZone);
  if (offset === null) return null;
  return `${year}-${month}-${day}T${hour}:${minute}:${second ?? "00"}${fraction ?? ""}${offsetSuffix(offset)}`;
}

/** The zone's wall-clock time for an instant, as "2026-08-20T09:30:00"; empty when the value isn't a valid instant. */
export function timestampToZonedWallClock(timestamp: string, timeZone: string): string {
  const instant = new Date(timestamp);
  if (Number.isNaN(instant.getTime())) return "";
  const offset = timeZoneOffsetMinutes(timeZone, instant);
  if (offset === null) return "";
  return new Date(instant.getTime() + offset * 60_000).toISOString().slice(0, 19);
}
