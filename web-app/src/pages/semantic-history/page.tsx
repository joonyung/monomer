import { useRef } from "react";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import { semanticDefinitionAPI, type SemanticDefinitionKind } from "@/api/semantic-definition-history";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { MainLayout } from "@/components/layout/MainLayout";
import { ResourceDetailHeader } from "@/components/resource-detail/ResourceDetailHeader";
import { DefinitionHistoryView } from "@/components/semantics/DefinitionHistoryView";
import { Button, buttonClassName } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { ApiError } from "@/lib/api/client";
import { Link, useRouter } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { refreshSemanticDefinitionCaches } from "@/lib/query/semantic-definition-cache";
import { stableSemanticMutationIntent, type SemanticMutationIntent } from "@/lib/semantic-mutation-intent";
import { webPaths } from "@/lib/web-paths";

export default function SemanticDefinitionHistoryPage({ kind }: { kind: SemanticDefinitionKind }) {
  const { id } = useParams({ strict: false });
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId() ?? "";
  const access = useWorkspaceAccess();
  const { workspace } = useWorkspaceRouteContext();
  const router = useRouter();
  const client = useQueryClient();
  const intent = useRef<SemanticMutationIntent | null>(null);
  const definitionId = id ?? "";
  const history = useInfiniteQuery({
    queryKey: kind === "metrics" ? queryKeys.metrics.history(workspaceId, principalId, definitionId) : queryKeys.semanticModels.history(workspaceId, principalId, definitionId),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => semanticDefinitionAPI.history(workspaceId, kind, definitionId, pageParam, signal),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: Boolean(workspaceId && principalId && definitionId),
  });
  const restore = useMutation({
    mutationFn: (changeId: string) => {
      intent.current = stableSemanticMutationIntent(intent.current, `${kind}:${definitionId}:${changeId}`);
      return semanticDefinitionAPI.restore(workspaceId, kind, definitionId, changeId, intent.current.idempotencyKey);
    },
    onSuccess: async () => {
      await refreshSemanticDefinitionCaches(client, workspaceId, principalId, kind, definitionId, false);
      router.push(kind === "metrics" ? webPaths.metric(workspaceId, definitionId) : webPaths.semanticModel(workspaceId, definitionId));
    },
  });
  const first = history.data?.pages[0];
  const displayName = first?.changes[0]?.definition.label || first?.name;
  const restoreError = restore.error instanceof ApiError && restore.error.problem?.code === "SEMANTIC_NAME_CONFLICT"
    ? `The name “${first?.name}” is already used by another definition. Rename or delete that definition before restoring this version.`
    : restore.error?.message ?? null;
  return <MainLayout><div className="h-full overflow-y-auto"><div className="mx-auto max-w-[1080px] p-6 md:p-8">
    {history.isPending ? <StateBlock kind="loading" layout="page" title="Loading history" /> : !first ? <StateBlock kind="error" layout="page" headingLevel={1} title="Unable to load definition history" description={history.error?.message ?? "History not found"} action={<Button variant="secondary" onClick={() => void history.refetch()}>Retry</Button>} /> : <>
      <ResourceDetailHeader backHref={webPaths.semantics(workspaceId)} backLabel="Semantics" title={`${displayName} history`} technicalName={displayName === first.name ? null : first.name} classification={first.deleted ? "Deleted" : kind === "metrics" ? "Metric" : "Model"} description={null} facts={[]}
        actions={!first.deleted ? <Link href={kind === "metrics" ? webPaths.metric(workspaceId, definitionId) : webPaths.semanticModel(workspaceId, definitionId)} className={buttonClassName("secondary", "sm", "")}>View current definition</Link> : null} />
      <div className="mt-6"><DefinitionHistoryView changes={history.data?.pages.flatMap((page) => page.changes) ?? []} deleted={first.deleted} canRestore={access.hasRole("EDITOR")} principalId={principalId} sampleWorkspace={Boolean(workspace.sample)} restoringId={restore.isPending ? restore.variables : null} error={restoreError} onRestore={(changeId) => restore.mutate(changeId)} />
        {history.hasNextPage && <Button variant="secondary" pending={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>Load older changes</Button>}
        {history.isError && <p role="alert" className="type-body mt-4 text-error">History couldn’t be refreshed. <Button variant="ghost" size="sm" onClick={() => void history.refetch()}>Retry</Button></p>}
      </div>
    </>}
  </div></div></MainLayout>;
}
