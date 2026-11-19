import { useState } from "react";
import { DefinitionActionsMenu } from "@/components/semantics/DefinitionActionsMenu";
import { DefinitionDeleteDialog } from "@/components/semantics/DefinitionDeleteDialog";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "@tanstack/react-router";
import { ArrowLeft, Compass, Edit } from "lucide-react";
import { Link } from "@/lib/navigation";
import { MainLayout } from "@/components/layout/MainLayout";
import { ActorProvenance } from "@/components/ui/ActorProvenance";
import { StateBlock } from "@/components/ui/StateBlock";
import { buttonClassName } from "@/components/ui/Button";
import {
  semanticModelAPI,
  type SemanticModelDetail,
} from "@/api/semantic-model";
import { catalogAPI } from "@/api/catalog";
import { SemanticModelEditor } from "@/components/semantic-model/SemanticModelEditor";
import { SemanticCalculationRail } from "@/components/semantics/SemanticCalculationRail";
import { SourcePartitionHints } from "@/components/semantics/SourcePartitionHints";
import { SemanticCheckPanel, useSemanticCheck } from "@/components/semantics/SemanticCheckPanel";
import { keyedModels } from "@/components/semantic-model/semantic-model-fields";
import { metricAPI } from "@/api/metric";
import { getDisplayName } from "@/lib/utils/semantic-model-utils";
import { formatRelativeTime } from "@/lib/utils/relative-time";
import { useConnectors } from "@/hooks/useConnectors";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";
import { ResourceDetailHeader } from "@/components/resource-detail/ResourceDetailHeader";
import { SemanticModelFields } from "@/components/semantic-model-detail/SemanticModelFields";
import { SourceTableDisclosure } from "@/components/semantic-model-detail/SourceTableLink";
import { SEMANTIC_PAGE_COLUMNS, SEMANTIC_PAGE_WIDTH } from "@/components/semantics/SemanticPageLayout";
import { queryKeys } from "@/lib/query/keys";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { webPaths } from "@/lib/web-paths";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";

export default function SemanticModelDetailPage({ editing = false }: { editing?: boolean }) {
  const { id: semanticModelId } = useParams({ from: "/workspaces/$workspaceId/semantic-models/$id" });
  const workspaceId = useWorkspaceId();
  const { workspace } = useWorkspaceRouteContext();
  const navigate = useNavigate();
  const [deleteDefinitionId, setDeleteDefinitionId] = useState<string | null>(null);
  const principalId = usePrincipalId();
  const workspaceAccess = useWorkspaceAccess();
  const { connectors } = useConnectors({ workspaceId });
  const modelQuery = useQuery({
    queryKey: queryKeys.semanticModels.detail(
      workspaceId ?? "",
      principalId ?? "",
      semanticModelId,
    ),
    queryFn: () => semanticModelAPI.getById(workspaceId, semanticModelId),
    enabled: Boolean(workspaceId && principalId),
  });
  const metricsQuery = useQuery({
    queryKey: queryKeys.metrics.all(workspaceId ?? "", principalId ?? ""),
    queryFn: () => metricAPI.list(workspaceId),
    enabled: Boolean(workspaceId && principalId),
  });
  const modelsQuery = useQuery({
    queryKey: queryKeys.semanticModels.all(workspaceId ?? "", principalId ?? ""),
    queryFn: () => semanticModelAPI.list(workspaceId),
    enabled: Boolean(workspaceId && principalId),
  });
  const source = modelQuery.data?.sourceRelations[0];
  const relationQuery = useQuery({
    queryKey: queryKeys.catalog.relation(workspaceId ?? "", principalId ?? "", source?.connectorId ?? "", source?.catalog ?? null, source?.schema ?? "", source?.relation ?? ""),
    queryFn: () => catalogAPI.getRelation(workspaceId, source!.connectorId, source!.relation, source!.schema, source!.catalog),
    enabled: Boolean(workspaceId && principalId && source),
  });
  const showsEditor = editing && workspaceAccess.hasRole("EDITOR");
  const check = useSemanticCheck(modelQuery.data && !showsEditor ? { kind: "SEMANTIC_MODEL", semanticModelId, request: modelQuery.data } : null);
  const model: SemanticModelDetail | null = modelQuery.data ?? null;
  const error = modelQuery.error?.message ?? null;

  if (modelQuery.isPending) {
    return (
      <MainLayout>
        <StateBlock kind="loading" layout="page" title="Loading semantic model" />
      </MainLayout>
    );
  }

  if (error || !model) {
    return (
      <MainLayout>
        <StateBlock headingLevel={1}
          kind="error"
          layout="page"
          title="Unable to load this semantic model"
          description={error || "Model not found"}
          action={
            <Link href={webPaths.semanticModelCollection(workspaceId)} className={buttonClassName("secondary", "sm", "")}>
              <ArrowLeft aria-hidden="true" />
              Back to Semantics
            </Link>
          }
        />
      </MainLayout>
    );
  }

  const displayName = getDisplayName(model);
  const canEditSemanticModel = workspaceAccess.hasRole("EDITOR");
  const canExplore = workspaceAccess.hasRole("EXPLORER");
  const primaryEntityName = model.entities.find((entity) => entity.type === "primary")?.name;
  const joins = model.entities.filter((entity) => entity.type === "foreign").map((entity) => ({ entity, targets: keyedModels(modelsQuery.data ?? [], model.id).filter(({ key }) => key.name === entity.name).map(({ model }) => model) }));
  if (editing && canEditSemanticModel) return <MainLayout><SemanticModelEditor modelId={model.id} initialData={model} onCancel={() => { void navigate({ href: webPaths.semanticModel(workspaceId, model.id), replace: true }); }} onSaved={() => { void navigate({ href: webPaths.semanticModel(workspaceId, model.id), replace: true }); }} /></MainLayout>;

  return (
    <MainLayout>
      {deleteDefinitionId === model.id && <DefinitionDeleteDialog workspaceId={workspaceId} principalId={principalId ?? ""} kind="semantic-models" definition={model} onCancel={() => setDeleteDefinitionId(null)} onDeleted={async () => { await navigate({ href: webPaths.semanticModelCollection(workspaceId) }); }} />}
      <div className="h-full overflow-y-auto bg-background">
        <div className={`${SEMANTIC_PAGE_WIDTH} py-6 md:py-8`}>
          <ResourceDetailHeader
            backHref={webPaths.semanticModelCollection(workspaceId)}
            backLabel="Semantics"
            title={displayName}
            technicalName={displayName === model.name ? null : model.name}
            classification="Model"
            description={null}
            supportingDescription={model.description ?? null}
            identityFacts={[
              {
                label: "Source table",
                value: (
                  <SourceTableDisclosure key={model.id} workspaceId={workspaceId} sources={model.sourceRelations} connectors={connectors} />
                ),
              },
              { label: "Row key", value: <code className="font-mono">{primaryEntityName ?? "—"}</code> },
              ...(joins.length ? [{ label: "Joins", value: <span className="flex flex-wrap gap-x-2 gap-y-1">{joins.map(({entity, targets}) => <span key={entity.name}>{targets.length ? targets.map((target, index) => <span key={target.id}>{index > 0 ? ", " : ""}<Link href={webPaths.semanticModel(workspaceId, target.id)} className={SEMANTIC_LINK_CLASS}>{target.label || target.name}</Link></span>) : `No model keyed by ${entity.name}`}</span>)}</span> }] : []),
              ...(model.aggregationTimeDimension
                ? [{
                    label: "Metric time",
                    value: <code className="font-mono">{model.aggregationTimeDimension}</code>,
                  }]
                : []),
            ]}
            facts={[
              {
                label: "Created",
                value: <time className="tabular-nums" dateTime={model.createdAt} title={new Date(model.createdAt).toLocaleString("en-US")}>{formatRelativeTime(model.createdAt)}</time>,
              },
              {
                label: "Updated",
                value: (
                  <ActorProvenance
                    verb={null}
                    actor={model.updatedBy ?? null}
                    surface={model.credentialSurface ?? null}
                    agentApp={model.agentApp ?? null}
                    principalId={principalId ?? null}
                    sampleDefinition={Boolean(workspace.sample)}
                    time={<time className="tabular-nums" dateTime={model.updatedAt} title={new Date(model.updatedAt).toLocaleString("en-US")}>{formatRelativeTime(model.updatedAt)}</time>}
                  />
                ),
              },
            ]}
            actions={
              <>
                <DefinitionActionsMenu historyHref={webPaths.semanticModelHistory(workspaceId, model.id)} canDelete={canEditSemanticModel} onDelete={() => setDeleteDefinitionId(model.id)} />
                {canEditSemanticModel && (
                  <Link
                    href={`${webPaths.semanticModel(workspaceId, model.id)}?edit=true`}
                    className={buttonClassName("secondary", "sm", "")}
                  >
                    <Edit aria-hidden="true" />
                    Edit
                  </Link>
                )}
                {canExplore && (
                  <Link
                    href={webPaths.exploreModel(workspaceId, model.id)}
                    className={buttonClassName("primary", "sm", "")}
                  >
                    <Compass aria-hidden="true" />
                    Explore
                  </Link>
                )}
              </>
            }
          />

          <div className={`mt-8 ${SEMANTIC_PAGE_COLUMNS}`}>
            <div className="min-w-0">
              <SourcePartitionHints sources={model.sourcePartitionInfo ?? []} models={modelsQuery.data ?? [model]} connectors={connectors} />
              <SemanticModelFields workspaceId={workspaceId} model={model} models={modelsQuery.data ?? []} relation={relationQuery.data ?? null} metrics={metricsQuery.data ?? null} canCreateMetric={canEditSemanticModel} />
            </div>
            <SemanticCalculationRail workspaceId={workspaceId} principalId={principalId ?? ""} kind="SEMANTIC_MODEL" id={model.id} editing={false}>
              <SemanticCheckPanel mode="read" state={check} connectors={connectors} incompleteMessage="The saved definition has not been checked yet." onGoToField={() => undefined} canGoToField={() => false} />
            </SemanticCalculationRail>
          </div>
        </div>
      </div>
    </MainLayout>
  );
}
