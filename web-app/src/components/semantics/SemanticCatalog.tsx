import { useQuery } from "@tanstack/react-query";
import { CircleAlert } from "lucide-react";
import type { Metric } from "@/lib/types/metric";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Connector } from "@/lib/types/connector";
import { lineageAPI } from "@/api/lineage";
import { usePrincipalId } from "@/hooks/use-workspace";
import { queryKeys } from "@/lib/query/keys";
import { Link, useRouter } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { newSemanticModelFromTableHref, tableCatalogHref } from "@/lib/catalog/catalog-links";
import { CatalogTablePicker } from "@/components/catalog/CatalogTablePicker";
import { metricTypeLabel } from "@/components/semantic-model-detail/semantic-vocabulary";
import { metricCatalogFormula, metricCatalogQualifiers, modelsForMetric, referenceProblem, hasMetricSourceConnector } from "@/components/semantics/semantic-insights";
import { StateBlock } from "@/components/ui/StateBlock";
import { fieldTextWithBreaks } from "@/components/semantic-model/SemanticModelFieldsTable";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";

const HEADER_CELL = "type-group py-3 pr-5 text-label";
const IDENTITY_CELL = "block w-full min-w-0 sm:sticky sm:left-0 sm:z-10 sm:w-72 sm:min-w-72 sm:max-w-72 sm:table-cell sm:bg-background sm:py-4 sm:pr-5 sm:group-hover:bg-surface";
const NAME_LINK = "block text-heading underline-offset-4 hover:underline focus-visible:underline";
const REFERENCE_LINK = `block max-w-full break-words [overflow-wrap:anywhere] ${SEMANTIC_LINK_CLASS}`;
const CATALOG_ROW = "group flex flex-wrap items-start gap-x-2 gap-y-2 border-b border-border py-4 hover:bg-surface sm:table-row sm:align-top sm:py-0";
const META_CELL = "type-meta flex min-w-0 max-w-full items-start gap-2 text-foreground-secondary sm:table-cell sm:py-4 sm:pr-5 sm:type-body sm:text-foreground";

export function SemanticCatalog({ workspaceId, view, models, metrics, connectors, connectorsLoaded, query, canEdit, filters = { modelFilter: "", kindFilter: "", statusFilter: "", sort: "name" } }: { workspaceId: string; view: "metrics" | "models"; models: SemanticModelResponse[]; metrics: Metric[]; connectors: Connector[]; connectorsLoaded: boolean; query: string; canEdit: boolean; filters?: { modelFilter: string; kindFilter: string; statusFilter: string; sort: string } }) {
  const router = useRouter();
  const { modelFilter, kindFilter, statusFilter, sort } = filters;
  const status = (metric: Metric) => referenceProblem(metric, metrics, models) ? "broken" : connectorsLoaded && !hasMetricSourceConnector(metric, metrics, models, connectors.map((connector) => connector.id)) ? "connector" : "";
  const needle = query.trim().toLowerCase();
  const shownMetrics = metrics.filter((metric) => (!modelFilter || modelsForMetric(metric, metrics, models).some((model) => model.name === modelFilter)) && (!kindFilter || metric.type === kindFilter) && (!statusFilter || status(metric) === statusFilter) && [metric.label, metric.name, metric.description, metricCatalogFormula(metric, models), ...metricCatalogQualifiers(metric)].join(" ").toLowerCase().includes(needle)).sort((left, right) => sort === "updated" ? right.updatedAt.localeCompare(left.updatedAt) : (left.label || left.name).localeCompare(right.label || right.name));
  const shownModels = models.filter((model) => [model.label, model.name, model.description, ...model.sourceRelations.map((source) => `${source.schema}.${source.relation}`)].join(" ").toLowerCase().includes(needle)).sort((left, right) => sort === "updated" ? right.updatedAt.localeCompare(left.updatedAt) : (left.label || left.name).localeCompare(right.label || right.name));
  const headings = view === "metrics" ? ["Metric", "How it's calculated", "Model", "Used in", "Status"] : ["Model", "Source table", "Row key", "Links to", "Linked metrics", "Status"];

  return (
    <div className="min-h-0 flex-1 overflow-y-auto" data-testid="semantic-catalog">
      {(view === "metrics" ? shownMetrics.length : shownModels.length) === 0 ? (
        <StateBlock
          kind="empty"
          layout="page"
          title={needle || modelFilter || kindFilter || statusFilter ? "No matches" : view === "metrics" ? "No metrics yet" : "No models yet"}
          description={needle ? "Try a different search or filter." : view === "metrics" ? "Describe a table to create your first aggregate metric." : "Choose a Catalog table to describe its columns for analysis."}
          action={canEdit && !needle && view === "metrics" ? <CatalogTablePicker triggerLabel="Describe a table" onSelect={(table) => router.push(newSemanticModelFromTableHref(workspaceId, table))} /> : null}
        />
      ) : (
        <div className="max-w-[1440px] px-8 sm:overflow-x-auto">
          <table className="block w-full text-left type-body sm:table sm:min-w-[960px] sm:table-fixed" role="table" aria-label={view === "metrics" ? "Metrics catalog" : "Models catalog"}>
            <colgroup className="hidden sm:table-column-group">
              <col className="w-72" />
              <col />
              {view === "metrics" ? <><col className="w-40" /><col className="w-28" /><col className="w-40" /></> : <><col className="w-32" /><col className="w-40" /><col className="w-16" /><col className="w-40" /></>}
            </colgroup>
            <thead className="sr-only sm:not-sr-only" role="rowgroup">
              <tr className="border-b border-border" role="row">
                {headings.map((heading, index) => <th key={heading} scope="col" role="columnheader" className={`${HEADER_CELL} ${index === 0 ? "sm:sticky sm:left-0 sm:z-20 sm:bg-background" : ""} ${view === "models" && heading === "Linked metrics" ? "text-right" : ""}`}>{heading}</th>)}
              </tr>
            </thead>
            <tbody className="block sm:table-row-group" role="rowgroup">
              {view === "metrics" ? shownMetrics.map((metric) => (
                <tr key={metric.id} className={CATALOG_ROW} role="row">
                  <td className={IDENTITY_CELL} role="cell">
                    <Link className={NAME_LINK} href={webPaths.metric(workspaceId, metric.id)} aria-label={`${metric.label || metric.name} (${metric.name})`}>
                      <span className="block break-words">{metric.label || metric.name}</span>
                      <span className="type-meta mt-1 block break-words text-foreground-secondary">{metric.name} · {metricTypeLabel(metric.type)}</span>
                    </Link>
                  </td>
                  <td className="block w-full min-w-0 sm:table-cell sm:w-auto sm:py-4 sm:pr-5" role="cell"><code className="type-mono block truncate sm:line-clamp-2 sm:whitespace-pre-wrap sm:[overflow-wrap:anywhere]" title={metricCatalogFormula(metric, models)}>{metricCatalogFormula(metric, models)}</code>{metricCatalogQualifiers(metric).length > 0 && <p className="type-meta mt-1 truncate text-foreground-secondary" title={metricCatalogQualifiers(metric).join("\n")}>{metricCatalogQualifiers(metric).join(" · ")}</p>}</td>
                  <td className={META_CELL} role="cell"><div className="flex flex-wrap gap-x-2 gap-y-1 sm:block sm:space-y-1">{modelsForMetric(metric, metrics, models).map((model) => <Link key={model.id} className={REFERENCE_LINK} href={webPaths.semanticModel(workspaceId, model.id)}>{model.label || model.name}</Link>)}</div></td>
                  <td className={META_CELL} role="cell"><span className="sm:hidden" aria-hidden="true">·</span><UsageCount workspaceId={workspaceId} kind="METRIC" id={metric.id} /></td>
                  <td className={META_CELL} role="cell"><ReferenceStatus problem={referenceProblem(metric, metrics, models)} noConnector={status(metric) === "connector"} /></td>
                </tr>
              )) : shownModels.map((model) => {
                const foreignEntities = model.entities.filter((entity) => entity.type === "foreign");
                const keyNames = model.entities.filter((entity) => ["primary", "unique", "natural"].includes(entity.type)).map((entity) => entity.label || entity.name);
                const modelMetricCount = metrics.filter((metric) => modelsForMetric(metric, metrics, models).some((input) => input.id === model.id)).length;
                return (
                  <tr key={model.id} className={CATALOG_ROW} role="row">
                    <td className={IDENTITY_CELL} role="cell">
                      <Link className={NAME_LINK} href={webPaths.semanticModel(workspaceId, model.id)} aria-label={`${model.label || model.name} (${model.name})`}>
                        <span className="block break-words">{model.label || model.name}</span>
                        <span className="type-meta mt-1 block break-words text-foreground-secondary">{model.name}</span>
                      </Link>
                    </td>
                    <td className="block w-full min-w-0 sm:table-cell sm:w-auto sm:py-4 sm:pr-5" role="cell"><div className="space-y-1 break-words">{model.sourceRelations.map((source) => <Link key={source.connectorId} className={REFERENCE_LINK} href={tableCatalogHref(workspaceId, { connectorId: source.connectorId, catalogName: source.catalog ?? null, schemaName: source.schema, tableName: source.relation })}>{[source.catalog, source.schema, source.relation].filter(Boolean).join(".")}</Link>)}</div></td>
                    <td className={`${META_CELL} w-full sm:w-auto`} role="cell"><span className="shrink-0 sm:hidden" aria-hidden="true">Row key</span><span className="min-w-0 [overflow-wrap:anywhere]">{keyNames.length ? keyNames.flatMap((keyName, index) => index === 0 ? fieldTextWithBreaks(keyName) : [", ", ...fieldTextWithBreaks(keyName)]) : "—"}</span></td>
                    <td className={`${META_CELL} w-full sm:w-auto`} role="cell"><span className="shrink-0 sm:hidden" aria-hidden="true">Links to</span><div className="min-w-0 break-words [overflow-wrap:anywhere]">{foreignEntities.length === 0 ? "—" : foreignEntities.map((entity) => {
                      const targets = models.filter((target) => target.id !== model.id && target.entities.some((key) => key.name === entity.name && (key.type === "primary" || key.type === "unique")));
                      return <span key={entity.name} className="block">{targets.length ? targets.map((target) => target.label || target.name).join(", ") : `No model keyed by ${entity.name}`}</span>;
                    })}</div></td>
                    <td className={`${META_CELL} sm:text-right tabular-nums`} role="cell">{modelMetricCount}<span className="sm:hidden" aria-hidden="true">{modelMetricCount === 1 ? "metric" : "metrics"}</span></td>
                    <td className={META_CELL} role="cell"><ReferenceStatus problem={null} noConnector={connectorsLoaded && !model.sourceRelations.some((source) => connectors.some((connector) => connector.id === source.connectorId))} /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export function ReferenceStatus({ problem, noConnector }: { problem: string | null; noConnector: boolean }) {
  if (!problem && !noConnector) return <span className="type-status text-foreground-secondary">No issues</span>;
  return <span className="type-status inline-flex items-start gap-1.5" title={problem ?? undefined}><span className="sm:hidden" aria-hidden="true">·</span><CircleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0 text-error" aria-hidden="true" />{problem ? "Broken reference" : "No executable connector"}</span>;
}

function UsageCount({ workspaceId, kind, id }: { workspaceId: string; kind: "METRIC" | "SEMANTIC_MODEL"; id: string }) {
  const principalId = usePrincipalId();
  const usage = useQuery({ queryKey: queryKeys.lineage.focus(workspaceId, principalId ?? "", kind, id), queryFn: ({ signal }) => lineageAPI.getFocusCone(workspaceId, { kind, id }, signal), enabled: Boolean(principalId), retry: false });
  if (usage.isError) return <span title={usage.error.message}>Unavailable</span>;
  if (usage.isPending) return <span role="status" aria-label="Loading dashboard usage" className="inline-block h-4 w-20 animate-pulse rounded-sm bg-surface motion-reduce:animate-none" />;
  const count = usage.data.dashboardUsages?.length ?? 0;
  return <span className="tabular-nums">{count ? `${count} ${count === 1 ? "dashboard" : "dashboards"}` : "—"}</span>;
}
