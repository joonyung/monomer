import { useId } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import { ExternalLink, Sigma } from "lucide-react";
import type { DashboardDefinition } from "@/api/dashboard";
import { metricAPI, type MetricDetail } from "@/api/metric";
import { semanticModelAPI } from "@/api/semantic-model";
import { metricDefinitionText, metricTypeLabel } from "@/components/semantic-model-detail/semantic-vocabulary";
import { ActorProvenance } from "@/components/ui/ActorProvenance";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { StateBlock } from "@/components/ui/StateBlock";
import { queryGroupingText, widgetMetricSources } from "@/features/dashboard/widget-metrics";
import { Link } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { getMetricLabel, type Metric } from "@/lib/types/metric";
import { formatRelativeTime } from "@/lib/utils/relative-time";
import { webPaths } from "@/lib/web-paths";
import type { DashboardWidgetMetricsTarget } from "./DashboardWidgetRenderer";

export type WidgetMetricListState =
  | { status: "pending" }
  | { status: "error"; message: string; onRetry: () => void }
  | { status: "success"; metrics: readonly Metric[] };

export interface WidgetQueryGrouping {
  queryName: string;
  text: string;
}

const METRIC_LINK_CLASS = "type-meta inline-flex items-center gap-1.5 text-foreground-secondary underline decoration-border underline-offset-4 hover:text-foreground focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-2 focus-visible:outline-[var(--focus-ring)]";

export function WidgetMetricsDialog({ workspaceId, principalId, sampleWorkspace, queries, target, onClose }: {
  workspaceId: string;
  principalId: string | null;
  sampleWorkspace: boolean;
  queries: DashboardDefinition["queries"];
  target: DashboardWidgetMetricsTarget | null;
  onClose: () => void;
}) {
  const sql = target?.queryType === "SQL";
  return (
    <Dialog closeLabel={sql ? "Close SQL" : "Close metric definitions"} open={target !== null} onOpenChange={(open) => { if (!open) onClose(); }} size="md" title={sql ? "SQL" : target?.lineage ? "Open Lineage" : "Metric definitions"} description={target?.widgetTitle ?? undefined}>
      {target ? sql
        ? <WidgetSqlTemplates queries={queries} queryNames={target.queryNames} />
        : <WidgetMetricsContent workspaceId={workspaceId} principalId={principalId} sampleWorkspace={sampleWorkspace} queries={queries} queryNames={target.queryNames} /> : null}
    </Dialog>
  );
}

export function WidgetSqlTemplates({ queries, queryNames }: {
  queries: DashboardDefinition["queries"];
  queryNames: readonly string[];
}) {
  return <div className="space-y-4">
    <p className="type-meta text-foreground-secondary">Stored templates from this definition. Input values are bound when a new run starts.</p>
    {queryNames.map(name => {
      const query = Object.hasOwn(queries, name) ? queries[name] : undefined;
      if (query?.type !== "SQL") return null;
      return <section key={name} aria-label={`SQL template for ${name}`}>
        <h3 className="type-group mb-2 text-foreground-secondary"><span className="font-mono">{name}</span> · SQL template</h3>
        <pre className="type-mono max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-[4px] bg-surface px-4 py-3 text-foreground"><code>{query.sql}</code></pre>
      </section>;
    })}
  </div>;
}

function WidgetMetricsContent({ workspaceId, principalId, sampleWorkspace, queries, queryNames }: {
  workspaceId: string;
  principalId: string | null;
  sampleWorkspace: boolean;
  queries: DashboardDefinition["queries"];
  queryNames: readonly string[];
}) {
  const sources = widgetMetricSources(queries, queryNames);
  const metricsQuery = useQuery({
    queryKey: queryKeys.metrics.all(workspaceId, principalId ?? ""),
    queryFn: () => metricAPI.list(workspaceId),
    enabled: Boolean(principalId),
  });
  const modelsQuery = useQuery({
    queryKey: queryKeys.semanticModels.all(workspaceId, principalId ?? ""),
    queryFn: () => semanticModelAPI.list(workspaceId),
    enabled: Boolean(principalId),
  });
  const usedMetrics = (metricsQuery.data ?? []).filter((metric) => sources.metricNames.includes(metric.name));
  const detailQueries = useQueries({
    queries: usedMetrics.map((metric) => ({
      queryKey: queryKeys.metrics.detail(workspaceId, principalId ?? "", metric.id),
      queryFn: () => metricAPI.get(workspaceId, metric.id),
      enabled: Boolean(principalId),
    })),
  });
  const detailsByMetricId: Record<string, MetricDetail> = {};
  detailQueries.forEach((detailQuery) => { if (detailQuery.data) detailsByMetricId[detailQuery.data.id] = detailQuery.data; });
  const metricList: WidgetMetricListState = metricsQuery.data
    ? { status: "success", metrics: metricsQuery.data }
    : metricsQuery.isError
      ? { status: "error", message: metricsQuery.error.message, onRetry: () => void metricsQuery.refetch() }
      : { status: "pending" };
  const groupings = sources.queries.map(({ queryName, groupBys }) => ({ queryName, text: queryGroupingText(groupBys, modelsQuery.data ?? []) }));
  return <WidgetMetricDefinitions workspaceId={workspaceId} principalId={principalId} sampleWorkspace={sampleWorkspace} metricNames={sources.metricNames} groupings={groupings} metricList={metricList} detailsByMetricId={detailsByMetricId} />;
}

export function WidgetMetricDefinitions({ workspaceId, principalId, sampleWorkspace, metricNames, groupings, metricList, detailsByMetricId }: {
  workspaceId: string;
  principalId: string | null;
  sampleWorkspace: boolean;
  metricNames: readonly string[];
  groupings: readonly WidgetQueryGrouping[];
  metricList: WidgetMetricListState;
  detailsByMetricId: Readonly<Record<string, MetricDetail>>;
}) {
  const groupingHeadingId = useId();
  const metricsHeadingId = useId();
  let body;
  if (metricNames.length === 0) {
    body = <StateBlock kind="empty" layout="inline" icon={Sigma} title="This widget doesn't read any metrics." />;
  } else if (metricList.status === "pending") {
    body = <StateBlock kind="loading" layout="inline" title="Loading metric definitions…" />;
  } else if (metricList.status === "error") {
    body = <StateBlock kind="error" layout="inline" title="Metric definitions couldn't load." description={metricList.message}
      action={<Button type="button" variant="secondary" size="sm" onClick={metricList.onRetry}>Retry</Button>} />;
  } else {
    body = <ul className="divide-y divide-border-light border-y border-border">
      {metricNames.map((metricName) => {
        const metric = metricList.metrics.find((candidate) => candidate.name === metricName);
        return metric
          ? <WidgetMetricItem key={metricName} workspaceId={workspaceId} principalId={principalId} sampleWorkspace={sampleWorkspace} metric={metric} detail={detailsByMetricId[metric.id] ?? null} />
          : <li key={metricName} className="py-4">
            <h4 className="type-control font-mono text-heading">{metricName}</h4>
            <p className="type-meta mt-1 text-foreground-secondary">Not found in this workspace&apos;s metrics. It may have been renamed or deleted.</p>
          </li>;
      })}
    </ul>;
  }
  return (
    <div className="space-y-5">
      {groupings.length > 0 ? <section aria-labelledby={groupingHeadingId}>
        <h3 id={groupingHeadingId} className="type-group mb-2 text-foreground-secondary">Grouping</h3>
        {groupings.length === 1
          ? <p className="type-meta text-foreground">{groupings[0].text}</p>
          : <ul className="type-meta space-y-1 text-foreground">{groupings.map((grouping) => <li key={grouping.queryName}><span className="font-mono">{grouping.queryName}</span> · {grouping.text}</li>)}</ul>}
      </section> : null}
      <section aria-labelledby={metricsHeadingId}>
        <h3 id={metricsHeadingId} className="type-group mb-2 text-foreground-secondary">{metricNames.length === 1 ? "Metric" : "Metrics"}</h3>
        {body}
      </section>
    </div>
  );
}

function WidgetMetricItem({ workspaceId, principalId, sampleWorkspace, metric, detail }: { workspaceId: string; principalId: string | null; sampleWorkspace: boolean; metric: Metric; detail: MetricDetail | null }) {
  const displayName = getMetricLabel(metric);
  const updatedAt = detail?.updatedAt ?? metric.updatedAt;
  return (
    <li className="py-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h4 className="type-control text-heading">{displayName}</h4>
          {displayName !== metric.name ? <p className="type-status mt-0.5 font-mono text-foreground-secondary">{metric.name}</p> : null}
        </div>
        <span className="type-group flex-none text-foreground-secondary">{metricTypeLabel(metric.type)} metric</span>
      </div>
      <div className="mt-3 rounded-[4px] bg-surface px-4 py-3"><code className="type-mono block break-words text-foreground">{metricDefinitionText(metric)}</code></div>
      {metric.description ? <p className="type-meta mt-3 text-foreground-secondary">{metric.description}</p> : null}
      <div className="mt-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <p className="type-status tabular-nums text-foreground-secondary">
          <ActorProvenance
            verb="Updated"
            actor={detail?.updatedBy ?? null}
            surface={detail?.credentialSurface ?? null}
            agentApp={detail?.agentApp ?? null}
            principalId={principalId}
            sampleDefinition={sampleWorkspace && detail !== null}
            time={<time dateTime={updatedAt} title={new Date(updatedAt).toLocaleString("en-US")}>{formatRelativeTime(updatedAt)}</time>}
          />
        </p>
        <Link href={webPaths.lineageResource(workspaceId, "METRIC", metric.id)} className={METRIC_LINK_CLASS}>Open map</Link>
        <Link href={webPaths.metric(workspaceId, metric.id)} aria-label={`Open metric ${displayName}`} className={METRIC_LINK_CLASS}>
          Open metric <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
        </Link>
      </div>
    </li>
  );
}
