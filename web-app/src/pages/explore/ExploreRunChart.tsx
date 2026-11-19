import { useMemo, useState } from "react";
import { LineChart } from "lucide-react";
import type { ExploreResult, SemanticQuerySpec } from "@/api/explore";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { DashboardChartWidget } from "@/components/dashboard/DashboardChartWidget";
import { StateBlock } from "@/components/ui/StateBlock";
import { validateDashboardResult } from "@/features/dashboard/dashboard-result";
import type { Metric } from "@/lib/types/metric";
import { EXPLORE_CHART_SOURCE, exploreChartPlan } from "./chart-plan";
import { columnFractionDigits, formatNumericCell } from "./ExploreResultTable";
import { groupByTokenLabel, metricLabel } from "./run-presentation";
import { timeGrainLabels } from "./time-grain-format";
import { formatMetricNumber, metricNumberFormat } from "@/lib/metric-display";

const NO_VARIABLES = {};
const NO_VARIABLE_NAMES: string[] = [];
const RESULT_QUERY_NAMES = ["result"];
const noop = () => undefined;

export function ExploreRunChart({
  runId,
  reportingTimeZone,
  query,
  result,
  totalRowCount,
  complete,
  rowLimitReached,
  metrics,
  models,
}: {
  runId: string;
  reportingTimeZone: string | null;
  query: SemanticQuerySpec;
  result: ExploreResult;
  totalRowCount: number;
  complete: boolean;
  rowLimitReached: boolean;
  metrics: Metric[];
  models: SemanticModelResponse[];
}) {
  const plan = useMemo(
    () => exploreChartPlan({ query, result, totalRowCount, complete, rowLimitReached }),
    [complete, query, result, rowLimitReached, totalRowCount],
  );
  const [selectedMetric, setSelectedMetric] = useState(0);
  const chartResults = useMemo(() => ({
    result: validateDashboardResult({ queryRunId: runId, queryType: "SEMANTIC", reportingTimeZone, columns: result.columns.map((column) => ({ ...column, display: column.display ?? null })), rows: result.rows }),
  }), [reportingTimeZone, result, runId]);

  const drawing = useMemo(() => {
    if (plan.kind !== "ready") return null;
    const metric = plan.metrics[Math.min(selectedMetric, plan.metrics.length - 1)] as (typeof plan.metrics)[number];
    const label = metricLabel(metric.metricName, metrics);
    const xLabel = groupByTokenLabel(plan.xGroupBy, models);
    const xIndex = result.columns.findIndex((column) => column.name === plan.xColumn);
    const xValues = result.rows.map((row) => row[xIndex]).filter((value): value is string => typeof value === "string");
    const metricIndex = result.columns.findIndex((column) => column.name === metric.column);
    const numberFormat = metricNumberFormat(result.columns[metricIndex]?.display);
    const valueLabels = Object.fromEntries(result.rows.map((row) => row[metricIndex])
      .filter((raw): raw is string => typeof raw === "string")
      .map((raw) => [raw, numberFormat ? formatMetricNumber(raw, numberFormat, undefined) : raw]));
    const parameters = {
      shape: plan.shape,
      xColumn: plan.xColumn,
      xLabels: plan.xGranularity ? timeGrainLabels(xValues, plan.xGranularity, undefined) : {},
      seriesColumn: plan.seriesColumn,
      metricColumn: metric.column,
      metricLabel: label,
      xLabel,
      valueLabels,
    };
    return { plan, metric, label, xLabel, parameters, parameterNames: Object.keys(parameters) };
  }, [metrics, models, plan, result, selectedMetric]);

  if (plan.kind === "values") {
    return (
      <section aria-label="Values" className="flex flex-wrap gap-x-12 gap-y-6 px-5 py-6 sm:px-6">
        {plan.metrics.map((metric) => {
          const index = result.columns.findIndex((column) => column.name === metric.column);
          const value = result.rows[0]?.[index] ?? null;
          const numberFormat = metricNumberFormat(result.columns[index]?.display);
          return (
            <div key={metric.metricName} className="min-w-0">
              <p className="type-meta text-foreground-secondary">{metricLabel(metric.metricName, metrics)}</p>
              {value === null ? <p className="type-kpi mt-2 text-[40px] text-foreground-secondary">No value</p> : (
                <p className="type-kpi mt-2 text-[40px] text-heading" title={typeof value === "string" ? value : undefined}>
                  {typeof value === "string" ? numberFormat ? formatMetricNumber(value, numberFormat, undefined)
                    : formatNumericCell(value, undefined, columnFractionDigits([value])) : String(value)}
                </p>
              )}
            </div>
          );
        })}
      </section>
    );
  }
  if (plan.kind === "unavailable" || !drawing) {
    return <div className="p-5 sm:p-6"><StateBlock kind="empty" layout="inline" icon={LineChart} title={plan.kind === "unavailable" ? plan.message : ""} /></div>;
  }
  const { metric, label, xLabel, parameters, parameterNames } = drawing;
  return (
    <section className="flex min-h-0 flex-1 flex-col" aria-label="Chart">
      {plan.metrics.length > 1 ? (
        <div role="radiogroup" aria-label="Metric to chart" className="flex min-h-12 flex-wrap items-center gap-1 px-5 py-2 sm:px-6">
          {plan.metrics.map((candidate, index) => {
            const checked = candidate === metric;
            return (
              <button
                key={candidate.metricName}
                type="button"
                role="radio"
                aria-checked={checked}
                onClick={() => setSelectedMetric(index)}
                className={`type-status h-7 rounded-[4px] border px-2.5 ${checked ? "border-label bg-surface text-foreground" : "border-border text-foreground-secondary hover:bg-surface hover:text-foreground"}`}
              >
                {metricLabel(candidate.metricName, metrics)}
              </button>
            );
          })}
        </div>
      ) : null}
      <div className={`h-[360px] min-h-0 px-3 pb-4 sm:px-4 ${plan.metrics.length > 1 ? "" : "pt-4"}`}>
        <DashboardChartWidget
          accessibleName={`${label} by ${xLabel} chart`}
          moduleSource={EXPLORE_CHART_SOURCE}
          queryNames={RESULT_QUERY_NAMES}
          parameterNames={parameterNames}
          variableNames={NO_VARIABLE_NAMES}
          parameters={parameters}
          variables={NO_VARIABLES}
          variableDefinitions={NO_VARIABLES}
          results={chartResults}
          variableRevision={0}
          onVariablesSet={noop}
          readOnly
        />
      </div>
      <p className="type-status px-5 pb-4 text-foreground-secondary sm:px-6">Rows are drawn as returned — no re-aggregation.</p>
    </section>
  );
}
