import { formatRefreshTime } from "@/components/catalog/catalogCopy";
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as Dialog from "@radix-ui/react-dialog";
import { Check, ChevronRight, CircleAlert, CircleCheck, CircleSlash, Database, FolderTree, RefreshCw, Search, X } from "lucide-react";
import { catalogIngestionAPI } from "@/api/catalog";
import { ConnectionRecovery } from "@/components/catalog/ConnectionRecovery";
import { countLabel, relationKindLabel, selectionCountLabel } from "@/components/catalog/catalogCopy";
import { ConnectorTypeMark } from "@/components/connectors/ConnectorTypeMark";
import { Button } from "@/components/ui/Button";
import { Dialog as DialogShell, DIALOG_OVERLAY_CLASS } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { configuredCatalogName, connectorLocationLabel, THREE_DEPTH_CONNECTOR_TYPES } from "@/lib/catalog/catalog-links";
import type { Connector } from "@/lib/types/connector";
import type {
  CatalogListOption,
  CatalogListOptionPage,
  CatalogListRefreshJob,
  CatalogListRefreshParentKind,
  CatalogRelationCoverage,
  CatalogSelection,
} from "@/lib/types/catalog";
import { queryKeys } from "@/lib/query/keys";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";

const PAGE_SIZE = 50;
const ACTIVE_REFRESH_STATES = new Set<CatalogListRefreshJob["state"]>(["RUNNING", "CANCEL_REQUESTED"]);
const FAILED_REFRESH_STATES = new Set<CatalogListRefreshJob["state"]>(["COMPLETED_WITH_ERRORS"]);

interface CatalogSelectionDrawerProps {
  // Editor or higher may change the selection; Explorer can still list and refresh candidates.
  canEdit: boolean;
  // Explorer or higher may start a refresh after saving.
  canRefresh: boolean;
  open: boolean;
  connector: Connector;
  // The selected Catalog entry of a 3-depth connector; null for 2-depth connectors and the catalog step.
  catalogName: string | null;
  // A 3-depth connector with no selected catalogs first chooses its catalogs in the same panel.
  catalogStep: boolean;
  selection: CatalogSelection | null;
  loading: boolean;
  saving: boolean;
  error: string | null;
  // This panel's exact URL, so fixing the connector comes back here.
  returnTo: string;
  onClose: () => void;
  onSave: (selection: CatalogSelection, refresh: boolean) => Promise<void>;
  onCatalogsSaved: (selectedCatalogNames: string[]) => void;
}

interface ConnectorRecovery {
  connector: Pick<Connector, "id" | "type" | "updatedAtEpochMs">;
  returnTo: string | null;
}

// Lists inside the panel share one connector for the failure recovery block; tests render lists without it.
const ConnectorRecoveryContext = createContext<ConnectorRecovery | null>(null);

interface OptionTarget {
  parentKind: CatalogListRefreshParentKind;
  catalogName: string | null;
  schemaName: string | null;
}

// The first listing of a parent is the connection check; this names what it found and where.
interface DiscoveryLabel {
  singular: string;
  plural: string;
  location: string;
}

function targetKey(target: OptionTarget): string {
  return JSON.stringify([target.parentKind, target.catalogName, target.schemaName]);
}

function schemaKey(schemaName: string): string {
  return JSON.stringify([schemaName]);
}

function cloneSelection(selection: CatalogSelection | null): CatalogSelection {
  return {
    selectedSchemas: selection?.selectedSchemas.map((entry) => ({
      ...entry,
      relations: entry.relations.kind === "ALL_RELATIONS"
        ? { kind: "ALL_RELATIONS" }
        : { kind: "SELECTED_RELATIONS", relationNames: [...entry.relations.relationNames] },
    })) ?? [],
  };
}

function findSchema(selection: CatalogSelection, schemaName: string) {
  return selection.selectedSchemas.find((entry) => entry.schemaName === schemaName);
}

function updateSchemaCoverage(selection: CatalogSelection, schemaName: string, coverage: CatalogRelationCoverage | null): CatalogSelection {
  const existing = selection.selectedSchemas.filter((entry) => entry.schemaName !== schemaName);
  return coverage ? { selectedSchemas: [...existing, { schemaName, relations: coverage }] } : { selectedSchemas: existing };
}

function toggleRelation(selection: CatalogSelection, schemaName: string, relationName: string): CatalogSelection {
  const existing = findSchema(selection, schemaName);
  if (existing?.relations.kind === "ALL_RELATIONS") return selection;
  const names = new Set(existing?.relations.kind === "SELECTED_RELATIONS" ? existing.relations.relationNames : []);
  if (names.has(relationName)) names.delete(relationName);
  else names.add(relationName);
  return updateSchemaCoverage(selection, schemaName, names.size > 0 ? { kind: "SELECTED_RELATIONS", relationNames: [...names].sort() } : null);
}

function relationSelected(selection: CatalogSelection, schemaName: string, relationName: string): boolean {
  const schema = findSchema(selection, schemaName);
  return schema?.relations.kind === "ALL_RELATIONS" || (schema?.relations.kind === "SELECTED_RELATIONS" && schema.relations.relationNames.includes(relationName));
}

function formatFetchedAt(value: string | null): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? null : `Updated ${formatRefreshTime(value)}`;
}

function formatTotal(count: number): string {
  return `${count.toLocaleString()} ${count === 1 ? "item" : "items"}`;
}

function sortedNames(names: string[]): string[] {
  return [...new Set(names)].sort((left, right) => left.localeCompare(right));
}

interface OptionDiscovery {
  pageData: CatalogListOptionPage | undefined;
  latestJob: CatalogListRefreshJob | null;
  active: boolean;
  failed: boolean;
  firstFailure: boolean;
  // The list this panel just fetched from the warehouse, for "Connected. Found N … just now."
  freshlyConnected: boolean;
  isPending: boolean;
  isPlaceholderData: boolean;
  queryError: Error | null;
  refreshError: Error | null;
  refreshing: boolean;
  page: number;
  pageCount: number;
  setPage: (page: number) => void;
  search: string;
  setSearch: (search: string) => void;
  retry: () => void;
}

// Reads one parent's cached candidate list and fetches it from the warehouse when it was never listed,
// or again when the connector changed after the last failed listing.
function useOptionDiscovery(open: boolean, workspaceId: string, principalId: string | null, connectorId: string, target: OptionTarget): OptionDiscovery {
  const queryClient = useQueryClient();
  const recovery = useContext(ConnectorRecoveryContext);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");
  const autoRefreshTargets = useRef(new Set<string>());
  const key = JSON.stringify([workspaceId, principalId, connectorId, targetKey(target)]);
  const prefixKey = [...queryKeys.catalog.all(workspaceId, principalId ?? ""), connectorId, "selection-options", target.parentKind, target.catalogName, target.schemaName] as const;
  const optionsQuery = useQuery({
    queryKey: queryKeys.catalog.selectionOptions(workspaceId, principalId ?? "", connectorId, target.parentKind, target.catalogName, target.schemaName, page, PAGE_SIZE, search),
    queryFn: () => catalogIngestionAPI.getSelectionOptions(workspaceId, connectorId, { parentKind: target.parentKind, catalogName: target.catalogName, schemaName: target.schemaName ?? undefined, page, pageSize: PAGE_SIZE, query: search }),
    enabled: open && Boolean(workspaceId && principalId && connectorId),
    retry: false,
    placeholderData: (previous) => previous,
    refetchInterval: (query) => {
      const state = query.state.data?.latestJob?.state;
      return state && ACTIVE_REFRESH_STATES.has(state) ? 1_500 : false;
    },
  });
  const pageData = optionsQuery.data;
  const latestJob = pageData?.latestJob ?? null;
  const refreshMutation = useMutation({
    mutationFn: () => catalogIngestionAPI.startSelectionListRefresh(workspaceId, connectorId, target.parentKind, target.catalogName, target.schemaName),
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: prefixKey }); },
  });

  const failed = Boolean(latestJob && FAILED_REFRESH_STATES.has(latestJob.state));
  const failedBeforeConnectorChange = Boolean(
    failed && recovery && latestJob?.terminalAt && recovery.connector.updatedAtEpochMs > Date.parse(latestJob.terminalAt),
  );
  const { mutate: startRefresh } = refreshMutation;
  useEffect(() => {
    if (!open || !pageData || optionsQuery.isPlaceholderData || autoRefreshTargets.current.has(key)) return;
    const neverListed = !pageData.fetchedAt && !pageData.latestJob;
    if (!neverListed && !failedBeforeConnectorChange) return;
    autoRefreshTargets.current.add(key);
    startRefresh();
  }, [failedBeforeConnectorChange, key, open, optionsQuery.isPlaceholderData, pageData, startRefresh]);
  useEffect(() => { setPage(1); }, [search]);

  const totalCount = pageData?.totalCount ?? 0;
  const pageCount = Math.max(1, Math.ceil(totalCount / PAGE_SIZE));
  useEffect(() => { if (page > pageCount) setPage(pageCount); }, [page, pageCount]);
  const active = refreshMutation.isPending || Boolean(latestJob && ACTIVE_REFRESH_STATES.has(latestJob.state));
  const startedJobId = refreshMutation.data?.jobId ?? null;
  return {
    pageData,
    latestJob,
    active,
    failed,
    firstFailure: Boolean(pageData && failed && !pageData.fetchedAt),
    freshlyConnected: Boolean(startedJobId && latestJob?.jobId === startedJobId && latestJob.state === "COMPLETED" && pageData?.fetchedAt),
    isPending: optionsQuery.isPending,
    isPlaceholderData: optionsQuery.isPlaceholderData,
    queryError: optionsQuery.error,
    refreshError: refreshMutation.error,
    refreshing: refreshMutation.isPending,
    page,
    pageCount,
    setPage,
    search,
    setSearch,
    retry: () => { if (optionsQuery.error) void optionsQuery.refetch(); else startRefresh(); },
  };
}

export function CatalogSelectionDrawer({
  canEdit,
  canRefresh,
  open,
  connector,
  catalogName,
  catalogStep,
  selection,
  loading,
  saving,
  error,
  returnTo,
  onClose,
  onSave,
  onCatalogsSaved,
}: CatalogSelectionDrawerProps) {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<CatalogSelection>(() => cloneSelection(selection));
  const [catalogDraft, setCatalogDraft] = useState<string[]>([]);
  const [dirty, setDirty] = useState(false);
  const [catalogSequence, setCatalogSequence] = useState(catalogStep);
  const [discardOpen, setDiscardOpen] = useState(false);
  const threeDepth = THREE_DEPTH_CONNECTOR_TYPES.has(connector.type);

  const initializedTarget = useRef<string | null>(null);
  const selectionTarget = JSON.stringify([workspaceId, connector.id, catalogName, catalogStep]);
  useEffect(() => {
    if (open && initializedTarget.current === null) setCatalogSequence(catalogStep);
    if (open && (catalogStep || !loading) && initializedTarget.current !== selectionTarget) {
      initializedTarget.current = selectionTarget;
      setDraft(cloneSelection(selection));
      const configured = configuredCatalogName(connector);
      setCatalogDraft(configured ? [configured] : []);
      setDirty(catalogStep && configured !== null);
    }
    if (!open) {
      initializedTarget.current = null;
      setCatalogSequence(false);
      setDirty(false);
      setDiscardOpen(false);
    }
  }, [catalogStep, connector, loading, open, selection, selectionTarget]);

  const saveCatalogs = useMutation({
    mutationFn: (names: string[]) => catalogIngestionAPI.saveConnectorCatalogSelection(workspaceId, connector.id, { selectedCatalogNames: names }),
    onSuccess: (saved) => {
      queryClient.setQueryData(queryKeys.catalog.connectorSelection(workspaceId, principalId ?? "", connector.id), saved);
      setDirty(false);
      onCatalogsSaved(sortedNames(saved.selectedCatalogNames));
    },
  });
  const busy = saving || saveCatalogs.isPending;

  const requestClose = () => {
    if (busy) return;
    if (dirty) {
      setDiscardOpen(true);
      return;
    }
    onClose();
  };
  const changeDraft = useCallback((next: CatalogSelection) => {
    if (!canEdit) return;
    setDraft(next);
    setDirty(true);
  }, [canEdit]);
  const toggleCatalog = (name: string) => {
    if (!canEdit) return;
    setCatalogDraft((current) => current.includes(name) ? current.filter((candidate) => candidate !== name) : sortedNames([...current, name]));
    setDirty(true);
  };

  const hasCoverage = draft.selectedSchemas.length > 0;
  const stepLabel = threeDepth ? (catalogStep ? "Step 1 of 2 · Catalogs" : `${catalogSequence ? "Step 2 of 2 · " : ""}Schemas and tables in ${catalogName ?? "this catalog"}`) : null;
  const panelError = error ?? (saveCatalogs.error instanceof Error ? saveCatalogs.error.message : null);
  const footer = catalogStep
    ? <>
        <span className="type-meta tabular-nums text-foreground-secondary" data-testid="catalog-selection-count">{catalogDraft.length === 0 ? "No catalogs selected" : `${countLabel(catalogDraft.length, "catalog", "catalogs")} selected`}</span>
        <div className="flex gap-2">
          <Button type="button" variant="secondary" onClick={requestClose} disabled={busy}>Cancel</Button>
          {canEdit ? <Button type="button" onClick={() => saveCatalogs.mutate(catalogDraft)} disabled={catalogDraft.length === 0} pending={saveCatalogs.isPending}>{saveCatalogs.isPending ? "Saving…" : "Continue"}</Button> : null}
        </div>
      </>
    : <>
        <span className="type-meta tabular-nums text-foreground-secondary" data-testid="catalog-selection-count">{selectionCountLabel(draft)}</span>
        <div className="flex gap-2">
          {canEdit ? <>
            <Button type="button" variant="secondary" onClick={() => void onSave(draft, false)} disabled={loading || !dirty || busy}>Save only</Button>
            {canRefresh ? <Button type="button" onClick={() => void onSave(draft, true)} disabled={loading || !hasCoverage} pending={saving}>{saving ? "Saving…" : "Save and refresh"}</Button> : null}
          </> : <Button type="button" variant="secondary" onClick={requestClose}>Close</Button>}
        </div>
      </>;

  return <ConnectorRecoveryContext.Provider value={{ connector, returnTo }}>
    <Dialog.Root open={open} onOpenChange={(next) => { if (!next) requestClose(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className={DIALOG_OVERLAY_CLASS} />
        <Dialog.Content data-testid="catalog-selection-drawer" className="fixed inset-y-0 right-0 z-50 flex w-[min(840px,calc(100vw-16px))] flex-col border-l border-border bg-background shadow-modal focus:outline-none" onEscapeKeyDown={(event) => { event.preventDefault(); requestClose(); }} onPointerDownOutside={(event) => { event.preventDefault(); requestClose(); }}>
          <header className="flex items-start justify-between gap-4 border-b border-border px-6 py-4">
            <div className="min-w-0">
              <p className="type-status flex items-center gap-2 text-foreground-secondary"><ConnectorTypeMark type={connector.type} size="sm" /><span className="truncate">{connector.name}</span></p>
              <Dialog.Title className="type-section mt-1 text-heading">Choose tables to load</Dialog.Title>
              <Dialog.Description className="type-body mt-1 text-foreground-secondary">Monomer only reads what you choose. You can change this later.</Dialog.Description>
              {stepLabel ? <p className="type-status mt-2 text-foreground-secondary">{stepLabel}</p> : null}
            </div>
            <IconButton label="Close table selection" icon={X} size="md" onClick={requestClose} disabled={busy} />
          </header>
          {!canEdit && <p className="type-body border-b border-border px-6 py-3 text-foreground-secondary">Requires Editor role to change selection. You can refresh the available schemas and tables.</p>}
          <div className="min-h-0 flex-1 overflow-y-auto">
            {catalogStep
              ? <CatalogChoiceList canEdit={canEdit} open={open} workspaceId={workspaceId} principalId={principalId} connector={connector} draft={catalogDraft} onToggle={toggleCatalog} />
              : <>
                  {loading && <StateBlock kind="loading" layout="page" title="Loading selection…" />}
                  {(!loading || selection !== null) && <OptionList canEdit={canEdit} open={open} workspaceId={workspaceId} principalId={principalId} connectorId={connector.id} target={threeDepth ? { parentKind: "CATALOG", catalogName, schemaName: null } : { parentKind: "CONNECTOR", catalogName: null, schemaName: null }} label="Schemas" childKind="SCHEMA" draft={draft} onChange={changeDraft} discovery={{ singular: "schema", plural: "schemas", location: threeDepth ? catalogName ?? connector.name : connectorLocationLabel(connector) }} />}
                </>}
            {panelError && <p role="alert" className="type-meta border-t border-error/20 bg-error/5 px-5 py-3 text-error">{panelError}</p>}
          </div>
          <footer className="flex flex-shrink-0 flex-wrap items-center justify-between gap-3 border-t border-border px-6 py-4">{footer}</footer>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
    <DialogShell
      open={discardOpen}
      onOpenChange={setDiscardOpen}
      size="sm"
      title="Discard selection changes?"
      description="Your unsaved selection changes will be lost."
      role="alertdialog"
      footer={<><Button type="button" variant="secondary" onClick={() => setDiscardOpen(false)}>Keep editing</Button><Button type="button" onClick={() => { setDiscardOpen(false); setDirty(false); onClose(); }}>Discard changes</Button></>}
    >
      {null}
    </DialogShell>
  </ConnectorRecoveryContext.Provider>;
}

function ConnectedStatus({ discovery, totalCount }: { discovery: DiscoveryLabel; totalCount: number }) {
  return <p role="status" className="type-meta flex items-center gap-2 border-b border-border px-5 py-2.5 text-foreground">
    <CircleCheck aria-hidden="true" className="h-3.5 w-3.5 flex-shrink-0 text-success-text" />
    <span>Connected. Found {countLabel(totalCount, discovery.singular, discovery.plural)} in {discovery.location} just now.</span>
  </p>;
}

function FirstListingFailure({ latestJob, onRetry, retrying }: { latestJob: CatalogListRefreshJob | null; onRetry: () => void; retrying: boolean }) {
  const recovery = useContext(ConnectorRecoveryContext);
  if (!recovery) return <ListFailure message={latestJob?.safeMessage ?? "The warehouse list refresh failed."} onRetry={onRetry} retrying={retrying} />;
  return <div className="px-5 py-5">
    <ConnectionRecovery failureCode={latestJob?.failureCode ?? null} safeMessage={latestJob?.safeMessage ?? null} connector={recovery.connector} consequence="Nothing was saved to the catalog." returnTo={recovery.returnTo} onRetry={onRetry} retrying={retrying} links={null} density="panel" />
  </div>;
}

function CachedListingFailure({ latestJob, onRetry, retrying }: { latestJob: CatalogListRefreshJob | null; onRetry: () => void; retrying: boolean }) {
  const recovery = useContext(ConnectorRecoveryContext);
  if (!recovery) {
    return <div role="status" className="type-meta flex items-center justify-between gap-3 border-b border-error/20 bg-error/5 px-4 py-2.5 text-error"><span className="inline-flex min-w-0 items-center gap-1.5"><CircleAlert className="h-3.5 w-3.5" /><span className="min-w-0">{latestJob?.safeMessage ?? "Refresh failed. Showing the last fetched list."}</span></span><button type="button" onClick={onRetry} disabled={retrying} className="underline underline-offset-2 disabled:cursor-not-allowed disabled:text-foreground-secondary">Retry</button></div>;
  }
  return <div className="border-b border-border px-4 py-3">
    <ConnectionRecovery failureCode={latestJob?.failureCode ?? null} safeMessage={latestJob?.safeMessage ?? null} connector={recovery.connector} consequence="Showing the last fetched list." returnTo={recovery.returnTo} onRetry={onRetry} retrying={retrying} links={null} density="row" />
  </div>;
}

function CatalogChoiceList({ canEdit, open, workspaceId, principalId, connector, draft, onToggle }: { canEdit: boolean; open: boolean; workspaceId: string; principalId: string | null; connector: Connector; draft: string[]; onToggle: (name: string) => void }) {
  const discovery = useOptionDiscovery(open, workspaceId, principalId, connector.id, { parentKind: "CONNECTOR", catalogName: null, schemaName: null });
  const { pageData, latestJob, active } = discovery;
  const options = pageData?.options.filter((option) => option.available || draft.includes(option.name)) ?? [];
  return <section data-testid="catalog-option-list-catalog">
    {discovery.freshlyConnected && pageData ? <ConnectedStatus discovery={{ singular: "catalog", plural: "catalogs", location: connectorLocationLabel(connector) }} totalCount={pageData.totalCount} /> : null}
    <div className="flex items-center gap-2 border-b border-border px-5 py-3"><SearchInput value={discovery.search} onChange={discovery.setSearch} placeholder="Search catalogs" /><span className="type-status tabular-nums flex-shrink-0 text-foreground-secondary">{pageData?.fetchedAt ? formatTotal(pageData.totalCount) : active ? "Discovering" : "Not fetched"}</span><IconButton label={discovery.failed ? "Retry catalogs" : "Refresh catalogs"} icon={RefreshCw} onClick={discovery.retry} pending={active} /></div>
    {draft.length > 0 && <div aria-label="Selected catalogs" className="type-meta border-b border-border px-5 py-3 text-foreground-secondary">Selected: {draft.map((name) => <button key={name} type="button" disabled={!canEdit} aria-label={`Remove selected catalog ${name}`} onClick={() => onToggle(name)} className="mr-3 inline-flex items-center gap-1 py-1 text-foreground enabled:hover:underline disabled:cursor-not-allowed disabled:text-foreground-secondary">{name}<X className="h-3.5 w-3.5" /></button>)}</div>}
    {!pageData && discovery.isPending ? <ListLoading label="Loading catalogs…" />
      : discovery.queryError && !pageData ? <ListFailure message={discovery.queryError.message} onRetry={discovery.retry} retrying={discovery.refreshing} />
        : pageData && active && !pageData.fetchedAt ? <ListLoading label="Connecting to the warehouse…" />
          : discovery.firstFailure ? <FirstListingFailure latestJob={latestJob} onRetry={discovery.retry} retrying={discovery.refreshing} />
            : discovery.refreshError && !pageData?.fetchedAt ? <ListFailure message={discovery.refreshError.message} onRetry={discovery.retry} retrying={discovery.refreshing} />
              : options.length === 0 ? <StateBlock kind="empty" layout="inline" icon={Database} title={discovery.search ? "No matching catalogs." : "No catalogs found."} />
                : <>
                    {discovery.failed && pageData?.fetchedAt ? <CachedListingFailure latestJob={latestJob} onRetry={discovery.retry} retrying={discovery.refreshing} /> : null}
                    <div className="divide-y divide-border-light">{options.map((option) => {
                      const selected = draft.includes(option.name);
                      return <div key={option.name} className={`flex min-h-[52px] items-center gap-3 px-5 py-2 ${selected ? "bg-surface" : "hover:bg-surface/60"}`}><CheckBox checked={selected} disabled={!canEdit || (!option.available && !selected)} ariaLabel={`${option.name} catalog`} onClick={() => onToggle(option.name)} /><Database aria-hidden="true" className="h-4 w-4 flex-shrink-0 text-foreground-secondary" /><span className={`type-control min-w-0 flex-1 truncate ${option.available ? "text-foreground" : "text-foreground-secondary line-through"}`}>{option.name}</span>{!option.available ? <span className="type-status text-foreground-secondary">Unavailable</span> : null}</div>;
                    })}</div>
                    <Pagination page={discovery.page} pageCount={discovery.pageCount} totalCount={pageData?.totalCount ?? 0} onPage={discovery.setPage} inset />
                  </>}
  </section>;
}

export function OptionList({ canEdit, open, workspaceId, principalId, connectorId, target, label, childKind, draft, onChange, discovery: discoveryLabel }: { canEdit: boolean; open: boolean; workspaceId: string; principalId: string | null; connectorId: string; target: OptionTarget; label: string; childKind: "SCHEMA" | "RELATION"; draft: CatalogSelection; onChange: (selection: CatalogSelection) => void; discovery?: DiscoveryLabel }) {
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const discovery = useOptionDiscovery(open, workspaceId, principalId, connectorId, target);
  const { pageData, latestJob, active, failed } = discovery;
  const visibleOptions = pageData?.options ?? [];
  const fetchedAt = formatFetchedAt(pageData?.fetchedAt ?? null);
  const toggleExpanded = (name: string) => setExpanded((previous) => { const next = new Set(previous); if (next.has(name)) next.delete(name); else next.add(name); return next; });

  const selectedRelations = findSchema(draft, target.schemaName ?? "")?.relations;
  const selectedNames = childKind === "SCHEMA" ? draft.selectedSchemas.map((schema) => schema.schemaName)
    : selectedRelations?.kind === "SELECTED_RELATIONS" ? selectedRelations.relationNames : [];
  const relationList = childKind === "RELATION";
  const totalLabel = pageData?.fetchedAt ? formatTotal(pageData.totalCount) : active ? "Discovering" : "Not fetched";
  return <section className="bg-background" data-testid={`catalog-option-list-${childKind.toLowerCase()}`} data-option-target={target.schemaName ?? target.catalogName ?? target.parentKind}>
    {discoveryLabel && discovery.freshlyConnected && pageData ? <ConnectedStatus discovery={discoveryLabel} totalCount={pageData.totalCount} /> : null}
    {!relationList && <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-5 py-3"><h2 className="type-group text-foreground-secondary">{label}</h2><span className="type-status tabular-nums text-foreground-secondary">{totalLabel}</span></div>}
    <div className={`flex items-center gap-2 ${relationList ? "pb-3" : "border-b border-border px-5 py-3"}`}><SearchInput value={discovery.search} onChange={discovery.setSearch} placeholder={childKind === "RELATION" ? "Search tables" : `Search ${label.toLowerCase()}`} />{relationList && <span className="type-status tabular-nums flex-shrink-0 text-foreground-secondary">{totalLabel}</span>}<span className="group relative flex-shrink-0"><IconButton label={failed ? `Retry ${label.toLowerCase()}` : `Refresh ${label.toLowerCase()}`} icon={RefreshCw} title="" onClick={discovery.retry} pending={active || discovery.refreshing} /><span className="pointer-events-none absolute right-0 top-full z-20 mt-1 whitespace-nowrap type-status rounded-[4px] bg-heading px-2 py-1 text-background opacity-0 shadow-ambient transition-opacity group-hover:opacity-100">{fetchedAt ?? "Not updated yet"}</span></span></div>
    {selectedNames.length > 0 && <div aria-label={`Selected ${label.toLowerCase()}`} className="type-meta max-h-32 overflow-y-auto border-b border-border px-5 py-3 text-foreground-secondary">Selected: {selectedNames.map((name) => <button key={name} type="button" disabled={!canEdit} aria-label={`Remove selected ${childKind === "SCHEMA" ? "schema" : "table"} ${name}`} onClick={() => onChange(childKind === "SCHEMA" ? updateSchemaCoverage(draft, name, null) : toggleRelation(draft, target.schemaName ?? "", name))} className="mr-3 inline-flex items-center gap-1 py-1 text-foreground enabled:hover:underline disabled:cursor-not-allowed disabled:text-foreground-secondary">{name}{childKind === "SCHEMA" && (() => { const coverage = findSchema(draft, name)?.relations; return coverage?.kind === "SELECTED_RELATIONS" ? ` (${coverage.relationNames.join(", ")})` : null; })()}<X className="h-3.5 w-3.5" /></button>)}</div>}
    {discovery.refreshError && <ListFailure message={discovery.refreshError instanceof Error ? discovery.refreshError.message : "The list refresh could not be started."} onRetry={discovery.retry} retrying={discovery.refreshing} />}
    {relationList && findSchema(draft, target.schemaName ?? "")?.relations.kind === "ALL_RELATIONS" && <p role="status" className="type-status border-y border-border bg-surface/40 px-3 py-2.5 text-foreground-secondary">All tables are selected. Clear the schema checkbox to choose individual tables.</p>}
    {!pageData && discovery.isPending ? <ListLoading label={`Loading ${label.toLowerCase()}…`} />
      : discovery.queryError && !pageData ? <ListFailure message={discovery.queryError.message} onRetry={discovery.retry} retrying={discovery.refreshing} />
        : pageData && active && !pageData.fetchedAt ? <ListLoading label={discoveryLabel ? "Connecting to the warehouse…" : `Loading ${label.toLowerCase()} from the warehouse…`} />
          : discovery.firstFailure ? <FirstListingFailure latestJob={latestJob} onRetry={discovery.retry} retrying={discovery.refreshing} />
            : !pageData?.fetchedAt && !latestJob && !active && !discovery.refreshError ? <ListFailure message="This list has not been fetched yet." onRetry={discovery.retry} retrying={discovery.refreshing} />
              : latestJob?.state === "CANCELLED" && !pageData?.fetchedAt ? <ListCancelled onReload={discovery.retry} reloading={discovery.refreshing} />
                : !pageData?.fetchedAt && discovery.refreshError ? null
                  : pageData && visibleOptions.length === 0 && !failed ? <StateBlock kind="empty" layout="inline" icon={FolderTree} title={`No ${childKind === "SCHEMA" ? "schemas" : "tables"} found.`} />
                    : <>
                        {pageData && failed && pageData.fetchedAt && <CachedListingFailure latestJob={latestJob} onRetry={discovery.retry} retrying={discovery.refreshing} />}
                        {visibleOptions.length === 0 && <StateBlock kind="empty" layout="inline" icon={FolderTree} title={`No ${childKind === "SCHEMA" ? "schemas" : "tables"} found.`} />}
                        <div className={`divide-y divide-border-light ${relationList ? "border-y border-border" : ""}`}>{visibleOptions.map((option) => <OptionRow canEdit={canEdit} key={option.name} option={option} childKind={childKind} target={target} expanded={expanded.has(option.name)} draft={draft} onChange={onChange} onToggleExpand={() => toggleExpanded(option.name)} open={open} workspaceId={workspaceId} principalId={principalId} connectorId={connectorId} />)}</div>
                        <Pagination page={discovery.page} pageCount={discovery.pageCount} totalCount={pageData?.totalCount ?? 0} onPage={discovery.setPage} inset={!relationList} />
                      </>}
  </section>;
}

function Pagination({ page, pageCount, totalCount, onPage, inset }: { page: number; pageCount: number; totalCount: number; onPage: (page: number) => void; inset: boolean }) {
  if (pageCount <= 1) return null;
  return <div className={`flex items-center justify-between py-3 ${inset ? "border-t border-border px-5" : ""}`}><span className="type-status tabular-nums text-foreground-secondary">Page {page} of {pageCount} · {formatTotal(totalCount)}</span><div className="flex gap-2"><Button type="button" variant="secondary" size="sm" onClick={() => onPage(Math.max(1, page - 1))} disabled={page === 1}>Previous</Button><Button type="button" variant="secondary" size="sm" onClick={() => onPage(Math.min(pageCount, page + 1))} disabled={page >= pageCount}>Next</Button></div></div>;
}

function SearchInput({ value, onChange, placeholder }: { value: string; onChange: (value: string) => void; placeholder: string }) {
  return <div className="relative min-w-[180px] max-w-sm flex-1"><Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-secondary" /><Input value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} aria-label={placeholder} className="pl-8" /></div>;
}

function OptionRow({ canEdit, option, childKind, target, expanded, draft, onChange, onToggleExpand, open, workspaceId, principalId, connectorId }: { canEdit: boolean; option: CatalogListOption; childKind: "SCHEMA" | "RELATION"; target: OptionTarget; expanded: boolean; draft: CatalogSelection; onChange: (selection: CatalogSelection) => void; onToggleExpand: () => void; open: boolean; workspaceId: string; principalId: string | null; connectorId: string }) {
  if (childKind === "RELATION") {
    const schemaName = target.schemaName;
    if (!schemaName) return null;
    const selected = relationSelected(draft, schemaName, option.name);
    const allRelations = findSchema(draft, schemaName)?.relations.kind === "ALL_RELATIONS";
    return <div className="flex min-h-[48px] items-center gap-3 px-4 py-2 hover:bg-surface/60"><CheckBox checked={selected} disabled={!canEdit || allRelations || (!option.available && !selected)} ariaLabel={`${option.name} table`} onClick={() => onChange(toggleRelation(draft, schemaName, option.name))} /><span className={`type-control min-w-0 flex-1 truncate ${option.available ? "text-foreground" : "text-foreground-secondary line-through"}`}>{option.name}</span><span className="type-status text-foreground-secondary">{option.available ? option.relationKind ? relationKindLabel(option.relationKind) : "Selected" : "Unavailable"}</span></div>;
  }
  const schema = findSchema(draft, option.name);
  const coverage = schema?.relations ?? null;
  const checked = coverage?.kind === "ALL_RELATIONS";
  const mixed = coverage?.kind === "SELECTED_RELATIONS";
  return <div className="border-b border-border-light last:border-b-0"><div className={`flex min-h-[52px] items-center gap-3 px-5 py-2 ${coverage ? "bg-surface" : "hover:bg-surface/60"}`}><CheckBox checked={checked} mixed={mixed} disabled={!canEdit || (!option.available && !coverage)} ariaLabel={`${option.name} schema`} onClick={() => onChange(updateSchemaCoverage(draft, option.name, checked ? null : { kind: "ALL_RELATIONS" }))} /><button type="button" onClick={onToggleExpand} aria-expanded={expanded} aria-label={`${expanded ? "Collapse" : "Expand"} ${option.name} schema`} className="flex min-w-0 flex-1 items-center gap-2 text-left"><ChevronRight className={`h-4 w-4 flex-shrink-0 text-foreground-secondary transition-transform ${expanded ? "rotate-90" : ""}`} /><span className={`type-control min-w-0 flex-1 truncate ${option.available ? "text-foreground" : "text-foreground-secondary line-through"}`}>{option.name}</span></button>{checked && <span className="type-status text-foreground-secondary">All tables</span>}{mixed && <span className="type-status tabular-nums text-foreground-secondary">{countLabel(coverage.relationNames.length, "table", "tables")}</span>}{!option.available && <span className="type-status text-foreground-secondary">Unavailable</span>}</div>{expanded && <div className="border-t border-border-light bg-background px-5 py-3 pl-[52px]"><OptionList canEdit={canEdit} open={open} workspaceId={workspaceId} principalId={principalId} connectorId={connectorId} target={{ parentKind: "SCHEMA", catalogName: target.catalogName, schemaName: option.name }} label={`Tables in ${option.name}`} childKind="RELATION" draft={draft} onChange={onChange} /></div>}</div>;
}

function CheckBox({ checked, mixed, disabled, ariaLabel, onClick }: { checked: boolean; mixed?: boolean; disabled?: boolean; ariaLabel: string; onClick: () => void }) {
  return <button type="button" role="checkbox" aria-checked={mixed ? "mixed" : checked} aria-label={ariaLabel} disabled={disabled} onClick={onClick} className={`relative inline-flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-[4px] border transition ${checked || mixed ? "border-foreground bg-accent text-accent-foreground" : "border-[1.5px] border-foreground-secondary bg-background text-transparent hover:border-foreground"} ${disabled ? "cursor-not-allowed opacity-55" : ""}`}>{mixed ? <span className="h-[2px] w-2.5 rounded-full bg-accent-foreground" /> : checked ? <Check className="h-3.5 w-3.5" /> : null}</button>;
}

function ListLoading({ label }: { label: string }) {
  return <StateBlock kind="loading" layout="inline" title={label} />;
}

function ListFailure({ message, detail, onRetry, retrying }: { message: string; detail?: ReactNode; onRetry: () => void; retrying: boolean }) {
  return <StateBlock kind="error" layout="inline" title={message} description={detail} action={<Button type="button" variant="secondary" size="sm" onClick={onRetry} pending={retrying}>{retrying ? "Retrying…" : "Retry"}</Button>} />;
}

function ListCancelled({ onReload, reloading }: { onReload: () => void; reloading: boolean }) {
  return <StateBlock kind="empty" layout="inline" icon={CircleSlash} title="List refresh was cancelled." action={<Button type="button" variant="secondary" size="sm" onClick={onReload} pending={reloading}>{reloading ? "Reloading…" : "Reload"}</Button>} />;
}

export function selectionHasCoverage(selection: CatalogSelection | null): boolean {
  return Boolean(selection && selection.selectedSchemas.length > 0);
}

export function selectionSchemaKey(schemaName: string): string {
  return schemaKey(schemaName);
}
