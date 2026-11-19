import { useState } from "react";
import { DefinitionActionsMenu } from "@/components/semantics/DefinitionActionsMenu";
import { DefinitionDeleteDialog } from "@/components/semantics/DefinitionDeleteDialog";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams, useSearch } from "@tanstack/react-router";
import { useRouter } from "@/lib/navigation";
import { MainLayout } from "@/components/layout/MainLayout";
import { ActorProvenance } from "@/components/ui/ActorProvenance";
import { StateBlock } from "@/components/ui/StateBlock";
import { Button } from "@/components/ui/Button";
import { ArrowLeft } from "lucide-react";
import { metricAPI } from "@/api/metric";
import { semanticModelAPI } from "@/api/semantic-model";
import { getMetricLabel } from "@/lib/types/metric";
import { ResourceDetailHeader } from "@/components/resource-detail/ResourceDetailHeader";
import { MetricDetailBody } from "@/components/metric-detail/MetricDetailBody";
import { queryKeys } from "@/lib/query/keys";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { exploreMetricPath } from "@/lib/utils/semantic-model-utils";
import { formatRelativeTime } from "@/lib/utils/relative-time";
import { webPaths } from "@/lib/web-paths";
import { connectorsAPI } from "@/api/connectors";
import { exploreAPI } from "@/api/explore";
import { checkSemanticDefinition } from "@/api/semantic-check";
import { MetricEditor } from "@/components/metric/MetricEditor";
import { SemanticCalculationRail } from "@/components/semantics/SemanticCalculationRail";
import { SemanticCheckPanel } from "@/components/semantics/SemanticCheckPanel";
import { needsCalculationDescription } from "@/components/semantics/semantic-insights";
import { SEMANTIC_PAGE_COLUMNS, SEMANTIC_PAGE_WIDTH } from "@/components/semantics/SemanticPageLayout";
import { metricTypeLabel } from "@/components/semantic-model-detail/semantic-vocabulary";
import { MetricDetailActions } from "@/components/metric/MetricDetailActions";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";

export default function MetricDetailPage() {
  const { id } = useParams({ from: "/workspaces/$workspaceId/metrics/$id" });
  const workspaceId = useWorkspaceId();
  const { workspace } = useWorkspaceRouteContext();
  const [savedSampleMetric, setSavedSampleMetric] = useState(false);
  const principalId = usePrincipalId();
  const router = useRouter();
  const navigate = useNavigate();
  const access = useWorkspaceAccess();
  const search = useSearch({ from: "/workspaces/$workspaceId/metrics/$id/" });
  const editing = Boolean(search.edit && access.hasRole("EDITOR"));
  const [deleteDefinitionId, setDeleteDefinitionId] = useState<string | null>(null);
  const metricQuery = useQuery({
    queryKey: queryKeys.metrics.detail(workspaceId ?? "", principalId ?? "", id),
    queryFn: () => metricAPI.get(workspaceId, id),
    enabled: Boolean(workspaceId && principalId),
  });
  const semanticModelsQuery = useQuery({
    queryKey: queryKeys.semanticModels.all(workspaceId ?? "", principalId ?? ""),
    queryFn: () => semanticModelAPI.list(workspaceId),
    enabled: Boolean(workspaceId && principalId),
  });
  const metricsQuery = useQuery({
    queryKey: queryKeys.metrics.all(workspaceId ?? "", principalId ?? ""),
    queryFn: () => metricAPI.list(workspaceId),
    enabled: Boolean(workspaceId && principalId),
  });
  const metric = metricQuery.data ?? null;
  const connectorsQuery = useQuery({ queryKey: queryKeys.connectors.list(workspaceId, principalId ?? ""), queryFn: () => connectorsAPI.list({ workspaceId }), enabled: Boolean(principalId) });
  const savedRequest = metric ? { name: metric.name, ...(metric.label ? {label: metric.label} : {}), ...(metric.description ? {description: metric.description} : {}), type: metric.type, typeParams: metric.typeParams, ...(metric.filters ? {filters: metric.filters} : {}), ...(metric.timeGranularity ? {timeGranularity: metric.timeGranularity} : {}) } : null;
  const checkQuery = useQuery({ queryKey: queryKeys.semanticCheck(workspaceId, principalId ?? "", JSON.stringify({metricId: id, request: savedRequest})), queryFn: ({signal}) => checkSemanticDefinition(workspaceId, {kind: "METRIC", metricId: id, request: savedRequest!}, signal), enabled: Boolean(principalId && savedRequest && !editing), retry: false, staleTime: 0, refetchOnWindowFocus: false });
  const runsOn = checkQuery.data?.runsOn ?? [];
  const groupByQuery = useQuery({ queryKey: queryKeys.explore.semanticGroupBys(workspaceId, principalId ?? "", runsOn[0] ?? "", [metric?.name ?? ""]), queryFn: () => exploreAPI.listSemanticGroupBys({ workspaceId, connectorId: runsOn[0], metrics: [metric!.name] }), enabled: Boolean(principalId && metric && runsOn.length && !editing) });
  const error = metricQuery.error?.message ?? null;
  const relationsError = semanticModelsQuery.error?.message ?? metricsQuery.error?.message ?? null;

  if (metricQuery.isPending) {
    return (
      <MainLayout>
        <StateBlock kind="loading" layout="page" title="Loading metric" />
      </MainLayout>
    );
  }

  if (error || !metric) {
    return (
      <MainLayout>
        <StateBlock headingLevel={1}
          kind="error"
          layout="page"
          title="Unable to load this metric"
          description={error || "Metric not found"}
          action={
            <Button
              variant="secondary"
              size="sm"
              onClick={() => router.push(webPaths.metricCollection(workspaceId))}
            >
              <ArrowLeft aria-hidden="true" />
              Back to Semantics
            </Button>
          }
        />
      </MainLayout>
    );
  }

  const displayName = getMetricLabel(metric);
  const needsDescription = needsCalculationDescription(metric, semanticModelsQuery.data ?? []);
  if (editing) return <MainLayout><MetricEditor key={metric.id} metricId={metric.id} onSaved={() => { setSavedSampleMetric(Boolean(workspace.sample)); router.push(webPaths.metric(workspaceId, metric.id)); }} onCancel={() => router.push(webPaths.metric(workspaceId, metric.id))} /></MainLayout>;

  return (
    <MainLayout>
      {deleteDefinitionId === metric.id && <DefinitionDeleteDialog workspaceId={workspaceId} principalId={principalId ?? ""} kind="metrics" definition={metric} onCancel={() => setDeleteDefinitionId(null)} onDeleted={async () => { await navigate({ href: webPaths.metricCollection(workspaceId) }); }} />}
      <div className="h-full overflow-y-auto bg-background">
        <div className={`${SEMANTIC_PAGE_WIDTH} py-6 md:py-8`}>
          <ResourceDetailHeader
            backHref={webPaths.metricCollection(workspaceId)}
            backLabel="Semantics"
            title={displayName}
            technicalName={displayName === metric.name ? null : metric.name}
            classification={metric.type === "simple" ? "Aggregate metric" : metricTypeLabel(metric.type)}
            description={null}
            supportingDescription={metric.description}
            facts={[
              {
                label: "Created",
                value: <time className="tabular-nums" dateTime={metric.createdAt} title={new Date(metric.createdAt).toLocaleString("en-US")}>{formatRelativeTime(metric.createdAt)}</time>,
              },
              {
                label: "Updated",
                value: (
                  <ActorProvenance
                    verb={null}
                    actor={metric.updatedBy ?? null}
                    surface={metric.credentialSurface ?? null}
                    agentApp={metric.agentApp ?? null}
                    principalId={principalId ?? null}
                    sampleDefinition={Boolean(workspace.sample)}
                    time={<time className="tabular-nums" dateTime={metric.updatedAt} title={new Date(metric.updatedAt).toLocaleString("en-US")}>{formatRelativeTime(metric.updatedAt)}</time>}
                  />
                ),
              },
            ]}
            actions={
              <>
                <DefinitionActionsMenu historyHref={webPaths.metricHistory(workspaceId, metric.id)} canDelete={access.hasRole("EDITOR")} onDelete={() => setDeleteDefinitionId(metric.id)} />
                <MetricDetailActions
                  exploreHref={exploreMetricPath(workspaceId, metric.name)}
                  onEdit={() => router.push(`${webPaths.metric(workspaceId, metric.id)}?edit=true`)}
                />
              </>
            }
          />

          {needsDescription && (
            <div role="note" className="mt-4 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-[4px] border border-border bg-surface px-3 py-2">
              <p className="type-meta min-w-0 flex-1 text-foreground-secondary">This metric aggregates a calculated expression. A description tells people and agents what it counts.</p>
              {access.hasRole("EDITOR") && <Button type="button" variant="ghost" size="sm" onClick={() => router.push(`${webPaths.metric(workspaceId, metric.id)}?edit=true`)}>Add description</Button>}
            </div>
          )}
          {savedSampleMetric && <p role="status" className="mt-4 type-meta text-foreground-secondary">Open a dashboard that uses this metric to see updated results.</p>}

          <div className={`mt-8 ${SEMANTIC_PAGE_COLUMNS}`}>
            <MetricDetailBody
              workspaceId={workspaceId}
              metric={metric}
              relationsPending={semanticModelsQuery.isPending || metricsQuery.isPending}
              relationsError={relationsError}
              metrics={metricsQuery.data ?? []}
              models={semanticModelsQuery.data ?? []}
              connectors={connectorsQuery.data ?? []}
              groupBys={groupByQuery.data?.groupBys ?? []}
              slicePending={checkQuery.isPending || (runsOn.length > 0 && groupByQuery.isPending)}
              sliceError={groupByQuery.error?.message ?? checkQuery.error?.message ?? null}
            />
            <SemanticCalculationRail workspaceId={workspaceId} principalId={principalId ?? ""} kind="METRIC" id={metric.id} editing={false}>
              <SemanticCheckPanel mode="read" state={{ result: checkQuery.data ?? null, checking: checkQuery.isFetching, failed: checkQuery.isError, checkNow: () => { void checkQuery.refetch(); } }} connectors={connectorsQuery.data ?? []} incompleteMessage="The saved definition has not been checked yet." onGoToField={() => undefined} canGoToField={() => false} />
            </SemanticCalculationRail>
          </div>
        </div>
      </div>
    </MainLayout>
  );
}
