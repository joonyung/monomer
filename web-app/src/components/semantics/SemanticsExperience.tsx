import { useRef, type KeyboardEvent, type ReactNode } from "react";
import { Boxes, ChevronDown, Plus, Search, BarChart3 } from "lucide-react";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric, MetricType } from "@/lib/types/metric";
import type { Connector } from "@/lib/types/connector";
import type { TimeSpineDefinition } from "@/lib/types/time-spine";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Select } from "@/components/ui/Select";
import { metricTypeLabel } from "@/components/semantic-model-detail/semantic-vocabulary";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "@/components/ui/Menu";
import { StateBlock } from "@/components/ui/StateBlock";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { Link, useRouter } from "@/lib/navigation";
import { CatalogTablePicker } from "@/components/catalog/CatalogTablePicker";
import { newSemanticModelFromTableHref } from "@/lib/catalog/catalog-links";
import { webPaths } from "@/lib/web-paths";
import { SemanticCatalog } from "@/components/semantics/SemanticCatalog";
import {
  TimeSpinesExperience,
  type TimeSpinesCollectionState,
} from "@/components/semantics/TimeSpinesExperience";

export type SemanticsView = "metrics" | "models" | "setup";

export interface SemanticsResourceState<Item> {
  items: Item[];
  loading: boolean;
  error: string | null;
}

interface SemanticsExperienceProps {
  workspaceId: string;
  view: SemanticsView;
  searchQuery: string;
  semanticModels: SemanticsResourceState<SemanticModelResponse>;
  metrics: SemanticsResourceState<Metric>;
  filters?: {modelFilter:string;kindFilter:string;statusFilter:string;sort:string};
  onFilterChange?: (patch:Partial<{modelFilter:string;kindFilter:string;statusFilter:string;sort:string}>) => void;
  modelPage: number;
  modelPageSize: number;
  connectors?: Connector[];
  /** True once the connector list has loaded without error. */
  connectorsLoaded?: boolean;
  timeSpines?: TimeSpinesCollectionState;
  selectedConnectorId?: string;
  onViewChange: (view: SemanticsView) => void;
  onSearchQueryChange: (query: string) => void;
  onModelPageChange: (page: number) => void;
  onConnectorChange?: (connectorId: string) => void;
  onTimeSpineSave?: (connectorId: string, definition: TimeSpineDefinition) => Promise<TimeSpineDefinition>;
}

function tabId(view: SemanticsView): string {
  return `semantics-${view}-tab`;
}

function panelId(view: SemanticsView): string {
  return `semantics-${view}-panel`;
}

export function SemanticsExperience({
  workspaceId,
  filters,
  onFilterChange,
  view,
  searchQuery,
  semanticModels,
  metrics,
  connectors = [],
  connectorsLoaded = false,
  timeSpines = { items: [], loading: false, error: null },
  selectedConnectorId,
  onViewChange,
  onSearchQueryChange,
  onConnectorChange,
  onTimeSpineSave,
}: SemanticsExperienceProps) {
  const workspaceAccess = useWorkspaceAccess();
  const router = useRouter();
  const canCreateSemanticModel = workspaceAccess.hasRole("EDITOR");
  const canCreateMetric = workspaceAccess.hasRole("EDITOR");
  const timeSpineCount = timeSpines.items.reduce(
    (total, configuration) => total + configuration.definition.timeSpines.length,
    0,
  );

  return (
    <div className="flex h-full min-h-0 flex-col bg-background">
      <WorkspacePageHeader
        section="Model"
        title="Semantics"
        actions={view === "models" && canCreateSemanticModel ? (
          <CatalogTablePicker
            triggerLabel="Describe a table"
            onSelect={(table) => router.push(newSemanticModelFromTableHref(workspaceId, table))}
          />
        ) : view !== "setup" && (canCreateSemanticModel || canCreateMetric) ? (
          <DefinitionsNewMenu
            workspaceId={workspaceId}
            canCreateSemanticModel={canCreateSemanticModel}
            canCreateMetric={canCreateMetric}
          />
        ) : null}
      />
      <SemanticsAreaTabs view={view} onViewChange={onViewChange} />
      <section
        id={panelId(view)}
        role="tabpanel"
        aria-labelledby={tabId(view)}
        className="flex min-h-0 flex-1 flex-col"
      >
        <SemanticsToolbar
          view={view}
          query={searchQuery}
          onQueryChange={onSearchQueryChange}
          modelCount={semanticModels.loading || semanticModels.error ? null : semanticModels.items.length}
          metricCount={metrics.loading || metrics.error ? null : metrics.items.length}
          connectorCount={connectors.length}
          timeSpineCount={timeSpines.loading || timeSpines.error ? null : timeSpineCount}
          models={semanticModels.items}
          filters={filters}
          onFilterChange={onFilterChange}
        />
        {view !== "setup" ? (
          semanticModels.loading || metrics.loading ? (
            <LoadingState label={view} />
          ) : semanticModels.error || metrics.error ? (
            <ErrorState message={semanticModels.error ?? metrics.error ?? "Definitions could not be loaded."} />
          ) : (
            <SemanticCatalog
              view={view}
              filters={filters}
              workspaceId={workspaceId}
              models={semanticModels.items}
              metrics={metrics.items}
              connectors={connectors}
              connectorsLoaded={connectorsLoaded}
              canEdit={workspaceAccess.hasRole("EDITOR")}
              query={searchQuery}
            />
          )
        ) : !workspaceAccess.hasRole("EDITOR") ? <StateBlock kind="empty" layout="page" title="Setup is available to workspace editors" /> : (
          <TimeSpinesExperience
            canEdit={workspaceAccess.hasRole("EDITOR")}
            connectors={connectors}
            configurations={timeSpines}
            query={searchQuery}
            selectedConnectorId={selectedConnectorId}
            onConnectorChange={onConnectorChange ?? (() => undefined)}
            onSave={onTimeSpineSave ?? (async () => ({ schemaVersion: 1, timeSpines: [] }))}
          />
        )}
      </section>
    </div>
  );
}

function SemanticsAreaTabs({
  view,
  onViewChange,
}: {
  view: SemanticsView;
  onViewChange: (view: SemanticsView) => void;
}) {
  const tabs = useRef(new Map<SemanticsView, HTMLButtonElement>());
  const workspaceAccess = useWorkspaceAccess();
  const options: Array<{ value: SemanticsView; label: string }> = [
    { value: "metrics", label: "Metrics" },
    { value: "models", label: "Models" },
    ...(workspaceAccess.hasRole("EDITOR") ? [{ value: "setup" as const, label: "Setup" }] : []),
  ];
  const selectAt = (index: number) => {
    const option = options[(index + options.length) % options.length];
    onViewChange(option.value);
    requestAnimationFrame(() => tabs.current.get(option.value)?.focus());
  };
  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (event.key === "ArrowRight") {
      event.preventDefault();
      selectAt(index + 1);
    } else if (event.key === "ArrowLeft") {
      event.preventDefault();
      selectAt(index - 1);
    } else if (event.key === "Home") {
      event.preventDefault();
      selectAt(0);
    } else if (event.key === "End") {
      event.preventDefault();
      selectAt(options.length - 1);
    }
  };

  return (
    <div className="flex h-12 flex-none items-stretch border-b border-border px-8" role="tablist" aria-label="Semantics areas">
      {options.map((option, index) => {
        const selected = option.value === view;
        return (
          <button
            key={option.value}
            ref={(node) => {
              if (node) tabs.current.set(option.value, node);
              else tabs.current.delete(option.value);
            }}
            id={tabId(option.value)}
            type="button"
            role="tab"
            aria-selected={selected}
            aria-controls={panelId(option.value)}
            tabIndex={selected ? 0 : -1}
            onClick={() => onViewChange(option.value)}
            onKeyDown={(event) => handleKeyDown(event, index)}
            className={`type-control relative px-3 focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-[-3px] focus-visible:outline-focus-ring ${selected ? "text-foreground" : "text-foreground-secondary hover:bg-surface/60 hover:text-foreground"}`}
          >
            {option.label}
            {selected ? <span className="absolute inset-x-0 bottom-0 h-0.5 bg-accent-marker" aria-hidden="true" /> : null}
          </button>
        );
      })}
    </div>
  );
}

function SemanticsToolbar({
  view,
  query,
  onQueryChange,
  modelCount,
  metricCount,
  connectorCount,
  timeSpineCount,
  models,
  filters = { modelFilter: "", kindFilter: "", statusFilter: "", sort: "name" },
  onFilterChange = () => undefined,
}: {
  view: SemanticsView;
  query: string;
  onQueryChange: (query: string) => void;
  modelCount: number | null;
  metricCount: number | null;
  connectorCount: number;
  timeSpineCount: number | null;
  models: SemanticModelResponse[];
  filters?: { modelFilter: string; kindFilter: string; statusFilter: string; sort: string };
  onFilterChange?: (patch: Partial<{ modelFilter: string; kindFilter: string; statusFilter: string; sort: string }>) => void;
}) {
  const select = (label: string, value: string, onChange: (value: string) => void, options: Array<{ value: string; label: string }>, width: string) => (
    <div className={`w-[calc(50%_-_6px)] min-w-0 ${width}`}>
      <Select label={label} value={value} onChange={onChange} options={options} disabled={false} invalid={false} />
    </div>
  );
  return (
    <div data-testid="semantics-toolbar" role="search" aria-label="Semantics filters" className="flex min-h-[64px] flex-none flex-wrap items-center gap-3 border-b border-border px-8 py-3">
      <div className="flex w-full min-w-0 items-center gap-3 sm:w-auto">
        <label className="relative block min-w-0 flex-1 sm:w-[220px] sm:flex-none">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
          <Input
            type="search"
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            aria-label={view !== "setup" ? view === "metrics" ? "Search metrics" : "Search models" : "Search calendar tables"}
            placeholder={view !== "setup" ? view === "metrics" ? "Search metrics" : "Search models" : "Search connectors, relations, or columns"}
            className="pl-9"
          />
        </label>
        <div className="type-meta flex flex-none items-center gap-3 whitespace-nowrap tabular-nums text-foreground-secondary">
          {view !== "setup" ? (
            <>
              {modelCount !== null ? <ToolbarCount count={modelCount} singular="model" plural="models" /> : null}
              {metricCount !== null ? <ToolbarCount count={metricCount} singular="metric" plural="metrics" /> : null}
            </>
          ) : (
            <>
              <ToolbarCount count={connectorCount} singular="connector" plural="connectors" />
              {timeSpineCount !== null ? <ToolbarCount count={timeSpineCount} singular="calendar table" plural="calendar tables" /> : null}
            </>
          )}
        </div>
      </div>
      {view === "metrics" ? <>
        {select("Filter by model", filters.modelFilter, (modelFilter) => onFilterChange({ modelFilter }), [{ value: "", label: "All models" }, ...models.map((model) => ({ value: model.name, label: model.label || model.name }))], "sm:w-36")}
        {select("Filter by kind", filters.kindFilter, (kindFilter) => onFilterChange({ kindFilter }), [{ value: "", label: "All kinds" }, ...(["simple", "ratio", "derived", "cumulative", "conversion"] as MetricType[]).map((type) => ({ value: type, label: metricTypeLabel(type) }))], "sm:w-32")}
        {select("Filter by status", filters.statusFilter, (statusFilter) => onFilterChange({ statusFilter }), [{ value: "", label: "All statuses" }, { value: "broken", label: "Broken reference" }, { value: "connector", label: "No executable connector" }], "sm:w-48")}
      </> : null}
      {view !== "setup" ? select("Sort definitions", filters.sort, (sort) => onFilterChange({ sort }), [{ value: "name", label: "Name" }, { value: "updated", label: "Recently updated" }], "sm:w-40") : null}
    </div>
  );
}

function DefinitionsNewMenu({
  workspaceId,
  canCreateSemanticModel,
  canCreateMetric,
}: {
  workspaceId: string;
  canCreateSemanticModel: boolean;
  canCreateMetric: boolean;
}) {
  return (
    <Menu>
      <MenuTrigger asChild>
        <Button type="button" variant="primary" size="md">
          <Plus aria-hidden="true" />
          New
          <ChevronDown aria-hidden="true" />
        </Button>
      </MenuTrigger>
      <MenuContent align="end" className="w-60">
        {canCreateSemanticModel ? (
          <NewResourceItem
            href={webPaths.newSemanticModel(workspaceId)}
            icon={<Boxes className="h-4 w-4" aria-hidden="true" />}
            title="Model"
            detail="Describe a table for analysis"
          />
        ) : null}
        {canCreateMetric ? (
          <NewResourceItem
            href={webPaths.newMetric(workspaceId)}
            icon={<BarChart3 className="h-4 w-4" aria-hidden="true" />}
            title="Metric"
            detail="Build a trusted measurement"
          />
        ) : null}
      </MenuContent>
    </Menu>
  );
}

function NewResourceItem({
  href,
  icon,
  title,
  detail,
}: {
  href: string;
  icon: ReactNode;
  title: string;
  detail: string;
}) {
  return (
    <MenuItem asChild>
      <Link href={href}>
        <span className="flex items-start gap-3 py-1">
          <span className="mt-0.5 text-foreground-secondary">{icon}</span>
          <span>
            <span className="type-control block text-foreground">{title}</span>
            <span className="type-status mt-0.5 block text-foreground-secondary">{detail}</span>
          </span>
        </span>
      </Link>
    </MenuItem>
  );
}

function LoadingState({ label }: { label: string }) {
  return <StateBlock kind="loading" layout="page" title={`Loading ${label}`} />;
}

function ErrorState({ message }: { message: string }) {
  return <StateBlock kind="error" layout="page" title={message} />;
}

function ToolbarCount({ count, singular, plural }: { count: number; singular: string; plural: string }) {
  return <span><strong className="font-normal text-foreground">{count.toLocaleString("en-US")}</strong> {count === 1 ? singular : plural}</span>;
}
