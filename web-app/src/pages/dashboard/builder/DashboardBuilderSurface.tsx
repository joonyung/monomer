import { useEffect, useMemo, useRef, useState } from "react";
import * as RadixDialog from "@radix-ui/react-dialog";
import { Braces, Check, History, RefreshCw, Undo2 } from "lucide-react";
import type { DashboardDefinition, DashboardDraftResponse, DashboardJsonObject, DashboardJsonValue } from "@/api/dashboard";
import { useOptionalWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";
import { DashboardParameterToolbar } from "@/components/dashboard/DashboardParameterToolbar";
import type { DashboardWidgetMetricsTarget } from "@/components/dashboard/DashboardWidgetRenderer";
import { WidgetMetricsDialog } from "@/components/dashboard/WidgetMetricsDialog";
import { RunDetailDialog, RunsRail, RunsStatusDot, type RunPresentation } from "@/components/runs";
import { ActorProvenance } from "@/components/ui/ActorProvenance";
import { Button } from "@/components/ui/Button";
import { Dialog, DIALOG_OVERLAY_CLASS } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { dashboardQueryTypes } from "@/features/dashboard/widget-metrics";
import { relativeTime } from "@/features/dashboard/schedule-summary";
import { dashboardBuilderDisplayState, dashboardRunPresentation, retainHydratingBuilderWidgets, type DashboardBuilderDisplayState } from "./presentation";

import { useChangeHighlight } from "@/lib/change-highlight";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { definitionChangeSummary, summarizeDefinitionChanges } from "./definition-changes";
import { compatibleDraftWidgetInput } from "./shared-interactions";
import { dashboardWidgetChanges } from "./widget-changes";

export interface DashboardBuilderSurfaceProps {
  scheduleControl?: React.ReactNode;
  observationOnly?: boolean;
  draft: DashboardDraftResponse;
  // The viewer, so the review can say "you" when they made the last edit.
  principalId: string | null;
  // The definition everyone currently sees; the review compares the shared Draft against it.
  appliedDefinition: DashboardDefinition;
  // The Draft revision this reader just applied, while it is still what everyone sees.
  appliedRevision?: number | null;
  resultsByRunId: Readonly<Record<string, NamedDashboardResult>>;
  deniedRunIds?: ReadonlySet<string>;
  selectedRunId?: string | null;
  onSelectedRunChange?: (runId: string | null) => void;
  newlyAddedRunId?: string | null;
  writePending?: boolean;
  onVariablesSet?: (values: DashboardJsonObject, expectedRevision: number) => void;
  refreshPending?: boolean;
  applyPending?: boolean;
  discardPending?: boolean;
  parameterPendingName?: string | null;
  cancellingRunId?: string | null;
  errorMessage?: string | null;
  onRefresh: (queryNames: readonly string[]) => void;
  onApply: () => void;
  onDiscard: () => void;
  onParameterSet: (name: string, value: DashboardJsonValue) => void;
  onCancel: (runId: string) => void;
}

function DashboardDefinitionDialog({ draft }: { draft: DashboardDraftResponse }) {
  const [open, setOpen] = useState(false);
  return (
    <Dialog closeLabel="Close Definition"
      open={open}
      onOpenChange={setOpen}
      size="xl"
      trigger={<IconButton label="View definition" icon={Braces} size="md" />}
      title="Dashboard definition"
      description={<>The complete working definition for shared draft revision <span className="tabular-nums">{draft.authoringRevision}</span>.</>}
      bodyClassName="overflow-x-auto bg-surface/40 p-4 sm:p-5"
    >
      <pre className="type-mono min-w-max leading-[1.65] text-foreground" data-testid="dashboard-dsl-json">
        {JSON.stringify(draft.definition, null, 2)}
      </pre>
    </Dialog>
  );
}

export function DashboardBuilderSurface({
  scheduleControl,
  observationOnly = false,
  draft,
  principalId,
  appliedDefinition,
  appliedRevision = null,
  resultsByRunId,
  deniedRunIds = new Set<string>(),
  selectedRunId: selectedRunIdProp,
  onSelectedRunChange,
  newlyAddedRunId = null,
  writePending = false,
  onVariablesSet,
  refreshPending = false,
  applyPending = false,
  discardPending = false,
  parameterPendingName = null,
  cancellingRunId = null,
  errorMessage = null,
  onRefresh,
  onApply,
  onDiscard,
  onParameterSet,
  onCancel,
}: DashboardBuilderSurfaceProps) {
  const [discardOpen, setDiscardOpen] = useState(false);
  const [widgetMetricsTarget, setWidgetMetricsTarget] = useState<DashboardWidgetMetricsTarget | null>(null);
  const sampleWorkspace = Boolean(useOptionalWorkspaceRouteContext()?.workspace.sample);
  const scrollbar = useTransientScrollbar();
  const highlightContainer = useChangeHighlight(JSON.stringify([draft.workspaceId, draft.dashboardId]), dashboardWidgetChanges(draft.definition), { animateArrival: true, arrivalStyle: "fade-slide" });
  const [internalSelectedRunId, setInternalSelectedRunId] = useState<string | null>(null);
  const [runsCollapsed, setRunsCollapsed] = useState(false);
  const [runsDrawerOpen, setRunsDrawerOpen] = useState(false);
  const scope = JSON.stringify([draft.workspaceId, draft.dashboardId, draft.definition]);
  const [retained, setRetained] = useState<{ scope: string; draft: DashboardDraftResponse; results: typeof resultsByRunId; denied: string; display: DashboardBuilderDisplayState } | null>(null);
  const denied = [...deniedRunIds].sort().join(",");
  const display = retainHydratingBuilderWidgets(draft, dashboardBuilderDisplayState(draft, resultsByRunId), retained?.scope === scope ? retained.display : null, deniedRunIds);
  if (!retained || retained.scope !== scope || retained.draft !== draft || retained.results !== resultsByRunId || retained.denied !== denied) {
    setRetained({ scope, draft, results: resultsByRunId, denied, display });
  }
  const latestInteraction = useRef({ draft, display, disabled: observationOnly || writePending, onVariablesSet });
  latestInteraction.current = { draft, display, disabled: observationOnly || writePending, onVariablesSet };
  useEffect(() => {
    latestInteraction.current.disabled = observationOnly || writePending;
    return () => { latestInteraction.current.disabled = true; };
  }, [observationOnly, writePending]);
  const [interactionMessage, setInteractionMessage] = useState<string | null>(null);
  const setWidgetVariables = (widgetId: string, values: DashboardJsonObject) => {
    const latest = latestInteraction.current;
    if (latest.disabled || !latest.onVariablesSet) return;
    if (latest.display.errorMessagesByWidgetId[widgetId] || !compatibleDraftWidgetInput(widgetId, draft, display, latest.draft, values)) {
      setInteractionMessage("This chart is showing an older input or result. Wait for the latest result, then try again.");
      return;
    }
    setInteractionMessage(null);
    latest.onVariablesSet(values, latest.draft.authoringRevision);
  };
  const runs = useMemo<RunPresentation[]>(() => draft.recentQueryRuns.map((run) => dashboardRunPresentation(
    run,
    draft.targetQueryRuns,
    resultsByRunId[run.queryRunId],
  )), [draft.recentQueryRuns, draft.targetQueryRuns, resultsByRunId]);
  const selectedRunId = selectedRunIdProp === undefined ? internalSelectedRunId : selectedRunIdProp;
  const selectedRun = runs.find((run) => run.id === selectedRunId) ?? null;
  const runningCount = draft.recentQueryRuns.filter((run) => run.state === "RUNNING").length;
  const changes = useMemo(() => summarizeDefinitionChanges(appliedDefinition, draft.definition), [appliedDefinition, draft.definition]);
  const hasChanges = changes.length > 0;
  const queryNames = Object.keys(draft.definition.queries);
  const hasParameters = Object.keys(draft.definition.parameters).length > 0;
  const dashboardHref = webPaths.dashboard(draft.workspaceId, draft.dashboardId);

  useEffect(() => {
    if (!selectedRunId || runs.some((run) => run.id === selectedRunId)) return;
    if (selectedRunIdProp === undefined) setInternalSelectedRunId(null);
    onSelectedRunChange?.(null);
  }, [onSelectedRunChange, runs, selectedRunId, selectedRunIdProp]);

  const inspectRun = (runId: string) => {
    if (selectedRunIdProp === undefined) setInternalSelectedRunId(runId);
    onSelectedRunChange?.(runId);
    setRunsDrawerOpen(false);
  };

  return (
    <RadixDialog.Root open={runsDrawerOpen} onOpenChange={setRunsDrawerOpen}>
      <div className="flex h-full min-h-0 flex-col bg-background" style={{ fontFeatureSettings: '"ss01"' }}>
        <WorkspacePageHeader
          section="Draft review"
          title={observationOnly ? `${draft.definition.meta.title} · Draft run` : draft.definition.meta.title}
          description={observationOnly ? undefined : (
            <DraftStatusLine
              draft={draft}
              principalId={principalId}
              hasChanges={hasChanges}
              appliedRevision={!hasChanges && appliedRevision === draft.authoringRevision ? appliedRevision : null}
              dashboardHref={dashboardHref}
            />
          )}
          actions={(
            <>
              <RadixDialog.Trigger asChild>
                <IconButton label={runningCount > 0 ? "Runs, activity in progress" : "Runs"} icon={History} size="md" title="Runs" className="relative lg:hidden">
                  <RunsStatusDot running={runningCount > 0} />
                </IconButton>
              </RadixDialog.Trigger>
              {scheduleControl}
              <DashboardDefinitionDialog draft={draft} />
              {!observationOnly && hasChanges && (
                <IconButton label="Discard changes" icon={Undo2} size="md" onClick={() => setDiscardOpen(true)} pending={discardPending} disabled={writePending} />
              )}
              {!observationOnly && (
                <Button variant="secondary" size="sm" onClick={() => onRefresh(queryNames)} pending={refreshPending} disabled={queryNames.length === 0 || writePending}>
                  {!refreshPending && <RefreshCw aria-hidden="true" />}
                  {refreshPending ? "Refreshing…" : "Refresh"}
                </Button>
              )}
              {!observationOnly && (
                <span title={hasChanges ? undefined : "No changes to apply"} className="inline-flex">
                  <Button variant="primary" size="sm" onClick={onApply} pending={applyPending} disabled={!hasChanges || writePending}>
                    {!applyPending && <Check aria-hidden="true" />}
                    {applyPending ? "Applying…" : "Apply"}
                  </Button>
                </span>
              )}
            </>
          )}
        />

        {!observationOnly && <DashboardParameterToolbar
          definitions={draft.definition.parameters}
          values={draft.parameterBindings}
          pendingName={writePending ? parameterPendingName ?? "__draft_write__" : parameterPendingName}
          onSet={onParameterSet}
        />}

        <div className="flex min-h-0 flex-1">
          <main onScroll={scrollbar.onScroll} className={`min-w-0 flex-1 overflow-y-auto px-5 py-5 sm:px-8 sm:py-6 ${scrollbar.className}`} data-testid="dashboard-builder-preview">
            <div ref={highlightContainer} className="mx-auto w-full max-w-[1080px]">
              {!observationOnly && hasParameters ? <p className="type-meta mb-3 text-foreground-secondary">Preview inputs here don&apos;t change the live dashboard.</p> : null}
              {interactionMessage && <p role="status" className="type-meta mb-3 text-foreground-secondary">{interactionMessage}</p>}
              {errorMessage ? <p role="alert" className="type-meta mb-4 border border-error/20 bg-error/5 px-3 py-2 text-error">{errorMessage}</p> : null}
              <DashboardLayout
                definition={draft.definition}
                queryTypes={dashboardQueryTypes(draft.definition.queries)}
                parameters={draft.parameterBindings}
                parametersByWidgetId={display.parametersByWidgetId}
                variables={draft.variables}
                variablesByWidgetId={display.variablesByWidgetId}
                variableRevision={draft.authoringRevision}
                variableRevisionsByWidgetId={display.variableRevisionsByWidgetId}
                variableDefinitions={draft.definition.variables}
                resultsByWidgetId={display.resultsByWidgetId}
                displayedWidgetIds={display.displayedWidgetIds}
                refreshingWidgetIds={display.refreshingWidgetIds}
                failureWidgetIds={display.failureWidgetIds}
                errorMessagesByWidgetId={display.errorMessagesByWidgetId}
                onVariablesSet={(values, revision) => { if (!latestInteraction.current.disabled) latestInteraction.current.onVariablesSet?.(values, revision); }}
                onWidgetVariablesSet={setWidgetVariables}
                sharedInputRevision={observationOnly ? undefined : draft.authoringRevision}
                readOnly={observationOnly || writePending || !onVariablesSet}
                widgetActions={{
                  pending: refreshPending || writePending,
                  onRefresh: observationOnly ? undefined : onRefresh,
                  onShowRuns: (queryNames) => {
                    const runId = latestWidgetRunId(draft, queryNames);
                    if (runId) { setInteractionMessage(null); inspectRun(runId); } else setInteractionMessage("No run details for this widget yet.");
                  },
                  onShowMetrics: setWidgetMetricsTarget,
                  onShowSql: setWidgetMetricsTarget,
                }}
              />
            </div>
          </main>

          <RunsRail
            className="hidden lg:flex"
            runs={runs}
            principalId={principalId}
            newlyAddedRunId={newlyAddedRunId}
            collapsed={runsCollapsed}
            onCollapsedChange={setRunsCollapsed}
            onInspect={inspectRun}
            ariaLabel="Dashboard runs"
          />
        </div>

        <RadixDialog.Portal>
          <RadixDialog.Overlay className={`runs-overlay-motion ${DIALOG_OVERLAY_CLASS} lg:hidden`} />
          <RadixDialog.Content className="runs-drawer-motion fixed inset-y-0 right-0 z-50 w-[min(520px,calc(100vw-24px))] bg-background shadow-modal outline-none lg:hidden">
            <RadixDialog.Title className="sr-only">Dashboard runs</RadixDialog.Title>
            <RadixDialog.Description className="sr-only">Inspect Dashboard Draft query runs.</RadixDialog.Description>
            <RunsRail
              className="flex h-full"
              runs={runs}
              principalId={principalId}
              newlyAddedRunId={newlyAddedRunId}
              collapsed={false}
              onInspect={inspectRun}
              onClose={() => setRunsDrawerOpen(false)}
              ariaLabel="Dashboard runs"
            />
          </RadixDialog.Content>
        </RadixDialog.Portal>

        <Dialog
          open={discardOpen}
          onOpenChange={setDiscardOpen}
          size="md"
          role="alertdialog"
          closeLabel="Close discard changes"
          testId="dashboard-discard-dialog"
          title={`Discard unapplied changes to ${draft.definition.meta.title}?`}
          description="This can’t be undone."
          footer={(
            <div className="ml-auto flex gap-2">
              <Button type="button" variant="secondary" onClick={() => setDiscardOpen(false)}>Keep changes</Button>
              <Button type="button" variant="danger" disabled={writePending} onClick={() => { setDiscardOpen(false); onDiscard(); }}>
                Discard changes
              </Button>
            </div>
          )}
        >
          <p className="type-body text-foreground">
            The draft goes back to what everyone sees now{changes.length > 0 ? <> and drops {definitionChangeSummary(changes)}</> : null}. Ask your agent to redo any change you still want.
          </p>
        </Dialog>

        <WidgetMetricsDialog workspaceId={draft.workspaceId} principalId={principalId} sampleWorkspace={sampleWorkspace} queries={draft.definition.queries} target={widgetMetricsTarget} onClose={() => setWidgetMetricsTarget(null)} />

        <RunDetailDialog
          run={selectedRun}
          principalId={principalId}
          onOpenChange={(open) => {
            if (open) return;
            if (selectedRunIdProp === undefined) setInternalSelectedRunId(null);
            onSelectedRunChange?.(null);
          }}
          onCancel={observationOnly || writePending ? undefined : (runId) => onCancel(runId)}
          cancelling={selectedRunId !== null && cancellingRunId === selectedRunId}
        />
      </div>
    </RadixDialog.Root>
  );
}

function DraftStatusLine({ draft, principalId, hasChanges, appliedRevision, dashboardHref }: {
  draft: DashboardDraftResponse;
  principalId: string | null;
  hasChanges: boolean;
  appliedRevision: number | null;
  dashboardHref: string;
}) {
  return (
    <span role="status" data-testid="draft-status" className="inline-flex flex-wrap items-center gap-x-1.5">
      {appliedRevision !== null ? (
        <span className="inline-flex flex-wrap items-center gap-x-1.5 text-success-text">
          <span className="tabular-nums">Applied. Everyone now sees revision {appliedRevision}.</span>
          <Link href={dashboardHref} className="text-foreground underline underline-offset-4">View dashboard</Link>
        </span>
      ) : (
        <>
          <span className="tabular-nums">Revision {draft.authoringRevision}</span>
          <span aria-hidden="true">·</span>
          <span>{hasChanges ? "Unapplied changes" : "Matches what everyone sees"}</span>
          {hasChanges && draft.lastEditedBy ? (
            <>
              <span aria-hidden="true">·</span>
              <ActorProvenance
                verb={null}
                actor={draft.lastEditedBy}
                surface={draft.lastEditSurface}
                agentApp={draft.lastEditAgentApp}
                principalId={principalId}
                time={draft.lastEditedAt ? <time dateTime={draft.lastEditedAt} title={new Date(draft.lastEditedAt).toLocaleString("en-US")}>{relativeTime(draft.lastEditedAt, new Date())}</time> : null}
              />
            </>
          ) : null}
        </>
      )}
    </span>
  );
}

// The run behind what a widget shows, among the runs the Draft lists: its current target run, else its most recent run.
function latestWidgetRunId(draft: DashboardDraftResponse, queryNames: readonly string[]): string | null {
  const listed = draft.recentQueryRuns.filter((run) => queryNames.includes(run.queryName));
  const target = listed.find((run) => queryNames.some((name) => draft.targetQueryRuns[name] === run.queryRunId));
  if (target) return target.queryRunId;
  return [...listed].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]?.queryRunId ?? null;
}
