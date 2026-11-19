import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { DashboardDefinition, DashboardScheduleOccurrence, DashboardScheduleResponse } from "@/api/dashboard";
import { DashboardScheduleOccurrenceList, DashboardSchedulePreviewList } from "./DashboardSchedulesDialog";

const definition: DashboardDefinition = {
  schemaVersion: 1,
  meta: { title: "Scheduled sales" },
  parameters: {
    country: { type: "TEXT", nullable: false, label: "Country", control: { options: [{ value: "US", label: "United States" }] } },
    period: { type: "DATE_RANGE", nullable: false, label: "Period" },
    optional: { type: "TEXT", nullable: true },
  },
  variables: {},
  queries: {},
  widgets: {},
  layout: { sections: [] },
  schedules: { daily_us: { cron: "0 9 * * *", timeZone: "Asia/Seoul", parameters: {} } },
};
const preview: DashboardScheduleResponse = {
  schedules: [{ scheduleKey: "daily_us", nextScheduledAt: "2026-09-20T00:00:00Z" }],
  preview: [{ scheduleKey: "daily_us", scheduledAt: "2026-09-20T00:00:00Z", localTime: "2026-09-20T09:00+09:00[Asia/Seoul]", parameters: { country: "US", optional: null, period: { start: "2026-09-19", end: "2026-09-19" } } }],
  previewError: null,
};
const clock = { now: new Date("2026-09-19T12:00:00Z"), viewerTimeZone: "Asia/Seoul" };
const visibleText = (markup: string) => markup.replace(/<[^>]+>/g, "");
const occurrence: DashboardScheduleOccurrence = {
  scheduleKey: "daily_us", scheduledAt: "2026-09-19T00:00:00Z", decidedAt: "2026-09-19T00:00:03Z",
  definition, initialQueryRuns: { sales: "run-sales" }, outcome: "ACCEPTED", parameters: { country: "US" },
  queryRuns: {}, reason: null, resultState: "READY", sessionId: "scheduled-session",
};

describe("Dashboard schedules", () => {
  it("reads a schedule as a human cadence, zone, labelled inputs, and the next run", () => {
    const text = visibleText(renderToStaticMarkup(<DashboardSchedulePreviewList data={preview} definition={definition} draft={false} clock={clock} />));
    expect(text).toContain("Daily · 9:00 AM Asia/Seoul");
    expect(text).toContain("Inputs: Country: United States · Optional: None · Period: Sep 19 – Sep 19, 2026");
    expect(text).toContain("Next run: Sun, Sep 20 · 9:00 AM (in 12 hours)");
    expect(text).not.toContain("daily_us");
    expect(text).not.toContain("[Asia/Seoul]");
    expect(text).not.toContain("UTC");
    expect(text).not.toContain("your time");
  });

  it("adds the reader's own clock when it differs from the schedule's zone", () => {
    const text = visibleText(renderToStaticMarkup(<DashboardSchedulePreviewList data={preview} definition={definition} draft={false} clock={{ ...clock, viewerTimeZone: "America/New_York" }} />));
    expect(text).toContain("Sat, Sep 19 · 8:00 PM your time");
  });

  it("previews draft runs without claiming an active next run", () => {
    const text = visibleText(renderToStaticMarkup(<DashboardSchedulePreviewList data={preview} definition={definition} draft clock={clock} />));
    expect(text).not.toContain("Next run:");
    expect(text).toContain("Next runs after you apply");
    expect(text).toContain("Sun, Sep 20 · 9:00 AM");
    expect(text).not.toContain("View results");
  });

  it("distinguishes empty schedules from unavailable preview", () => {
    const empty = { schedules: [], preview: [], previewError: null } satisfies DashboardScheduleResponse;
    const unscheduled = { ...definition, schedules: {} };
    expect(renderToStaticMarkup(<DashboardSchedulePreviewList data={empty} definition={unscheduled} draft={false} clock={clock} />)).toContain("No schedules yet.");
    expect(renderToStaticMarkup(<DashboardSchedulePreviewList data={empty} definition={unscheduled} draft clock={clock} />)).toContain("This draft has no schedules.");
    const unavailable = renderToStaticMarkup(<DashboardSchedulePreviewList data={{ ...empty, previewError: "SCHEDULE_PREVIEW_UNAVAILABLE" }} definition={unscheduled} draft={false} clock={clock} />);
    expect(unavailable).toContain('role="alert"');
    expect(unavailable).not.toContain("No schedules yet.");
  });

  it("lists recent runs with readable times, shared statuses, human reasons, and results only for accepted runs", () => {
    const markup = renderToStaticMarkup(<DashboardScheduleOccurrenceList definition={definition} clock={clock} occurrences={[
      occurrence,
      { ...occurrence, scheduledAt: "2026-09-18T00:00:00Z", outcome: "SKIPPED", reason: "OVERLAP", sessionId: null, resultState: null },
      { ...occurrence, scheduledAt: "2026-09-17T00:00:00Z", outcome: "ADMISSION_FAILED", reason: "EXECUTION_ADMISSION_REJECTED", sessionId: null, resultState: null },
      { ...occurrence, scheduledAt: "2026-09-16T00:00:00Z", outcome: "ADMISSION_FAILED", reason: "INVALID_SCHEDULE_PARAMETERS", sessionId: null, resultState: null },
    ]} onViewResult={() => undefined} />);
    const text = visibleText(markup);
    expect(markup.match(/>View results<\/button>/g)).toHaveLength(1);
    expect(markup).toContain('aria-label="View results for Daily · 9:00 AM at Sat, Sep 19 · 9:00 AM"');
    expect(text).toContain("Sat, Sep 19 · 9:00 AM");
    expect(text).toContain("Completed");
    expect(markup).toContain('data-work-status="COMPLETED" data-work-status-tone="success"');
    expect(text).toContain("Skipped · the previous run was still going");
    expect(text).toContain("Skipped · workspace at its run limit");
    expect(markup).toContain('data-work-status="SKIPPED" data-work-status-tone="neutral"');
    expect(text).toContain("Admission failed · inputs couldn&#x27;t be calculated");
    expect(markup).toContain('data-work-status="ADMISSION_FAILED" data-work-status-tone="failure"');
    expect(text).not.toContain("daily_us");
    expect(text).not.toContain("2026-09");
  });

  it("never displays raw errors from a reason or QueryRun", () => {
    const markup = renderToStaticMarkup(<DashboardScheduleOccurrenceList definition={definition} clock={clock} occurrences={[{ ...occurrence, reason: "secret warehouse error", queryRuns: { sales: { failureCode: "INTERNAL", failureMessage: "raw credential", queryRunId: "run-sales", resultAvailable: false, state: "FAILED", updatedAt: occurrence.decidedAt } }, resultState: "FAILED" }]} />);
    expect(markup).not.toContain("secret warehouse error");
    expect(markup).not.toContain("raw credential");
    expect(markup).toContain("Failed");
    expect(markup).not.toContain("View results");
  });

  it("labels several schedules, unavailable results, and empty history", () => {
    const several = { ...definition, schedules: { ...definition.schedules, weekly: { cron: "0 8 * * 1", timeZone: "Asia/Seoul", parameters: {} } } };
    expect(visibleText(renderToStaticMarkup(<DashboardScheduleOccurrenceList definition={several} clock={clock} occurrences={[{ ...occurrence, resultState: "UNAVAILABLE" }]} />))).toContain("Daily · 9:00 AMSat, Sep 19 · 9:00 AMResult unavailable");
    const partial = renderToStaticMarkup(<DashboardScheduleOccurrenceList definition={definition} clock={clock} occurrences={[{ ...occurrence, resultState: "PARTIAL_FAILURE" }]} />);
    expect(visibleText(partial)).toContain("Partial failure");
    expect(partial).toContain('data-work-status="PARTIAL_FAILURE" data-work-status-tone="partial-failure"');
    expect(renderToStaticMarkup(<DashboardScheduleOccurrenceList definition={definition} clock={clock} occurrences={[]} />)).toContain("No runs yet.");
  });
});
