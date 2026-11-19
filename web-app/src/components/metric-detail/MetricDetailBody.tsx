import type { ReactNode } from "react";
import { ArrowUpRight } from "lucide-react";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { SemanticGroupBy } from "@/api/explore";
import type { Connector } from "@/lib/types/connector";
import { SourcePartitionHints } from "@/components/semantics/SourcePartitionHints";
import { metricCatalogFormula, metricInputs } from "@/components/semantics/semantic-insights";
import { AGGREGATION_LABELS } from "@/components/semantic-model-detail/semantic-vocabulary";
import { StateBlock } from "@/components/ui/StateBlock";
import { DefinitionRows, DetailPanel, StoredExpression } from "@/components/resource-detail/DetailPanel";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";
import { Link } from "@/lib/navigation";
import type { Metric, MetricFilter, MetricRef, PeriodAggregation, SimpleMetricTypeParams, DerivedMetricTypeParams, CumulativeMetricTypeParams, ConversionMetricTypeParams } from "@/lib/types/metric";
import { TIME_GRANULARITY_LABELS } from "@/lib/types/semantic-model";
import { webPaths } from "@/lib/web-paths";
import { FILTER_OPERATOR_LABELS, filterValuesText } from "@/components/metric-detail/filter-operator-labels";

interface MetricDetailBodyProps {
  workspaceId: string;
  metric: Metric;
  relationsPending: boolean;
  relationsError: string | null;
  metrics?: Metric[];
  models?: SemanticModelResponse[];
  connectors?: readonly Pick<Connector, "id" | "name">[];
  groupBys?: SemanticGroupBy[];
  slicePending?: boolean;
  sliceError?: string | null;
}

export function MetricDetailBody({ workspaceId, metric, relationsPending, relationsError, metrics = [metric], models = [], connectors = [], groupBys = [], slicePending = false, sliceError = null }: MetricDetailBodyProps) {
  const dimensions = groupBys.filter((groupBy) => groupBy.kind === "DIMENSION");
  const distinctDimensions = dimensions.filter((groupBy, index) => dimensions.findIndex((candidate) => candidate.semanticModelName === groupBy.semanticModelName && candidate.name === groupBy.name) === index);
  const sliceModels = [...new Set(distinctDimensions.map((groupBy) => groupBy.semanticModelName))];
  return <div className="min-w-0 space-y-6">
    <SourcePartitionHints sources={metric.sourcePartitionInfo ?? []} models={models} connectors={connectors} />
    <DetailPanel id="metric-calculation-heading" title="How it's calculated">
      {relationsError ? <StateBlock kind="error" layout="inline" title="Calculation details could not be loaded" description={relationsError} /> : relationsPending ? <StateBlock kind="loading" layout="inline" title="Loading calculation details" /> : <CalculationTree workspaceId={workspaceId} metric={metric} metrics={metrics} models={models} ancestors={[]} />}
    </DetailPanel>
    <DefinitionRows rows={[{ term: "Display", value: metric.display ? `${metric.display.style.charAt(0)}${metric.display.style.slice(1).toLowerCase()}${metric.display.style === "CURRENCY" ? ` · ${metric.display.currency}` : metric.display.style === "NUMBER" && metric.display.unit ? ` · ${metric.display.unit}` : ""} · ${metric.display.decimals} decimals` : "Default" }]} />
    {((metric.filters?.length ?? 0) > 0 || metric.timeGranularity) && <DetailPanel id="metric-conditions-heading" title="Applied to every query"><MetricConditions metric={metric} /></DetailPanel>}
    <DetailPanel id="metric-slice-heading" title="Slice by">
      {slicePending ? <StateBlock kind="loading" layout="inline" title="Loading dimensions" /> : sliceError ? <StateBlock kind="error" layout="inline" title="Dimensions could not be loaded" description={sliceError} /> : sliceModels.length === 0 ? <p className="type-body text-foreground-secondary">No dimensions are available for this metric.</p> : <div className="divide-y divide-border-light">{sliceModels.map((modelName) => <div key={modelName} className="py-3 first:pt-0 last:pb-0"><h3 className="type-group mb-2 text-label">{models.find((model) => model.name === modelName)?.label || modelName}</h3><ul className="flex flex-wrap gap-x-5 gap-y-2">{distinctDimensions.filter((groupBy) => groupBy.semanticModelName === modelName).map((groupBy) => {
        const dimension = models.find((model) => model.name === modelName)?.dimensions.find((item) => item.name === groupBy.name);
        const label = dimension?.label || groupBy.name;
        const defaultGroupBy = dimensions.find((candidate) => candidate.semanticModelName === modelName && candidate.name === groupBy.name && candidate.granularity === dimension?.granularity?.toUpperCase()) ?? groupBy;
        const search = new URLSearchParams({ metric: metric.name, dimensionModel: groupBy.semanticModelName, dimension: groupBy.name });
        if (defaultGroupBy.granularity) search.set("granularity", defaultGroupBy.granularity);
        return <li key={groupBy.name}><Link href={`${webPaths.explore(workspaceId)}?${search}`} aria-label={`Explore by ${label}`} className="type-body inline-flex items-baseline gap-1 text-foreground"><span className={SEMANTIC_LINK_CLASS}>{label}</span><ArrowUpRight className="h-3.5 w-3.5 translate-y-0.5 text-foreground-secondary" aria-hidden="true" /></Link></li>;
      })}</ul></div>)}</div>}
    </DetailPanel>
  </div>;
}

function CalculationTree({ workspaceId, metric, metrics, models, ancestors }: { workspaceId: string; metric: Metric; metrics: Metric[]; models: SemanticModelResponse[]; ancestors: string[] }) {
  if (ancestors.includes(metric.name)) return <p className="type-body text-error">Circular reference: {metric.name}</p>;
  const inputs = metricInputs(metric);
  let calculation: ReactNode = null;
  if (metric.type === "simple") {
    const params = metric.typeParams as SimpleMetricTypeParams;
    const model = models.find((item) => item.name === params.semanticModelName);
    const aggregate = model?.measures.find((item) => item.name === params.measure);
    const countedBy = aggregate?.aggregationTimeDimension ?? model?.aggregationTimeDimension;
    calculation = <DefinitionRows rows={[
      { term: "Aggregation", value: aggregate ? AGGREGATION_LABELS[aggregate.aggregation] : <span className="text-error">Missing aggregate: {params.measure}</span> },
      { term: "Expression", value: <StoredExpression expression={aggregate?.expression ?? params.measure} /> },
      { term: "From", value: model ? <Link href={webPaths.semanticModel(workspaceId, model.id)} className={SEMANTIC_LINK_CLASS}>{model.label || model.name}</Link> : <span className="text-error">Missing model: {params.semanticModelName}</span> },
      ...(countedBy ? [{ term: "Metric time", value: <code className="type-mono">{countedBy}</code> }] : []),
    ]} />;
  } else if (metric.type === "derived") calculation = <DefinitionRows rows={[{ term: "Formula", value: <StoredExpression expression={(metric.typeParams as DerivedMetricTypeParams).expression} /> }]} />;
  else if (metric.type === "ratio") calculation = <DefinitionRows rows={[{ term: "Formula", value: <StoredExpression expression={metricCatalogFormula(metric, models)} /> }]} />;
  else if (metric.type === "cumulative") {
    const params = metric.typeParams as CumulativeMetricTypeParams;
    calculation = <DefinitionRows rows={[
      { term: "Window", value: params.window ?? (params.grainToDate ? `${TIME_GRANULARITY_LABELS[params.grainToDate]} to date` : "All time") },
      ...(params.periodAggregation ? [{ term: "Period aggregation", value: PERIOD_AGGREGATION_LABELS[params.periodAggregation] }] : []),
    ]} />;
  } else {
    const params = metric.typeParams as ConversionMetricTypeParams;
    calculation = <DefinitionRows rows={[
      { term: "Calculation", value: params.calculation === "conversion_rate" ? "Conversion rate" : "Conversions" },
      { term: "Key", value: <code className="type-mono [overflow-wrap:anywhere]">{params.entity}</code> },
      ...(params.window ? [{ term: "Within", value: params.window }] : []),
      ...(params.constantProperties?.length ? [{ term: "Same value", value: <div className="space-y-1">{params.constantProperties.map((property, index) => <p key={index}><code className="type-mono [overflow-wrap:anywhere]">{property.baseProperty}</code> <span className="text-foreground-secondary">=</span> <code className="type-mono [overflow-wrap:anywhere]">{property.conversionProperty}</code></p>)}</div> }] : []),
    ]} />;
  }
  return <div className="space-y-4">{calculation}{ancestors.length > 0 && <MetricConditions metric={metric} />}{inputs.length > 0 && <ul className="divide-y divide-border">{inputs.map(({ ref, role }, index) => {
    const input = metrics.find((item) => item.name === ref.metricName);
    return <li key={`${ref.metricName}-${index}`} className="py-4 first:pt-0 last:pb-0">
      <div className="mb-3">
        <span className="type-group mb-1 block text-label">{role}</span>
        <h3 className="type-item text-heading">{input ? <Link href={webPaths.metric(workspaceId, input.id)} className={SEMANTIC_LINK_CLASS}>{input.label || input.name}</Link> : <span className="text-error">Missing metric: {ref.metricName}</span>}</h3>
      </div>
      {input && <div className="space-y-3 pl-4"><InputQualifiers input={ref} /><CalculationTree workspaceId={workspaceId} metric={input} metrics={metrics} models={models} ancestors={[...ancestors, metric.name]} /></div>}
    </li>;
  })}</ul>}</div>;
}

function InputQualifiers({ input }: { input: MetricRef }) {
  if (!input.alias && !input.offsetWindow && !input.offsetToGrain && !input.filter) return null;
  return <div className="space-y-1 type-meta text-foreground-secondary">{input.alias && <p>Alias: <code className="type-mono whitespace-pre-wrap [overflow-wrap:anywhere]">{input.alias}</code></p>}{input.offsetWindow && <p>Offset: {input.offsetWindow}</p>}{input.offsetToGrain && <p>Offset to start of {TIME_GRANULARITY_LABELS[input.offsetToGrain].toLowerCase()}</p>}{input.filter && <FilterExpression filter={input.filter} />}</div>;
}

function MetricConditions({ metric }: { metric: Metric }) {
  const rows = [
    ...(metric.filters ?? []).map((filter, index) => ({ term: index === 0 ? "Filter" : "And", value: <FilterExpression filter={filter} /> })),
    ...(metric.timeGranularity ? [{ term: "Default grain", value: TIME_GRANULARITY_LABELS[metric.timeGranularity] }] : []),
  ];
  return rows.length ? <DefinitionRows rows={rows} /> : null;
}

const PERIOD_AGGREGATION_LABELS: Record<PeriodAggregation, string> = { first: "First", last: "Last", average: "Average" };

function FilterExpression({ filter }: { filter: MetricFilter }) {
  if ("and" in filter) {
    return (
      <code className="inline-flex flex-wrap items-center gap-1.5 type-mono text-foreground [overflow-wrap:anywhere]">
        ({filter.and.map((child, index) => <FilterExpression key={index} filter={child} />).reduce<ReactNode[]>((parts, child, index) => index === 0 ? [child] : [...parts, <span key={`and-${index}`} className="font-sans text-foreground-secondary">and</span>, child], [])})
      </code>
    );
  }
  if ("or" in filter) {
    return (
      <code className="inline-flex flex-wrap items-center gap-1.5 type-mono text-foreground [overflow-wrap:anywhere]">
        ({filter.or.map((child, index) => <FilterExpression key={index} filter={child} />).reduce<ReactNode[]>((parts, child, index) => index === 0 ? [child] : [...parts, <span key={`or-${index}`} className="font-sans text-foreground-secondary">or</span>, child], [])})
      </code>
    );
  }
  if ("not" in filter) {
    return (
      <code className="inline-flex flex-wrap items-center gap-1.5 type-mono text-foreground [overflow-wrap:anywhere]">
        <span className="font-sans text-foreground-secondary">not</span> <FilterExpression filter={filter.not} />
      </code>
    );
  }
  return (
    <code className="inline-flex flex-wrap items-center gap-1.5 type-mono text-foreground [overflow-wrap:anywhere]">
      <span>{`${filter.dimension.semanticModelName}.${filter.dimension.name}`}</span>
      {filter.dimension.granularity && (
        <span className="font-sans text-foreground-secondary">by {TIME_GRANULARITY_LABELS[filter.dimension.granularity].toLowerCase()}</span>
      )}
      <span className="font-sans text-foreground-secondary">{FILTER_OPERATOR_LABELS[filter.operator]}</span>
      {filter.values.length > 0 && <span>{filterValuesText(filter.operator, filter.values)}</span>}
    </code>
  );
}
