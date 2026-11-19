import type { SemanticMetricTimeRange, SemanticTimeGranularity } from "@/api/explore";

const TIME_VALUE = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?(Z)?$/;

/**
 * Reading form of a time group-by value at its grain: "Jun 1, 2026" (day), "Week of Jun 1" (week),
 * "Jun 2026" (month), "Q2 2026" (quarter), "2026" (year). Hour to second grains keep the date and
 * time; finer grains and values that are not ISO dates stay as returned. The wall-clock fields are
 * read as written, so a date never shifts with the viewer's time zone; a UTC instant says "UTC".
 */
export function formatTimeGrainValue(value: string, granularity: SemanticTimeGranularity, locale: string | undefined): string {
  const match = TIME_VALUE.exec(value);
  if (!match) return value;
  const [, year, month, day, hour, minute, second, utc] = match;
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour ?? 0), Number(minute ?? 0), Number(second ?? 0)));
  if (date.getUTCMonth() + 1 !== Number(month) || date.getUTCDate() !== Number(day)) return value;
  const format = (options: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale, { ...options, timeZone: "UTC" }).format(date);
  const zone = utc ? " UTC" : "";
  switch (granularity) {
    case "YEAR":
      return year as string;
    case "QUARTER":
      return `Q${Math.floor((Number(month) - 1) / 3) + 1} ${year}`;
    case "MONTH":
      return format({ month: "short", year: "numeric" });
    case "WEEK":
      return `Week of ${format({ month: "short", day: "numeric" })}`;
    case "DAY":
      return format({ month: "short", day: "numeric", year: "numeric" });
    case "HOUR":
    case "MINUTE":
      return `${format({ month: "short", day: "numeric", year: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })}${zone}`;
    case "SECOND":
      return `${format({ month: "short", day: "numeric", year: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" })}${zone}`;
    default:
      return value;
  }
}

/**
 * Axis labels for the distinct values of one time column. Labels that would merge two values (for
 * example weeks a year apart) fall back to the returned values so no two points share a category.
 */
export function timeGrainLabels(values: readonly string[], granularity: SemanticTimeGranularity, locale: string | undefined): Record<string, string> {
  const distinct = [...new Set(values)];
  const labels = distinct.map((value) => formatTimeGrainValue(value, granularity, locale));
  const unique = new Set(labels).size === labels.length;
  return Object.fromEntries(distinct.map((value, index) => [value, unique ? labels[index] as string : value]));
}

interface RangeEndpointText {
  date: string;
  /** "Sep 1": the date without its year, for a range that shares one. */
  monthDay: string;
  year: string;
  time: string | null;
}

function rangeEndpoint(value: string, locale: string | undefined): RangeEndpointText | null {
  const match = TIME_VALUE.exec(value);
  if (!match) return null;
  const [, year, month, day, hour, minute, second, utc] = match;
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour ?? 0), Number(minute ?? 0), Number(second ?? 0)));
  if (date.getUTCMonth() + 1 !== Number(month) || date.getUTCDate() !== Number(day)) return null;
  const format = (options: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale, { ...options, timeZone: "UTC" }).format(date);
  const midnight = !hour || (Number(hour) === 0 && Number(minute ?? 0) === 0 && Number(second ?? 0) === 0);
  return {
    date: format({ month: "short", day: "numeric", year: "numeric" }),
    monthDay: format({ month: "short", day: "numeric" }),
    year: year as string,
    time: midnight ? null : `${format({ hour: "2-digit", minute: "2-digit", hourCycle: "h23" })}${utc ? " UTC" : ""}`,
  };
}

/**
 * Reading form of a metric time range: "Sep 1 – Sep 30, 2024", "Dec 1, 2023 – Jan 31, 2024", or with a time
 * of day when a boundary isn't midnight ("Sep 1, 2024, 09:00 UTC – Sep 2, 2024"). Values that aren't ISO
 * dates stay as written.
 */
export function formatMetricTimeRange(range: SemanticMetricTimeRange, locale: string | undefined): string {
  const start = rangeEndpoint(range.start.value, locale);
  const end = rangeEndpoint(range.end.value, locale);
  if (!start || !end) return `${range.start.value} – ${range.end.value}`;
  if (!start.time && !end.time && start.year === end.year) return `${start.monthDay} – ${end.date}`;
  const text = (endpoint: RangeEndpointText) => endpoint.time ? `${endpoint.date}, ${endpoint.time}` : endpoint.date;
  return `${text(start)} – ${text(end)}`;
}
