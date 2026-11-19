import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Database as DatabaseIcon, History, ListChecks, MoreHorizontal, Play, Search, X } from "lucide-react";
import { catalogIngestionAPI } from "@/api/catalog";
import { CatalogConnectorListError } from "@/components/catalog/CatalogConnectorListError";
import { CatalogManagerDialog } from "@/components/catalog/CatalogManagerDialog";
import { CatalogScopeSwitcher, type CatalogNamesState, type CatalogScopeSelection } from "@/components/catalog/CatalogScopeSwitcher";
import { CatalogSelectionDrawer, selectionHasCoverage } from "@/components/catalog/CatalogSelectionDrawer";
import { CatalogMain } from "@/components/catalog/browser/CatalogMain";
import { ConnectionRecovery } from "@/components/catalog/ConnectionRecovery";
import { FindTableDialog, isFindTableShortcut } from "@/components/catalog/FindTableDialog";
import { RecentRefreshesDialog } from "@/components/catalog/RecentRefreshesDialog";
import { countLabel, formatElapsed } from "@/components/catalog/catalogCopy";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { connectorCreationNoticeMessage, useConnectorCreationNotice } from "@/components/connectors/connectorCreationNotice";
import { MainLayout } from "@/components/layout/MainLayout";
import { Button, buttonClassName, type ButtonSize } from "@/components/ui/Button";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "@/components/ui/Menu";
import { IconButton } from "@/components/ui/IconButton";
import { StateBlock } from "@/components/ui/StateBlock";
import { workStatus, WorkStatus } from "@/components/ui/WorkStatus";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import { useCatalogTree } from "@/hooks/useCatalogTree";
import { useConnectors } from "@/hooks/useConnectors";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import {
  catalogHref,
  configuredCatalogName,
  readLastCatalogConnector,
  rememberLastCatalogConnector,
  THREE_DEPTH_CONNECTOR_TYPES,
} from "@/lib/catalog/catalog-links";
import { queryKeys } from "@/lib/query/keys";
import { Link, usePathname, useRouter } from "@/lib/navigation";
import { catalogSearchHref, linkedListRefreshTarget, linkedRunSearch } from "@/lib/catalog/catalog-url";
import type { CatalogSearch } from "@/lib/route-search";
import { webPaths } from "@/lib/web-paths";
import type { CatalogIngestJob, CatalogIngestTask, CatalogSelection } from "@/lib/types/catalog";

const TERMINAL_JOB_STATES = new Set<CatalogIngestJob["state"]>(["COMPLETED", "COMPLETED_WITH_ERRORS", "CANCELLED"]);
const REFRESH_ERRORS_PAGE_SIZE = 100;
// Matches Tailwind's `max-sm` variant, which lays the command bar out as phone rows.
const PHONE_COMMAND_BAR_QUERY = "(width < 40rem)";

function subscribePhoneCommandBar(onChange: () => void): () => void {
  const query = window.matchMedia(PHONE_COMMAND_BAR_QUERY);
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
}

// Phone rows pair buttons with the 32px connector field, so they use the compact size.
function useCommandBarButtonSize(): ButtonSize {
  const phone = useSyncExternalStore(subscribePhoneCommandBar, () => window.matchMedia(PHONE_COMMAND_BAR_QUERY).matches, () => false);
  return phone ? "sm" : "md";
}

export type CatalogTarget = CatalogSearch;

function normalizedSelection(selection: CatalogSelection): string {
  return JSON.stringify(selection.selectedSchemas
    .map((schema) => ({
      schemaName: schema.schemaName,
      relations: schema.relations.kind === "ALL_RELATIONS" ? "ALL" : [...schema.relations.relationNames].sort(),
    }))
    .sort((left, right) => left.schemaName.localeCompare(right.schemaName)));
}

// The browse location lives in the URL, so refresh, Back, and shared links reopen the same place.
// Other search parameters of the hosting route are preserved.
function useCatalogNavigation() {
  const pathname = usePathname();
  const router = useRouter();
  return useCallback((next: CatalogTarget, mode: "push" | "replace") => {
    const href = catalogSearchHref(pathname, window.location.search, next);
    if (mode === "push") router.push(href);
    else router.replace(href, { scroll: false });
  }, [pathname, router]);
}

function entryFailure(errors: CatalogIngestTask[]): CatalogIngestTask | null {
  return errors.find((task) => task.parentTaskId === null && (task.targetKind === "CONNECTOR" || task.targetKind === "CATALOG")) ?? null;
}

export default function CatalogPage({ target: search = {} }: { target?: CatalogTarget }) {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const workspaceAccess = useWorkspaceAccess();
  const canSelectCatalog = workspaceAccess.hasRole("EDITOR");
  const canManageCatalog = workspaceAccess.hasRole("EXPLORER");
  const canManageConnector = workspaceAccess.hasRole("OWNER");
  const queryClient = useQueryClient();
  const navigateCatalog = useCatalogNavigation();
  const { connectors, loading: loadingConnectors, retrying: retryingConnectors, error: connectorListError, retry: retryConnectors } = useConnectors();
  // The confirmation belongs to the visit that followed creation: this page takes it from the
  // store once, so returning to Catalog later never shows it again.
  const [creationNotice, setCreationNotice] = useState(() => useConnectorCreationNotice.getState().notice);
  const dismissCreationNotice = useCallback(() => setCreationNotice(null), []);
  useEffect(() => { useConnectorCreationNotice.getState().dismiss(); }, []);
  const [catalogManagerConnectorId, setCatalogManagerConnectorId] = useState<string | null>(null);
  const [recentOpen, setRecentOpen] = useState(false);
  const [findOpen, setFindOpen] = useState(false);
  const commandBarButtonSize = useCommandBarButtonSize();
  const [selectionError, setSelectionError] = useState<string | null>(null);
  const [startError, setStartError] = useState<string | null>(null);
  const [trackedJobId, setTrackedJobId] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [savingSelection, setSavingSelection] = useState(false);
  const [now, setNow] = useState(() => Date.now());

  const selectedConnector = connectors.find((connector) => connector.id === search.connector) ?? null;
  const connectorId = selectedConnector?.id ?? null;
  const selectedConnectorIsThreeDepth = selectedConnector ? THREE_DEPTH_CONNECTOR_TYPES.has(selectedConnector.type) : false;
  const catalogName = selectedConnectorIsThreeDepth ? search.catalog ?? null : null;
  const entryCatalogName = catalogName;
  const entryReady = !selectedConnectorIsThreeDepth || entryCatalogName !== null;
  const scopeName = entryCatalogName ?? selectedConnector?.name ?? "";
  const scopeKey = `${connectorId ?? ""}:${catalogName ?? ""}`;
  const previousScopeKey = useRef(scopeKey);
  const visibleCreationNotice = creationNotice && creationNotice.connectorId === connectorId ? creationNotice : null;
  const location = useMemo(() => ({
    connector: connectorId ?? undefined,
    catalog: catalogName ?? undefined,
    schema: search.schema,
    table: search.schema ? search.table : undefined,
  }), [catalogName, connectorId, search.schema, search.table]);

  const { ingest: linkedIngest, refresh: linkedRefreshJob, refreshCatalog, refreshSchema } = search;
  const linkedRun = useMemo(
    () => linkedRunSearch({ ingest: linkedIngest, refresh: linkedRefreshJob, refreshCatalog, refreshSchema }),
    [linkedIngest, linkedRefreshJob, refreshCatalog, refreshSchema],
  );

  const linkedHistoryConnectorId = linkedIngest || linkedRefreshJob ? search.connector ?? connectorId : connectorId;
  const missingConnectorHistory = Boolean(search.connector && (linkedIngest || linkedRefreshJob) && !selectedConnector);

  // Without a connector in the URL, open the one this browser used last in the workspace, else the first.
  useEffect(() => {
    if (loadingConnectors || connectors.length === 0 || selectedConnector || missingConnectorHistory) return;
    const remembered = readLastCatalogConnector(workspaceId);
    const fallback = connectors.find((connector) => connector.id === remembered) ?? connectors[0];
    navigateCatalog({ connector: fallback.id, choose: search.choose, ...linkedRun }, "replace");
  }, [connectors, linkedRun, loadingConnectors, navigateCatalog, missingConnectorHistory, search.choose, selectedConnector, workspaceId]);

  useEffect(() => {
    if (connectorId) rememberLastCatalogConnector(workspaceId, connectorId);
  }, [connectorId, workspaceId]);

  useEffect(() => {
    if (previousScopeKey.current === scopeKey) return;
    previousScopeKey.current = scopeKey;
    setTrackedJobId(null);
    setStartError(null);
    setSelectionError(null);
    setRecentOpen(false);
  }, [scopeKey]);

  useEffect(() => {
    setTrackedJobId(search.ingest ?? null);
  }, [search.ingest]);

  useEffect(() => {
    if (creationNotice && connectorId && creationNotice.connectorId !== connectorId) dismissCreationNotice();
  }, [connectorId, creationNotice, dismissCreationNotice]);

  const connectorSelectionQuery = useQuery({
    queryKey: queryKeys.catalog.connectorSelection(workspaceId, principalId ?? "", connectorId ?? ""),
    queryFn: () => catalogIngestionAPI.getConnectorCatalogSelection(workspaceId, connectorId as string),
    enabled: Boolean(principalId && connectorId && selectedConnectorIsThreeDepth),
  });
  const selectedCatalogNames = connectorSelectionQuery.data
    ? [...connectorSelectionQuery.data.selectedCatalogNames].sort((left, right) => left.localeCompare(right))
    : null;
  const catalogNamesState: CatalogNamesState | null = !selectedConnectorIsThreeDepth
    ? null
    : selectedCatalogNames
      ? { status: "loaded", catalogNames: selectedCatalogNames }
      : connectorSelectionQuery.isError ? { status: "error" } : { status: "loading" };
  const firstCatalogName = selectedCatalogNames?.[0] ?? null;
  const catalogStep = selectedConnectorIsThreeDepth && selectedCatalogNames !== null && selectedCatalogNames.length === 0;

  useEffect(() => {
    if (!connectorId || !selectedConnectorIsThreeDepth || search.catalog || firstCatalogName === null) return;
    navigateCatalog({ connector: connectorId, catalog: firstCatalogName, choose: search.choose, ...linkedRun }, "replace");
  }, [connectorId, firstCatalogName, linkedRun, navigateCatalog, search.catalog, search.choose, selectedConnectorIsThreeDepth]);

  // Control answers 404 for an entry that is not currently selected, such as an Overview link to a
  // Catalog deselected since; read the active ingest only for a selected entry.
  const entrySelected = !selectedConnectorIsThreeDepth
    || (entryCatalogName !== null && (selectedCatalogNames?.includes(entryCatalogName) ?? false));
  const activeQuery = useQuery({
    queryKey: queryKeys.catalogIngest.active(workspaceId, principalId ?? "", connectorId ?? "", catalogName),
    queryFn: () => catalogIngestionAPI.getActive(workspaceId, connectorId as string, catalogName),
    enabled: Boolean(principalId && selectedConnector && entryReady && entrySelected),
    retry: false,
    refetchInterval: (query) => query.state.data ? 2_000 : false,
  });
  const activeJobId = activeQuery.data?.jobId ?? null;
  const linkedIngestId = search.ingest;
  const jobId = linkedIngestId ?? trackedJobId ?? activeJobId;
  const jobQuery = useQuery({
    queryKey: queryKeys.catalogIngest.job(workspaceId, principalId ?? "", linkedHistoryConnectorId ?? "", jobId ?? ""),
    queryFn: () => catalogIngestionAPI.getJob(workspaceId, linkedHistoryConnectorId as string, jobId as string),
    enabled: Boolean(principalId && linkedHistoryConnectorId && jobId),
    retry: false,
    refetchInterval: (query) => {
      const state = query.state.data?.state;
      return state && !TERMINAL_JOB_STATES.has(state) ? 2_000 : false;
    },
  });
  const observedJob = linkedIngestId
    ? (!jobQuery.isError && jobQuery.data?.jobId === linkedIngestId ? jobQuery.data : null)
    : jobQuery.data ?? activeQuery.data ?? null;
  const activeJob = observedJob && !TERMINAL_JOB_STATES.has(observedJob.state) ? observedJob : null;

  useEffect(() => {
    if (!linkedIngestId && activeJobId && trackedJobId !== activeJobId) setTrackedJobId(activeJobId);
  }, [activeJobId, trackedJobId, linkedIngestId]);

  const linkedRefreshId = search.refresh;
  const linkedRefreshTarget = linkedListRefreshTarget(linkedRun);
  const linkedRefresh = useQuery({
    queryKey: ["catalog-refresh", workspaceId, principalId, linkedHistoryConnectorId, linkedRefreshId, linkedRefreshTarget.parentKind, linkedRefreshTarget.catalogName, linkedRefreshTarget.schemaName],
    queryFn: () => catalogIngestionAPI.getSelectionListRefresh(workspaceId, linkedHistoryConnectorId!, linkedRefreshId!, linkedRefreshTarget.parentKind, linkedRefreshTarget.catalogName, linkedRefreshTarget.schemaName),
    enabled: Boolean(principalId && linkedHistoryConnectorId && linkedRefreshId),
    retry: false,
    refetchInterval: query => query.state.data && ["RUNNING", "CANCEL_REQUESTED"].includes(query.state.data.state) ? 2_000 : false,
  });

  const previousTaskSignature = useRef<string | null>(null);
  useEffect(() => {
    if (!observedJob || !principalId || !connectorId) return;
    const signature = `${observedJob.jobId}:${observedJob.state}:${observedJob.tasks.map((task) => `${task.taskId}:${task.state}`).join(",")}`;
    if (previousTaskSignature.current === signature) return;
    previousTaskSignature.current = signature;
    void queryClient.invalidateQueries({ queryKey: queryKeys.catalog.all(workspaceId, principalId) });
    void queryClient.invalidateQueries({ queryKey: [...queryKeys.catalogIngest.all(workspaceId, principalId), connectorId, "history"] });
    if (TERMINAL_JOB_STATES.has(observedJob.state)) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.catalogIngest.active(workspaceId, principalId, connectorId, catalogName) });
    }
  }, [catalogName, connectorId, observedJob, principalId, queryClient, workspaceId]);

  useEffect(() => {
    if (!activeJob) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [activeJob]);

  const selectionQuery = useQuery({
    queryKey: queryKeys.catalog.selection(workspaceId, principalId ?? "", connectorId ?? "", entryCatalogName),
    queryFn: () => catalogIngestionAPI.getSelection(workspaceId, connectorId as string, entryCatalogName),
    enabled: Boolean(principalId && connectorId && entryReady),
    retry: false,
  });
  const selection = selectionQuery.data ?? null;
  const selectedSchemaCount = selection?.selectedSchemas.length ?? 0;
  const hasCoverage = selectionHasCoverage(selection);

  const historyPreviewQuery = useQuery({
    queryKey: queryKeys.catalogIngest.history(workspaceId, principalId ?? "", connectorId ?? "", catalogName, 0, 1),
    queryFn: () => catalogIngestionAPI.listJobs(workspaceId, connectorId as string, catalogName, 0, 1),
    enabled: Boolean(principalId && connectorId && entryReady),
    retry: false,
    refetchInterval: activeJob ? 2_000 : false,
  });
  const latestRefresh = historyPreviewQuery.data?.jobs[0] ?? null;
  const latestFinishedRefresh = latestRefresh && TERMINAL_JOB_STATES.has(latestRefresh.state) ? latestRefresh : null;
  const recentErrorCount = activeJob ? 0 : latestRefresh?.errorCount ?? 0;

  // Row-level failures come from the running refresh, or from the latest finished one after it ends.
  const errorsSource = activeJob ?? latestFinishedRefresh;
  const errorsJobId = errorsSource && errorsSource.errorCount > 0 ? errorsSource.jobId : null;
  const refreshErrorsQuery = useQuery({
    queryKey: queryKeys.catalogIngest.errors(workspaceId, principalId ?? "", connectorId ?? "", errorsJobId ?? "", 0, REFRESH_ERRORS_PAGE_SIZE),
    queryFn: () => catalogIngestionAPI.listErrors(workspaceId, connectorId as string, errorsJobId as string, 0, REFRESH_ERRORS_PAGE_SIZE),
    enabled: Boolean(principalId && connectorId && errorsJobId),
    retry: false,
    refetchInterval: activeJob ? 2_000 : false,
  });
  const refreshErrors = errorsJobId ? refreshErrorsQuery.data?.errors ?? [] : [];
  const lastEntryFailure = activeJob ? null : entryFailure(refreshErrors);

  const selectSchema = useCallback((schemaName: string) => {
    if (!connectorId) return;
    navigateCatalog({ connector: connectorId, catalog: catalogName ?? undefined, schema: schemaName }, "push");
  }, [catalogName, connectorId, navigateCatalog]);
  const selectTable = useCallback((schemaName: string, tableName: string) => {
    if (!connectorId) return;
    navigateCatalog({ connector: connectorId, catalog: catalogName ?? undefined, schema: schemaName, table: tableName }, "push");
  }, [catalogName, connectorId, navigateCatalog]);
  const treeNavigation = useMemo(() => ({ selectSchema, selectTable }), [selectSchema, selectTable]);
  const tree = useCatalogTree(
    entryReady ? connectorId : null,
    catalogName,
    { schemaName: search.schema ?? null, tableName: search.schema ? search.table ?? null : null },
    treeNavigation,
  );

  const saveSelection = useMutation({
    mutationFn: (next: CatalogSelection) => catalogIngestionAPI.saveSelection(workspaceId, connectorId as string, entryCatalogName, next),
    onSuccess: async () => {
      await selectionQuery.refetch();
      void queryClient.invalidateQueries({ queryKey: queryKeys.catalog.all(workspaceId, principalId ?? "") });
    },
  });

  const startEntryRefresh = useCallback(async () => {
    if (!connectorId) return;
    const entryKind = catalogName === null ? "CONNECTOR" : "CATALOG";
    const accepted = await catalogIngestionAPI.start(workspaceId, connectorId, entryKind, catalogName);
    setTrackedJobId(accepted.jobId);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.catalogIngest.active(workspaceId, principalId ?? "", connectorId, catalogName) }),
      queryClient.invalidateQueries({ queryKey: [...queryKeys.catalogIngest.all(workspaceId, principalId ?? ""), connectorId, "history"] }),
      queryClient.invalidateQueries({ queryKey: queryKeys.catalog.connectorSummaries(workspaceId, principalId ?? "") }),
    ]);
  }, [catalogName, connectorId, principalId, queryClient, workspaceId]);

  const startRefresh = useCallback(async () => {
    if (!canManageCatalog || !connectorId || !entryReady || !hasCoverage) return;
    try {
      setStartError(null);
      await startEntryRefresh();
    } catch (error) {
      setStartError(error instanceof Error ? error.message : "Failed to start catalog refresh.");
    }
  }, [canManageCatalog, connectorId, entryReady, hasCoverage, startEntryRefresh]);

  const requestCancel = useCallback(async () => {
    if (!activeJob || !connectorId || !canManageCatalog || cancelling) return;
    try {
      setCancelling(true);
      setStartError(null);
      await catalogIngestionAPI.requestCancel(workspaceId, connectorId, activeJob.jobId);
      await jobQuery.refetch();
    } catch (error) {
      setStartError(error instanceof Error ? error.message : "Failed to request cancellation.");
    } finally {
      setCancelling(false);
    }
  }, [activeJob, canManageCatalog, cancelling, connectorId, jobQuery, workspaceId]);

  const openChooser = useCallback(() => {
    if (!connectorId) return;
    navigateCatalog({ ...location, ...linkedRun, choose: 1 }, "push");
  }, [connectorId, linkedRun, location, navigateCatalog]);
  const closeChooser = useCallback(() => {
    setSelectionError(null);
    navigateCatalog({ ...location, ...linkedRun }, "replace");
  }, [linkedRun, location, navigateCatalog]);
  const dismissLinkedRun = useCallback(() => {
    navigateCatalog({ ...location, choose: search.choose }, "replace");
  }, [location, navigateCatalog, search.choose]);

  // "Save and refresh" keeps the two server operations separate: save when the draft changed, then start.
  const handleSaveSelection = useCallback(async (next: CatalogSelection, refresh: boolean) => {
    if (!canSelectCatalog) return;
    setSelectionError(null);
    setSavingSelection(true);
    try {
      const changed = !selection || normalizedSelection(selection) !== normalizedSelection(next);
      if (changed || !refresh) await saveSelection.mutateAsync(next);
      if (refresh) await startEntryRefresh();
      dismissCreationNotice();
      closeChooser();
    } catch (error) {
      setSelectionError(error instanceof Error ? error.message : "Failed to save the selection.");
    } finally {
      setSavingSelection(false);
    }
  }, [canSelectCatalog, closeChooser, dismissCreationNotice, saveSelection, selection, startEntryRefresh]);

  const handleCatalogsSaved = useCallback((savedCatalogNames: string[]) => {
    if (!selectedConnector || savedCatalogNames.length === 0) return;
    const configured = configuredCatalogName(selectedConnector);
    const nextCatalog = configured && savedCatalogNames.includes(configured) ? configured : savedCatalogNames[0];
    navigateCatalog({ connector: selectedConnector.id, catalog: nextCatalog, choose: 1 }, "replace");
  }, [navigateCatalog, selectedConnector]);

  const handleCatalogManagerSaved = useCallback((savedCatalogNames: string[]) => {
    if (catalogManagerConnectorId !== connectorId || !connectorId) return;
    if (catalogName === null || !savedCatalogNames.includes(catalogName)) {
      navigateCatalog({ connector: connectorId, catalog: savedCatalogNames[0] }, "replace");
    }
  }, [catalogManagerConnectorId, catalogName, connectorId, navigateCatalog]);

  const selectScope = useCallback((next: CatalogScopeSelection) => {
    navigateCatalog({ connector: next.connectorId, catalog: next.catalogName ?? undefined }, "push");
  }, [navigateCatalog]);

  useEffect(() => {
    if (!selectedConnector || !canManageCatalog) return;
    const onKey = (event: KeyboardEvent) => {
      if (!isFindTableShortcut(event) || document.querySelector("[role='dialog'],[role='alertdialog']")) return;
      event.preventDefault();
      setFindOpen(true);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [canManageCatalog, selectedConnector]);

  const loadedSchemaCount = tree.schemas.filter((schema) => schema.lastRefreshedAt !== null).length;
  const nothingLoaded = loadedSchemaCount === 0 && tree.schemasTotalCount <= tree.schemasPageSize;
  const browserEmpty = !tree.loadingSchemas && nothingLoaded && !tree.schemaQuery.trim() && !activeJob;
  const catalogSelected = !selectedConnectorIsThreeDepth || entrySelected;
  const emptyStatePending = catalogNamesState?.status === "loading"
    || (catalogSelected && (selectionQuery.isPending || historyPreviewQuery.isPending));
  const catalogError = tree.error ?? activeQuery.error?.message ?? jobQuery.error?.message ?? null;
  const elapsed = activeJob ? Math.max(0, Math.floor((now - new Date(activeJob.requestedAt).valueOf()) / 1_000)) : 0;
  const cancelRequested = Boolean(activeJob && (cancelling || activeJob.state === "CANCEL_REQUESTED"));
  const refreshBlockedReason = activeJob ? null : !entryReady ? "Select a catalog first" : !selectionQuery.isPending && !hasCoverage ? "Choose tables first" : null;
  const openManageCatalogs = selectedConnector && canManageCatalog ? () => setCatalogManagerConnectorId(selectedConnector.id) : null;
  const chooserOpen = search.choose === 1 && Boolean(selectedConnector) && (catalogStep || (entryReady && entrySelected));
  const browseReturnTo = connectorId ? catalogHref(workspaceId, { connector: connectorId, catalog: catalogName, schema: location.schema, table: location.table }) : null;
  const chooserReturnTo = connectorId ? catalogHref(workspaceId, { connector: connectorId, catalog: catalogName, choose: true }) : "";
  const refreshFromBrowser = canManageCatalog && !activeJob && hasCoverage && entryReady ? () => void startRefresh() : null;

  const linkedHistoryStatus = <>
      {linkedRefreshId && <LinkedRunBanner label="Catalog list refresh" onDismiss={dismissLinkedRun}><p>Catalog list refresh · {linkedRefresh.isError ? "Unavailable" : linkedRefresh.data ? <WorkStatus state={linkedRefresh.data.state} labelDisplay="inline" size="sm" /> : "Loading…"}</p>{!linkedRefresh.isError && linkedRefresh.data?.safeMessage && <p className="mt-1 text-foreground-secondary">{linkedRefresh.data.safeMessage}</p>}{linkedRefresh.isError && <button type="button" className="mt-2 underline" onClick={() => void linkedRefresh.refetch()}>Retry</button>}</LinkedRunBanner>}
      {linkedIngestId && <LinkedRunBanner label="Catalog refresh" onDismiss={dismissLinkedRun}><p>Catalog refresh · {jobQuery.isError ? "Unavailable" : observedJob ? <WorkStatus state={observedJob.state} labelDisplay="inline" size="sm" /> : "Loading…"}</p>{observedJob && <p className="mt-1 tabular-nums text-foreground-secondary">{countLabel(observedJob.refreshedRelationCount, "table", "tables")} loaded · {countLabel(observedJob.errorCount, "error", "errors")}</p>}{jobQuery.isError && <button type="button" className="mt-2 underline" onClick={() => void jobQuery.refetch()}>Retry</button>}</LinkedRunBanner>}
  </>;

  return <MainLayout><div className="flex h-full flex-col bg-background">
    <WorkspacePageHeader section="Sources" title="Catalog" />
    {missingConnectorHistory && !loadingConnectors ? <><div className="border-b border-border px-4 py-2.5 sm:px-6"><p className="type-body">{linkedRefresh.data || observedJob ? "Deleted connector" : "Connector unavailable"}</p></div>{linkedHistoryStatus}<StateBlock kind="empty" layout="page" title="Catalog history" description={linkedRefresh.data || observedJob ? "This connector is no longer available. Its refresh history is preserved." : "Read the linked refresh status here, or browse the current catalog."} action={<Button type="button" variant="secondary" onClick={() => navigateCatalog({}, "push")}>Browse current catalog</Button>} /></> : connectorListError && connectors.length === 0 ? <CatalogConnectorListError error={connectorListError} retrying={retryingConnectors} onRetry={retryConnectors} /> : connectors.length === 0 && !loadingConnectors ? <NoConnectorState canCreateConnector={canManageConnector} connectorsHref={webPaths.connectors(workspaceId)} /> : !selectedConnector ? <StateBlock kind="loading" layout="page" title="Loading connectors" /> : <>
      {visibleCreationNotice ? <div className="flex flex-shrink-0 items-start justify-between gap-3 border-b border-border bg-surface px-4 py-2 sm:px-6"><p role="status" className="type-body min-w-0 pt-1.5 text-foreground">{connectorCreationNoticeMessage(visibleCreationNotice.connectorName, selectedConnectorIsThreeDepth)}</p><IconButton label="Dismiss message" icon={X} onClick={dismissCreationNotice} /></div> : null}
      {/* Phone utilities live in the menu; wider scopes and actions share a row only when both fit. */}
      <div data-testid="catalog-command-bar" className="flex flex-shrink-0 flex-wrap items-center gap-x-4 gap-y-2 border-b border-border px-4 py-2.5 sm:px-6">
        <div data-testid="catalog-command-scope" className="flex min-w-0 flex-1 flex-wrap items-center gap-2 sm:w-full sm:flex-none min-[940px]:w-auto">
          <CatalogScopeSwitcher connectors={connectors} selection={{ connectorId: selectedConnector.id, catalogName }} catalogs={catalogNamesState} onSelect={selectScope} onManageCatalogs={commandBarButtonSize === "sm" ? null : openManageCatalogs} />
        </div>
        <div data-testid="catalog-command-actions" className="flex min-w-0 flex-1 flex-wrap items-center gap-2 min-[940px]:justify-end max-sm:flex-none">
          <span className="sm:hidden"><Menu><MenuTrigger asChild><IconButton label="Catalog actions" icon={MoreHorizontal} /></MenuTrigger><MenuContent align="end" className="min-w-52">{canManageCatalog ? <MenuItem icon={Search} onSelect={() => setFindOpen(true)}>Find table</MenuItem> : null}<MenuItem icon={History} onSelect={() => setRecentOpen(true)}>Recent refreshes{recentErrorCount > 0 ? ` · ${countLabel(recentErrorCount, "error", "errors")}` : ""}</MenuItem>{canManageCatalog ? <MenuItem icon={ListChecks} onSelect={openChooser} disabled={!catalogStep && (selectionQuery.isPending || !entryReady)}>Choose tables{selectedSchemaCount > 0 ? ` · ${countLabel(selectedSchemaCount, "schema", "schemas")} selected` : ""}</MenuItem> : null}{openManageCatalogs ? <MenuItem icon={ListChecks} onSelect={openManageCatalogs}>Manage catalogs</MenuItem> : null}</MenuContent></Menu></span>
          {canManageCatalog ? <IconButton label="Find table (/)" icon={Search} className="max-sm:hidden" size="md" onClick={() => setFindOpen(true)} aria-keyshortcuts="/" /> : null}
          <span className="relative hidden items-center sm:inline-flex"><IconButton label={activeJob ? `Recent refreshes, refresh ${workStatus(activeJob.state).label.toLowerCase()}` : recentErrorCount > 0 ? `Recent refreshes, ${countLabel(recentErrorCount, "error", "errors")}` : "Recent refreshes"} icon={History} size="md" onClick={() => setRecentOpen(true)} />{recentErrorCount > 0 && !activeJob ? <span className="type-status tabular-nums text-error" aria-hidden="true">{recentErrorCount}</span> : null}{activeJob ? <span className="pointer-events-none absolute right-0 top-0"><WorkStatus state={activeJob.state} labelDisplay="external" size="sm" /></span> : null}</span>
          {canManageCatalog ? <Button type="button" variant="ghost" size={commandBarButtonSize} className="max-sm:hidden" onClick={openChooser} disabled={!catalogStep && (selectionQuery.isPending || !entryReady)}><ListChecks aria-hidden="true" />Choose tables{selectedSchemaCount > 0 ? <span className="tabular-nums text-foreground-secondary"> · {countLabel(selectedSchemaCount, "schema", "schemas")} selected</span> : null}</Button> : null}
          {canManageCatalog && activeJob ? <Button type="button" variant="secondary" size={commandBarButtonSize} className="max-sm:flex-auto" aria-label="Cancel refresh" onClick={() => void requestCancel()} disabled={cancelRequested}><X aria-hidden="true" />Cancel</Button> : null}
          {canManageCatalog && !activeJob ? <Button type="button" variant="ghost" size={commandBarButtonSize} className="max-sm:ml-auto" onClick={() => void startRefresh()} disabled={!entryReady || !hasCoverage} aria-describedby={refreshBlockedReason ? "catalog-refresh-blocked-reason" : undefined}><Play aria-hidden="true" />Refresh</Button> : null}
        </div>
        {canManageCatalog && activeJob ? <p role="status" data-testid="catalog-refresh-progress" className="type-meta flex w-full min-w-0 items-start gap-1.5 tabular-nums text-foreground-secondary min-[940px]:justify-end">
          <span className="flex h-[1lh] shrink-0 items-center"><WorkStatus state={cancelRequested ? "CANCEL_REQUESTED" : activeJob.state} labelDisplay="external" size="sm" /></span>
          <span className="min-w-0">{cancelRequested ? workStatus("CANCEL_REQUESTED").label : "Refreshing"} · {countLabel(activeJob.refreshedRelationCount, "table", "tables")} loaded · {activeJob.runningTaskCount.toLocaleString()} in progress · {formatElapsed(elapsed)}</span>
        </p> : null}
        {canManageCatalog && refreshBlockedReason ? <span id="catalog-refresh-blocked-reason" className="type-status w-full text-foreground-secondary min-[940px]:text-right">{refreshBlockedReason}</span> : null}
        {startError && <p role="alert" className="type-status w-full text-error min-[940px]:text-right">{startError}</p>}
      </div>
      {linkedHistoryStatus}
      <div data-testid="catalog-browser-scroll" className="flex min-h-0 flex-1 overflow-hidden">
        {catalogError && !tree.schemas.length ? <CatalogErrorState message={catalogError} onRetry={() => { void tree.refresh(); if (activeQuery.isError) void activeQuery.refetch(); if (jobQuery.isError) void jobQuery.refetch(); }} />
          : browserEmpty ? emptyStatePending ? <StateBlock kind="loading" layout="page" title="Loading catalog" />
            : lastEntryFailure && catalogSelected && hasCoverage ? <div className="mx-auto w-full max-w-xl px-6 py-12"><ConnectionRecovery failureCode={lastEntryFailure.failureCode} safeMessage={lastEntryFailure.safeMessage} connector={selectedConnector} consequence="The last refresh loaded nothing." returnTo={browseReturnTo} onRetry={refreshFromBrowser} retrying={false} links={null} density="panel" /></div>
            : <EmptyCatalogState
              connectorName={selectedConnector.name}
              scopeName={scopeName}
              catalogSelected={catalogSelected}
              catalogName={entryCatalogName}
              selectedCatalogCount={selectedCatalogNames?.length ?? 0}
              catalogsUnavailable={catalogNamesState?.status === "error"}
              hasCoverage={hasCoverage}
              selectedSchemaCount={selectedSchemaCount}
              latestRefreshErrorCount={latestRefresh ? latestRefresh.errorCount : null}
              onChoose={canSelectCatalog && (entryReady || catalogStep) ? openChooser : undefined}
              onStart={canManageCatalog && entryReady ? () => void startRefresh() : undefined}
              onManage={openManageCatalogs ?? undefined}
              onRetryCatalogs={() => void connectorSelectionQuery.refetch()}
            />
          : <CatalogMain key={scopeKey} connector={selectedConnector} data={tree} job={activeJob} refreshErrors={refreshErrors} onRefresh={refreshFromBrowser} returnTo={browseReturnTo} />}
      </div>
    </>}
  </div>{selectedConnector && <>{(catalogStep || (entryReady && entrySelected)) && <CatalogSelectionDrawer canEdit={canSelectCatalog} canRefresh={canManageCatalog} open={chooserOpen} connector={selectedConnector} catalogName={catalogStep ? null : entryCatalogName} catalogStep={catalogStep} selection={selection} loading={selectionQuery.isPending} saving={savingSelection} error={selectionError} returnTo={chooserReturnTo} onClose={closeChooser} onSave={handleSaveSelection} onCatalogsSaved={handleCatalogsSaved} />}<RecentRefreshesDialog open={recentOpen} workspaceId={workspaceId} principalId={principalId ?? ""} connector={selectedConnector} catalogName={catalogName} onClose={() => setRecentOpen(false)} /><FindTableDialog open={findOpen} onClose={() => setFindOpen(false)} onSelect={(hit) => navigateCatalog({ connector: hit.connectorId, catalog: hit.catalogName ?? undefined, schema: hit.schemaName, table: hit.tableName }, "push")} /></>}{catalogManagerConnectorId && <CatalogManagerDialog open={true} connector={connectors.find((connector) => connector.id === catalogManagerConnectorId) ?? null} canManage={canSelectCatalog} onClose={() => setCatalogManagerConnectorId(null)} onSaved={handleCatalogManagerSaved} />}</MainLayout>;
}

function LinkedRunBanner({ label, onDismiss, children }: { label: string; onDismiss: () => void; children: React.ReactNode }) {
  return <div role="status" className="type-meta flex items-start justify-between gap-3 border-b border-border px-4 py-4 sm:px-6"><div className="min-w-0">{children}</div><IconButton label={`Dismiss ${label.toLowerCase()} status`} icon={X} onClick={onDismiss} /></div>;
}

interface EmptyCatalogStateProps {
  connectorName: string;
  scopeName: string;
  catalogSelected: boolean;
  catalogName: string | null;
  selectedCatalogCount: number;
  catalogsUnavailable: boolean;
  hasCoverage: boolean;
  selectedSchemaCount: number;
  latestRefreshErrorCount: number | null;
  onChoose?: () => void;
  onStart?: () => void;
  onManage?: () => void;
  onRetryCatalogs: () => void;
}

function EmptyCatalogState({ connectorName, scopeName, catalogSelected, catalogName, selectedCatalogCount, catalogsUnavailable, hasCoverage, selectedSchemaCount, latestRefreshErrorCount, onChoose, onStart, onManage, onRetryCatalogs }: EmptyCatalogStateProps) {
  const heading = (text: string) => <span role="heading" aria-level={2}>{text}</span>;
  const chooseAction = onChoose ? <Button type="button" onClick={onChoose}><ListChecks aria-hidden="true" />Choose tables</Button> : undefined;
  if (!catalogSelected && catalogsUnavailable) {
    return <StateBlock kind="error" layout="page" title={heading("Catalogs unavailable")} description={`Monomer couldn't load the catalogs selected for ${connectorName}.`} action={<Button type="button" variant="secondary" onClick={onRetryCatalogs}>Retry</Button>} />;
  }
  if (!catalogSelected && catalogName !== null && selectedCatalogCount > 0) {
    return <StateBlock kind="empty" layout="page" icon={DatabaseIcon} title={heading(`${catalogName} is not selected`)} description={`Choose another catalog, or add ${catalogName} to ${connectorName} with Manage catalogs.`} action={onManage ? <Button type="button" onClick={onManage}><ListChecks aria-hidden="true" />Manage catalogs</Button> : undefined} />;
  }
  if (!catalogSelected) {
    return chooseAction
      ? <StateBlock kind="empty" layout="page" icon={DatabaseIcon} title={heading(`Choose what to load from ${connectorName}`)} description="Pick the catalogs, then the schemas and tables Monomer should read." action={chooseAction} />
      : <StateBlock kind="empty" layout="page" icon={DatabaseIcon} title={heading("No catalogs selected")} description="Ask a workspace editor to choose which catalogs to load." />;
  }
  if (!hasCoverage) {
    return chooseAction
      ? <StateBlock kind="empty" layout="page" icon={DatabaseIcon} title={heading(`Choose what to load from ${scopeName}`)} description="Pick the schemas and tables Monomer should read. Saving and refreshing loads their columns." action={chooseAction} />
      : <StateBlock kind="empty" layout="page" icon={DatabaseIcon} title={heading("No tables are chosen yet")} description="Ask a workspace editor to choose which tables to load." />;
  }
  const refreshAction = onStart ? <Button type="button" onClick={onStart}><Play aria-hidden="true" />Refresh now</Button> : undefined;
  if (latestRefreshErrorCount === null) {
    return <StateBlock kind="empty" layout="page" icon={DatabaseIcon} title={heading(`Ready to load ${countLabel(selectedSchemaCount, "schema", "schemas")}`)} description="Refresh reads table and column metadata from the warehouse. Large schemas can take a few minutes." action={refreshAction} />;
  }
  return <StateBlock kind="empty" layout="page" icon={DatabaseIcon} title={heading("No tables loaded")} description={latestRefreshErrorCount > 0 ? `The last refresh finished with ${countLabel(latestRefreshErrorCount, "error", "errors")}. Open Recent refreshes for details.` : "The last refresh found no tables in the selected schemas."} action={refreshAction} />;
}

function CatalogErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return <StateBlock kind="error" layout="page" title={<span role="heading" aria-level={2}>Catalog unavailable</span>} description={message} action={<Button type="button" variant="secondary" onClick={onRetry}>Retry</Button>} />;
}

function NoConnectorState({ canCreateConnector, connectorsHref }: { canCreateConnector: boolean; connectorsHref: string }) {
  return <StateBlock kind="empty" layout="page" icon={DatabaseIcon} title={<span role="heading" aria-level={2}>No connectors</span>} description={canCreateConnector ? "Connect a data source to browse its tables here." : "Ask a workspace owner to connect a data source."} action={canCreateConnector ? <Link href={connectorsHref} className={buttonClassName("primary", "md", "")}>Create connector</Link> : undefined} />;
}
