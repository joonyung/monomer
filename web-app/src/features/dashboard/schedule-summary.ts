import { formatRelativeTime } from "@/lib/utils/relative-time";
import type { DashboardScheduleOccurrence } from "@/api/dashboard";
import type { WorkStatusState } from "@/components/ui/WorkStatus";

export const CUSTOM_SCHEDULE = "Custom schedule";

export interface CronDescription {
  label: string;
  // True when the label names a wall-clock time, so the schedule's time zone changes its meaning.
  timeOfDay: boolean;
}

const DAY_NAMES = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"] as const;
const DAY_ABBREVIATIONS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"] as const;
// Monday-first reading order for day lists.
const WEEK_ORDER = [1, 2, 3, 4, 5, 6, 0] as const;

function integerIn(token: string, min: number, max: number): number | null {
  if (!/^\d{1,2}$/.test(token)) return null;
  const value = Number(token);
  return value >= min && value <= max ? value : null;
}

function dayOfWeek(token: string): number | null {
  const named = DAY_ABBREVIATIONS.indexOf(token.toLowerCase() as typeof DAY_ABBREVIATIONS[number]);
  if (named >= 0) return named;
  const value = integerIn(token, 0, 7);
  return value === null ? null : value % 7;
}

function daysOfWeek(field: string): Set<number> | null {
  const days = new Set<number>();
  for (const part of field.split(",")) {
    const range = /^([^-]+)-([^-]+)$/.exec(part);
    if (range) {
      const start = dayOfWeek(range[1]);
      const end = dayOfWeek(range[2]);
      const rawEnd = integerIn(range[2], 0, 7);
      if (start === null || end === null) return null;
      const last = rawEnd === 7 ? 7 : end;
      if (last < start) return null;
      for (let day = start; day <= last; day += 1) days.add(day % 7);
      continue;
    }
    const day = dayOfWeek(part);
    if (day === null) return null;
    days.add(day);
  }
  return days;
}

function clockTime(hour: number, minute: number): string {
  const suffix = hour < 12 ? "AM" : "PM";
  return `${hour % 12 === 0 ? 12 : hour % 12}:${String(minute).padStart(2, "0")} ${suffix}`;
}

function ordinal(day: number): string {
  const remainder = day % 100;
  if (remainder >= 11 && remainder <= 13) return `${day}th`;
  return `${day}${({ 1: "st", 2: "nd", 3: "rd" } as Record<number, string>)[day % 10] ?? "th"}`;
}

function dayLabel(days: Set<number>): string {
  if (days.size === 7) return "Daily";
  if (days.size === 5 && [1, 2, 3, 4, 5].every((day) => days.has(day))) return "Weekdays";
  if (days.size === 2 && days.has(0) && days.has(6)) return "Weekends";
  const ordered = WEEK_ORDER.filter((day) => days.has(day));
  if (ordered.length === 1) return `${DAY_NAMES[ordered[0]]}s`;
  return ordered.map((day) => DAY_NAMES[day].slice(0, 3)).join(", ");
}

// Reads the common shapes of the Dashboard DSL's 5-field cron; every other expression is a custom schedule.
export function describeCron(cron: string): CronDescription | null {
  const fields = cron.trim().split(/\s+/);
  if (fields.length !== 5) return null;
  const [minuteField, hourField, dayField, monthField, weekdayField] = fields;
  if (monthField !== "*") return null;

  const interval = /^(?:\*|0)\/(\d{1,2})$/;
  if (hourField === "*" && dayField === "*" && weekdayField === "*") {
    if (minuteField === "*") return { label: "Every minute", timeOfDay: false };
    const everyMinutes = interval.exec(minuteField);
    if (everyMinutes) {
      const step = integerIn(everyMinutes[1], 1, 59);
      return step === null ? null : { label: step === 1 ? "Every minute" : `Every ${step} minutes`, timeOfDay: false };
    }
    const minute = integerIn(minuteField, 0, 59);
    if (minute === null) return null;
    return { label: minute === 0 ? "Hourly" : `Hourly at :${String(minute).padStart(2, "0")}`, timeOfDay: false };
  }

  const minute = integerIn(minuteField, 0, 59);
  if (minute === null) return null;

  const everyHours = interval.exec(hourField);
  if (everyHours) {
    const step = integerIn(everyHours[1], 1, 23);
    if (step === null || dayField !== "*" || weekdayField !== "*") return null;
    return { label: step === 1 ? "Hourly" : `Every ${step} hours`, timeOfDay: false };
  }

  const hours: number[] = [];
  for (const token of hourField.split(",")) {
    const hour = integerIn(token, 0, 23);
    if (hour === null) return null;
    hours.push(hour);
  }
  const times = [...new Set(hours)].sort((left, right) => left - right).map((hour) => clockTime(hour, minute)).join(", ");

  if (dayField !== "*") {
    const day = integerIn(dayField, 1, 31);
    if (day === null || weekdayField !== "*") return null;
    return { label: `Monthly on the ${ordinal(day)} · ${times}`, timeOfDay: true };
  }
  if (weekdayField === "*") return { label: `Daily · ${times}`, timeOfDay: true };
  const days = daysOfWeek(weekdayField);
  if (days === null || days.size === 0) return null;
  return { label: `${dayLabel(days)} · ${times}`, timeOfDay: true };
}

export function scheduleSummary(cron: string): string {
  return describeCron(cron)?.label ?? CUSTOM_SCHEDULE;
}

export function timeZoneAbbreviation(timeZone: string, at: Date): string {
  try {
    const part = new Intl.DateTimeFormat("en-US", { timeZone, timeZoneName: "short" })
      .formatToParts(at)
      .find((candidate) => candidate.type === "timeZoneName");
    return part?.value ?? timeZone;
  } catch {
    return timeZone;
  }
}

export function scheduleZoneLabel(timeZone: string): string {
  return timeZone.replace(/_/g, " ");
}

export function readerTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

// One cell of the Dashboard library: a single schedule reads as its cadence, several as a count.
export function scheduleListLabel(
  schedules: ReadonlyArray<{ cron: string; timeZone: string }>,
  viewerTimeZone: string,
  at: Date,
): string {
  if (schedules.length === 0) return "—";
  if (schedules.length > 1) return `${schedules.length} schedules`;
  const [schedule] = schedules;
  const description = describeCron(schedule.cron);
  if (description === null) return CUSTOM_SCHEDULE;
  if (!description.timeOfDay || schedule.timeZone === viewerTimeZone) return description.label;
  return `${description.label} ${timeZoneAbbreviation(schedule.timeZone, at)}`;
}

function datePart(date: Date, timeZone: string, options: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat("en-US", { ...options, timeZone }).format(date).replace(/\u202f/g, " ");
}

// "Fri, Oct 2 · 9:00 AM" in the given zone; the year appears only when it differs from now.
export function formatScheduleTime(value: string, timeZone: string, now: Date): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "—";
  const sameYear = datePart(date, timeZone, { year: "numeric" }) === datePart(now, timeZone, { year: "numeric" });
  const day = datePart(date, timeZone, { weekday: "short", month: "short", day: "numeric", year: sameYear ? undefined : "numeric" });
  const time = datePart(date, timeZone, { hour: "numeric", minute: "2-digit" });
  return `${day} · ${time}`;
}

// The reader's own clock for the same instant, or null when it reads the same as the schedule's clock.
export function formatReaderTime(value: string, scheduleTimeZone: string, viewerTimeZone: string, now: Date): string | null {
  const reader = formatScheduleTime(value, viewerTimeZone, now);
  return reader === formatScheduleTime(value, scheduleTimeZone, now) ? null : reader;
}

export function relativeTime(value: string, now: Date): string {
  const difference = new Date(value).getTime() - now.getTime();
  if (!Number.isFinite(difference)) return "";
  if (difference <= 0) return formatRelativeTime(value, now.getTime());
  const minutes = Math.round(difference / 60_000);
  const format = new Intl.RelativeTimeFormat("en-US", { numeric: "always" });
  if (Math.abs(minutes) < 1) return "now";
  if (Math.abs(minutes) < 60) return format.format(minutes, "minute");
  const hours = Math.round(difference / 3_600_000);
  if (Math.abs(hours) < 24) return format.format(hours, "hour");
  return format.format(Math.round(difference / 86_400_000), "day");
}

const SKIP_REASONS: Record<string, string> = {
  LATE: "started more than 15 minutes late",
  OVERLAP: "the previous run was still going",
  EXECUTION_ADMISSION_REJECTED: "workspace at its run limit",
};

const FAILURE_REASONS: Record<string, string> = {
  INVALID_SCHEDULE_PARAMETERS: "inputs couldn't be calculated",
  QUERY_PLANNING_REJECTED: "a query couldn't be planned",
  PLANNING_TIMEOUT: "query planning timed out",
};

export interface ScheduleRunPresentation {
  state: WorkStatusState;
  // Human sentence fragment after the status; never the server's raw reason text.
  reason: string | null;
}

export function scheduleRunPresentation(occurrence: Pick<DashboardScheduleOccurrence, "outcome" | "reason" | "resultState">): ScheduleRunPresentation {
  const reason = occurrence.reason;
  if (occurrence.outcome === "ACCEPTED") {
    const state: WorkStatusState = occurrence.resultState === null ? "ACCEPTED"
      : occurrence.resultState === "READY" ? "COMPLETED"
        : occurrence.resultState;
    return { state, reason: null };
  }
  // A run skipped for capacity is an expected outcome, not a definition problem the author must fix.
  if (occurrence.outcome === "SKIPPED" || (reason !== null && reason in SKIP_REASONS)) {
    return { state: "SKIPPED", reason: reason === null ? null : SKIP_REASONS[reason] ?? "couldn't start" };
  }
  return { state: "ADMISSION_FAILED", reason: reason === null ? null : FAILURE_REASONS[reason] ?? "couldn't start" };
}
