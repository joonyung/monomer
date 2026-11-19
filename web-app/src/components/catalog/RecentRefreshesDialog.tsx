import { formatRefreshTime } from "@/components/catalog/catalogCopy";
import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, History } from "lucide-react";
import { catalogIngestionAPI } from "@/api/catalog";
import { ConnectorTypeMark } from "@/components/connectors/ConnectorTypeMark";
import { CONNECTOR_TYPE_META } from "@/components/connectors/connectorTypeMeta";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { StateBlock } from "@/components/ui/StateBlock";
import { workStatus, WorkStatus } from "@/components/ui/WorkStatus";
import { ConnectionRecovery } from "@/components/catalog/ConnectionRecovery";
import { formatElapsed, requesterLabel } from "@/components/catalog/catalogCopy";
import { AgentMarker } from "@/components/ui/AgentMarker";
import { catalogHref } from "@/lib/catalog/catalog-links";
import { Link } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import type { CatalogIngestJobState, CatalogIngestJobSummary, CatalogIngestTask } from "@/lib/types/catalog";
import type { Connector } from "@/lib/types/connector";

const PAGE_SIZE = 10;
const ACTIVE_STATES = new Set<CatalogIngestJobState>(["RUNNING", "CANCEL_REQUESTED"]);

interface RecentRefreshesDialogProps {
  open: boolean;
  workspaceId: string;
  principalId: string;
  connector: Connector;
  catalogName: string | null;
  onClose: () => void;
}


function elapsedSeconds(requestedAt: string, terminalAt: string | null, now: number): number {
  const start = new Date(requestedAt).valueOf();
  const end = terminalAt ? new Date(terminalAt).valueOf() : now;
  if (!Number.isFinite(start) || !Number.isFinite(end)) return 0;
  return Math.max(0, Math.floor((end - start) / 1_000));
}

function targetLabel(error: { catalogName: string | null; schemaName: string | null; relationName: string | null }): string {
  return [error.catalogName, error.schemaName, error.relationName].filter(Boolean).join(".") || "Catalog refresh";
}

export function RecentRefreshesDialog({ open, workspaceId, principalId, connector, catalogName, onClose }: RecentRefreshesDialogProps) {
  const [page, setPage] = useState(1);
  const [expandedJobId, setExpandedJobId] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const historyQuery = useQuery({
    queryKey: queryKeys.catalogIngest.history(workspaceId, principalId, connector.id, catalogName, page, PAGE_SIZE),
    queryFn: () => catalogIngestionAPI.listJobs(workspaceId, connector.id, catalogName, page, PAGE_SIZE),
    enabled: open,
    retry: false,
    refetchInterval: (query) => query.state.data?.jobs.some((job) => ACTIVE_STATES.has(job.state)) ? 2_000 : false,
  });
  const jobs = useMemo(() => historyQuery.data?.jobs ?? [], [historyQuery.data?.jobs]);
  const totalCount = historyQuery.data?.totalCount ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalCount / PAGE_SIZE));

  useEffect(() => {
    setPage(1);
    setExpandedJobId(null);
  }, [connector.id, catalogName]);

  useEffect(() => {
    if (!open || !jobs.some((job) => ACTIVE_STATES.has(job.state))) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [jobs, open]);

  const scopeTitle = catalogName ?? connector.name;

  return <Dialog closeLabel="Close recent refreshes"
    open={open}
    onOpenChange={(next) => { if (!next) onClose(); }}
    size="md"
    title="Recent refreshes"
    description={<span className="flex items-center gap-3"><ConnectorTypeMark type={connector.type} size="sm" /><span className="min-w-0"><span className="type-item block truncate text-foreground">{scopeTitle}</span><span className="type-status block truncate text-foreground-secondary">{catalogName ? `${connector.name} · ${CONNECTOR_TYPE_META[connector.type].label}` : CONNECTOR_TYPE_META[connector.type].label}</span></span></span>}
    bodyClassName="p-0"
    footer={totalCount > PAGE_SIZE ? <div className="flex w-full items-center justify-between"><span className="type-status tabular-nums text-foreground-secondary">{(page - 1) * PAGE_SIZE + 1}–{Math.min(page * PAGE_SIZE, totalCount)} of {totalCount}</span><div className="flex items-center gap-2"><Button type="button" variant="secondary" size="sm" onClick={() => setPage((current) => Math.max(1, current - 1))} disabled={page === 1}>Previous</Button><span className="type-status min-w-12 text-center tabular-nums text-foreground-secondary">{page}/{totalPages}</span><Button type="button" variant="secondary" size="sm" onClick={() => setPage((current) => Math.min(totalPages, current + 1))} disabled={page >= totalPages}>Next</Button></div></div> : undefined}
  >
    {historyQuery.isPending ? <StateBlock kind="loading" layout="inline" title="Loading refreshes" />
      : historyQuery.error ? <StateBlock kind="error" layout="inline" title={historyQuery.error.message} action={<Button type="button" variant="secondary" size="sm" onClick={() => void historyQuery.refetch()}>Retry</Button>} />
        : jobs.length === 0 ? <StateBlock kind="empty" layout="inline" icon={History} title="No refreshes yet" />
          : <ul className="divide-y divide-border">{jobs.map((job) => <RefreshRow key={job.jobId} job={job} connector={connector} workspaceId={workspaceId} principalId={principalId} now={now} expanded={expandedJobId === job.jobId} onOpenInCatalog={onClose} onToggle={job.errorCount > 0 ? () => setExpandedJobId((current) => current === job.jobId ? null : job.jobId) : undefined} />)}</ul>}
  </Dialog>;
}

function RefreshRow({ job, connector, workspaceId, principalId, now, expanded, onOpenInCatalog, onToggle }: { job: CatalogIngestJobSummary; connector: Connector; workspaceId: string; principalId: string; now: number; expanded: boolean; onOpenInCatalog: () => void; onToggle?: () => void }) {
  const active = ACTIVE_STATES.has(job.state);
  const elapsed = elapsedSeconds(job.requestedAt, job.terminalAt, now);
  return <li>
    <button type="button" onClick={onToggle} disabled={!onToggle} aria-expanded={onToggle ? expanded : undefined} className="flex w-full items-start gap-3 px-6 py-4 text-left disabled:cursor-default">
      <WorkStatus state={job.state} labelDisplay="external" size="md" className="mt-0.5" />
      <span className="min-w-0 flex-1"><span className="flex items-baseline justify-between gap-3"><span className="type-control text-foreground">{workStatus(job.state).label}</span><span className="type-status tabular-nums text-foreground-secondary">{active ? formatElapsed(elapsed) : formatRefreshTime(job.requestedAt)}</span></span><span className="type-status mt-1 flex flex-wrap items-center gap-2 tabular-nums text-foreground-secondary"><span>{job.refreshedRelationCount.toLocaleString()} refreshed</span>{active && <><span aria-hidden="true">·</span><span>{job.runningTaskCount.toLocaleString()} in progress</span></>}{job.errorCount > 0 && <><span aria-hidden="true">·</span><span className="text-error">{job.errorCount.toLocaleString()} {job.errorCount === 1 ? "error" : "errors"}</span></>}</span><span className="type-status mt-1 flex flex-wrap items-center gap-2 text-foreground-secondary"><span>Started by {requesterLabel(job.requestedBy)}</span>{job.requestedBy.credentialSurface === "AGENT" ? <AgentMarker app={job.requestedBy.agentApp} focusable={false} /> : null}</span></span>
      {onToggle ? expanded ? <ChevronDown className="mt-0.5 h-4 w-4 text-foreground-secondary" /> : <ChevronRight className="mt-0.5 h-4 w-4 text-foreground-secondary" /> : null}
    </button>
    {expanded && <RefreshErrors workspaceId={workspaceId} principalId={principalId} connector={connector} catalogName={job.catalogName} jobId={job.jobId} active={active} onOpenInCatalog={onOpenInCatalog} />}
  </li>;
}

function errorLocation(connectorId: string, catalogName: string | null, error: CatalogIngestTask) {
  return { connector: connectorId, catalog: error.catalogName ?? catalogName, schema: error.schemaName, table: error.relationName };
}

function RefreshErrors({ workspaceId, principalId, connector, catalogName, jobId, active, onOpenInCatalog }: { workspaceId: string; principalId: string; connector: Connector; catalogName: string | null; jobId: string; active: boolean; onOpenInCatalog: () => void }) {
  const connectorId = connector.id;
  const returnTo = catalogHref(workspaceId, { connector: connectorId, catalog: catalogName });
  const [page, setPage] = useState(1);
  const errorsQuery = useQuery({
    queryKey: queryKeys.catalogIngest.errors(workspaceId, principalId, connectorId, jobId, page, PAGE_SIZE),
    queryFn: () => catalogIngestionAPI.listErrors(workspaceId, connectorId, jobId, page, PAGE_SIZE),
    retry: false,
    refetchInterval: active ? 2_000 : false,
  });
  const errors = errorsQuery.data?.errors ?? [];
  const totalCount = errorsQuery.data?.totalCount ?? 0;
  const totalPages = Math.max(1, Math.ceil(totalCount / PAGE_SIZE));
  const range = useMemo(() => ({ start: (page - 1) * PAGE_SIZE + 1, end: Math.min(page * PAGE_SIZE, totalCount) }), [page, totalCount]);
  return <div className="border-t border-border bg-surface/30 px-6 py-3">
    {errorsQuery.isPending ? <StateBlock kind="loading" layout="inline" title="Loading errors" />
      : errorsQuery.error ? <StateBlock kind="error" layout="inline" title={errorsQuery.error.message} />
        : <><ul className="divide-y divide-border-light">{errors.map((error) => <li key={error.taskId} className="py-3"><p className="type-status mb-1.5 text-foreground-secondary">{targetLabel(error)}</p><ConnectionRecovery failureCode={error.failureCode} safeMessage={error.safeMessage} connector={connector} consequence={null} returnTo={returnTo} onRetry={null} retrying={false} density="row" links={error.schemaName ? <Link href={catalogHref(workspaceId, errorLocation(connectorId, catalogName, error))} onClick={onOpenInCatalog} className="type-status text-foreground underline underline-offset-4">Open in Catalog</Link> : null} /></li>)}</ul>{totalCount > PAGE_SIZE && <div className="flex items-center justify-between pt-3"><span className="type-status tabular-nums text-foreground-secondary">{range.start}–{range.end} of {totalCount}</span><div className="flex gap-2"><Button type="button" variant="secondary" size="sm" onClick={() => setPage((current) => Math.max(1, current - 1))} disabled={page === 1}>Previous</Button><Button type="button" variant="secondary" size="sm" onClick={() => setPage((current) => Math.min(totalPages, current + 1))} disabled={page >= totalPages}>Next</Button></div></div>}</>}
  </div>;
}
