import { PartitionFilterHint } from "@/components/semantics/SourcePartitionHints";
import { queryPartitionHints, type PartitionRepairAction, type PartitionRepairRequest } from "@/components/semantics/source-partition";
import { useId, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { ArrowDown, ArrowUp, Ban, BarChart3, Check, Clock3, Info, Plus, Search, X } from "lucide-react";
import type { SemanticDimensionFilterType, SemanticExcludedModel, SemanticFilter, SemanticGroupBy, SemanticMetricTimeRange, SemanticQueryOrder } from "@/api/explore";
import type { ExploreErrorPresentation } from "@/api/explore-errors";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { ExploreFailureNotice } from "@/components/explore/ExploreFailureNotice";
import { FilterValueList } from "@/components/explore/FilterValueList";
import { TokenPopover } from "@/components/explore/TokenPopover";
import { Button, buttonClassName } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Select } from "@/components/ui/Select";
import { StateBlock } from "@/components/ui/StateBlock";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { Link } from "@/lib/navigation";
import type { Connector } from "@/lib/types/connector";
import type { Metric } from "@/lib/types/metric";
import { webPaths } from "@/lib/web-paths";
import type { SemanticDraft, UnavailableSelections } from "./explore-draft";
import { formatCount, granularityWord, groupByBaseLabel, groupByTokenLabel, metricLabel } from "./run-presentation";
import { CompactConnectorSelect, ReadOnlyConnector } from "./CompactConnectorSelect";
import { metricModelNames, metricRunProblem, metricRunProblemText, resolveSemanticConnectors } from "./semantic-connectors";
import { groupByPresentation } from "./semantic-labels";
import { excludedModelLabels, excludedModelsSummary, problemReference, semanticProblemGuidance, type SemanticProblemContext } from "./semantic-problems";
import { formatMetricTimeRange } from "./time-grain-format";
import { browserKnowsTimeZone, timestampToZonedWallClock, zonedWallClockToTimestamp } from "@/lib/time-zone";
import {
  buildSemanticFilter,
  CATEGORICAL_FILTER_KINDS,
  defaultFilterKind,
  filterAllowsMultipleValues,
  filterComparisonMismatch,
  filterDimensions,
  filterKindLabel,
  filterNeedsValues,
  filterOperators,
  filterOperatorWords,
  filterValueHint,
  initialFilterComparison,
  knownDimensionFilterKind,
  reconcileFilterComparison,
  type DimensionGroupBy,
  type FilterKind,
} from "./semantic-filters";

const CHIP_SHAPE = "type-control inline-flex h-7 min-w-0 max-w-[280px] items-center gap-1 rounded-[4px] border px-2";
const TOKEN_CLASS = `${CHIP_SHAPE} border-border bg-surface text-foreground hover:border-label`;
const STATIC_TOKEN_CLASS = `${CHIP_SHAPE} border-border bg-surface text-foreground`;
const UNAVAILABLE_TOKEN_CLASS = `${CHIP_SHAPE} border-error/50 bg-error/5 text-error`;
const METRIC_LIST_SEARCH_ID = "explore-metric-list-search";
const GROUP_BY_LIST_SEARCH_ID = "explore-group-by-list-search";
const ADD_CLASS = "type-control inline-flex h-7 items-center gap-1 rounded-[4px] border border-dashed border-border px-2 text-foreground-secondary hover:bg-surface hover:text-foreground";

export interface SemanticConnectorChoice {
  /** Every workspace connector. */
  all: Connector[];
  /** How many metric choices switched the connector; the toolbar marks each new switch once. */
  switchCount: number;
  /** Connectors that can run every selected metric. */
  compatible: Connector[];
  selectedId: string | null;
  onSelect: (connectorId: string) => void;
  /** True while metric or model definitions needed to decide are loading. */
  resolving: boolean;
  /** Why no connector qualifies, when none does. */
  problem: string | null;
}

export interface SemanticQuestionProps {
  workspaceId: string | null;
  /** The Workspace Reporting time zone: time filter values are entered and compared in it. */
  reportingTimeZone: string;
  draft: SemanticDraft;
  onDraftChange: (draft: SemanticDraft) => void;
  metrics: Metric[];
  metricsLoading: boolean;
  metricsError: string | null;
  modelFilter: SemanticModelResponse | null;
  onClearModelFilter: () => void;
  visibleMetricNames: string[];
  semanticModels: SemanticModelResponse[];
  semanticModelsLoading: boolean;
  semanticModelsError: ExploreErrorPresentation | null;
  onRetrySemanticModels: () => void;
  groupBys: SemanticGroupBy[];
  dimensionFilterTypes: SemanticDimensionFilterType[];
  /** Models whose group bys and filters are left out because the engine rejected their definitions. */
  excludedSemanticModels: SemanticExcludedModel[];
  groupBysLoading: boolean;
  groupBysError: ExploreErrorPresentation | null;
  /** Group-bys and filters the chosen metrics can't use on this connector; Run waits until they're removed. */
  unavailable: UnavailableSelections;
  connector: SemanticConnectorChoice;
  /** Chooses or removes a metric; choosing one from another connector switches the question to it. */
  onChooseMetric: (metricName: string) => void;
  /** Editors and Owners get links to fix a broken model; others are told to ask an editor. */
  canEditDefinitions: boolean;
  metricSwap: MetricSwap | null;
  partitionRepair?: PartitionRepairRequest | null;
  onPartitionRepair?: (action: PartitionRepairAction) => void;
}

function semanticProblemContext(workspaceId: string | null, connector: SemanticConnectorChoice, metrics: Metric[], models: SemanticModelResponse[]): SemanticProblemContext {
  const chosen = connector.compatible.find((candidate) => candidate.id === connector.selectedId);
  return { workspaceId, connectorId: connector.selectedId, connectorName: chosen?.name ?? "this connector", metrics, models };
}

function groupByKey(groupBy: SemanticGroupBy): string {
  return JSON.stringify(groupBy);
}

/** Group-bys that differ only by grain share one row in the picker. */
function groupByIdentity(groupBy: SemanticGroupBy): string {
  return groupBy.kind === "METRIC_TIME" ? "metric_time" : `${groupBy.kind}:${groupBy.semanticModelName}:${groupBy.name}`;
}

function granularityOf(groupBy: SemanticGroupBy): string | null {
  if (groupBy.kind === "METRIC_TIME") return groupBy.granularity;
  return groupBy.kind === "DIMENSION" ? groupBy.granularity ?? null : null;
}


function modelLabel(groupBy: SemanticGroupBy, models: SemanticModelResponse[]): string | null {
  if (groupBy.kind === "METRIC_TIME") return null;
  const model = models.find((candidate) => candidate.name === groupBy.semanticModelName);
  return model?.label || groupBy.semanticModelName;
}

/**
 * The chosen metrics after choosing or removing one. Choosing a metric that can't run with the chosen ones
 * switches the question to its connector: chosen metrics that can't run beside it leave, the rest stay. A
 * metric no connector can run is simply added, so the toolbar names its problem instead of dropping working
 * metrics for it.
 */
export function chooseMetric(selected: string[], metricName: string, metrics: Metric[], models: SemanticModelResponse[], connectorIds: string[]): { metrics: string[]; removed: string[] } {
  if (selected.includes(metricName)) return { metrics: selected.filter((name) => name !== metricName), removed: [] };
  const runs = (names: string[]) => resolveSemanticConnectors(names, metrics, models, connectorIds).compatible.length > 0;
  if (selected.length === 0 || runs([...selected, metricName]) || !runs([metricName])) return { metrics: [...selected, metricName], removed: [] };
  const kept: string[] = [];
  for (const chosen of selected) {
    if (runs([...kept, chosen, metricName])) kept.push(chosen);
  }
  return { metrics: [...kept, metricName], removed: selected.filter((name) => !kept.includes(name)) };
}

export function withMetricChosen(draft: SemanticDraft, metricName: string, metrics: Metric[], models: SemanticModelResponse[], connectorIds: string[]): { draft: SemanticDraft; removed: string[] } {
  const chosen = chooseMetric(draft.metrics, metricName, metrics, models, connectorIds);
  return { draft: { ...draft, metrics: chosen.metrics }, removed: chosen.removed };
}

/** Metrics a cross-connector choice removed, shown beside the Metrics row with a way back. */
export interface MetricSwap {
  removed: string[];
  /** Every removed metric is one no connector can run. */
  removedCannotRun: boolean;
  /** Where the question runs now; null when several connectors qualify and none is chosen yet. */
  connectorName: string | null;
  /** The metric whose choice removed the others, to return focus to after Undo. */
  chosen: string;
  onUndo: () => void;
}

/** "Order count", "Order count and Revenue", "A, B, and C"; the short form says "3 metrics" from three on. */
function metricList(names: string[], metrics: Metric[], short: boolean): string {
  const labels = names.map((name) => metricLabel(name, metrics));
  if (short && labels.length >= 3) return `${labels.length} metrics`;
  if (labels.length <= 2) return labels.join(" and ");
  return `${labels.slice(0, -1).join(", ")}, and ${labels[labels.length - 1]}`;
}

export function metricSwapMessage(swap: Pick<MetricSwap, "removed" | "removedCannotRun" | "connectorName">, metrics: Metric[], short: boolean): string {
  const removed = metricList(swap.removed, metrics, short);
  if (swap.removedCannotRun) return `Removed ${removed}, which can’t run on any connector.`;
  if (!swap.connectorName) return `Removed ${removed}. Choose a connector to run.`;
  return `Switched to ${swap.connectorName}. Removed ${removed}.`;
}

function withGroupByToggled(draft: SemanticDraft, groupBy: SemanticGroupBy): SemanticDraft {
  const exists = draft.groupBys.some((candidate) => groupByKey(candidate) === groupByKey(groupBy));
  return { ...draft, groupBys: exists ? draft.groupBys.filter((candidate) => groupByKey(candidate) !== groupByKey(groupBy)) : [...draft.groupBys, groupBy] };
}

function moveItem<T>(items: T[], from: number, to: number): T[] {
  if (to < 0 || to >= items.length) return items;
  const next = [...items];
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item as T);
  return next;
}

function sameDimension(filter: SemanticFilter, dimension: DimensionGroupBy): boolean {
  return filter.dimension.semanticModelName === dimension.semanticModelName && filter.dimension.name === dimension.name;
}

/**
 * `unavailable` marks a filter whose dimension discovery answered without; it still reads by its
 * model's name for the dimension. While discovery is loading or failed, the filter says so instead.
 */
export function filterTokenLabel(filter: SemanticFilter, dimensions: DimensionGroupBy[], models: SemanticModelResponse[], metadataLoading: boolean, unavailable: boolean): string {
  const dimension = dimensions.find((candidate) => sameDimension(filter, candidate));
  const modelDimension = unavailable
    ? models.find((model) => model.name === filter.dimension.semanticModelName)?.dimensions.find((candidate) => candidate.name === filter.dimension.name)
    : undefined;
  const subject = dimension
    ? groupByPresentation(dimension, models).label
    : modelDimension
      ? modelDimension.label || modelDimension.name
      : `${filter.dimension.semanticModelName} / ${filter.dimension.name} · ${metadataLoading ? "Loading metadata…" : "Metadata unavailable"}`;
  const values = filter.values.map(String).map((value) => value.includes(",") ? `“${value}”` : value).join(", ");
  return `${subject} ${filterOperatorWords(filter.operator, filter.kind)}${values ? ` ${values}` : ""}`;
}

export function orderTokenLabel(order: SemanticQueryOrder, metrics: Metric[], models: SemanticModelResponse[]): string {
  const target = order.target.kind === "METRIC"
    ? metricLabel(order.target.metricName, metrics)
    : groupByTokenLabel(order.target.groupBy, models);
  return `${target} ${order.direction === "ASC" ? "↑" : "↓"}`;
}

function orderTokenContent(order: SemanticQueryOrder, metrics: Metric[], models: SemanticModelResponse[]): ReactNode {
  const target = order.target.kind === "METRIC" ? metricLabel(order.target.metricName, metrics) : groupByTokenLabel(order.target.groupBy, models);
  return <>{target} <span aria-hidden="true">{order.direction === "ASC" ? "↑" : "↓"}</span><span className="sr-only">{order.direction === "ASC" ? "ascending" : "descending"}</span></>;
}

function orderTokenName(order: SemanticQueryOrder, metrics: Metric[], models: SemanticModelResponse[]): string {
  const target = order.target.kind === "METRIC" ? metricLabel(order.target.metricName, metrics) : groupByTokenLabel(order.target.groupBy, models);
  return `${target}, ${order.direction === "ASC" ? "ascending" : "descending"}`;
}

function timeRangeTokenLabel(range: SemanticMetricTimeRange | null): string {
  return range ? formatMetricTimeRange(range, undefined) : "Any time";
}

function PanelSearch({ id, label, value, onChange }: { id: string | undefined; label: string; value: string; onChange: (value: string) => void }) {
  return (
    <div className="relative border-b border-border p-2">
      <Search className="pointer-events-none absolute left-4 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
      <Input id={id} data-autofocus type="search" aria-label={label} placeholder={label} value={value} onChange={(event) => onChange(event.target.value)} className="pl-8" />
    </div>
  );
}

function PanelList({ children, onScroll }: { children: ReactNode; onScroll?: () => void }) {
  const scrollbar = useTransientScrollbar();
  return <div onScroll={() => { scrollbar.onScroll(); onScroll?.(); }} className={`min-h-0 flex-1 overflow-y-auto pb-1 ${scrollbar.className}`}>{children}</div>;
}

interface ListGroup<T> {
  key: string;
  /** The model the rows belong to; null for rows that belong to no one model, listed first without a heading. */
  label: string | null;
  items: T[];
}

/** One model's rows under a heading that stays in view while they scroll. */
function ListGroupSection({ label, children }: { label: string | null; children: ReactNode }) {
  const headingId = useId();
  const rows = <ul className="divide-y divide-border-light">{children}</ul>;
  if (label === null) return rows;
  return (
    <div role="group" aria-labelledby={headingId}>
      <p id={headingId} className="type-group sticky top-0 z-[1] truncate bg-background px-3 pb-1.5 pt-3 text-label">{label}</p>
      {rows}
    </div>
  );
}

/** A 14px mark centred on the cap height of the row's 13px label, not on its taller line box. */
function CheckMark({ checked, broken }: { checked: boolean; broken: boolean }) {
  if (broken) return <Ban className="h-3.5 w-3.5 text-foreground-secondary" aria-hidden="true" />;
  return (
    <span aria-hidden="true" className={`grid h-3.5 w-3.5 place-items-center rounded-[3px] border ${checked ? "border-accent bg-accent text-accent-foreground" : "border-foreground-secondary/70 bg-background"}`}>
      {checked ? <Check className="h-2.5 w-2.5" strokeWidth={3} /> : null}
    </span>
  );
}

type RowHint = { text: string; anchor: DOMRect };

function OptionRow({ label, description, context, selected, hint, broken, metricName, onHint, onClick }: {
  label: string;
  description: string | null;
  /** The owning model, read after the label by assistive technology; the list heading shows it on screen. */
  context: string | null;
  selected: boolean;
  /** What choosing this row does or why it can't run; shown on hover and keyboard focus, and given as its description. */
  hint?: string;
  /** No connector can run it: a slash mark takes the checkbox slot and the label reads secondary. */
  broken?: boolean;
  metricName?: string;
  onHint?: (hint: RowHint | null) => void;
  onClick: () => void;
}) {
  const hintId = useId();
  const show = (element: HTMLElement) => { if (hint) onHint?.({ text: hint, anchor: element.getBoundingClientRect() }); };
  return (
    <li>
      <button
        type="button"
        aria-pressed={selected}
        aria-describedby={hint ? hintId : undefined}
        data-metric-row={metricName}
        onClick={onClick}
        onMouseEnter={(event) => show(event.currentTarget)}
        onFocus={(event) => show(event.currentTarget)}
        onMouseLeave={() => onHint?.(null)}
        onBlur={() => onHint?.(null)}
        title={hint ? undefined : description ?? undefined}
        className={`type-control flex min-h-10 w-full items-start gap-2.5 px-3 py-2.5 text-left ${selected ? "bg-surface" : "hover:bg-surface"} ${broken ? "text-foreground-secondary" : "text-foreground"}`}
      >
        <span className="flex h-[18px] w-4 shrink-0 justify-center pt-px"><CheckMark checked={selected} broken={Boolean(broken)} /></span>
        <span className="min-w-0 flex-1">
          <span className="block truncate">{label}</span>
          {description ? <span className="type-status mt-1 line-clamp-2 text-foreground-secondary">{description}</span> : null}
        </span>
        {context ? <span className="sr-only">, {context}</span> : null}
      </button>
      {hint ? <span id={hintId} hidden>{hint}</span> : null}
    </li>
  );
}

/** A row hint drawn above the scrolling list so the panel edge never clips it. */
function RowHintTooltip({ hint }: { hint: RowHint | null }) {
  if (!hint) return null;
  const below = hint.anchor.bottom + 56 < window.innerHeight;
  return createPortal(
    <div
      role="tooltip"
      className={`pointer-events-none fixed z-50 max-w-[320px] rounded-[6px] bg-accent px-2.5 py-1.5 type-meta text-accent-foreground shadow-ambient ${below ? "" : "-translate-y-full"}`}
      style={{ top: below ? hint.anchor.bottom + 4 : hint.anchor.top - 4, left: hint.anchor.left + 32 }}
    >
      {hint.text}
    </div>,
    document.body,
  );
}

function connectorNames(ids: string[], connectors: Connector[]): string[] {
  return ids.map((id) => connectors.find((connector) => connector.id === id)?.name ?? "another connector");
}

/** The hint and mark for one metric row, given what is chosen now. */
function metricRowState(metric: Metric, selected: string[], metrics: Metric[], models: SemanticModelResponse[], connectors: Connector[]): { hint?: string; broken: boolean } {
  const connectorIds = connectors.map((connector) => connector.id);
  const problem = metricRunProblem(metric.name, metrics, models, connectorIds);
  if (problem) return { hint: metricRunProblemText(problem), broken: true };
  if (selected.includes(metric.name)) return { broken: false };
  const chosen = chooseMetric(selected, metric.name, metrics, models, connectorIds);
  if (chosen.removed.length === 0) return { broken: false };
  const removed = metricList(chosen.removed, metrics, false);
  const targets = connectorNames(resolveSemanticConnectors(chosen.metrics, metrics, models, connectorIds).compatible, connectors);
  if (targets.length === 1) return { hint: `Switches to ${targets[0]} and removes ${removed}.`, broken: false };
  const others = targets.length - 1;
  return { hint: `Removes ${removed}. Runs on ${targets[0]} and ${others} other ${others === 1 ? "connector" : "connectors"}.`, broken: false };
}

function RemoveButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" data-question-remove aria-label={label} title={label} onClick={onClick} className="-ml-1 grid h-7 w-6 shrink-0 place-items-center rounded-[4px] text-foreground-secondary hover:bg-surface hover:text-foreground">
      <X className="h-3.5 w-3.5" aria-hidden="true" />
    </button>
  );
}

function modelGroupLabel(modelName: string, models: SemanticModelResponse[]): string {
  return models.find((model) => model.name === modelName)?.label || modelName;
}

const SEVERAL_MODELS_KEY = "\u0000several";
const UNRESOLVED_KEY = "\u0001unresolved";

/**
 * Metrics under the one model each reads, in model label order. Metrics over several models follow, then
 * metrics whose inputs no longer resolve.
 */
function metricGroups(visible: Metric[], metrics: Metric[], models: SemanticModelResponse[]): ListGroup<Metric>[] {
  const groups = new Map<string, ListGroup<Metric>>();
  for (const metric of visible) {
    const modelNames = metricModelNames(metric.name, metrics);
    const modelName = modelNames?.size === 1 ? [...modelNames][0] as string : null;
    const key = modelName ?? (modelNames && modelNames.size > 1 ? SEVERAL_MODELS_KEY : UNRESOLVED_KEY);
    const label = modelName ? modelGroupLabel(modelName, models) : key === SEVERAL_MODELS_KEY ? "Several models" : "Other";
    const group = groups.get(key) ?? { key, label, items: [] };
    group.items.push(metric);
    groups.set(key, group);
  }
  const rank = (group: ListGroup<Metric>) => group.key === SEVERAL_MODELS_KEY ? 1 : group.key === UNRESOLVED_KEY ? 2 : 0;
  return [...groups.values()].sort((left, right) => rank(left) - rank(right) || (left.label ?? "").localeCompare(right.label ?? ""));
}

function MetricPicker({ searchId, workspaceId, metrics, models, connectors, loading, error, selected, visibleMetricNames, modelFilter, onClearModelFilter, onToggle }: {
  searchId: string | undefined;
  workspaceId: string | null;
  metrics: Metric[];
  models: SemanticModelResponse[];
  /** Every workspace connector, to say where choosing a metric takes the question. */
  connectors: Connector[];
  loading: boolean;
  error: string | null;
  selected: string[];
  visibleMetricNames: string[];
  modelFilter: SemanticModelResponse | null;
  onClearModelFilter: () => void;
  onToggle: (metricName: string) => void;
}) {
  const [search, setSearch] = useState("");
  const [hint, setHint] = useState<RowHint | null>(null);
  const normalized = search.trim().toLowerCase();
  const visible = metrics.filter((metric) => visibleMetricNames.includes(metric.name)
    && `${metric.label ?? ""} ${metric.name}`.toLowerCase().includes(normalized));
  return (
    <>
      <PanelSearch id={searchId} label="Search metrics" value={search} onChange={setSearch} />
      {modelFilter ? (
        <div className="type-status flex items-center justify-between gap-2 border-b border-border px-3 py-1.5 text-foreground-secondary">
          <span className="truncate">Metrics from {modelFilter.label || modelFilter.name}</span>
          <button type="button" onClick={onClearModelFilter} className="shrink-0 text-foreground underline underline-offset-4">Show all</button>
        </div>
      ) : null}
      <RowHintTooltip hint={hint} />
      <PanelList onScroll={() => setHint(null)}>
        {loading ? <StateBlock kind="loading" layout="inline" title="Loading metrics…" />
          : error ? <StateBlock kind="error" layout="inline" title={error} />
            : metrics.length === 0 ? (
              <StateBlock
                kind="empty"
                layout="inline"
                icon={BarChart3}
                title="No metrics yet"
                description="Metrics are defined in Semantics. Use SQL to query tables directly."
                action={workspaceId ? <Link href={webPaths.semantics(workspaceId)} className={buttonClassName("secondary", "sm", "")}>Go to Semantics</Link> : undefined}
              />
            ) : visible.length === 0 ? <StateBlock kind="empty" layout="inline" icon={Search} title="No metrics match this search." />
              : metricGroups(visible, metrics, models).map((group) => (
                <ListGroupSection key={group.key} label={group.label}>
                  {group.items.map((metric) => (
                    <OptionRow
                      key={metric.id}
                      label={metric.label || metric.name}
                      description={metric.description?.trim() || null}
                      context={group.label}
                      selected={selected.includes(metric.name)}
                      {...metricRowState(metric, selected, metrics, models, connectors)}
                      metricName={metric.name}
                      onHint={setHint}
                      onClick={() => { setHint(null); onToggle(metric.name); }}
                    />
                  ))}
                </ListGroupSection>
              ))}
      </PanelList>
    </>
  );
}

/** Metric time first without a heading, then each model's group bys under its label, in label order. */
function groupByGroups(rows: SemanticGroupBy[][], models: SemanticModelResponse[]): ListGroup<SemanticGroupBy[]>[] {
  const groups = new Map<string, ListGroup<SemanticGroupBy[]>>();
  for (const variants of rows) {
    const first = variants[0] as SemanticGroupBy;
    const key = first.kind === "METRIC_TIME" ? "" : first.semanticModelName;
    const group = groups.get(key) ?? { key, label: first.kind === "METRIC_TIME" ? null : modelGroupLabel(first.semanticModelName, models), items: [] };
    group.items.push(variants);
    groups.set(key, group);
  }
  return [...groups.values()].sort((left, right) => (left.label === null ? -1 : right.label === null ? 1 : left.label.localeCompare(right.label)));
}

/** A time group by: its grains sit beside the label, and choosing one adds that grain. */
function TimeGroupByRow({ label, context, variants, selectedKeys, models, onPick }: {
  label: string;
  context: string | null;
  variants: SemanticGroupBy[];
  selectedKeys: Set<string>;
  models: SemanticModelResponse[];
  onPick: (groupBy: SemanticGroupBy) => void;
}) {
  const anyChosen = variants.some((variant) => selectedKeys.has(groupByKey(variant)));
  return (
    <li className={`flex min-h-10 items-start gap-2.5 px-3 py-2 ${anyChosen ? "bg-surface" : ""}`}>
      <span className="grid h-6 w-4 shrink-0 place-items-center"><Clock3 className={`h-3.5 w-3.5 ${anyChosen ? "text-foreground" : "text-foreground-secondary"}`} aria-hidden="true" /></span>
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-1">
        <span className="type-control min-w-0 truncate text-foreground">{label}</span>
        <div className="flex flex-wrap gap-1" role="group" aria-label={context ? `${label}, ${context}, granularity` : `${label} granularity`}>
          {variants.map((variant) => {
            const chosen = selectedKeys.has(groupByKey(variant));
            return (
              <button
                key={groupByKey(variant)}
                type="button"
                aria-pressed={chosen}
                aria-label={groupByTokenLabel(variant, models)}
                onClick={() => onPick(variant)}
                className={`type-status h-6 rounded-[4px] border px-1.5 ${chosen ? "border-label bg-surface text-foreground" : "border-border-light bg-background text-foreground-secondary hover:border-border hover:text-foreground"}`}
              >
                {granularityWord(granularityOf(variant) as string)}
              </button>
            );
          })}
        </div>
      </div>
    </li>
  );
}

function GroupByPicker({ searchId, groupBys, excluded, problemContext, canEditDefinitions, loading, error, metricSelected, connectorChosen, selected, models, current, onPick }: {
  searchId: string | undefined;
  groupBys: SemanticGroupBy[];
  excluded: SemanticExcludedModel[];
  canEditDefinitions: boolean;
  problemContext: SemanticProblemContext;
  loading: boolean;
  error: ExploreErrorPresentation | null;
  metricSelected: boolean;
  connectorChosen: boolean;
  selected: SemanticGroupBy[];
  models: SemanticModelResponse[];
  current: SemanticGroupBy | null;
  onPick: (groupBy: SemanticGroupBy) => void;
}) {
  const [search, setSearch] = useState("");
  const normalized = search.trim().toLowerCase();
  const rows = new Map<string, SemanticGroupBy[]>();
  for (const groupBy of groupBys) {
    if (current && groupByIdentity(groupBy) !== groupByIdentity(current)) continue;
    const text = `${groupByBaseLabel(groupBy, models)} ${modelLabel(groupBy, models) ?? ""} ${groupBy.kind === "METRIC_TIME" ? "" : `${groupBy.semanticModelName} ${groupBy.name}`}`.toLowerCase();
    if (!text.includes(normalized)) continue;
    rows.set(groupByIdentity(groupBy), [...(rows.get(groupByIdentity(groupBy)) ?? []), groupBy]);
  }
  const selectedKeys = new Set(selected.map(groupByKey));
  const empty = !metricSelected ? "Select a metric to see available group bys."
    : !connectorChosen ? "Choose a connector to see available group bys."
      : normalized ? "No group bys match this search." : "No group bys are available for the selected metrics.";
  const renderRow = (variants: SemanticGroupBy[], context: string | null) => {
    const first = variants[0] as SemanticGroupBy;
    const timed = variants.filter((variant) => granularityOf(variant));
    if (timed.length === 0) {
      return <OptionRow key={groupByKey(first)} label={groupByBaseLabel(first, models)} description={null} context={context} selected={selectedKeys.has(groupByKey(first))} onClick={() => onPick(first)} />;
    }
    return <TimeGroupByRow key={groupByIdentity(first)} label={groupByBaseLabel(first, models)} context={context} variants={timed} selectedKeys={selectedKeys} models={models} onPick={onPick} />;
  };
  return (
    <>
      {current ? null : <PanelSearch id={searchId} label="Search group bys" value={search} onChange={setSearch} />}
      <PanelList>
        {loading ? <StateBlock kind="loading" layout="inline" title="Loading group bys…" />
          : error ? <SemanticRequestProblem error={error} context={problemContext} fallbackTitle="Couldn’t load group bys for these metrics." onRetry={null} retryLabel="" />
            : rows.size === 0 ? <StateBlock kind="empty" layout="inline" icon={Search} title={empty} />
              : current ? <ul>{[...rows.values()].map((variants) => renderRow(variants, modelLabel(variants[0] as SemanticGroupBy, models)))}</ul>
                : groupByGroups([...rows.values()], models).map((group) => (
                  <ListGroupSection key={group.key} label={group.label}>
                    {group.items.map((variants) => renderRow(variants, group.label))}
                  </ListGroupSection>
                ))}
      </PanelList>
      {!loading && !error ? <ExcludedModelsNote excluded={excluded} context={problemContext} subject="group bys" canEditDefinitions={canEditDefinitions} /> : null}
    </>
  );
}

/**
 * A question panel's form. Enter applies; ⌘↵ applies and then runs. `apply` is null while the input is
 * incomplete. A dirty form is one a click outside the panel applies instead of discarding.
 */
function EditorForm({ dirty, apply, close, onRunRequest, children }: {
  dirty: boolean;
  apply: (() => void) | null;
  close: () => void;
  onRunRequest: () => void;
  children: ReactNode;
}) {
  return (
    <form
      className="grid grid-cols-[minmax(0,1fr)] gap-2 p-3"
      data-dirty={dirty ? "" : undefined}
      onSubmit={(event) => {
        event.preventDefault();
        apply?.();
      }}
      onKeyDown={(event) => {
        if (!(event.metaKey || event.ctrlKey) || event.key !== "Enter") return;
        event.preventDefault();
        event.stopPropagation();
        if (dirty) {
          if (!apply) return;
          apply();
        } else {
          close();
        }
        onRunRequest();
      }}
    >
      {children}
    </form>
  );
}

const ZONED_TIMESTAMP = /([zZ]|[+-]\d{2}:\d{2})$/;

/** A saved value reads as the zone's wall-clock time; a value being typed stays as typed. */
function timestampInputValue(value: string, timeZone: string): string {
  return ZONED_TIMESTAMP.test(value) ? timestampToZonedWallClock(value, timeZone) : value;
}

function FilterEditor({ reportingTimeZone, dimensions, dimensionFilterTypes, models, initial, initialDimension, others, onSave, onRemove, close, onRunRequest }: {
  reportingTimeZone: string;
  dimensions: DimensionGroupBy[];
  dimensionFilterTypes: SemanticDimensionFilterType[];
  models: SemanticModelResponse[];
  initial: SemanticFilter | null;
  initialDimension?: { semanticModelName: string; name: string };
  /** The question's other filters, so an edit can't duplicate one. */
  others: SemanticFilter[];
  onSave: (filter: SemanticFilter) => void;
  onRemove: (() => void) | null;
  close: () => void;
  onRunRequest: () => void;
}) {
  const [opening] = useState(() => {
    const dimension = initial ? dimensions.find((candidate) => sameDimension(initial, candidate)) : initialDimension ? dimensions.find((candidate) => candidate.semanticModelName === initialDimension.semanticModelName && candidate.name === initialDimension.name) : dimensions[0];
    return {
      targetKey: dimension ? groupByKey(dimension) : "",
      ...initialFilterComparison(dimension, dimensionFilterTypes, initial),
    };
  });
  const [targetKey, setTargetKey] = useState(opening.targetKey);
  const target = dimensions.find((dimension) => groupByKey(dimension) === targetKey);
  const granularity = target?.granularity ?? null;
  const [kind, setKind] = useState<FilterKind>(opening.kind);
  const [operator, setOperator] = useState<string>(opening.operator);
  const [values, setValues] = useState<string[]>(opening.values);
  const knownKind = granularity ? null : knownDimensionFilterKind(target, dimensionFilterTypes);
  const currentComparison = { kind, operator, values };
  const reconciledComparison = reconcileFilterComparison(currentComparison, knownKind);
  if (reconciledComparison !== currentComparison) {
    setKind(reconciledComparison.kind);
    setOperator(reconciledComparison.operator);
    setValues(reconciledComparison.values);
  }
  const repairing = initial !== null && target !== undefined && sameDimension(initial, target)
    && knownKind !== null && initial.kind !== knownKind;
  const operators = filterOperators(kind);
  const needsValues = filterNeedsValues(operator);
  const multiple = filterAllowsMultipleValues(operator);
  // A datetime-local field has no zone. The hint names the Reporting time zone, so an entered time is that
  // zone's wall-clock time; a browser without the zone's data reads it, and says so, as UTC.
  const inputTimeZone = browserKnowsTimeZone(reportingTimeZone) ? reportingTimeZone : "UTC";
  const sent = kind === "TIMESTAMP" ? values.map((value) => !value || ZONED_TIMESTAMP.test(value) ? value : zonedWallClockToTimestamp(value, inputTimeZone) ?? value) : values;
  const built = target ? buildSemanticFilter(kind, { semanticModelName: target.semanticModelName, name: target.name }, operator, sent, granularity) : { filter: null, problem: null };
  const duplicate = built.filter !== null && others.some((other) => JSON.stringify(other) === JSON.stringify(built.filter));
  const filter = duplicate ? null : built.filter;
  const problem = duplicate ? "This filter is already in the query." : built.problem;
  const dirty = opening.repairing || repairing || targetKey !== opening.targetKey || kind !== opening.kind || operator !== opening.operator || JSON.stringify(values) !== JSON.stringify(opening.values);
  const resetForKind = (nextKind: FilterKind) => {
    setKind(nextKind);
    if (!filterOperators(nextKind).includes(operator as never)) setOperator("EQUALS");
    setValues([]);
  };
  const kindOptions = CATEGORICAL_FILTER_KINDS.includes(kind) ? CATEGORICAL_FILTER_KINDS : [...CATEGORICAL_FILTER_KINDS, kind];
  const hint = filterValueHint(kind, operator, granularity, (grain) => granularityWord(grain).toLowerCase(), inputTimeZone);
  return (
    <EditorForm dirty={dirty} apply={filter ? () => { onSave(filter); close(); } : null} close={close} onRunRequest={onRunRequest}>
      <div className="flex items-center gap-2">
        <div className="min-w-0 flex-1">
          <Select
            label="Filter dimension"
            value={target ? groupByKey(target) : ""}
            currentLabel={target ? undefined : "Choose a dimension"}
            options={dimensions.map((dimension) => ({ value: groupByKey(dimension), label: `${groupByPresentation(dimension, models).label} · ${modelLabel(dimension, models)}` }))}
            disabled={false}
            invalid={false}
            onChange={(value) => {
              setTargetKey(value);
              resetForKind(defaultFilterKind(dimensions.find((dimension) => groupByKey(dimension) === value), dimensionFilterTypes));
            }}
          />
        </div>
        {granularity || knownKind ? null : (
          <div className="w-[132px] shrink-0">
            <Select
              label="Compare as"
              value={kind}
              currentLabel={`as ${filterKindLabel(kind)}`}
              options={kindOptions.map((value) => ({ value, label: filterKindLabel(value) }))}
              disabled={false}
              invalid={false}
              onChange={(value) => resetForKind(value as FilterKind)}
            />
          </div>
        )}
      </div>
      {repairing ? <p className="type-status text-foreground-secondary">This column uses {filterKindLabel(kind)} comparisons. {needsValues ? "Enter a new value to update the filter." : "Update the filter to use this type."}</p> : null}
      <Select label="Filter operator" value={operator} options={operators.map((value) => ({ value, label: filterOperatorWords(value, kind) }))} disabled={false} invalid={false}
        onChange={(next) => { setOperator(next); if (filterAllowsMultipleValues(next) !== multiple) setValues((current) => current.slice(0, 1)); }} />
      {!needsValues ? null
        : kind === "BOOLEAN" ? (
          <Select label="Filter value" value={values[0] ?? ""} currentLabel={values[0] ? undefined : "Select value"} options={[{ value: "true", label: "true" }, { value: "false", label: "false" }]} disabled={false} invalid={false} onChange={(value) => setValues([value])} />
        ) : multiple ? (
          <FilterValueList values={values} onChange={setValues} placeholder={kind === "DATE" ? "2026-08-20, then Enter" : "Type a value, then Enter"} />
        ) : kind === "DATE" ? (
          <Input aria-label="Filter value" type="date" value={values[0] ?? ""} onChange={(event) => setValues([event.target.value])} />
        ) : kind === "TIMESTAMP" ? (
          <Input aria-label="Filter value" type="datetime-local" step={1} value={timestampInputValue(values[0] ?? "", inputTimeZone)} onChange={(event) => setValues([event.target.value])} />
        ) : (
          <Input aria-label="Filter value" inputMode={kind === "NUMBER" ? "decimal" : undefined} value={values[0] ?? ""} onChange={(event) => setValues([event.target.value])} placeholder={kind === "NUMBER" ? "1234.5" : "Value"} />
        )}
      {problem ? <p role="alert" className="type-status text-error">{problem}</p> : hint ? <p className="type-status text-foreground-secondary">{hint}</p> : null}
      <div className="mt-1 flex items-center justify-between gap-2">
        {onRemove ? <Button type="button" variant="ghost" size="sm" onClick={() => { onRemove(); close(); }}>Remove filter</Button> : <span />}
        <Button type="submit" size="sm" disabled={!filter}>{initial ? "Update filter" : "Add filter"}</Button>
      </div>
    </EditorForm>
  );
}

function FilterPanel({ metricSelected, connectorChosen, dimensions, models, excluded, problemContext, canEditDefinitions, loading, error, metadataMissing, onRetry, children }: {
  canEditDefinitions: boolean;
  metricSelected: boolean;
  connectorChosen: boolean;
  dimensions: DimensionGroupBy[];
  models: SemanticModelResponse[];
  excluded: SemanticExcludedModel[];
  problemContext: SemanticProblemContext;
  loading: boolean;
  error: ExploreErrorPresentation | null;
  metadataMissing: boolean;
  onRetry: (() => void) | null;
  children: ReactNode;
}) {
  if (!metricSelected) return <StateBlock kind="empty" layout="inline" title="Select a metric to filter by its dimensions." />;
  if (!connectorChosen) return <StateBlock kind="empty" layout="inline" title="Choose a connector to filter by its dimensions." />;
  if (loading) return <StateBlock kind="loading" layout="inline" title="Loading filter dimensions…" />;
  if (error) {
    return <SemanticRequestProblem error={error} context={problemContext} fallbackTitle="Couldn’t load filter dimensions." onRetry={onRetry} retryLabel="Retry filter dimensions" />;
  }
  // The Group by list already says which models were left out; the filter form repeats it only when they leave nothing to filter.
  if (dimensions.length === 0 || models.length === 0) {
    return <><StateBlock kind="empty" layout="inline" title="No filterable dimensions are available." /><ExcludedModelsNote excluded={excluded} context={problemContext} subject="dimensions" canEditDefinitions={canEditDefinitions} /></>;
  }
  return <>
    {metadataMissing ? <p className="type-status border-b border-border px-3 py-2 text-foreground-secondary">Some filter dimensions are unavailable because their model metadata is missing or incomplete.</p> : null}
    {children}
  </>;
}

function TimeRangeEditor({ value, onChange, close, onRunRequest }: {
  value: SemanticMetricTimeRange | null;
  onChange: (range: SemanticMetricTimeRange | null) => void;
  close: () => void;
  onRunRequest: () => void;
}) {
  const [kind, setKind] = useState<"DATE" | "TIMESTAMP">(value?.start.kind ?? "DATE");
  const [start, setStart] = useState(value?.start.value ?? "");
  const [end, setEnd] = useState(value?.end.value ?? "");
  const complete = start.trim() && end.trim();
  const dirty = kind !== (value?.start.kind ?? "DATE") || start !== (value?.start.value ?? "") || end !== (value?.end.value ?? "");
  return (
    <EditorForm dirty={dirty} apply={complete ? () => { onChange({ start: { kind, value: start.trim() }, end: { kind, value: end.trim() } }); close(); } : null} close={close} onRunRequest={onRunRequest}>
      <p className="type-status text-foreground-secondary">Metric time range</p>
      <Select label="Metric time range type" value={kind} options={[{ value: "DATE", label: "Date" }, { value: "TIMESTAMP", label: "Timestamp" }]} disabled={false} invalid={false} onChange={(next) => setKind(next as "DATE" | "TIMESTAMP")} />
      <div className="grid grid-cols-2 gap-2">
        <Input data-autofocus aria-label="Metric time range start" type={kind === "DATE" ? "date" : "text"} value={start} onChange={(event) => setStart(event.target.value)} placeholder={kind === "TIMESTAMP" ? "2026-08-20T00:00:00Z" : undefined} />
        <Input aria-label="Metric time range end" type={kind === "DATE" ? "date" : "text"} value={end} onChange={(event) => setEnd(event.target.value)} placeholder={kind === "TIMESTAMP" ? "2026-08-21T00:00:00Z" : undefined} />
      </div>
      <div className="mt-1 flex items-center justify-between gap-2">
        <Button type="button" variant="ghost" size="sm" disabled={!value} onClick={() => { onChange(null); close(); }}>Any time</Button>
        <Button type="submit" size="sm" disabled={!complete}>Apply range</Button>
      </div>
    </EditorForm>
  );
}

function OrderEditor({ targets, initial, index, count, onSave, onMove, onRemove, close, onRunRequest }: {
  targets: Array<{ key: string; label: string; target: SemanticQueryOrder["target"] }>;
  initial: SemanticQueryOrder | null;
  index: number;
  count: number;
  onSave: (order: SemanticQueryOrder) => void;
  onMove: ((to: number) => void) | null;
  onRemove: (() => void) | null;
  close: () => void;
  onRunRequest: () => void;
}) {
  const initialKey = initial ? targets.find((candidate) => JSON.stringify(candidate.target) === JSON.stringify(initial.target))?.key : targets[0]?.key;
  const [targetKey, setTargetKey] = useState(initialKey ?? "");
  const [direction, setDirection] = useState<"ASC" | "DESC">(initial?.direction ?? "ASC");
  const target = targets.find((candidate) => candidate.key === targetKey);
  const dirty = targetKey !== (initialKey ?? "") || direction !== (initial?.direction ?? "ASC");
  return (
    <EditorForm dirty={dirty} apply={target ? () => { onSave({ target: target.target, direction }); close(); } : null} close={close} onRunRequest={onRunRequest}>
      <Select label="Order target" value={targetKey} options={targets.map((candidate) => ({ value: candidate.key, label: candidate.label }))} disabled={Boolean(initial)} invalid={false} onChange={setTargetKey} />
      <Select label="Order direction" value={direction} options={[{ value: "ASC", label: "Ascending" }, { value: "DESC", label: "Descending" }]} disabled={false} invalid={false} onChange={(value) => setDirection(value as "ASC" | "DESC")} />
      {onMove && count > 1 ? (
        <div className="flex gap-1">
          <Button type="button" variant="ghost" size="sm" disabled={index === 0} onClick={() => onMove(index - 1)}><ArrowUp aria-hidden="true" />Sort earlier</Button>
          <Button type="button" variant="ghost" size="sm" disabled={index === count - 1} onClick={() => onMove(index + 1)}><ArrowDown aria-hidden="true" />Sort later</Button>
        </div>
      ) : null}
      <div className="mt-1 flex items-center justify-between gap-2">
        {onRemove ? <Button type="button" variant="ghost" size="sm" onClick={() => { onRemove(); close(); }}>Remove sort</Button> : <span />}
        <Button type="submit" size="sm" disabled={!target}>{initial ? "Update sort" : "Add sort"}</Button>
      </div>
    </EditorForm>
  );
}

function RowLimitEditor({ value, onChange, close, onRunRequest }: { value: number; onChange: (value: number) => void; close: () => void; onRunRequest: () => void }) {
  const [raw, setRaw] = useState(String(value));
  const parsed = Math.floor(Number(raw));
  const valid = Number.isFinite(parsed) && parsed >= 1 && parsed <= 100_000;
  return (
    <EditorForm dirty={raw !== String(value)} apply={valid ? () => { onChange(parsed); close(); } : null} close={close} onRunRequest={onRunRequest}>
      <Input data-autofocus label="Row limit" type="number" min={1} max={100_000} value={raw} onChange={(event) => setRaw(event.target.value)} error={valid ? undefined : "Use a number from 1 to 100,000."} />
      <div className="flex justify-end"><Button type="submit" size="sm" disabled={!valid}>Apply limit</Button></div>
    </EditorForm>
  );
}

const ROW_LABEL_CLASS = "type-control text-foreground-secondary";

/** One part of the question: its name on the left, its tokens on the right. */
function QuestionRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-start gap-3 py-1">
      <span className={`${ROW_LABEL_CLASS} flex h-8 w-[88px] shrink-0 items-center`}>{label}</span>
      <div className="flex min-h-8 min-w-0 flex-1 flex-wrap items-center gap-x-1.5 gap-y-1">{children}</div>
    </div>
  );
}

function ListHint({ children }: { children: ReactNode }) {
  return <span className="type-control text-foreground-secondary">{children}</span>;
}

/** An empty part shows its add control as words; a filled one keeps only "+" beside its tokens. */
function AddLabel({ empty, visible, hidden }: { empty: boolean; visible: string; hidden: string }) {
  return <><Plus className="h-3.5 w-3.5" aria-hidden="true" />{empty ? <>{visible}{hidden ? <span className="sr-only">{hidden}</span> : null}</> : <span className="sr-only">{visible}{hidden}</span>}</>;
}

/**
 * The Semantic composer names each part of the question on its own row: Metrics, Group by, Filters,
 * then the time range, sort, and row limit. Each token opens a panel to change it.
 */
export function SemanticQuestion({
  workspaceId,
  reportingTimeZone,
  draft,
  onDraftChange,
  metrics,
  metricsLoading,
  metricsError,
  modelFilter,
  onClearModelFilter,
  visibleMetricNames,
  semanticModels,
  semanticModelsLoading,
  semanticModelsError,
  onRetrySemanticModels,
  groupBys,
  dimensionFilterTypes,
  excludedSemanticModels,
  groupBysLoading,
  groupBysError,
  unavailable,
  connector,
  onChooseMetric,
  metricSwap,
  canEditDefinitions,
  listsOpen,
  onRunRequest,
  partitionRepair,
  onPartitionRepair,
}: SemanticQuestionProps & {
  /** The Metrics and Group by lists are open below, so those parts pick there instead of in a panel. */
  listsOpen: boolean;
  /** Runs the question once a panel's ⌘↵ has applied its edit. */
  onRunRequest: () => void;
}) {
  const update = (patch: Partial<SemanticDraft>) => onDraftChange({ ...draft, ...patch });
  const metricSelected = draft.metrics.length > 0;
  const filterLoading = semanticModelsLoading || groupBysLoading;
  const filterError = semanticModelsError ?? groupBysError;
  const dimensions = filterLoading || filterError ? [] : filterDimensions(groupBys, semanticModels);
  const partitionHints = semanticModelsLoading || semanticModelsError ? [] : queryPartitionHints(draft, connector.selectedId, metrics, semanticModels);
  const metadataMissing = !filterLoading && !filterError && groupBys.some((groupBy) => groupBy.kind === "DIMENSION"
    && !dimensions.some((dimension) => dimension.semanticModelName === groupBy.semanticModelName && dimension.name === groupBy.name));
  const groupByUnavailable = (groupBy: SemanticGroupBy) => unavailable.groupBys.some((candidate) => groupByKey(candidate) === groupByKey(groupBy));
  const filterUnavailable = (filter: SemanticFilter) => unavailable.filters.some((candidate) => JSON.stringify(candidate) === JSON.stringify(filter));
  const mismatchedFilters = filterLoading || filterError ? [] : draft.filters.filter((filter) => filterComparisonMismatch(filter, dimensionFilterTypes));
  const questionRef = useRef<HTMLDivElement>(null);
  // A removed token takes its focus with it; focus lands on the token that took its place, or "+".
  const focusAfterRemoval = (group: string, index: number) => {
    window.requestAnimationFrame(() => {
      const groupElement = questionRef.current?.querySelector(`[data-token-group="${group}"]`);
      const listSearchId = !listsOpen ? null : group === "metrics" ? METRIC_LIST_SEARCH_ID : group === "group-bys" ? GROUP_BY_LIST_SEARCH_ID : null;
      const tokens = groupElement?.querySelectorAll<HTMLElement>(listSearchId ? "[data-question-remove]" : "[data-question-token]");
      const fallback = listSearchId ? document.getElementById(listSearchId) : groupElement?.querySelector<HTMLElement>("[data-question-add]");
      (tokens?.[index] ?? fallback)?.focus();
    });
  };
  const toggleMetric = (metricName: string) => {
    const index = draft.metrics.indexOf(metricName);
    onChooseMetric(metricName);
    if (index >= 0) focusAfterRemoval("metrics", index);
  };
  const removeGroupBy = (index: number) => {
    update({ groupBys: draft.groupBys.filter((_, itemIndex) => itemIndex !== index) });
    focusAfterRemoval("group-bys", index);
  };
  const removeFilter = (index: number) => {
    update({ filters: draft.filters.filter((_, itemIndex) => itemIndex !== index) });
    focusAfterRemoval("filters", index);
  };
  const removeOrder = (index: number) => {
    update({ orderBy: draft.orderBy.filter((_, itemIndex) => itemIndex !== index) });
    focusAfterRemoval("sorts", index);
  };
  const replaceGroupBy = (previous: SemanticGroupBy | null, next: SemanticGroupBy) => {
    if (!previous) {
      onDraftChange(withGroupByToggled(draft, next));
      return;
    }
    if (draft.groupBys.some((candidate) => groupByKey(candidate) === groupByKey(next))) return;
    update({ groupBys: draft.groupBys.map((candidate) => groupByKey(candidate) === groupByKey(previous) ? next : candidate) });
  };
  const orderTargets = [
    ...draft.metrics.map((metricName) => ({ key: `metric:${metricName}`, label: metricLabel(metricName, metrics), target: { kind: "METRIC" as const, metricName } })),
    ...draft.groupBys.map((groupBy) => ({ key: `group:${groupByKey(groupBy)}`, label: groupByTokenLabel(groupBy, semanticModels), target: { kind: "GROUP_BY" as const, groupBy } })),
  ];
  const unorderedTargets = orderTargets.filter((candidate) => !draft.orderBy.some((order) => JSON.stringify(order.target) === JSON.stringify(candidate.target)));
  const retryFilters = semanticModelsError ? onRetrySemanticModels : null;
  const problemContext = semanticProblemContext(workspaceId, connector, metrics, semanticModels);
  const brokenGroupByModels = excludedModelLabels(unavailable.groupBys.flatMap((groupBy) => groupBy.kind === "METRIC_TIME" ? [] : [groupBy.semanticModelName]), excludedSemanticModels, semanticModels);
  const brokenFilterModels = excludedModelLabels(unavailable.filters.map((filter) => filter.dimension.semanticModelName), excludedSemanticModels, semanticModels);
  const metricPicker = (searchId: string | undefined) => (
    <MetricPicker searchId={searchId} workspaceId={workspaceId} metrics={metrics} models={semanticModels} connectors={connector.all} loading={metricsLoading} error={metricsError} selected={draft.metrics} visibleMetricNames={visibleMetricNames} modelFilter={modelFilter} onClearModelFilter={onClearModelFilter} onToggle={toggleMetric} />
  );

  return (
    <div ref={questionRef} className="divide-y divide-border-light" aria-label="Query">
      <QuestionRow label="Metrics">
        <span className="contents" data-token-group="metrics">
          {draft.metrics.map((metricName) => {
            const problem = metricRunProblem(metricName, metrics, semanticModels, connector.all.map((candidate) => candidate.id));
            const label = problem
              ? <span className="inline-flex min-w-0 items-center gap-1" title={metricRunProblemText(problem)}><Ban className="h-3.5 w-3.5 shrink-0" aria-hidden="true" /><span className="truncate">{metricLabel(metricName, metrics)}</span><span className="sr-only">. {metricRunProblemText(problem)}</span></span>
              : metricLabel(metricName, metrics);
            return (
              <span key={metricName} className="inline-flex min-w-0 max-w-full items-center">
                {listsOpen ? <span className={STATIC_TOKEN_CLASS}>{problem ? label : <span className="truncate">{label}</span>}</span> : (
                  <TokenPopover label={problem ? label : <span className="truncate">{label}</span>} panelLabel="Metrics" className={TOKEN_CLASS} panelWidth={400}>
                    {() => metricPicker(undefined)}
                  </TokenPopover>
                )}
                <RemoveButton label={`Remove ${metricLabel(metricName, metrics)}`} onClick={() => toggleMetric(metricName)} />
              </span>
            );
          })}
          {metricSwap ? (
            <span className="type-status inline-flex min-w-0 items-center gap-1 text-foreground">
              <span className="truncate">{metricSwapMessage(metricSwap, metrics, true)}</span>
              <Button type="button" variant="ghost" size="sm" className="shrink-0" onClick={() => {
                const chosen = metricSwap.chosen;
                metricSwap.onUndo();
                window.requestAnimationFrame(() => {
                  const row = document.querySelector<HTMLElement>(`[data-metric-row="${CSS.escape(chosen)}"]`);
                  (row ?? questionRef.current?.querySelector<HTMLElement>("[data-question-remove], [data-question-add]"))?.focus();
                });
              }}>Undo</Button>
            </span>
          ) : null}
          {listsOpen ? (
            metricSelected ? null : <ListHint>Choose from the list below</ListHint>
          ) : (
            <TokenPopover addControl label={<AddLabel empty={!metricSelected} visible={metricSelected ? "Add" : "Choose a metric"} hidden={metricSelected ? " metric" : ""} />} panelLabel="Metrics" className={ADD_CLASS} panelWidth={400}>
              {() => metricPicker(undefined)}
            </TokenPopover>
          )}
        </span>
      </QuestionRow>

      <QuestionRow label="Group by">
        <span className="contents" data-token-group="group-bys">
          {draft.groupBys.map((groupBy, index) => {
            const label = groupByTokenLabel(groupBy, semanticModels);
            const isUnavailable = groupByUnavailable(groupBy);
            const content = <><span className="truncate">{label}</span>{isUnavailable ? <span className="sr-only">, not available for the chosen metrics</span> : null}</>;
            return (
              <span key={groupByKey(groupBy)} className="inline-flex min-w-0 max-w-full items-center">
                {listsOpen ? <span className={isUnavailable ? UNAVAILABLE_TOKEN_CLASS : STATIC_TOKEN_CLASS}>{content}</span> : (
                  <TokenPopover label={<span className="truncate">{label}</span>} accessibleName={isUnavailable ? `${label}, not available for the chosen metrics` : undefined} panelLabel="Group by" className={isUnavailable ? UNAVAILABLE_TOKEN_CLASS : TOKEN_CLASS} panelWidth={320}>
                    {(close) => <GroupByPicker searchId={undefined} groupBys={groupBys} excluded={excludedSemanticModels} problemContext={problemContext} canEditDefinitions={canEditDefinitions} loading={groupBysLoading} error={groupBysError} metricSelected={metricSelected} connectorChosen={Boolean(connector.selectedId)} selected={draft.groupBys} models={semanticModels} current={groupBy} onPick={(next) => { replaceGroupBy(groupBy, next); close(); }} />}
                  </TokenPopover>
                )}
                <RemoveButton label={`Remove ${label}`} onClick={() => removeGroupBy(index)} />
              </span>
            );
          })}
          {listsOpen ? (
            draft.groupBys.length === 0 ? <ListHint>None</ListHint> : null
          ) : (
            <TokenPopover addControl label={<AddLabel empty={draft.groupBys.length === 0} visible="Add" hidden=" group by" />} panelLabel="Group by" className={ADD_CLASS} panelWidth={340}>
              {() => <GroupByPicker searchId={undefined} groupBys={groupBys} excluded={excludedSemanticModels} problemContext={problemContext} canEditDefinitions={canEditDefinitions} loading={groupBysLoading} error={groupBysError} metricSelected={metricSelected} connectorChosen={Boolean(connector.selectedId)} selected={draft.groupBys} models={semanticModels} current={null} onPick={(next) => replaceGroupBy(null, next)} />}
            </TokenPopover>
          )}
        </span>
        {unavailable.groupBys.length > 0 ? <UnavailableHint brokenModelLabels={brokenGroupByModels} connectorName={connector.compatible.find((candidate) => candidate.id === connector.selectedId)?.name ?? null} /> : null}
      </QuestionRow>

      <QuestionRow label="Filters">
        <span className="contents" data-token-group="filters">
          {draft.filters.map((filter, index) => {
            const isUnavailable = filterUnavailable(filter);
            const comparisonMismatch = mismatchedFilters.includes(filter);
            const label = filterTokenLabel(filter, dimensions, semanticModels, filterLoading, isUnavailable);
            return (
              <span key={`${JSON.stringify(filter)}-${index}`} className="inline-flex min-w-0 max-w-full items-center">
                {index > 0 ? <span className="type-status mr-1.5 shrink-0 text-foreground-secondary">and</span> : null}
                <TokenPopover label={<span className="truncate">{label}</span>} accessibleName={isUnavailable ? `${label}, not available for the chosen metrics` : comparisonMismatch ? `${label}, comparison type doesn’t match the column` : undefined} panelLabel="Filter" className={isUnavailable || comparisonMismatch ? UNAVAILABLE_TOKEN_CLASS : TOKEN_CLASS} panelWidth={340}>
                  {(close) => (
                    <FilterPanel metricSelected={metricSelected} connectorChosen={Boolean(connector.selectedId)} dimensions={dimensions} models={semanticModels} excluded={excludedSemanticModels} problemContext={problemContext} canEditDefinitions={canEditDefinitions} loading={filterLoading} error={filterError} metadataMissing={metadataMissing} onRetry={retryFilters}>
                      <FilterEditor reportingTimeZone={reportingTimeZone} dimensions={dimensions} dimensionFilterTypes={dimensionFilterTypes} models={semanticModels} initial={filter} others={draft.filters.filter((_, itemIndex) => itemIndex !== index)} close={close} onRunRequest={onRunRequest}
                        onSave={(next) => update({ filters: draft.filters.map((candidate, itemIndex) => itemIndex === index ? next : candidate) })}
                        onRemove={() => removeFilter(index)} />
                    </FilterPanel>
                  )}
                </TokenPopover>
                <RemoveButton label={`Remove filter ${label}`} onClick={() => removeFilter(index)} />
              </span>
            );
          })}
          <TokenPopover openRequest={partitionRepair?.kind === "FILTER" ? partitionRepair.id : undefined} addControl label={<AddLabel empty={draft.filters.length === 0} visible="Add" hidden=" filter" />} panelLabel="Filter" className={ADD_CLASS} panelWidth={340}>
            {(close) => (
              <FilterPanel metricSelected={metricSelected} connectorChosen={Boolean(connector.selectedId)} dimensions={dimensions} models={semanticModels} excluded={excludedSemanticModels} problemContext={problemContext} canEditDefinitions={canEditDefinitions} loading={filterLoading} error={filterError} metadataMissing={metadataMissing} onRetry={retryFilters}>
                <FilterEditor reportingTimeZone={reportingTimeZone} dimensions={dimensions} dimensionFilterTypes={dimensionFilterTypes} models={semanticModels} initial={null} initialDimension={partitionRepair?.kind === "FILTER" ? { semanticModelName: partitionRepair.semanticModelName, name: partitionRepair.dimensionName } : undefined} others={draft.filters} close={close} onRemove={null} onRunRequest={onRunRequest}
                  onSave={(next) => update({ filters: [...draft.filters, next] })} />
              </FilterPanel>
            )}
          </TokenPopover>
        </span>
        {unavailable.filters.length > 0 ? <UnavailableHint brokenModelLabels={brokenFilterModels} connectorName={connector.compatible.find((candidate) => candidate.id === connector.selectedId)?.name ?? null} /> : null}
        {mismatchedFilters.length > 0 ? <span className="type-status text-error">A filter’s comparison type doesn’t match its column. Edit or remove it to run.</span> : null}
      </QuestionRow>

      <QuestionRow label="Time range">
        <TokenPopover openRequest={partitionRepair?.kind === "TIME_RANGE" ? partitionRepair.id : undefined} label={timeRangeTokenLabel(draft.metricTimeRange)} panelLabel="Metric time range" className={TOKEN_CLASS} panelWidth={320}>
          {(close) => <TimeRangeEditor value={draft.metricTimeRange} onChange={(metricTimeRange) => update({ metricTimeRange })} close={close} onRunRequest={onRunRequest} />}
        </TokenPopover>
        {metricSelected ? (
          <span className="ml-5 inline-flex flex-wrap items-center gap-x-1.5 gap-y-1">
            <span className={ROW_LABEL_CLASS}>Sort</span>
            <span className="contents" data-token-group="sorts">
              {draft.orderBy.map((order, index) => (
                <span key={JSON.stringify(order.target)} className="inline-flex min-w-0 max-w-full items-center">
                  <TokenPopover label={orderTokenContent(order, metrics, semanticModels)} panelLabel="Sort" className={TOKEN_CLASS} panelWidth={300}>
                    {(close) => <OrderEditor targets={orderTargets} initial={order} index={index} count={draft.orderBy.length} close={close} onRunRequest={onRunRequest}
                      onSave={(next) => update({ orderBy: draft.orderBy.map((candidate, itemIndex) => itemIndex === index ? next : candidate) })}
                      onMove={(to) => { update({ orderBy: moveItem(draft.orderBy, index, to) }); close(); }}
                      onRemove={() => removeOrder(index)} />}
                  </TokenPopover>
                  <RemoveButton label={`Remove sort by ${orderTokenName(order, metrics, semanticModels)}`} onClick={() => removeOrder(index)} />
                </span>
              ))}
              {unorderedTargets.length > 0 ? (
                <TokenPopover addControl label={<AddLabel empty={draft.orderBy.length === 0} visible="Add" hidden=" sort" />} panelLabel="Sort" className={ADD_CLASS} panelWidth={300}>
                  {(close) => <OrderEditor targets={unorderedTargets} initial={null} index={draft.orderBy.length} count={draft.orderBy.length} close={close} onMove={null} onRemove={null} onRunRequest={onRunRequest}
                    onSave={(next) => update({ orderBy: [...draft.orderBy, next] })} />}
                </TokenPopover>
              ) : null}
            </span>
          </span>
        ) : null}
        <span className="ml-5 inline-flex items-center gap-x-1.5 whitespace-nowrap">
          <span className={ROW_LABEL_CLASS}>Limit</span>
          <TokenPopover label={<span className="tabular-nums">{formatCount(draft.rowLimit)} rows</span>} panelLabel="Row limit" className={TOKEN_CLASS} panelWidth={240}>
            {(close) => <RowLimitEditor value={draft.rowLimit} onChange={(rowLimit) => update({ rowLimit })} close={close} onRunRequest={onRunRequest} />}
          </TokenPopover>
        </span>
      </QuestionRow>
      {partitionHints.length > 0 && <div className="space-y-2 border-t border-warning/30 bg-warning/5 px-5 py-3 sm:px-8" aria-label="Partition filter guidance">{partitionHints.map((filter) => <PartitionFilterHint key={`${filter.connectorId}:${filter.kind === "SEMANTIC_MODEL" ? filter.semanticModelName : filter.relation.name}`} filter={filter} models={semanticModels} workspaceId={workspaceId} canEdit={canEditDefinitions} onRepair={onPartitionRepair} />)}</div>}
      <p role="status" className="sr-only">{metricSwap ? metricSwapMessage(metricSwap, metrics, false) : ""}</p>
    </div>
  );
}

/** Names the broken models when they are why a selection went away; otherwise the metrics changed. */
function UnavailableHint({ brokenModelLabels, connectorName }: { brokenModelLabels: string[]; connectorName: string | null }) {
  if (brokenModelLabels.length === 0) {
    return <span className="type-status text-error">{connectorName ? `Not available for these metrics on ${connectorName}.` : "Not available for the chosen metrics."} Remove to run.</span>;
  }
  const names = brokenModelLabels.map((label) => `“${label}”`).join(", ");
  return <span className="type-status text-error">{names} {brokenModelLabels.length === 1 ? "has a problem" : "have problems"}. Remove to run.</span>;
}

/**
 * One quiet line under the options when the engine left models out of them, with the models, their errors,
 * and a way to fix them behind Details. It sits below the list so it never pushes options down.
 */
function ExcludedModelsNote({ excluded, context, subject, canEditDefinitions }: {
  excluded: SemanticExcludedModel[];
  context: SemanticProblemContext;
  subject: "group bys" | "dimensions";
  canEditDefinitions: boolean;
}) {
  const [open, setOpen] = useState(false);
  const detailsId = useId();
  const note = excludedModelsSummary(excluded, context, subject);
  if (!note) return null;
  return (
    <div className="type-status shrink-0 border-t border-border px-3 py-2 text-foreground-secondary">
      <p className="flex items-start gap-1.5">
        <Info className="mt-px h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        <span className="min-w-0 flex-1">{note.summary}</span>
        <button type="button" aria-expanded={open} aria-controls={detailsId} onClick={() => setOpen((shown) => !shown)} className="shrink-0 text-foreground underline underline-offset-4">
          {open ? "Hide details" : "Details"}
        </button>
      </p>
      {open ? (
        <div id={detailsId} className="mt-1.5 space-y-1.5 pl-5">
          <ul className="space-y-1">
            {note.models.map((model) => (
              <li key={model.label}>
                <span className="text-foreground">{model.label}</span>: {model.reason}
                {canEditDefinitions && model.href ? <> <Link href={model.href} className="whitespace-nowrap text-foreground underline underline-offset-2 hover:text-label">Open model</Link></> : null}
              </li>
            ))}
          </ul>
          {canEditDefinitions ? null : <p>Ask an editor to fix {note.models.length === 1 ? "it" : "them"}.</p>}
        </div>
      ) : null}
    </div>
  );
}

/** A rejected group-by or filter request, explained the same way as a rejected run. */
function SemanticRequestProblem({ error, context, fallbackTitle, onRetry, retryLabel }: {
  error: ExploreErrorPresentation;
  context: SemanticProblemContext;
  fallbackTitle: string;
  onRetry: (() => void) | null;
  retryLabel: string;
}) {
  const guidance = semanticProblemGuidance(error, context);
  if (guidance) return <ExploreFailureNotice failure={guidance} className="px-3 py-3" />;
  // A server reason code is not a sentence; show it as the reference instead.
  const codesOnly = /^[A-Z][A-Z0-9_]*(?:,[A-Z][A-Z0-9_]*)*$/.test(error.detail);
  const reference = problemReference(codesOnly ? error.detail : error.code, error.traceId);
  return <StateBlock kind="error" layout="inline" title={codesOnly ? fallbackTitle : error.detail} description={reference ?? undefined} action={onRetry ? <Button type="button" variant="secondary" size="sm" onClick={onRetry}>{retryLabel}</Button> : undefined} />;
}

export function SemanticConnectorPart({ connector }: { connector: SemanticConnectorChoice }) {
  if (connector.resolving) return <span className="type-control text-foreground-secondary">Finding connector…</span>;
  if (connector.compatible.length === 0) {
    return <span className="type-control max-w-[280px] text-error" role="status">{connector.problem ?? "No connector has source tables for all of these metrics."}</span>;
  }
  if (connector.compatible.length === 1) {
    const only = connector.compatible[0] as Connector;
    return <ReadOnlyConnector connector={only} />;
  }
  return (
    <CompactConnectorSelect
      connectors={connector.compatible}
      value={connector.selectedId}
      onChange={connector.onSelect}
      loading={false}
      error={null}
      onRetry={() => undefined}
    />
  );
}

function BrowserPanel({ title, meta, children }: { title: string; meta: string; children: ReactNode }) {
  return (
    <section aria-label={`${title} list`} className="flex h-[340px] min-h-0 min-w-0 flex-col overflow-hidden rounded-[4px] border border-border lg:h-auto">
      <div className="flex min-h-10 items-center justify-between gap-3 border-b border-border px-3">
        <p className="type-group text-label">{title}</p>
        <span className="type-status tabular-nums text-foreground-secondary">{meta}</span>
      </div>
      {children}
    </section>
  );
}

/** The question's Metrics and Group by lists, laid open while no run is in view. */
export function SemanticBrowser({
  workspaceId,
  draft,
  onDraftChange,
  metrics,
  metricsLoading,
  metricsError,
  modelFilter,
  onClearModelFilter,
  visibleMetricNames,
  semanticModels,
  groupBys,
  excludedSemanticModels,
  groupBysLoading,
  groupBysError,
  connector,
  onChooseMetric,
  canEditDefinitions,
}: SemanticQuestionProps) {
  const listed = metrics.filter((metric) => visibleMetricNames.includes(metric.name));
  const connectorIds = connector.all.map((candidate) => candidate.id);
  const cannotRun = listed.filter((metric) => metricRunProblem(metric.name, metrics, semanticModels, connectorIds)).length;
  const metricsMeta = `${draft.metrics.length} selected · ${listed.length} ${listed.length === 1 ? "metric" : "metrics"}${cannotRun > 0 ? ` · ${cannotRun} can’t run` : ""}`;
  const groupByRows = new Set(groupBys.map(groupByIdentity)).size;
  return (
    <div className="grid gap-4 px-5 py-5 sm:px-8 sm:pb-6 lg:min-h-[360px] lg:flex-1 lg:grid-cols-2 lg:grid-rows-[minmax(0,1fr)]">
      <BrowserPanel title="Metrics" meta={metricsMeta}>
        <MetricPicker searchId={METRIC_LIST_SEARCH_ID} workspaceId={workspaceId} metrics={metrics} models={semanticModels} connectors={connector.all} loading={metricsLoading} error={metricsError} selected={draft.metrics} visibleMetricNames={visibleMetricNames} modelFilter={modelFilter} onClearModelFilter={onClearModelFilter} onToggle={onChooseMetric} />
      </BrowserPanel>
      <BrowserPanel title="Group by" meta={`${draft.groupBys.length} selected · ${groupByRows} available`}>
        <GroupByPicker searchId={GROUP_BY_LIST_SEARCH_ID} groupBys={groupBys} excluded={excludedSemanticModels} problemContext={semanticProblemContext(workspaceId, connector, metrics, semanticModels)} canEditDefinitions={canEditDefinitions} loading={groupBysLoading} error={groupBysError} metricSelected={draft.metrics.length > 0} connectorChosen={Boolean(connector.selectedId)} selected={draft.groupBys} models={semanticModels} current={null} onPick={(groupBy) => onDraftChange(withGroupByToggled(draft, groupBy))} />
      </BrowserPanel>
    </div>
  );
}
