import { type Dispatch, type SetStateAction, useEffect, useId, useRef, useState } from "react";
import { sessionCreatedLabel, sessionExactTime } from "./session-time";
import * as RadixDialog from "@radix-ui/react-dialog";
import { Code2, ChevronLeft, ChevronRight, History, LoaderCircle, RefreshCw, X } from "lucide-react";
import type { DashboardDefinition, DashboardQueryRun, DashboardSessionSummary } from "@/api/dashboard";
import { formatExactTime } from "@/components/runs";
import { ActorProvenance, actorLabel, actorProvenanceLabel } from "@/components/ui/ActorProvenance";
import { AgentMarker } from "@/components/ui/AgentMarker";
import { Button } from "@/components/ui/Button";
import { Dialog, DIALOG_OVERLAY_CLASS } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { StateBlock } from "@/components/ui/StateBlock";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { WorkStatus } from "@/components/ui/WorkStatus";
import { resultsFromLabel } from "./capture/provenance";
import { dashboardInputChips, dashboardInputsSummary } from "./DashboardParameterForm";

import styles from "./DashboardActivitySidebar.module.css";

// Public Page readers' runs carry no person or request path.
type ActivityRun = Omit<DashboardQueryRun, "connectorId" | "failureMessage" | "startedBy" | "credentialSurface" | "agentApp"> & { failureMessage?: string | null; startedBy?: DashboardQueryRun["startedBy"]; credentialSurface?: DashboardQueryRun["credentialSurface"]; agentApp?: DashboardQueryRun["agentApp"] };
type ActivitySession = Pick<DashboardSessionSummary, "dashboardSessionId" | "createdAt" | "updatedAt"> & Partial<Pick<DashboardSessionSummary, "dashboardId" | "lastQueryRunAt" | "parameterBindings" | "runningQueryRuns">>;

// History lists Sessions as entries named by their inputs; "Session" stays an internal term.
export interface DashboardActivitySidebarProps {
  /** The viewer, so their own runs read "You". */
  principalId: string | null;
  readOnly?: boolean;
  drawerOnly?: boolean;
  queryNames?: readonly string[];
  linkedRunId?: string;
  expandedGroups: Record<string, readonly string[]>;
  onExpandedGroupsChange: Dispatch<SetStateAction<Record<string, readonly string[]>>>;
  sessions: readonly ActivitySession[];
  selectedSessionId: string;
  selectedSession: { dashboardSessionId?: string; parameterBindings?: DashboardSessionSummary["parameterBindings"]; definition: Pick<DashboardDefinition, "parameters"> & Partial<DashboardDefinition> };
  queryRuns: readonly ActivityRun[];
  targetQueryRuns: Record<string, string>;
  queryRunsLoading: boolean;
  queryRunsError: string | null;
  hasNextQueryRuns: boolean;
  loadingNextQueryRuns: boolean;
  onLoadMoreQueryRuns: () => void;
  onRefreshQuery: (queryName: string) => void;
  refreshingQueryNames: readonly string[];
  hasNextSessions: boolean;
  loadingNextSessions: boolean;
  sessionsError: string | null;
  onLoadMoreSessions: () => void;
  openingSession?: boolean;
  onNewSession?: () => void;
  onSelectSession: (sessionId: string) => void;
  view: "sessions" | "queries";
  onViewChange: (view: "sessions" | "queries") => void;
  // The desktop panel is hidden while collapsed; narrow screens use the drawer.
  collapsed: boolean;
  onCollapsedChange: (collapsed: boolean) => void;
  mobileOpen: boolean;
  onMobileOpenChange: (open: boolean) => void;
}

function historyEntrySummary(session: ActivitySession, definitions: DashboardDefinition["parameters"]): string | null {
  return session.parameterBindings ? dashboardInputsSummary(dashboardInputChips(session.parameterBindings, definitions), "first-labelled") : null;
}

export function DashboardActivitySidebar(props: DashboardActivitySidebarProps) {
  const { collapsed, onCollapsedChange, mobileOpen, onMobileOpenChange, view, onViewChange, queryRuns, targetQueryRuns } = props;
  useEffect(() => {
    if (props.drawerOnly) return;
    const desktopQuery = window.matchMedia("(min-width: 1024px)");
    const moveDrawerToPanel = () => {
      if (!desktopQuery.matches || !mobileOpen) return;
      onMobileOpenChange(false);
      onCollapsedChange(false);
    };
    moveDrawerToPanel();
    desktopQuery.addEventListener("change", moveDrawerToPanel);
    return () => desktopQuery.removeEventListener("change", moveDrawerToPanel);
  }, [mobileOpen, onCollapsedChange, onMobileOpenChange, props.drawerOnly]);
  const desktopSidebar = useRef<HTMLElement | null>(null);
  const mobileTrigger = useRef<HTMLElement | null>(null);
  const inspectedRunTrigger = useRef<HTMLElement | null>(null);
  const [inspection, setInspection] = useState<{ sessionId: string; runId: string } | null>(null);
  const inspectedRun = inspection?.sessionId === props.selectedSessionId ? queryRuns.find((run) => run.queryRunId === inspection.runId) : undefined;
  const setInspectedRunId = (runId: string | null) => setInspection(runId ? { sessionId: props.selectedSessionId, runId } : null);
  useEffect(() => {
    setInspection(props.linkedRunId ? { sessionId: props.selectedSessionId, runId: props.linkedRunId } : null);
  }, [props.linkedRunId, props.selectedSessionId]);
  const inspecting = Boolean(inspectedRun);
  const wasInspecting = useRef(false);
  useEffect(() => {
    if (inspecting) { wasInspecting.current = true; return; }
    if (!wasInspecting.current) return;
    wasInspecting.current = false;
    // Runs after the dialog's own focus restoration so a detached or linked-run trigger still lands on the History surface.
    const timer = window.setTimeout(() => {
      if (inspectedRunTrigger.current?.isConnected) inspectedRunTrigger.current.focus();
      else if (desktopSidebar.current?.getClientRects().length) desktopSidebar.current.querySelector<HTMLButtonElement>("button")?.focus();
      else mobileTrigger.current?.focus();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [inspecting]);
  const expanded = props.expandedGroups[props.selectedSessionId] ?? [];
  const toggleGroup = (name: string) => props.onExpandedGroupsChange((current) => {
    const names = current[props.selectedSessionId] ?? [];
    return { ...current, [props.selectedSessionId]: names.includes(name) ? names.filter((item) => item !== name) : [...names, name] };
  });
  const inspect = (runId: string) => {
    inspectedRunTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setInspectedRunId(runId);
  };
  const selectedSummary = props.sessions.find((session) => session.dashboardSessionId === props.selectedSessionId);
  const definitions = props.selectedSession.definition.parameters;
  const content = <>
    {view === "queries" ? <button type="button" aria-label="Back to history" aria-description={selectedSummary ? `Opened ${sessionExactTime(selectedSummary.createdAt)}.${selectedSummary.lastQueryRunAt ? ` Last query run ${sessionExactTime(selectedSummary.lastQueryRunAt)}.` : ""}` : undefined} onClick={() => onViewChange("sessions")} className="group/session relative block w-full shrink-0 border-b border-border-light bg-surface px-4 py-4 text-left hover:bg-surface-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring focus-visible:outline-offset-[-2px]">
      <span className="absolute inset-y-0 left-0 w-0.5 bg-accent-marker" aria-hidden="true" />
      <span className="type-status flex items-center gap-1 text-foreground-secondary"><ChevronLeft className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />History</span>
      {selectedSummary ? <EntrySummary session={selectedSummary} definitions={definitions} /> : null}
      <span className="type-item mt-2 block text-heading">Query runs</span>
    </button> : null}
    <ActivityContent {...props} expanded={expanded} onToggleGroup={toggleGroup} onInspect={inspect} />
  </>;
  const heading = <div className="flex min-w-0 items-center gap-2"><History className="h-4 w-4 text-foreground-secondary" aria-hidden="true" /><h2 className="type-item text-heading">History</h2></div>;
  return <>
    {!props.drawerOnly && !collapsed && <aside ref={desktopSidebar} id="dashboard-activity-sidebar" aria-label="History" className={`${styles.rail} hidden h-full min-h-0 w-[320px] shrink-0 flex-col border-l border-border bg-background lg:flex`}>
      <header className="flex h-14 shrink-0 items-center justify-between border-b border-border px-4">
        {heading}<IconButton label="Close history" icon={X} onClick={() => onCollapsedChange(true)} />
      </header>
      {content}
    </aside>}
    <RadixDialog.Root open={mobileOpen} onOpenChange={onMobileOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className={`${DIALOG_OVERLAY_CLASS} ${props.drawerOnly ? "" : "lg:hidden"}`} />
        <RadixDialog.Content onOpenAutoFocus={() => { mobileTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; }} onCloseAutoFocus={(event) => { event.preventDefault(); if (mobileTrigger.current?.isConnected && mobileTrigger.current.getClientRects().length > 0) mobileTrigger.current.focus(); else desktopSidebar.current?.querySelector<HTMLButtonElement>("button")?.focus(); }} aria-describedby={undefined} className={`fixed inset-y-0 right-0 z-50 flex w-[min(520px,calc(100vw-24px))] flex-col border-l border-border bg-background shadow-modal outline-none max-sm:w-full max-sm:border-0 ${props.drawerOnly ? "" : "lg:hidden"}`}>
          <RadixDialog.Title className="sr-only">History</RadixDialog.Title>
          <header className="flex h-14 shrink-0 items-center justify-between border-b border-border px-4">{heading}<RadixDialog.Close asChild><IconButton label="Close history" icon={X} /></RadixDialog.Close></header>
          {content}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
    <Dialog closeLabel="Close run detail"
      open={inspecting}
      onOpenChange={(open) => { if (!open) setInspectedRunId(null); }}
      size="sm"
      title={inspectedRun?.queryName ?? ""}
      description="Query run details"
    >
      {inspectedRun ? <DashboardQueryRunDetail run={inspectedRun} principalId={props.principalId} currentTarget={targetQueryRuns[inspectedRun.queryName] === inspectedRun.queryRunId} /> : null}
    </Dialog>
  </>;
}

export function DashboardQueryRunDetail({ run, principalId, currentTarget }: { run: ActivityRun; principalId: string | null; currentTarget: boolean }) {
  const agent = run.credentialSurface === "AGENT";
  return <>
    <dl className="type-meta grid grid-cols-2 gap-4">
      <div><dt className="text-foreground-secondary">Status</dt><dd className="mt-1 text-foreground"><WorkStatus state={run.state} labelDisplay="inline" size="sm" />{currentTarget ? " · Current target" : ""}</dd></div>
      <div><dt className="text-foreground-secondary">Started</dt><dd className="mt-1 tabular-nums text-foreground">{formatExactTime(run.createdAt)}</dd></div>
      {run.startedBy || agent ? (
        <div className="col-span-2"><dt className="text-foreground-secondary">Started by</dt><dd className="mt-1 text-foreground">
          {run.startedBy
            ? <ActorProvenance verb={null} actor={run.startedBy} surface={run.credentialSurface ?? null} agentApp={run.agentApp ?? null} principalId={principalId} time={null} />
            : <AgentMarker app={run.agentApp ?? null} focusable />}
        </dd></div>
      ) : null}
      <div><dt className="text-foreground-secondary">Result</dt><dd className="mt-1 text-foreground">{run.resultAvailable ? "Result available" : "No result"}</dd></div>
      <div><dt className="text-foreground-secondary">Columns</dt><dd className="mt-1 tabular-nums text-foreground">{run.outputColumnNames.length}</dd></div>
      <div className="col-span-2"><dt className="text-foreground-secondary">Run ID</dt><dd className="mt-1 break-all text-foreground">{run.queryRunId}</dd></div>
    </dl>
    {run.failureMessage || run.failureCode ? <p className="type-meta mt-4 border-t border-border pt-4 text-error">{run.failureMessage ?? run.failureCode}</p> : null}
  </>;
}

function ActivityContent(props: DashboardActivitySidebarProps & { expanded: readonly string[]; onToggleGroup: (name: string) => void; onInspect: (runId: string) => void }) {
  const scrollbar = useTransientScrollbar();
  const queryRuns = [...props.queryRuns].sort((left, right) => right.createdAt.localeCompare(left.createdAt) || right.queryRunId.localeCompare(left.queryRunId));
  const queryNames = Array.from(new Set([...(props.queryNames ?? Object.keys(props.selectedSession.definition.queries ?? {})), ...queryRuns.map((run) => run.queryName)]));
  const definitions = props.selectedSession.definition.parameters;
  return <>
    <div key={props.view} aria-label={props.view === "sessions" ? "History entries" : "Query runs"} onScroll={scrollbar.onScroll} className={`min-h-0 flex-1 overflow-y-auto ${scrollbar.className} ${props.view === "queries" ? styles.forward : styles.backward}`}>
      {props.view === "sessions" ? <>
        {props.sessionsError && props.sessions.length > 0 ? <p role="alert" className="type-meta px-4 py-3 text-error">{props.sessionsError}</p> : null}
        {props.sessions.length === 0 ? props.loadingNextSessions ? <StateBlock kind="loading" layout="inline" title="Loading history…" /> : props.sessionsError ? <StateBlock kind="error" layout="inline" title="History could not be loaded." description={props.sessionsError === "History could not be loaded." ? undefined : props.sessionsError} /> : <StateBlock kind="empty" layout="inline" icon={History} title="No history yet." /> : null}
        {props.sessions.map((session) => {
          const selected = session.dashboardSessionId === props.selectedSessionId;
          const summary = historyEntrySummary(session, definitions);
          return <button key={session.dashboardSessionId} type="button" data-session-id={session.dashboardSessionId} aria-label={`${summary ? `${summary}. ` : ""}Opened ${sessionExactTime(session.createdAt)}.${session.lastQueryRunAt ? ` Last run ${sessionExactTime(session.lastQueryRunAt)}.` : session.lastQueryRunAt === null ? " No results yet." : ""}`} aria-current={selected ? "true" : undefined} onClick={() => props.onSelectSession(session.dashboardSessionId)} className={`group/session relative block w-full border-b border-border-light px-4 py-4 text-left hover:bg-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring focus-visible:outline-offset-[-2px] ${selected ? "bg-surface" : ""}`}>
            {selected ? <span className="absolute inset-y-0 left-0 w-0.5 bg-accent-marker" aria-hidden="true" /> : null}
            <EntrySummary session={session} definitions={definitions} />
          </button>;
        })}
        {props.hasNextSessions || props.sessionsError ? <div className="p-4"><Button type="button" variant="secondary" size="sm" pending={props.loadingNextSessions} onClick={props.onLoadMoreSessions} className="w-full">{props.loadingNextSessions ? "Loading earlier entries…" : props.sessionsError ? "Retry history" : "Load earlier entries"}</Button></div> : null}
      </> : <>
        {props.queryRunsError && (queryNames.length > 0 || props.queryRunsLoading) ? <p role="alert" className="type-meta px-4 py-3 text-error">{props.queryRunsError}</p> : null}
        {props.queryRunsLoading ? <StateBlock kind="loading" layout="inline" title="Loading query runs…" /> : null}
        {queryNames.length === 0 && !props.queryRunsLoading ? props.queryRunsError ? <StateBlock kind="error" layout="inline" title="Query runs could not be loaded." description={props.queryRunsError === "Query runs could not be loaded." ? undefined : props.queryRunsError} /> : <StateBlock kind="empty" layout="inline" icon={Code2} title="These results have no queries." /> : null}
        {queryNames.map((name) => <QueryGroup key={name} name={name} principalId={props.principalId} runs={queryRuns.filter((run) => run.queryName === name)} expanded={props.expanded.includes(name)} onToggle={() => props.onToggleGroup(name)} onInspect={props.onInspect} onRefresh={props.readOnly ? undefined : () => props.onRefreshQuery(name)} refreshing={props.refreshingQueryNames.includes(name)} targetRunId={props.targetQueryRuns[name]} />)}
      </>}
    </div>
    <footer className="shrink-0 border-t border-border px-4 py-3">
      {props.view === "sessions"
        ? <div className="type-status flex items-center justify-between gap-2 text-foreground-secondary"><span>Select an entry to see its results.</span><Button type="button" variant="ghost" size="sm" onClick={() => props.onViewChange("queries")}>Show query runs<ChevronRight aria-hidden="true" /></Button></div>
        : <div className="type-status flex items-center justify-between gap-2 text-foreground-secondary"><span className="tabular-nums">{props.queryRuns.length} loaded runs</span>{props.hasNextQueryRuns || props.queryRunsError ? <Button type="button" variant="ghost" size="sm" pending={props.loadingNextQueryRuns} onClick={props.onLoadMoreQueryRuns}>{props.loadingNextQueryRuns ? "Loading…" : props.queryRunsError ? "Retry runs" : "Load earlier runs"}</Button> : null}</div>}
    </footer>
  </>;
}

function RunningCount({ count }: { count: number }) {
  return <span className="type-status mt-1 inline-flex items-center gap-1.5 tabular-nums text-foreground-secondary"><LoaderCircle className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" aria-hidden="true" />{count} running</span>;
}

function QueryGroup({ name, principalId, runs, expanded, onToggle, onInspect, onRefresh, refreshing, targetRunId }: { name: string; principalId: string | null; runs: readonly ActivityRun[]; expanded: boolean; onToggle: () => void; onInspect: (id: string) => void; onRefresh?: () => void; refreshing: boolean; targetRunId: string | undefined }) {
  const contentId = useId();
  const runningCount = runs.filter((run) => run.state === "RUNNING").length;
  return <section className="border-b border-border-light">
    <div className="relative">
      <button type="button" aria-label={`Query ${name}`} aria-expanded={expanded} aria-controls={contentId} onClick={onToggle} className="flex w-full min-w-0 items-center gap-2 px-4 py-3 text-left hover:bg-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring focus-visible:outline-offset-[-2px]">
        <span className="min-w-0 flex-1 pr-9"><span className="type-control flex min-w-0 items-center gap-2 text-foreground"><Code2 className="h-4 w-4 shrink-0 text-foreground-secondary" aria-hidden="true" /><span className="truncate" title={name}>{name}</span></span><span className="type-status mt-1 block text-foreground-secondary">{runs[0] ? <>Latest: <WorkStatus state={runs[0].state} labelDisplay="inline" size="sm" /></> : "No runs yet"}</span>{runningCount > 0 ? <RunningCount count={runningCount} /> : null}</span>
        <ChevronRight className={`h-3.5 w-3.5 shrink-0 text-foreground-secondary ${styles.chevron} ${expanded ? "rotate-90" : ""}`} aria-hidden="true" />
      </button>
      {onRefresh ? <IconButton label={`Refresh ${name}`} icon={RefreshCw} className="absolute right-10 top-1/2 -translate-y-1/2" pending={refreshing} onClick={onRefresh} /> : null}
    </div>
    <div id={contentId} inert={!expanded} aria-hidden={!expanded} data-expanded={expanded} className={styles.disclosure}>
      <div className={styles.disclosureClip}><div className="mb-3 ml-5 border-l border-border-light pl-2 pr-3">
        <p className="type-status px-2 pb-1 tabular-nums text-foreground-secondary">{runs.length} loaded runs</p>
        {runs.map((run) => {
          const starter = actorProvenanceLabel("started", run.startedBy ?? null, run.credentialSurface ?? null, run.agentApp ?? null, principalId);
          return <button key={run.queryRunId} type="button" aria-label={`Open ${name} run ${formatExactTime(run.createdAt)}${starter ? `, ${starter}` : ""}`} onClick={() => onInspect(run.queryRunId)} className="block w-full rounded-[4px] px-2 py-2 text-left hover:bg-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring"><span className="type-meta flex flex-wrap items-center gap-x-2 tabular-nums text-foreground">{formatExactTime(run.createdAt)}{run.startedBy ? <><span aria-hidden="true">·</span><span>{actorLabel(run.startedBy, principalId)}</span></> : null}{run.credentialSurface === "AGENT" ? <AgentMarker app={run.agentApp ?? null} focusable={false} /> : null}</span><span className="type-status mt-1 block text-foreground-secondary"><WorkStatus state={run.state} labelDisplay="inline" size="sm" />{targetRunId === run.queryRunId ? " · Current target" : ""}</span><span className="type-status mt-1 block text-foreground-secondary">{run.resultAvailable ? "Result available" : "No result"}</span></button>;
        })}
      </div></div>
    </div>
  </section>;
}

function EntrySummary({ session, definitions }: { session: ActivitySession; definitions: DashboardDefinition["parameters"] }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 15_000);
    return () => window.clearInterval(timer);
  }, []);
  const runningCount = (session.runningQueryRuns ?? []).filter((run) => run.state === "RUNNING").length;
  const summary = historyEntrySummary(session, definitions);
  return <>
    <span className="type-control mt-1 block truncate text-foreground" title={summary ?? undefined}>{summary ?? `Opened ${sessionCreatedLabel(session.createdAt, now).replace(/^(Today|Yesterday),/, (match) => match.toLowerCase())}`}</span>
    {session.lastQueryRunAt !== undefined ? <span className="type-status mt-1 block tabular-nums text-foreground-secondary">{session.lastQueryRunAt ? resultsFromLabel(session.lastQueryRunAt, now).replace(/^Results from /, "Last run ") : "No runs yet"}</span> : null}
    {runningCount > 0 ? <RunningCount count={runningCount} /> : null}
    <span aria-hidden="true" className={`${styles.timestampTooltip} pointer-events-none absolute inset-x-3 bottom-1 z-20 type-status rounded-[4px] bg-accent px-2 py-1.5 leading-relaxed tabular-nums text-accent-foreground shadow-ambient`}>
      <span className="block">Opened · {sessionExactTime(session.createdAt)}</span>
      {session.lastQueryRunAt ? <span className="block">Last query run · {sessionExactTime(session.lastQueryRunAt)}</span> : null}
    </span>
  </>;
}
