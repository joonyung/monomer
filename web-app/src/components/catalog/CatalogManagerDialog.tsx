import { formatRefreshTime } from "@/components/catalog/catalogCopy";
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, CircleAlert, Database, RefreshCw, Search, X } from "lucide-react";
import { catalogIngestionAPI } from "@/api/catalog";
import { ConnectorTypeMark } from "@/components/connectors/ConnectorTypeMark";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { queryKeys } from "@/lib/query/keys";
import type { Connector } from "@/lib/types/connector";
import type { ConnectorCatalogSelection, ConnectorCatalogSelectionView } from "@/lib/types/catalog";

const PAGE_SIZE = 50;
const ACTIVE_REFRESH_STATES = new Set(["RUNNING", "CANCEL_REQUESTED"]);
const FAILED_REFRESH_STATES = new Set(["COMPLETED_WITH_ERRORS"]);

interface CatalogManagerDialogProps {
  open: boolean;
  connector: Connector | null;
  canManage: boolean;
  onClose: () => void;
  onSaved: (selectedCatalogNames: string[]) => void;
}

function sortedNames(names: string[]): string[] {
  return [...new Set(names)].sort((left, right) => left.localeCompare(right));
}

function sameNames(left: string[], right: string[]): boolean {
  const sortedLeft = sortedNames(left);
  const sortedRight = sortedNames(right);
  return sortedLeft.length === sortedRight.length && sortedLeft.every((name, index) => name === sortedRight[index]);
}

function safeErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

export function CatalogManagerDialog({ open, connector, canManage, onClose, onSaved }: CatalogManagerDialogProps) {
  const workspaceIdFromContext = useWorkspaceId();
  const principalId = usePrincipalId();
  const workspaceId = workspaceIdFromContext ?? "";
  const connectorId = connector?.id ?? "";
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<string[] | null>(null);
  const initializedTarget = useRef<string | null>(null);
  const autoRefreshTargets = useRef(new Set<string>());
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [discardOpen, setDiscardOpen] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const search = query.trim();
  const optionsPrefixKey = [...queryKeys.catalog.all(workspaceId, principalId ?? ""), connectorId, "selection-options", "CONNECTOR", null, null] as const;
  const browsePrefixKey = [...queryKeys.catalog.all(workspaceId, principalId ?? ""), connectorId] as const;

  const selectionQuery = useQuery({
    queryKey: queryKeys.catalog.connectorSelection(workspaceId, principalId ?? "", connectorId),
    queryFn: () => catalogIngestionAPI.getConnectorCatalogSelection(workspaceId, connectorId),
    enabled: open && Boolean(workspaceId && principalId && connectorId),
    retry: false,
  });
  const selection = selectionQuery.data ?? null;
  const savedNames = selection?.selectedCatalogNames ?? [];
  const optionsQuery = useQuery({
    queryKey: queryKeys.catalog.selectionOptions(workspaceId, principalId ?? "", connectorId, "CONNECTOR", null, null, page, PAGE_SIZE, search),
    queryFn: () => catalogIngestionAPI.getSelectionOptions(workspaceId, connectorId, {
      parentKind: "CONNECTOR",
      page,
      pageSize: PAGE_SIZE,
      query: search,
    }),
    enabled: open && Boolean(workspaceId && principalId && connectorId),
    retry: false,
    placeholderData: (previous) => previous,
    refetchInterval: (current) => {
      const state = current.state.data?.latestJob?.state;
      return state && ACTIVE_REFRESH_STATES.has(state) ? 1_500 : false;
    },
  });
  const pageData = optionsQuery.data;
  const latestJob = pageData?.latestJob ?? null;
  const effectiveDraft = sortedNames(draft ?? savedNames);
  const visibleOptions = pageData?.options.filter((option) => option.available) ?? [];
  const totalCount = pageData?.totalCount ?? 0;
  const pageCount = Math.max(1, Math.ceil(totalCount / PAGE_SIZE));
  const dirty = selection !== null && !sameNames(effectiveDraft, savedNames);

  useEffect(() => {
    if (!open) { initializedTarget.current = null; setDraft(null); return; }
    if (!selection || initializedTarget.current === connectorId) return;
    initializedTarget.current = connectorId;
    setDraft(sortedNames(selection.selectedCatalogNames));
    setQuery("");
    setPage(1);
    setDiscardOpen(false);
  }, [open, selection, connectorId]);

  useEffect(() => {
    if (page > pageCount) setPage(pageCount);
  }, [page, pageCount]);

  const refreshMutation = useMutation({
    mutationFn: () => catalogIngestionAPI.startSelectionListRefresh(workspaceId, connectorId, "CONNECTOR", null, null),
    onMutate: () => {
      setRefreshError(null);
    },
    onError: (error) => {
      setRefreshError(safeErrorMessage(error, "The catalog list could not be refreshed."));
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: optionsPrefixKey,
      });
    },
  });

  useEffect(() => {
    const target = JSON.stringify([workspaceId, principalId, connectorId]);
    if (!open || !pageData || optionsQuery.isPlaceholderData || pageData.fetchedAt || pageData.latestJob || autoRefreshTargets.current.has(target)) return;
    autoRefreshTargets.current.add(target);
    refreshMutation.mutate();
  }, [open, pageData, optionsQuery.isPlaceholderData, workspaceId, principalId, connectorId, refreshMutation]);

  const saveMutation = useMutation({
    mutationFn: (next: ConnectorCatalogSelection) => catalogIngestionAPI.saveConnectorCatalogSelection(workspaceId, connectorId, next),
    onSuccess: (next: ConnectorCatalogSelectionView) => {
      queryClient.setQueryData(queryKeys.catalog.connectorSelection(workspaceId, principalId ?? "", connectorId), next);
      void queryClient.invalidateQueries({ queryKey: browsePrefixKey, refetchType: "none" });
      onSaved(sortedNames(next.selectedCatalogNames));
      onClose();
    },
  });

  const refreshing = refreshMutation.isPending || Boolean(latestJob && ACTIVE_REFRESH_STATES.has(latestJob.state));
  const failed = Boolean(latestJob && FAILED_REFRESH_STATES.has(latestJob.state));
  const unavailableNames = pageData?.fetchedAt && !optionsQuery.isFetching && !optionsQuery.isPlaceholderData && !refreshing && !failed && !refreshError
    ? effectiveDraft.filter((name) => {
      const option = pageData.options.find((candidate) => candidate.name === name);
      return option?.available === false || (!search && pageData.page === 1 && pageData.options.length === totalCount && !option);
    })
    : [];

  const loadRequired = (pageData === undefined && !optionsQuery.isPending)
    || Boolean(pageData && !pageData.fetchedAt && !latestJob);
  const requestClose = () => {
    if (refreshing || saveMutation.isPending) return;
    if (dirty) {
      setDiscardOpen(true);
      return;
    }
    onClose();
  };
  const toggle = (name: string) => {
    if (!canManage || refreshing) return;
    setDraft((current) => { const names = current ?? savedNames; return names.includes(name) ? names.filter((candidate) => candidate !== name) : [...names, name]; });
  };
  const save = () => {
    if (!dirty || !canManage || refreshing) return;
    saveMutation.mutate({ selectedCatalogNames: effectiveDraft });
  };
  const failureMessage = refreshError ?? latestJob?.safeMessage ?? "The catalog list could not be refreshed.";
  const selectionError = selectionQuery.error ? safeErrorMessage(selectionQuery.error, "The catalog selection could not be loaded.") : null;
  const saveError = saveMutation.error ? safeErrorMessage(saveMutation.error, "The catalog selection could not be saved.") : null;
  const optionsError = optionsQuery.error ? safeErrorMessage(optionsQuery.error, "The catalog list could not be loaded.") : null;

  const busy = refreshing || saveMutation.isPending;
  const startRefresh = () => { setRefreshError(null); refreshMutation.reset(); refreshMutation.mutate(); };

  return <>
    <Dialog closeLabel="Close catalog manager"
      open={open}
      onOpenChange={(next) => { if (!next) requestClose(); }}
      size="lg"
      testId="catalog-manager-dialog"
      busy={busy}
      title={<span className="flex min-w-0 items-center gap-4"><ConnectorTypeMark type={connector?.type ?? "POSTGRESQL"} size="sm" /><span className="truncate">{connector?.name ?? "Catalog"}</span></span>}
      description="Choose which catalogs from this connector are available in the workspace."
      bodyClassName="p-0"
      footer={<>
        {saveError ? <p role="alert" className="type-meta w-full text-error">{saveError}</p> : null}
        <div className="flex w-full items-center justify-between gap-4"><div className="type-status min-w-0 tabular-nums text-foreground-secondary">{effectiveDraft.length} {effectiveDraft.length === 1 ? "catalog" : "catalogs"} selected</div><div className="flex flex-shrink-0 items-center gap-2"><Button type="button" variant="secondary" onClick={requestClose} disabled={busy}>{canManage ? "Cancel" : "Close"}</Button>{canManage ? <Button type="button" onClick={save} disabled={!dirty || refreshing || selectionError !== null} pending={saveMutation.isPending}>{saveMutation.isPending ? "Saving…" : "Save selection"}</Button> : null}</div></div>
      </>}
    >
      <div className="sticky top-0 z-10 flex flex-wrap items-center gap-3 border-b border-border bg-background px-6 py-3">
        <div className="relative min-w-[220px] flex-1"><Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-secondary" /><Input value={query} onChange={(event) => { setQuery(event.target.value); setPage(1); }} aria-label="Search available catalogs" placeholder="Search catalogs" className="pl-8" /></div>
        <span className="type-status tabular-nums text-foreground-secondary">{pageData?.fetchedAt ? `Updated ${formatUpdatedAt(pageData.fetchedAt)}` : "Not loaded"}</span>
        <IconButton label={pageData?.fetchedAt ? "Refresh catalog list" : "Load catalog list"} icon={RefreshCw} onClick={startRefresh} pending={refreshing} disabled={optionsQuery.isPending} />
      </div>
      {selectionError ? <ErrorNotice message={selectionError} /> : null}
      {optionsError ? <ErrorNotice message={optionsError} /> : null}
      {failed || refreshError ? <ErrorNotice message={failureMessage} retry={startRefresh} /> : null}
      <div className="px-6 py-3">
        <div className="type-group mb-2 flex items-center justify-between text-foreground-secondary"><span>Catalogs</span><span className="tabular-nums">{pageData?.fetchedAt ? `${totalCount} available` : refreshing ? "Discovering" : "Not fetched"} · {effectiveDraft.length} selected</span></div>
        {effectiveDraft.length > 0 ? <div aria-label="Selected catalogs" className="type-meta mb-3 max-h-32 overflow-y-auto border-b border-border pb-3 text-foreground-secondary"><span>Selected: </span>{effectiveDraft.map((name) => <button key={name} type="button" disabled={!canManage || refreshing} onClick={() => toggle(name)} aria-label={`Remove selected catalog ${name}`} className="mr-3 inline-flex items-center gap-1 py-1 text-foreground enabled:hover:underline disabled:cursor-not-allowed disabled:text-foreground-secondary">{name}{unavailableNames.includes(name) ? <span className="text-foreground-secondary"> · Unavailable</span> : null}<X className="h-3.5 w-3.5" /></button>)}</div> : null}
        <div className="overflow-hidden rounded-[6px] border border-border">
          {(optionsQuery.isPending || refreshing) && !pageData?.fetchedAt ? <StateBlock kind="loading" layout="inline" title="Loading catalog list" /> : loadRequired ? <StateBlock kind="empty" layout="inline" icon={Database} title="catalog list has not been loaded" action={<Button type="button" onClick={startRefresh}>Load catalog list</Button>} /> : (failed || refreshError) && !pageData?.fetchedAt ? null : visibleOptions.length === 0 ? <StateBlock kind="empty" layout="inline" icon={Database} title={search ? "No matching catalogs" : "No catalogs were discovered"} /> : visibleOptions.map((option) => {
            const selected = effectiveDraft.includes(option.name);
            return <button key={option.name} type="button" role="checkbox" aria-checked={selected} disabled={!canManage || refreshing} onClick={() => toggle(option.name)} className={`relative flex min-h-[54px] w-full items-center gap-3 border-b border-border-light px-4 py-2.5 text-left last:border-b-0 disabled:cursor-not-allowed ${selected ? "bg-surface" : "hover:bg-surface/60"}`}><span className={`inline-flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-[4px] border ${selected ? "border-heading bg-heading text-background" : "border-foreground-secondary bg-background text-transparent"}`}><Check className="h-3.5 w-3.5" /></span><Database className="h-4 w-4 flex-shrink-0 text-foreground-secondary" /><span className="type-control min-w-0 flex-1 truncate text-foreground">{option.name}</span></button>;
          })}
        </div>
        {pageData && totalCount > PAGE_SIZE ? <div className="flex items-center justify-between gap-3 pt-3"><span className="type-status tabular-nums text-foreground-secondary">Page {page} of {pageCount} · {totalCount.toLocaleString()}</span><div className="flex gap-2"><Button type="button" variant="secondary" size="sm" onClick={() => setPage((current) => Math.max(1, current - 1))} disabled={page === 1}>Previous</Button><Button type="button" variant="secondary" size="sm" onClick={() => setPage((current) => Math.min(pageCount, current + 1))} disabled={page >= pageCount}>Next</Button></div></div> : null}
      </div>
    </Dialog>
    <Dialog
      open={discardOpen}
      onOpenChange={setDiscardOpen}
      size="sm"
      title="Discard catalog changes?"
      description="Your unsaved catalog selection will be lost."
      role="alertdialog"
      footer={<><Button type="button" variant="secondary" onClick={() => setDiscardOpen(false)}>Keep editing</Button><Button type="button" onClick={() => { setDiscardOpen(false); onClose(); }}>Discard changes</Button></>}
    >
      {null}
    </Dialog>
  </>;
}

function formatUpdatedAt(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? "recently" : formatRefreshTime(value);
}

function ErrorNotice({ message, retry }: { message: string; retry?: () => void }) {
  return <div role="alert" className="type-meta flex items-start justify-between gap-3 border-b border-error/20 bg-error/5 px-6 py-3 text-error"><span className="inline-flex min-w-0 items-start gap-2"><CircleAlert className="mt-px h-3.5 w-3.5 flex-shrink-0" />{message}</span>{retry ? <button type="button" onClick={retry} className="flex-shrink-0 underline underline-offset-2">Retry</button> : null}</div>;
}
