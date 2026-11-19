import { useEffect, useState } from "react";
import {
  ChevronLeft,
  ChevronRight,
  History,
  X,
} from "lucide-react";
import { useRunPresentationClock, formatDuration, runStartedLabel, runDurationSeconds } from "./clock";
import { RunsStatusDot } from "./RunsStatusDot";
import type { RunPresentation } from "./types";
import { useTransientScrollbar } from "../ui/useTransientScrollbar";
import { workStatus, WorkStatus } from "../ui/WorkStatus";
import { actorLabel, actorProvenanceLabel } from "@/components/ui/ActorProvenance";
import { AgentMarker } from "@/components/ui/AgentMarker";
import { IconButton } from "@/components/ui/IconButton";
import { StateBlock } from "@/components/ui/StateBlock";

export interface RunsRailProps {
  className: string;
  runs: readonly RunPresentation[];
  /** The viewer, so a row says "You" for their own runs. */
  principalId: string | null;
  newlyAddedRunId: string | null;
  /** The run being viewed; its row carries the single sandstone position marker. */
  selectedRunId?: string | null;
  collapsed: boolean;
  onCollapsedChange?: (collapsed: boolean) => void;
  onInspect: (runId: string) => void;
  onClose?: () => void;
  ariaLabel?: string;
  /** Shown in the expanded list when there are no runs; omitted, the list stays blank. */
  emptyState?: { title: string; description: string };
}

export function RunsRail({
  className,
  runs,
  principalId,
  newlyAddedRunId,
  selectedRunId = null,
  collapsed,
  onCollapsedChange,
  onInspect,
  onClose,
  ariaLabel = "Runs",
  emptyState,
}: RunsRailProps) {
  const runningCount = runs.filter((run) => run.state === "RUNNING").length;
  const scrollbar = useTransientScrollbar();
  const now = useRunPresentationClock(runs);

  return (
    <aside
      data-collapsed={collapsed || undefined}
      className={`${className} relative min-h-0 shrink-0 flex-col border-l border-border bg-background transition-[width] duration-200 motion-reduce:transition-none ${onClose ? "w-[min(520px,calc(100vw-24px))]" : collapsed ? "w-14" : "w-[320px]"}`}
      aria-label={ariaLabel}
    >
      {collapsed && !onClose ? (
        <>
          <div className="flex h-14 shrink-0 flex-col items-center justify-center gap-0.5 border-b border-border">
            <button
              type="button"
              onClick={() => onCollapsedChange?.(false)}
              aria-label={runningCount > 0 ? "Expand runs, activity in progress" : "Expand runs"}
              title="Expand runs"
              className="group relative grid h-8 w-8 place-items-center rounded-[4px] text-foreground-secondary hover:bg-surface hover:text-foreground"
            >
              <span className="relative block h-4 w-4" aria-hidden="true">
                <History data-testid="runs-dock-default-icon" className="absolute inset-0 h-4 w-4 transition-[opacity,transform] duration-150 group-hover:scale-90 group-hover:opacity-0 group-focus-visible:scale-90 group-focus-visible:opacity-0 motion-reduce:transition-none" />
                <ChevronLeft data-testid="runs-dock-hover-icon" className="absolute inset-0 h-4 w-4 scale-90 opacity-0 transition-[opacity,transform] duration-150 group-hover:scale-100 group-hover:opacity-100 group-focus-visible:scale-100 group-focus-visible:opacity-100 motion-reduce:transition-none" />
              </span>
              <RunsStatusDot running={runningCount > 0} />
            </button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto py-2">
            {runs.map((run) => {
              const starter = runStarterLabel(run, principalId);
              return (
                <button
                  key={run.id}
                  type="button"
                  aria-label={`Open ${run.title}${starter ? `, ${starter}` : ""}`}
                  title={`${run.title}${starter ? ` · ${starter}` : ""} · ${workStatus(run.state).label} · ${runStartedLabel(run, now)}`}
                  onClick={() => {
                    onCollapsedChange?.(false);
                    onInspect(run.id);
                  }}
                  className="grid h-11 w-full place-items-center text-foreground-secondary hover:bg-surface"
                >
                  <WorkStatus state={run.state} labelDisplay="accessible-name" size="md" />
                </button>
              );
            })}
          </div>
        </>
      ) : (
        <>
          <div data-testid="runs-header" className="flex h-14 shrink-0 items-center justify-between border-b border-border px-4">
            <div className="flex items-center gap-2">
              <History className="h-4 w-4 text-foreground-secondary" aria-hidden="true" />
              <h2 className="type-item text-heading">Runs</h2>
            </div>
            <div className="flex items-center gap-1">
              {runningCount > 0 ? <span className="type-status mr-1 tabular-nums text-foreground-secondary">{runningCount} running</span> : null}
              {onClose ? (
                <IconButton label="Close runs" icon={X} onClick={onClose} />
              ) : (
                <IconButton label="Collapse runs" icon={ChevronRight} onClick={() => onCollapsedChange?.(true)} />
              )}
            </div>
          </div>
          <div onScroll={scrollbar.onScroll} className={`min-h-0 flex-1 overflow-y-auto ${scrollbar.className}`}>
            {runs.length > 0 && (
              <div className="border-b border-border-light">
                {runs.map((run) => (
                  <RunRow
                    key={run.id}
                    run={run}
                    principalId={principalId}
                    now={now}
                    newlyAdded={run.id === newlyAddedRunId}
                    selected={run.id === selectedRunId}
                    onClick={() => onInspect(run.id)}
                  />
                ))}
              </div>
            )}
            {runs.length === 0 && emptyState ? (
              <StateBlock kind="empty" layout="inline" icon={History} title={emptyState.title} description={emptyState.description} />
            ) : null}
          </div>
          <div className="type-status flex h-10 shrink-0 items-center border-t border-border px-5 tabular-nums text-foreground-secondary">
            {runs.length} recent {runs.length === 1 ? "run" : "runs"}
          </div>
        </>
      )}
    </aside>
  );
}

function RunRow({
  run,
  principalId,
  now,
  newlyAdded,
  selected,
  onClick,
}: {
  run: RunPresentation;
  principalId: string | null;
  now: number;
  newlyAdded: boolean;
  selected: boolean;
  onClick: () => void;
}) {
  const duration = runDurationSeconds(run, now);
  const [highlighted, setHighlighted] = useState(newlyAdded);

  useEffect(() => {
    if (!newlyAdded) return;
    setHighlighted(true);
    const timer = window.setTimeout(() => setHighlighted(false), 100);
    return () => window.clearTimeout(timer);
  }, [newlyAdded]);

  return (
    <button
      type="button"
      onClick={onClick}
      data-newly-added={highlighted || undefined}
      aria-current={selected || undefined}
      className={`group relative flex w-full items-start gap-3 border-b border-border-light px-4 py-3.5 text-left transition-colors duration-1000 last:border-b-0 hover:bg-surface motion-reduce:transition-none ${highlighted ? "bg-selection-tint" : selected ? "bg-surface" : "bg-background"}`}
    >
      {selected ? <span className="absolute inset-y-0 left-0 w-0.5 bg-accent-marker" aria-hidden="true" /> : null}
      <WorkStatus state={run.state} labelDisplay="external" size="md" className="mt-0.5" />
      <span className="min-w-0 flex-1">
        <span className="type-control line-clamp-2 break-words text-foreground">{run.title}</span>
        <RunStarterLine run={run} principalId={principalId} />
        <span className="type-status mt-1.5 flex items-baseline justify-between gap-3 tabular-nums text-foreground-secondary">
          <span className={`min-w-0 truncate ${run.state === "RUNNING" ? "text-foreground" : ""}`}>{runStatusSummary(run, duration)}</span>
          <span className="shrink-0">{runStartedLabel(run, now)}</span>
        </span>
      </span>
    </button>
  );
}

/** "started by Sam Kim via Claude (claude.ai)" for a run's accessible name, or null when no starter was recorded. */
function runStarterLabel(run: RunPresentation, principalId: string | null): string | null {
  return actorProvenanceLabel("started", run.startedBy ?? null, run.credentialSurface ?? null, run.agentApp ?? null, principalId);
}

/**
 * Who started the run, then the run's subtitle: "Sam Kim · Dashboard query", "Sam Kim via Claude · SQL · Warehouse".
 * In a narrow row the subtitle gives up its width before the person does.
 */
function RunStarterLine({ run, principalId }: { run: RunPresentation; principalId: string | null }) {
  const agent = run.credentialSurface === "AGENT";
  const starter = Boolean(run.startedBy) || agent;
  return (
    <span className="type-status mt-1 flex min-w-0 items-center gap-1.5 text-foreground-secondary">
      {run.startedBy ? <span className="min-w-0 truncate">{actorLabel(run.startedBy, principalId)}</span> : null}
      {agent ? <span className="shrink-0"><AgentMarker app={run.agentApp ?? null} focusable={false} /></span> : null}
      {starter ? <span aria-hidden="true" className="shrink-0">·</span> : null}
      <span className="min-w-0 shrink-[10000] truncate">{run.subtitle}</span>
    </span>
  );
}

const ROW_COUNT_FORMAT = new Intl.NumberFormat("en-US");

function runStatusSummary(run: RunPresentation, duration: number): string {
  const status = `${workStatus(run.state).label} · ${formatDuration(duration)}`;
  const totalRowCount = run.result?.totalRowCount;
  return run.state === "SUCCEEDED" && totalRowCount != null
    ? `${status} · ${ROW_COUNT_FORMAT.format(totalRowCount)} ${totalRowCount === 1 ? "row" : "rows"}`
    : status;
}
