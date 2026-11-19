import type { ReactNode } from "react";
import { CircleSlash, Download, Square } from "lucide-react";
import { ActorProvenance } from "@/components/ui/ActorProvenance";
import { AgentMarker } from "@/components/ui/AgentMarker";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { StateBlock } from "@/components/ui/StateBlock";
import { WorkStatus } from "@/components/ui/WorkStatus";
import { formatCell, formatDuration, formatExactTime, formatResultBytes, runDurationSeconds, useRunPresentationClock } from "./clock";
import { useTransientScrollbar } from "../ui/useTransientScrollbar";
import type { RunPresentation, RunPresentationResult } from "./types";

export interface RunDetailDialogProps {
  run: RunPresentation | null;
  /** The viewer, so the run reads "Started by you" for their own runs. */
  principalId: string | null;
  onOpenChange: (open: boolean) => void;
  onCancel?: (runId: string) => void;
  onDownload?: (runId: string) => void;
  cancelling?: boolean;
  cancelError?: ReactNode;
  downloading?: boolean;
  resultDownload?: ReactNode;
}

export function RunDetailDialog({
  run,
  principalId,
  onOpenChange,
  onCancel,
  onDownload,
  cancelling = false,
  cancelError,
  downloading = false,
  resultDownload,
}: RunDetailDialogProps) {
  return (
    <Dialog closeLabel="Close run detail" overlayTestId="run-detail-overlay"
      open={Boolean(run)}
      onOpenChange={onOpenChange}
      size="lg"
      title={run?.title ?? ""}
      description="Inspect this run and its available result sample."
      descriptionHidden
      bodyClassName="flex flex-col p-0"
    >
      {run ? <div className="shrink-0 border-b border-border-light px-6 py-2"><RunStatusLine run={run} principalId={principalId} /></div> : null}
      {run ? (
        <RunDetail
          run={run}
          onCancel={onCancel ? () => onCancel(run.id) : undefined}
          onDownload={onDownload ? () => onDownload(run.id) : undefined}
          cancelling={cancelling}
          cancelError={cancelError}
          downloading={downloading}
          resultDownload={resultDownload}
        />
      ) : null}
    </Dialog>
  );
}

/** The dialog's summary line: status · duration · who started the run · Current target. */
export function RunStatusLine({ run, principalId }: { run: RunPresentation; principalId: string | null }) {
  const now = useRunPresentationClock(run);
  const agent = run.credentialSurface === "AGENT";
  return (
    <div className="type-status flex min-h-8 shrink-0 flex-wrap items-center gap-x-2 gap-y-1 text-foreground-secondary">
      <WorkStatus state={run.state} labelDisplay="inline" size="md" />
      <span>·</span>
      <span className="tabular-nums">{formatDuration(runDurationSeconds(run, now))}</span>
      {run.startedBy || agent ? <><span>·</span><RunStartedBy run={run} principalId={principalId} /></> : null}
      {run.isCurrentTarget ? <><span>·</span><span>Current target</span></> : null}
    </div>
  );
}

/** "Started by {Person} [via {App}]"; a run with only the agent surface recorded reads "Started via {App}". */
function RunStartedBy({ run, principalId }: { run: RunPresentation; principalId: string | null }) {
  if (run.startedBy) {
    return <ActorProvenance verb="Started" actor={run.startedBy} surface={run.credentialSurface ?? null} agentApp={run.agentApp ?? null} principalId={principalId} time={null} />;
  }
  return <span className="inline-flex items-center gap-x-1.5"><span>Started</span><AgentMarker app={run.agentApp ?? null} focusable /></span>;
}

function RunDetail({
  run,
  onCancel,
  onDownload,
  cancelling,
  cancelError,
  downloading,
  resultDownload,
}: {
  run: RunPresentation;
  onCancel?: () => void;
  onDownload?: () => void;
  cancelling: boolean;
  cancelError?: ReactNode;
  downloading: boolean;
  resultDownload?: ReactNode;
}) {
  const now = useRunPresentationClock(run);
  const duration = runDurationSeconds(run, now);

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <dl className="type-status grid shrink-0 grid-cols-2 gap-x-5 gap-y-3 border-b border-border px-5 py-4 sm:grid-cols-5 sm:px-6">
        {(run.metadata ?? []).map((item) => (
          <div key={item.label}><dt className="text-foreground-secondary">{item.label}</dt><dd className="mt-1 truncate text-foreground">{item.value}</dd></div>
        ))}
        <div><dt className="text-foreground-secondary">Started</dt><dd className="mt-1 tabular-nums text-foreground">{formatExactTime(run.createdAt)}</dd></div>
        <div><dt className="text-foreground-secondary">Duration</dt><dd className="mt-1 tabular-nums text-foreground">{formatDuration(duration)}</dd></div>
        <div><dt className="text-foreground-secondary">Run ID</dt><dd className="mt-1 truncate font-mono text-foreground">{run.id}</dd></div>
      </dl>

      {run.request ? (
        <details className="shrink-0 border-b border-border px-5 py-3 sm:px-6">
          <summary className="type-status cursor-pointer text-foreground-secondary hover:text-foreground">Request</summary>
          <pre className="type-mono mt-3 max-h-28 overflow-auto whitespace-pre-wrap text-foreground">{run.request}</pre>
        </details>
      ) : null}

      {run.state === "SUCCEEDED" && run.resultAvailable && run.result ? (
        <ResultSection result={run.result} onDownload={onDownload} downloading={downloading} resultDownload={resultDownload} />
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto p-5 sm:p-6">
          {run.state === "RUNNING" ? (
            <div className="flex items-center justify-between gap-3 border-y border-border py-5">
              <p className="type-control text-foreground-secondary">Results appear here when the run finishes.</p>
              {onCancel ? <Button variant="secondary" size="sm" onClick={onCancel} pending={cancelling}>{!cancelling && <Square />}{cancelling ? "Cancelling…" : "Cancel"}</Button> : null}
            </div>
          ) : null}
          {cancelError ? <div className="mt-4">{cancelError}</div> : null}
          {run.state === "FAILED" ? (
            <StateBlock kind="error" layout="inline" title="Execution failed" description={run.failureMessage ?? "The run failed before a result was available."} />
          ) : null}
          {run.state === "CANCELLED" ? <StateBlock kind="empty" layout="inline" icon={CircleSlash} title="This run was cancelled." /> : null}
          {run.state === "SUCCEEDED" && !run.result ? <StateBlock kind="empty" layout="inline" title="The result is not available for preview." /> : null}
        </div>
      )}
    </div>
  );
}

function ResultSection({
  result,
  onDownload,
  downloading,
  resultDownload,
}: {
  result: RunPresentationResult;
  onDownload?: () => void;
  downloading: boolean;
  resultDownload?: ReactNode;
}) {
  return (
    <section className="flex min-h-0 flex-1 flex-col">
      <div className="flex min-h-16 shrink-0 items-center justify-between gap-3 border-b border-border px-5 py-3 sm:px-6">
        <div>
          <p className="type-control text-foreground">Sample data</p>
          <p className="type-status mt-1 tabular-nums text-foreground-secondary">{result.returnedRowCount} shown{result.totalRowCount != null ? ` of ${result.totalRowCount}` : ""} rows · {result.columns.length} columns{result.byteSize ? ` · ${formatResultBytes(result.byteSize)}` : ""}</p>
        </div>
        {resultDownload ?? (onDownload ? <IconButton label="Download Arrow result" icon={Download} onClick={onDownload} pending={downloading} /> : null)}
      </div>
      <PreviewTable result={result} />
    </section>
  );
}

function PreviewTable({ result }: { result: RunPresentationResult }) {
  const scrollbar = useTransientScrollbar();
  return (
    <div onScroll={scrollbar.onScroll} data-testid="run-sample-grid" className={`min-h-0 flex-1 overflow-auto [scrollbar-gutter:stable] ${scrollbar.className}`}>
      <table className="type-meta w-full min-w-max border-collapse text-left">
        <thead className="sticky top-0 z-10 bg-surface"><tr className="border-b border-border">{result.columns.map((column) => <th key={column.id} className={`whitespace-nowrap px-4 py-2.5 font-normal text-foreground-secondary ${column.numeric ? "text-right" : ""}`} title={column.description}>{column.label}</th>)}</tr></thead>
        <tbody>{result.rows.map((row, rowIndex) => <tr key={rowIndex} className="border-b border-border-light">{row.map((cell, index) => <td key={`${rowIndex}-${index}`} className={`whitespace-nowrap px-4 py-3 text-foreground tabular-nums ${result.columns[index]?.numeric ? "text-right" : ""}`}>{formatCell(cell)}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}
