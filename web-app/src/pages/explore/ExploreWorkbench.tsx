import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { Code2, History, Play, X } from "lucide-react";
import type { SemanticSqlRender } from "@/api/explore";
import { ExploreFailureNotice, type ExploreFailureNoticeContent } from "@/components/explore/ExploreFailureNotice";
import { SemanticSqlPreviewDialog } from "@/components/explore/SemanticSqlPreviewDialog";
import { SqlEditor, sqlPlaceholder } from "@/components/explore/SqlEditor";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import { Button } from "@/components/ui/Button";
import { DIALOG_OVERLAY_CLASS } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { Link } from "@/lib/navigation";
import type { Connector } from "@/lib/types/connector";
import type { ExploreRun } from "@/api/explore";
import type { Metric } from "@/lib/types/metric";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { ExploreMode } from "./explore-draft";
import { CompactConnectorSelect } from "./CompactConnectorSelect";
import { ExploreRunPanel, type ExploreRunPanelProps } from "./ExploreRunPanel";
import { ExploreRunsRail, RunsStatusDot } from "./ExploreRunsSurface";
import { useChangeHighlight } from "@/lib/change-highlight";
import { SemanticBrowser, SemanticConnectorPart, SemanticQuestion, type SemanticQuestionProps } from "./SemanticQuestion";

const MODES: Array<{ value: ExploreMode; label: string }> = [
  { value: "SEMANTIC", label: "Metrics" },
  { value: "SQL", label: "SQL" },
];

export function modeTabId(mode: ExploreMode): string {
  return `explore-${mode.toLowerCase()}-tab`;
}

export function modePanelId(mode: ExploreMode): string {
  return `explore-${mode.toLowerCase()}-panel`;
}

/** Roving tab focus: Arrow keys wrap, Home and End jump. Other keys leave the selection alone. */
export function modeForKey(key: string, current: ExploreMode): ExploreMode | null {
  const index = MODES.findIndex((option) => option.value === current);
  const count = MODES.length;
  if (key === "ArrowRight") return (MODES[(index + 1) % count] as (typeof MODES)[number]).value;
  if (key === "ArrowLeft") return (MODES[(index - 1 + count) % count] as (typeof MODES)[number]).value;
  if (key === "Home") return (MODES[0] as (typeof MODES)[number]).value;
  if (key === "End") return (MODES[count - 1] as (typeof MODES)[number]).value;
  return null;
}

export interface RenderedSqlPreview {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  loading: boolean;
  failure: ExploreFailureNoticeContent | null;
  render: SemanticSqlRender | null;
  connectorName: string;
  disabled: boolean;
  onRender: () => void;
}

export interface ExploreWorkbenchProps {
  workspaceId: string;
  /** The viewer, so their own runs read "You". */
  principalId: string | null;
  mode: ExploreMode;
  onModeChange: (mode: ExploreMode) => void;
  connectors: Connector[];
  connectorsLoading: boolean;
  connectorsError: string | null;
  onRetryConnectors: () => void;
  semanticQuestion: SemanticQuestionProps;
  semanticRunDisabled: boolean;
  semanticStarting: boolean;
  onRunSemantic: () => void;
  renderedSql: RenderedSqlPreview;
  sql: string;
  onSqlChange: (sql: string) => void;
  sqlConnectorId: string | null;
  onSqlConnectorChange: (connectorId: string) => void;
  sqlRunDisabled: boolean;
  sqlStarting: boolean;
  onRunSql: () => void;
  startFailure: ExploreFailureNoticeContent | null;
  /** Opening a run replaced a question that had not run; it can come back once. */
  replacedQuestion: { onRestore: () => void; onDismiss: () => void } | null;
  runPanel: ExploreRunPanelProps;
  runs: ExploreRun[];
  metrics: Metric[];
  semanticModels: SemanticModelResponse[];
  newlyAddedRunId: string | null;
  selectedRunId: string | null;
  onInspectRun: (runId: string) => void;
}

export function ExploreWorkbench({
  workspaceId,
  principalId,
  mode,
  onModeChange,
  connectors,
  connectorsLoading,
  connectorsError,
  onRetryConnectors,
  semanticQuestion,
  semanticRunDisabled,
  semanticStarting,
  onRunSemantic,
  renderedSql,
  sql,
  onSqlChange,
  sqlConnectorId,
  onSqlConnectorChange,
  sqlRunDisabled,
  sqlStarting,
  onRunSql,
  startFailure,
  replacedQuestion,
  runPanel,
  runs,
  metrics,
  semanticModels,
  newlyAddedRunId,
  selectedRunId,
  onInspectRun,
}: ExploreWorkbenchProps) {
  const [runsDrawerOpen, setRunsDrawerOpen] = useState(false);
  const [runsCollapsed, setRunsCollapsed] = useState(false);
  const running = runs.some((candidate) => candidate.state === "RUNNING");
  const runInView = runPanel.run !== null || runPanel.loading || runPanel.error !== null;
  const starting = mode === "SQL" ? sqlStarting : semanticStarting;
  const runDisabled = mode === "SQL" ? sqlRunDisabled : semanticRunDisabled;
  const run = mode === "SQL" ? onRunSql : onRunSemantic;
  const startFailureRef = useRef<HTMLDivElement>(null);
  const startFailureKey = startFailure ? `${startFailure.title}|${startFailure.reference ?? ""}` : null;
  const focusedStartFailureKey = useRef(startFailureKey);

  // The SQL editor owns ⌘↵ while it has focus; the Metrics question has no text field to own it.
  const latestRun = useRef({ run, runDisabled, mode });
  latestRun.current = { run, runDisabled, mode };
  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.key !== "Enter") return;
      const current = latestRun.current;
      if (current.mode !== "SEMANTIC" || current.runDisabled) return;
      event.preventDefault();
      current.run();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  const [runRequested, setRunRequested] = useState(false);
  useEffect(() => {
    if (!runRequested) return;
    setRunRequested(false);
    const current = latestRun.current;
    if (current.mode === "SEMANTIC" && !current.runDisabled) current.run();
  }, [runRequested]);

  useEffect(() => {
    if (startFailureKey && focusedStartFailureKey.current !== startFailureKey) startFailureRef.current?.focus();
    focusedStartFailureKey.current = startFailureKey;
  }, [startFailureKey]);

  // A metric choice that switched the connector tints the toolbar connector once; ordinary changes stay quiet.
  const connectorHighlight = useChangeHighlight("explore-semantic-connector", [{ id: "semantic-connector", fingerprint: String(semanticQuestion.connector.switchCount) }]);
  const railProps = { runs, principalId, connectors: connectorsLoading || connectorsError ? null : connectors, metrics, semanticModels, newlyAddedRunId, selectedRunId };

  return (
    <Dialog.Root open={runsDrawerOpen} onOpenChange={setRunsDrawerOpen}>
      <div className="flex min-h-0 flex-1 flex-col">
        <WorkspacePageHeader
          section="Analyze"
          title="Explore"
          actions={<>
            <ModeTabs mode={mode} onModeChange={onModeChange} />
            <Dialog.Trigger asChild>
              <Button type="button" variant="ghost" className="relative xl:hidden" aria-label={running ? "Runs, activity in progress" : "Runs"}>
                <History aria-hidden="true" />Runs
                <RunsStatusDot running={running} />
              </Button>
            </Dialog.Trigger>
          </>}
        />
        {!connectorsLoading && !connectorsError && connectors.length === 0 ? (
          <p className="type-control border-b border-border px-5 py-3 text-foreground-secondary sm:px-8">
            Connect a data warehouse to run queries.{" "}
            <Link href={`/workspaces/${workspaceId}/connectors`} className="text-foreground underline underline-offset-4">Go to Connectors</Link>
          </p>
        ) : null}
        <div className="flex min-h-0 flex-1">
          <main className="flex min-w-0 flex-1 flex-col overflow-y-auto">
            <section id={modePanelId(mode)} role="tabpanel" aria-labelledby={modeTabId(mode)} className={`border-b border-border ${mode === "SQL" && !runInView ? "flex flex-1 flex-col" : "shrink-0"}`}>
              {mode === "SEMANTIC" ? (
                <div className="flex flex-col">
                  <div className="flex min-h-12 flex-wrap items-center justify-between gap-2 px-5 py-2 sm:px-8">
                    <div ref={connectorHighlight} className="min-w-0">
                      {semanticQuestion.draft.metrics.length > 0 ? (
                        <span data-change-id="semantic-connector" className="-ml-1.5 inline-flex rounded-[4px] pl-1.5">
                          <span className="change-highlight-overlay" aria-hidden="true" />
                          <SemanticConnectorPart connector={semanticQuestion.connector} />
                        </span>
                      ) : null}
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                      <SemanticSqlPreviewDialog
                        open={renderedSql.open}
                        onOpenChange={renderedSql.onOpenChange}
                        trigger={
                          <IconButton
                            label="View rendered SQL"
                            icon={Code2}
                            size="md"
                            title={renderedSql.disabled ? "Choose a metric and connector to view SQL" : "View rendered SQL"}
                            disabled={renderedSql.disabled}
                            onClick={() => {
                              renderedSql.onOpenChange(true);
                              renderedSql.onRender();
                            }}
                          />
                        }
                        loading={renderedSql.loading}
                        failure={renderedSql.failure}
                        sql={renderedSql.render?.sql ?? ""}
                        connectorName={renderedSql.connectorName}
                        dialect={renderedSql.render?.dialect ?? null}
                        onRetry={renderedSql.onRender}
                      />
                      <RunButton disabled={runDisabled} starting={starting} onRun={run} />
                    </div>
                  </div>
                  <div className="border-t border-border-light px-5 py-2 sm:px-8"><SemanticQuestion {...semanticQuestion} listsOpen={!runInView} onRunRequest={() => setRunRequested(true)} /></div>
                </div>
              ) : (
                <div className="flex flex-1 flex-col">
                  <div className="flex min-h-12 flex-wrap items-center justify-between gap-2 px-5 py-2 sm:px-8">
                    <CompactConnectorSelect
                      connectors={connectors}
                      value={sqlConnectorId}
                      onChange={onSqlConnectorChange}
                      loading={connectorsLoading}
                      error={connectorsError}
                      onRetry={onRetryConnectors}
                    />
                    <RunButton disabled={runDisabled} starting={starting} onRun={run} />
                  </div>
                  <div className={`border-t border-border-light ${runInView ? "h-[280px]" : "min-h-[280px] flex-1"}`}>
                    <SqlEditor value={sql} onChange={onSqlChange} onRun={onRunSql} runDisabled={sqlRunDisabled} placeholder={sqlPlaceholder(connectors.find((connector) => connector.id === sqlConnectorId)?.type)} />
                  </div>
                </div>
              )}
              {startFailure ? <ExploreFailureNotice ref={startFailureRef} failure={startFailure} className="border-t border-error/20 bg-error/5 px-5 py-3 sm:px-8" /> : null}
              {replacedQuestion ? (
                <div role="status" className="flex flex-wrap items-center gap-x-3 gap-y-2 border-t border-border-light bg-surface px-5 py-2 sm:px-8">
                  <p className="type-control min-w-0 flex-1 text-foreground">Opening this run replaced a query you hadn’t run.</p>
                  <Button type="button" variant="secondary" size="sm" onClick={replacedQuestion.onRestore}>Restore it</Button>
                  <IconButton label="Dismiss" icon={X} onClick={replacedQuestion.onDismiss} />
                </div>
              ) : null}
            </section>
            {mode === "SEMANTIC" && !runInView ? <SemanticBrowser {...semanticQuestion} /> : null}
            <ExploreRunPanel {...runPanel} />
          </main>
          <ExploreRunsRail className="hidden xl:flex" {...railProps} collapsed={runsCollapsed} onCollapsedChange={setRunsCollapsed} onInspect={onInspectRun} />
        </div>
        <Dialog.Portal>
          <Dialog.Overlay className={`${DIALOG_OVERLAY_CLASS} runs-overlay-motion xl:hidden`} />
          <Dialog.Content className="runs-drawer-motion fixed inset-y-0 right-0 z-50 w-[min(520px,calc(100vw-24px))] bg-background shadow-modal outline-none xl:hidden">
            <Dialog.Title className="sr-only">Explore runs</Dialog.Title>
            <Dialog.Description className="sr-only">Open a run to see its result and restore its query.</Dialog.Description>
            <ExploreRunsRail className="flex h-full" {...railProps} collapsed={false} onInspect={(runId) => { onInspectRun(runId); setRunsDrawerOpen(false); }} onClose={() => setRunsDrawerOpen(false)} />
          </Dialog.Content>
        </Dialog.Portal>
      </div>
    </Dialog.Root>
  );
}

const APPLE_PLATFORM = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform);

/** The shortcut sits in a tooltip on hover and keyboard focus, not inside the button. */
function RunButton({ disabled, starting, onRun }: { disabled: boolean; starting: boolean; onRun: () => void }) {
  const [tooltipDismissed, setTooltipDismissed] = useState(false);
  return (
    <span className="group relative inline-flex" onMouseEnter={() => setTooltipDismissed(false)} onFocus={() => setTooltipDismissed(false)}>
      <Button type="button" disabled={disabled} pending={starting} aria-keyshortcuts={APPLE_PLATFORM ? "Meta+Enter" : "Control+Enter"} onMouseDown={(event) => event.preventDefault()} onKeyDown={(event) => { if ((event.metaKey || event.ctrlKey) && event.key === "Enter") setTooltipDismissed(true); }} onClick={() => { setTooltipDismissed(true); onRun(); }}>
        {!starting && <Play aria-hidden="true" />}
        {starting ? "Starting…" : "Run"}
      </Button>
      {starting || tooltipDismissed ? null : (
        <span
          aria-hidden="true"
          data-testid="run-shortcut-tooltip"
          className="type-meta pointer-events-none absolute right-0 top-full z-40 mt-1.5 flex items-center gap-1.5 whitespace-nowrap rounded-[6px] bg-accent px-2.5 py-1.5 text-accent-foreground opacity-0 shadow-ambient transition-opacity duration-150 group-focus-within:opacity-100 group-hover:opacity-100 group-hover:delay-200 motion-reduce:transition-none"
        >
          Run<kbd className="font-sans text-accent-foreground/70">{APPLE_PLATFORM ? "⌘↵" : "Ctrl ↵"}</kbd>
        </span>
      )}
    </span>
  );
}

function ModeTabs({ mode, onModeChange }: { mode: ExploreMode; onModeChange: (mode: ExploreMode) => void }) {
  const tabs = useRef(new Map<ExploreMode, HTMLButtonElement>());
  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const next = modeForKey(event.key, mode);
    if (!next) return;
    event.preventDefault();
    onModeChange(next);
    tabs.current.get(next)?.focus();
  };
  return (
    <div className="flex h-9 items-stretch gap-1" role="tablist" aria-label="Query type">
      {MODES.map((option) => {
        const selected = option.value === mode;
        return (
          <button
            key={option.value}
            ref={(node) => {
              if (node) tabs.current.set(option.value, node);
              else tabs.current.delete(option.value);
            }}
            id={modeTabId(option.value)}
            type="button"
            role="tab"
            aria-selected={selected}
            aria-controls={modePanelId(option.value)}
            tabIndex={selected ? 0 : -1}
            onClick={() => onModeChange(option.value)}
            onKeyDown={handleKeyDown}
            className={`type-control relative h-9 px-3 focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-[-3px] focus-visible:outline-focus-ring ${selected ? "text-foreground" : "text-foreground-secondary hover:text-foreground"}`}
          >
            {option.label}
            {selected ? <span className="absolute inset-x-3 bottom-0 h-0.5 bg-accent-marker" aria-hidden="true" /> : null}
          </button>
        );
      })}
    </div>
  );
}
