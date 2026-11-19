import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { CircleSlash, Clock, Copy, Info, Link2, Play, Square, TriangleAlert, X } from "lucide-react";
import type { ExploreRun } from "@/api/explore";
import type { ExploreErrorPresentation } from "@/api/explore-errors";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { ExploreErrorNotice } from "@/components/explore/ExploreErrorNotice";
import { ExploreFailureNotice } from "@/components/explore/ExploreFailureNotice";
import { SqlEditor } from "@/components/explore/SqlEditor";
import { formatDuration, formatExactTime, runDurationSeconds, useRunPresentationClock } from "@/components/runs";
import { ActorProvenance } from "@/components/ui/ActorProvenance";
import { AgentMarker } from "@/components/ui/AgentMarker";
import { Button } from "@/components/ui/Button";
import { IconButton } from "@/components/ui/IconButton";
import { StateBlock } from "@/components/ui/StateBlock";
import { WorkStatus } from "@/components/ui/WorkStatus";
import { useClipboardCopy } from "@/hooks/useClipboardCopy";
import type { Connector, ConnectorType } from "@/lib/types/connector";
import type { Metric } from "@/lib/types/metric";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { ExploreResultDownload } from "./ExploreResultDownload";
import { defaultTableSort, ExploreResultTable } from "./ExploreResultTable";
import { ExploreRunChart } from "./ExploreRunChart";
import { toExploreRunPresentation } from "./ExploreRunsSurface";
import type { PartitionRepairAction } from "@/components/semantics/source-partition";
import { failedRunGuidance, resultCaption, resultTimeZoneCaption, type ResultCaption } from "./run-presentation";
import { resultTableColumns } from "./result-columns";

export type RunViewTab = "FAILURE" | "TABLE" | "CHART" | "SQL";

const DIALECT_LABELS: Record<ConnectorType, string> = {
  DUCKDB: "DuckDB",
  POSTGRESQL: "PostgreSQL",
  BIGQUERY: "GoogleSQL",
  SNOWFLAKE: "Snowflake SQL",
  DATABRICKS: "Databricks SQL",
  REDSHIFT: "Redshift SQL",
  TRINO: "Trino SQL",
};

/** The preview to show, when the run succeeded and its exact read carried one. */
export function viewedResult(run: ExploreRun): ExploreRun["result"] {
  return run.state === "SUCCEEDED" && (run.resultAvailable ?? Boolean(run.result)) ? run.result : null;
}

/**
 * A run without a result to show — failed, or succeeded with an expired or unpreviewable result —
 * keeps only its SQL to inspect.
 */
/** A failed run opens on why it failed; a run whose result can't be shown explains that in Table. */
export function runViewTabs(run: ExploreRun): RunViewTab[] {
  const sql: RunViewTab[] = run.sql.trim() ? ["SQL"] : [];
  if (run.state === "FAILED") return ["FAILURE", ...sql];
  if (run.state === "SUCCEEDED" && !viewedResult(run)) return ["TABLE", ...sql];
  return run.kind === "SEMANTIC" ? ["TABLE", "CHART", "SQL"] : ["TABLE", "SQL"];
}

/** Why a succeeded run shows no preview, and whether its original file can still be downloaded. */
export function missingResultNotice(run: ExploreRun): { title: string; downloadable: boolean } {
  if (run.resultState === "EXPIRED") return { title: "This result has expired. Run it again to see it.", downloadable: false };
  if (run.resultState === "PREVIEW_UNAVAILABLE") {
    return { title: "This result can’t be previewed. Download it, or run it again.", downloadable: true };
  }
  return { title: "This run has no result to preview. Run it again to see one.", downloadable: false };
}

const TAB_LABELS: Record<RunViewTab, string> = { FAILURE: "Why it failed", TABLE: "Table", CHART: "Chart", SQL: "SQL" };

export function exploreRunLink(origin: string, workspaceId: string, runId: string): string {
  return new URL(`${webPaths.explore(workspaceId)}?run=${encodeURIComponent(runId)}`, origin).toString();
}

function CopyLinkButton({ workspaceId, runId }: { workspaceId: string; runId: string }) {
  const { status, copy } = useClipboardCopy();
  return (
    <span className="inline-flex items-center gap-1">
      <span role="status" className={`type-status ${status === "failed" ? "text-error" : "text-foreground-secondary"}`}>
        {status === "copied" ? "Link copied" : status === "failed" ? "Couldn’t copy the link" : ""}
      </span>
      <IconButton
        label="Copy link"
        icon={Link2}
        onClick={() => { void copy(exploreRunLink(window.location.origin, workspaceId, runId)); }}
      />
    </span>
  );
}

function RunViewTabs({ tabs, tab, onTabChange, status }: { tabs: RunViewTab[]; tab: RunViewTab; onTabChange: (tab: RunViewTab) => void; status: ReactNode }) {
  const buttons = useRef(new Map<RunViewTab, HTMLButtonElement>());
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const index = tabs.indexOf(tab);
    const next = event.key === "ArrowRight" ? tabs[(index + 1) % tabs.length]
      : event.key === "ArrowLeft" ? tabs[(index - 1 + tabs.length) % tabs.length]
        : event.key === "Home" ? tabs[0]
          : event.key === "End" ? tabs[tabs.length - 1]
            : null;
    if (!next) return;
    event.preventDefault();
    onTabChange(next);
    buttons.current.get(next)?.focus();
  };
  return (
    <div className="flex h-10 items-stretch justify-between gap-3 border-b border-border px-3 sm:px-5">
    <div role="tablist" aria-label="Result view" className="flex items-stretch gap-1">
      {tabs.map((candidate) => {
        const selected = candidate === tab;
        return (
          <button
            key={candidate}
            ref={(node) => { if (node) buttons.current.set(candidate, node); else buttons.current.delete(candidate); }}
            id={`explore-result-${candidate.toLowerCase()}-tab`}
            type="button"
            role="tab"
            aria-selected={selected}
            aria-controls={`explore-result-${candidate.toLowerCase()}-panel`}
            tabIndex={selected ? 0 : -1}
            onClick={() => onTabChange(candidate)}
            onKeyDown={onKeyDown}
            className={`type-control relative px-3 ${selected ? "text-foreground" : "text-foreground-secondary hover:text-foreground"}`}
          >
            {TAB_LABELS[candidate]}
            {selected ? <span className="absolute inset-x-3 bottom-0 h-0.5 bg-accent-marker" aria-hidden="true" /> : null}
          </button>
        );
      })}
    </div>
    {status}
    </div>
  );
}

function SqlView({ run, connectorType, onOpenInSql }: { run: ExploreRun; connectorType: ConnectorType | null; onOpenInSql: (run: ExploreRun) => void }) {
  const sqlCopy = useClipboardCopy();
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex min-h-11 flex-wrap items-center justify-between gap-2 px-5 py-1.5 sm:px-6">
        <p className="type-status text-foreground-secondary">
          {run.kind === "SEMANTIC" ? `Rendered for ${connectorType ? DIALECT_LABELS[connectorType] : "this connector"}` : "SQL as run"}
        </p>
        <div className="flex items-center gap-2">
          <span role="status" className={`type-status ${sqlCopy.status === "failed" ? "text-error" : "text-foreground-secondary"}`}>
            {sqlCopy.status === "copied" ? "SQL copied" : sqlCopy.status === "failed" ? "Couldn’t copy the SQL" : ""}
          </span>
          <IconButton label="Copy SQL" icon={Copy} onClick={() => { void sqlCopy.copy(run.sql); }} />
          {run.kind === "SEMANTIC" ? <Button type="button" variant="ghost" size="sm" onClick={() => onOpenInSql(run)}>Open in SQL</Button> : null}
        </div>
      </div>
      <div className="h-[360px] border-t border-border-light">
        <SqlEditor value={run.sql} onChange={() => undefined} onRun={() => undefined} runDisabled readOnly ariaLabel={run.kind === "SEMANTIC" ? "Rendered SQL" : "Run SQL"} />
      </div>
    </div>
  );
}

function ResultStatus({ caption, timeZone, sortedInPreview, kind }: { caption: ResultCaption; timeZone: string | null; sortedInPreview: boolean; kind: ExploreRun["kind"] }) {
  const partial = !caption.complete;
  const detail = [caption.detail, sortedInPreview ? `Header sorting orders only the preview rows. ${kind === "SEMANTIC" ? "Add a sort to the query" : "Add ORDER BY to the query"} to order every row.` : null].filter(Boolean).join(" ") || null;
  return (
    <p className={`type-status flex min-w-0 items-center gap-1.5 tabular-nums ${partial ? "text-foreground" : "text-foreground-secondary"}`} title={detail ?? undefined}>
      <span className="truncate">{caption.status}{timeZone ? ` · ${timeZone}` : ""}{sortedInPreview ? " · sorted within preview" : ""}</span>
      {detail ? <><Info className="h-3.5 w-3.5 shrink-0 text-foreground-secondary" aria-hidden="true" /><span className="sr-only">. {detail}</span></> : null}
    </p>
  );
}

function RunStateContent({ run, children, onCancel, cancelling, cancelError }: {
  run: ExploreRun;
  children: ReactNode;
  onCancel: () => void;
  cancelling: boolean;
  cancelError: ExploreErrorPresentation | null;
}) {
  if (run.state === "RUNNING") {
    return (
      <div className="p-5 sm:p-6">
        <div className="flex flex-wrap items-center justify-between gap-3 border-y border-border py-5">
          <p className="type-control text-foreground-secondary">Results appear here when the run finishes.</p>
          <Button variant="ghost" size="sm" onClick={onCancel} pending={cancelling}>{!cancelling && <Square />}{cancelling ? "Cancelling…" : "Cancel"}</Button>
        </div>
        {cancelError ? <ExploreErrorNotice error={cancelError} className="mt-4" /> : null}
      </div>
    );
  }
  if (run.state === "CANCELLED") return <div className="p-5 sm:p-6"><StateBlock kind="empty" layout="inline" icon={CircleSlash} title="This run was cancelled." /></div>;
  return <>{children}</>;
}

export interface ExploreRunPanelProps {
  workspaceId: string;
  /** The viewer, so their own run reads "Started by you". */
  principalId: string | null;
  run: ExploreRun | null;
  loading: boolean;
  error: ExploreErrorPresentation | null;
  editedSinceRun: boolean;
  connectors: Connector[] | null;
  metrics: Metric[];
  semanticModels: SemanticModelResponse[];
  onCancel: (runId: string) => void;
  cancelling: boolean;
  cancelError: ExploreErrorPresentation | null;
  onOpenInSql: (run: ExploreRun) => void;
  /** Runs the question in the composer, which opening this run restored. */
  onRunAgain: () => void;
  runAgainDisabled: boolean;
  /** Leaves the run; the composer keeps its question. */
  onClose: () => void;
  canEditDefinitions?: boolean;
  onPartitionRepair?: (action: PartitionRepairAction) => void;
}

/** The result being viewed (`?run=`), directly under the composer. */
export function ExploreRunPanel({
  workspaceId,
  principalId,
  run,
  loading,
  error,
  editedSinceRun,
  connectors,
  metrics,
  semanticModels,
  onCancel,
  cancelling,
  cancelError,
  onOpenInSql,
  onRunAgain,
  runAgainDisabled,
  onClose,
  canEditDefinitions,
  onPartitionRepair,
}: ExploreRunPanelProps) {
  const [tab, setTab] = useState<RunViewTab>("TABLE");
  const [sortedInPreview, setSortedInPreview] = useState(false);
  const presentation = run ? toExploreRunPresentation(run, connectors, metrics, semanticModels) : null;
  const now = useRunPresentationClock(presentation);
  const runKind = run?.kind ?? null;
  useEffect(() => {
    if (runKind === "SQL") setTab((current) => current === "CHART" ? "TABLE" : current);
  }, [runKind]);
  const failedRunId = run?.state === "FAILED" ? run.runId : null;
  useEffect(() => {
    if (failedRunId) setTab("FAILURE");
  }, [failedRunId]);

  if (error) return <ExploreErrorNotice error={error} />;
  if (!run || !presentation) {
    if (loading) return <div className="p-5 sm:p-8"><StateBlock kind="loading" layout="inline" title="Loading run…" /></div>;
    return null;
  }
  const connectorType = connectors?.find((connector) => connector.id === run.connectorId)?.type ?? null;
  const query = run.kind === "SEMANTIC" ? run.query ?? null : null;
  const result = viewedResult(run);
  const missingResult = run.state === "SUCCEEDED" && !result ? missingResultNotice(run) : null;
  const totalRowCount = run.resultMetadata ? Number(run.resultMetadata.rowCount) : null;
  const caption = result ? resultCaption(result, totalRowCount != null && Number.isFinite(totalRowCount) ? totalRowCount : null, query) : null;
  const columns = result ? resultTableColumns(result, query, metrics, semanticModels) : [];
  const tabs = runViewTabs(run);
  const activeTab = tabs.includes(tab) ? tab : tabs[0] ?? "TABLE";
  const failure = run.state === "FAILED" ? (
    <ExploreFailureNotice failure={failedRunGuidance(run, { workspaceId, connectorId: run.connectorId, connectors, metricNames: query?.metrics ?? [], metrics, models: semanticModels, canEditDefinitions, onPartitionRepair })} />
  ) : null;

  return (
    <section aria-labelledby="explore-run-title" className={`flex flex-col ${result ? "min-h-full" : "min-h-0"}`}>
      <header className="flex items-start justify-between gap-4 px-5 pb-3 pt-4 sm:px-6">
        <div className="min-w-0 flex-1">
          <h2 id="explore-run-title" className="type-item break-words text-heading">{presentation.title}</h2>
          <p className="type-status mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-foreground-secondary">
            <WorkStatus state={run.state} labelDisplay="inline" size="sm" />
            <span aria-hidden="true">·</span>
            <span className="tabular-nums">{formatDuration(runDurationSeconds(presentation, now))}</span>
            <span aria-hidden="true">·</span>
            <time dateTime={run.createdAt} className="tabular-nums">{formatExactTime(run.createdAt)}</time>
            {run.startedBy ? (
              <><span aria-hidden="true">·</span><ActorProvenance verb="Started" actor={run.startedBy} surface={run.credentialSurface} agentApp={run.agentApp} principalId={principalId} time={null} /></>
            ) : run.credentialSurface === "AGENT" ? <><span aria-hidden="true">·</span><AgentMarker app={run.agentApp} focusable /></> : null}
          </p>
          {result && query ? (
            <p className="type-status mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-foreground-secondary">
              <span>Where does this number come from?</span>
              {query.metrics.map((name) => {
                const metric = metrics.find((candidate) => candidate.name === name);
                return metric ? <Link key={name} href={webPaths.lineageResource(workspaceId, "METRIC", metric.id)} className="text-foreground underline decoration-foreground-secondary underline-offset-4 hover:decoration-foreground">{metric.label || name} · Open map</Link> : null;
              })}
            </p>
          ) : null}
        </div>
        <div className="flex shrink-0 items-start gap-1">
          <CopyLinkButton key={`copy-link-${run.runId}`} workspaceId={workspaceId} runId={run.runId} />
          {result || missingResult?.downloadable ? <ExploreResultDownload key={run.runId} workspaceId={run.workspaceId} runId={run.runId} expiresAt={run.resultMetadata?.expiresAt ?? null} byteSize={run.resultMetadata?.byteSize ?? null} /> : null}
          <IconButton label="Close result" icon={X} onClick={onClose} />
        </div>
      </header>
      {editedSinceRun ? (
        <div role="status" className="mx-5 mb-3 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-[4px] border border-warning/30 bg-warning/5 px-3 py-2 sm:mx-6">
          <TriangleAlert className="h-4 w-4 shrink-0 text-warning" aria-hidden="true" />
          <p className="type-control min-w-0 flex-1 text-foreground">Query edited since this run. <span className="text-foreground-secondary">This result doesn’t include your changes.</span></p>
          <Button type="button" variant="secondary" size="sm" disabled={runAgainDisabled} onClick={onRunAgain}><Play aria-hidden="true" />Run</Button>
        </div>
      ) : null}
      {tabs.length > 0 ? <>
      <RunViewTabs
        tabs={tabs}
        tab={activeTab}
        onTabChange={setTab}
        status={caption && (activeTab === "TABLE" || activeTab === "CHART") ? <ResultStatus caption={caption} timeZone={resultTimeZoneCaption(run)} sortedInPreview={sortedInPreview && activeTab === "TABLE" && !caption.complete} kind={run.kind} /> : null}
      />
      <div id={`explore-result-${activeTab.toLowerCase()}-panel`} role="tabpanel" aria-labelledby={`explore-result-${activeTab.toLowerCase()}-tab`} className="flex min-h-0 flex-1 flex-col">
        {activeTab === "SQL" ? <SqlView key={run.runId} run={run} connectorType={connectorType} onOpenInSql={onOpenInSql} /> : activeTab === "FAILURE" ? (
          <div className="grid gap-4 p-5 sm:p-6">
            {failure}
            {editedSinceRun ? null : <div className="pl-7"><Button type="button" variant="secondary" size="sm" disabled={runAgainDisabled} onClick={onRunAgain}><Play aria-hidden="true" />Run again</Button></div>}
          </div>
        ) : missingResult ? (
          <div className="p-5 sm:p-6">
            <StateBlock
              kind="empty"
              layout="inline"
              icon={Clock}
              title={missingResult.title}
              action={<Button type="button" variant="secondary" size="sm" disabled={runAgainDisabled} onClick={onRunAgain}><Play aria-hidden="true" />Run</Button>}
            />
          </div>
        ) : (
          <RunStateContent run={run} onCancel={() => onCancel(run.runId)} cancelling={cancelling} cancelError={cancelError}>
            {!result || !caption ? (
              <div className="p-5 sm:p-6"><StateBlock kind="empty" layout="inline" title="The result is not available for preview." /></div>
            ) : (
              <>
                {activeTab === "CHART" && query ? (
                  <ExploreRunChart key={run.runId} runId={run.runId} reportingTimeZone={run.kind === "SEMANTIC" ? run.reportingTimeZone : null} query={query} result={result} totalRowCount={caption.totalRowCount} complete={caption.complete} rowLimitReached={caption.rowLimitReached} metrics={metrics} models={semanticModels} />
                ) : (
                  <ExploreResultTable key={run.runId} columns={columns} rows={result.rows} initialSort={query ? defaultTableSort(columns, query.orderBy.length > 0) : null} onSortChange={setSortedInPreview} />
                )}
              </>
            )}
          </RunStateContent>
        )}
      </div>
      </> : null}
    </section>
  );
}
