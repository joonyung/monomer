import { useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { CalendarClock, History } from "lucide-react";
import {
  dashboardAPI,
  type DashboardDefinition,
  type DashboardJsonValue,
  type DashboardScheduleOccurrence,
  type DashboardScheduleResponse,
} from "@/api/dashboard";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { queryKeys } from "@/lib/query/keys";
import { Button } from "@/components/ui/Button";
import { IconButton } from "@/components/ui/IconButton";
import { Dialog } from "@/components/ui/Dialog";
import { StateBlock } from "@/components/ui/StateBlock";
import { WorkStatus } from "@/components/ui/WorkStatus";
import {
  dashboardParameterLabel,
  formatDashboardParameterValue,
  formatUnknownDashboardParameterValue,
} from "@/features/dashboard/dashboard-parameter-presentation";
import {
  formatReaderTime,
  formatScheduleTime,
  readerTimeZone,
  relativeTime,
  scheduleRunPresentation,
  scheduleSummary,
  scheduleZoneLabel,
} from "@/features/dashboard/schedule-summary";

interface DashboardSchedulesDialogProps {
  dashboardId: string;
  // Present in the Draft review: the dialog previews the shared Draft's schedules instead of the applied ones.
  draftDefinition?: DashboardDefinition;
  onViewResult?: (scheduleKey: string, scheduledAt: string) => void;
}

interface ScheduleClock {
  now: Date;
  viewerTimeZone: string;
}

export function DashboardSchedulesDialog({ dashboardId, draftDefinition, onViewResult }: DashboardSchedulesDialogProps) {
  const [open, setOpen] = useState(false);
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const draft = draftDefinition !== undefined;
  // The same current-definition entry the Viewer reads, so an open Viewer adds no request.
  const appliedDefinition = useQuery({
    queryKey: queryKeys.dashboards.detail(workspaceId, principalId ?? "", dashboardId),
    queryFn: ({ signal }) => dashboardAPI.get(workspaceId, dashboardId, signal),
    enabled: open && !draft && Boolean(principalId),
    retry: false,
  });
  const definition = draftDefinition ?? appliedDefinition.data?.definition;
  const schedules = useQuery({
    queryKey: queryKeys.dashboards.schedules(workspaceId, principalId ?? "", dashboardId, draft),
    queryFn: ({ signal }) => dashboardAPI.getSchedules(workspaceId, dashboardId, draft, signal),
    enabled: open && Boolean(principalId),
    refetchInterval: open ? 15_000 : false,
  });
  const occurrences = useInfiniteQuery({
    queryKey: queryKeys.dashboards.occurrences(workspaceId, principalId ?? "", dashboardId),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => dashboardAPI.listOccurrences(workspaceId, dashboardId, pageParam, signal),
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: open && !draft && Boolean(principalId),
    refetchInterval: open && !draft ? 15_000 : false,
  });
  const clock: ScheduleClock = { now: new Date(), viewerTimeZone: readerTimeZone() };
  return <Dialog closeLabel="Close schedules"
    open={open}
    onOpenChange={setOpen}
    size="md"
    trigger={draft
      ? <IconButton label="Schedules" icon={CalendarClock} size="md" />
      : <Button type="button" variant="secondary" size="sm"><CalendarClock aria-hidden="true" />Schedules</Button>}
    title="Schedules"
    description={draft
      ? "These are the draft's schedules. They start running when you apply the draft to the dashboard."
      : "Schedules are part of the dashboard definition. Ask your agent to add or change one."}
  >
    {schedules.isError || (!draft && appliedDefinition.isError)
      ? <LoadError label="Schedules could not be loaded." retry={() => { void schedules.refetch(); if (!draft) void appliedDefinition.refetch(); }} />
      : schedules.isPending || definition === undefined
        ? <StateBlock kind="loading" layout="inline" title="Loading schedules" />
        : <DashboardSchedulePreviewList data={schedules.data} definition={definition} draft={draft} clock={clock} />}
    {!draft ? <section aria-labelledby="schedule-recent-runs" className="mt-6 border-t border-border pt-5">
      <h3 id="schedule-recent-runs" className="mb-2 type-group text-foreground-secondary">Recent runs</h3>
      {occurrences.isPending ? <StateBlock kind="loading" layout="inline" title="Loading recent runs" /> : occurrences.isError ? <LoadError label="Recent runs could not be loaded." retry={() => { void occurrences.refetch(); }} /> : <>
        <DashboardScheduleOccurrenceList
          occurrences={occurrences.data.pages.flatMap((page) => page.items)}
          definition={definition ?? null}
          clock={clock}
          onViewResult={onViewResult ? (key, at) => { setOpen(false); onViewResult(key, at); } : undefined}
        />
        {occurrences.hasNextPage ? <Button type="button" variant="secondary" size="sm" className="mt-4" pending={occurrences.isFetchingNextPage} onClick={() => { void occurrences.fetchNextPage(); }}>{occurrences.isFetchingNextPage ? "Loading earlier runs…" : "Show earlier runs"}</Button> : null}
      </>}
    </section> : null}
  </Dialog>;
}

function scheduleOf(definition: DashboardDefinition | null, key: string) {
  return definition?.schedules?.[key] ?? null;
}

function scheduleTitle(cron: string | null): string {
  return cron === null ? "Schedule" : scheduleSummary(cron);
}

function formatScheduleInputs(values: Readonly<Record<string, DashboardJsonValue>>, parameters: DashboardDefinition["parameters"]): string {
  return Object.keys(values)
    .sort((left, right) => left < right ? -1 : left > right ? 1 : 0)
    .map((name) => {
      const definition = parameters[name];
      const label = definition?.label ?? dashboardParameterLabel(name);
      const value = definition ? formatDashboardParameterValue(definition, values[name]) : formatUnknownDashboardParameterValue(values[name]);
      return `${label}: ${value}`;
    })
    .join(" · ");
}

function ScheduleInstant({ value, timeZone, clock, relative }: { value: string; timeZone: string; clock: ScheduleClock; relative: boolean }) {
  const reader = formatReaderTime(value, timeZone, clock.viewerTimeZone, clock.now);
  return <>
    <time dateTime={value} className="text-foreground">{formatScheduleTime(value, timeZone, clock.now)}</time>
    {relative && Date.parse(value) > clock.now.getTime() ? <span> ({relativeTime(value, clock.now)})</span> : null}
    {reader ? <span className="block text-foreground-secondary">{reader} your time</span> : null}
  </>;
}

export function DashboardSchedulePreviewList({ data, definition, draft, clock }: {
  data: DashboardScheduleResponse;
  definition: DashboardDefinition;
  draft: boolean;
  clock: ScheduleClock;
}) {
  const keys = [...new Set([
    ...Object.keys(definition.schedules ?? {}),
    ...data.schedules.map((item) => item.scheduleKey),
    ...data.preview.map((item) => item.scheduleKey),
  ])].sort();
  return <section aria-label={draft ? "Draft schedules" : "Schedules"}>
    {data.previewError ? <StateBlock kind="error" layout="inline" title="Upcoming runs can't be calculated. Ask your agent to check this schedule's dates and inputs." /> : null}
    {keys.length === 0 && !data.previewError ? <StateBlock kind="empty" layout="inline" icon={CalendarClock} title={draft ? "This draft has no schedules." : "No schedules yet."} /> : null}
    <div className="divide-y divide-border-light">{keys.map((key) => {
      const schedule = scheduleOf(definition, key);
      const timeZone = schedule?.timeZone ?? "UTC";
      const summary = scheduleTitle(schedule?.cron ?? null);
      const next = data.schedules.find((item) => item.scheduleKey === key);
      const preview = data.preview.filter((item) => item.scheduleKey === key);
      const nextInputs = (next ? preview.find((item) => item.scheduledAt === next.nextScheduledAt) : undefined) ?? preview[0];
      return <section key={key} aria-label={`Schedule ${summary}`} className="min-w-0 py-4 first:pt-0">
        <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          <h3 className="type-item text-heading">{summary} <span className="type-meta text-foreground-secondary">{scheduleZoneLabel(timeZone)}</span></h3>
          {!draft && nextInputs && Object.keys(nextInputs.parameters).length > 0
            ? <p className="min-w-0 break-words type-meta text-foreground-secondary">Inputs: {formatScheduleInputs(nextInputs.parameters, definition.parameters)}</p>
            : null}
        </div>
        {!draft && next ? <p className="mt-1 type-meta tabular-nums text-foreground-secondary">
          Next run: <ScheduleInstant value={next.nextScheduledAt} timeZone={timeZone} clock={clock} relative />
        </p> : null}
        {draft && preview.length > 0 ? <>
          <h4 className="mb-1 mt-3 type-group text-foreground-secondary">Next runs after you apply</h4>
          <ol className="divide-y divide-border-light border-y border-border-light">{preview.map((item) => <li key={item.scheduledAt} className="grid min-w-0 gap-1 py-2.5 type-meta tabular-nums sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] sm:gap-4">
            <span className="min-w-0"><ScheduleInstant value={item.scheduledAt} timeZone={timeZone} clock={clock} relative={false} /></span>
            <span className="min-w-0 break-words text-foreground-secondary">{Object.keys(item.parameters).length > 0 ? `Inputs: ${formatScheduleInputs(item.parameters, definition.parameters)}` : "No inputs"}</span>
          </li>)}</ol>
        </> : null}
      </section>;
    })}</div>
  </section>;
}

export function DashboardScheduleOccurrenceList({ occurrences, definition, clock, onViewResult }: {
  occurrences: readonly DashboardScheduleOccurrence[];
  definition: DashboardDefinition | null;
  clock: ScheduleClock;
  onViewResult?: (scheduleKey: string, scheduledAt: string) => void;
}) {
  if (occurrences.length === 0) return <StateBlock kind="empty" layout="inline" icon={History} title="No runs yet." />;
  const severalSchedules = new Set([
    ...Object.keys(definition?.schedules ?? {}),
    ...occurrences.map((occurrence) => occurrence.scheduleKey),
  ]).size > 1;
  return <ol className="divide-y divide-border-light border-y border-border-light">{occurrences.map((occurrence) => {
    const schedule = scheduleOf(definition, occurrence.scheduleKey) ?? occurrence.definition.schedules?.[occurrence.scheduleKey] ?? null;
    const timeZone = schedule?.timeZone ?? "UTC";
    const summary = scheduleTitle(schedule?.cron ?? null);
    const time = formatScheduleTime(occurrence.scheduledAt, timeZone, clock.now);
    const status = scheduleRunPresentation(occurrence);
    const viewable = occurrence.outcome === "ACCEPTED" && occurrence.sessionId !== null && onViewResult;
    return <li key={`${occurrence.scheduleKey}:${occurrence.scheduledAt}`} className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 py-2.5 type-meta sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]">
      <div className="min-w-0 tabular-nums">
        {severalSchedules ? <span className="block text-foreground-secondary">{summary}</span> : null}
        <ScheduleInstant value={occurrence.scheduledAt} timeZone={timeZone} clock={clock} relative={false} />
      </div>
      <p className="col-start-1 min-w-0 text-label sm:col-start-auto">
        <WorkStatus state={status.state} labelDisplay="inline" size="sm" />
        {status.reason ? <span> · {status.reason}</span> : null}
      </p>
      {viewable ? <Button type="button" variant="ghost" size="sm" className="col-start-2 row-span-2 row-start-1 sm:col-start-auto sm:row-span-1 sm:row-start-auto" aria-label={`View results for ${summary} at ${time}`} onClick={() => onViewResult(occurrence.scheduleKey, occurrence.scheduledAt)}>View results</Button> : <span aria-hidden="true" />}
    </li>;
  })}</ol>;
}

function LoadError({ label, retry }: { label: string; retry: () => void }) {
  return <StateBlock kind="error" layout="inline" title={label} action={<Button type="button" variant="secondary" onClick={retry}>Retry</Button>} />;
}
