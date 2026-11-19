import { useRef } from "react";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import { dashboardAPI, dashboardIdempotencyKey } from "@/api/dashboard";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { MainLayout } from "@/components/layout/MainLayout";
import { ResourceDetailHeader } from "@/components/resource-detail/ResourceDetailHeader";
import { DefinitionHistoryView } from "@/components/semantics/DefinitionHistoryView";
import { Button, buttonClassName } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { Link, useRouter } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { refreshDashboardDefinitionCaches } from "@/lib/query/dashboard-definition-cache";
import { webPaths } from "@/lib/web-paths";

export default function DashboardHistoryPage() {
  const { dashboardId } = useParams({ from: "/workspaces/$workspaceId/dashboards/$dashboardId/history" });
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId() ?? "";
  const access = useWorkspaceAccess();
  const { workspace } = useWorkspaceRouteContext();
  const router = useRouter();
  const client = useQueryClient();
  const restoreIntent = useRef<{ changeId: string; key: string } | null>(null);
  const history = useInfiniteQuery({
    queryKey: queryKeys.dashboards.history(workspaceId, principalId, dashboardId),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => dashboardAPI.history(workspaceId, dashboardId, pageParam, signal),
    getNextPageParam: page => page.nextCursor ?? undefined,
    enabled: Boolean(principalId), retry: false,
  });
  const restore = useMutation({
    mutationFn: (changeId: string) => {
      if (restoreIntent.current?.changeId !== changeId) restoreIntent.current = { changeId, key: dashboardIdempotencyKey("restore") };
      return dashboardAPI.restore(workspaceId, dashboardId, changeId, restoreIntent.current.key);
    },
    onSuccess: async () => {
      await refreshDashboardDefinitionCaches(client, workspaceId, principalId, dashboardId, false);
      router.push(webPaths.dashboard(workspaceId, dashboardId));
    },
  });
  const first = history.data?.pages[0];
  return <MainLayout><div className="h-full overflow-y-auto"><div className="mx-auto max-w-[1080px] p-6 md:p-8">
    {history.isPending ? <StateBlock kind="loading" layout="page" title="Loading history" /> : !first ? <StateBlock kind="error" layout="page" headingLevel={1} title="Unable to load dashboard history" description={history.error?.message ?? "History not found"} action={<Button variant="secondary" onClick={() => void history.refetch()}>Retry</Button>} /> : <>
      <ResourceDetailHeader backHref={webPaths.dashboards(workspaceId)} backLabel="Dashboards" title={`${first.name} history`} technicalName={null} classification={first.deleted ? "Deleted" : "Dashboard"} description={null} facts={[]} actions={!first.deleted ? <Link href={webPaths.dashboard(workspaceId, dashboardId)} className={buttonClassName("secondary", "sm", "")}>View current dashboard</Link> : null} />
      <div className="mt-6"><DefinitionHistoryView changes={history.data?.pages.flatMap(page => page.changes) ?? []} deleted={first.deleted} deletedMessage="Deleted · This dashboard no longer appears in Dashboards. Restoring it brings back its definition and schedules. Pages and past results don’t come back." canRestore={first.deleted && access.hasRole("EXPLORER")} principalId={principalId} sampleWorkspace={Boolean(workspace.sample)} restoringId={restore.isPending ? restore.variables : null} error={restore.error?.message ?? null} onRestore={changeId => restore.mutate(changeId)} />
        {history.hasNextPage && <Button variant="secondary" pending={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>Load older changes</Button>}
        {history.isError && <p role="alert" className="type-body mt-4 text-error">History couldn’t be refreshed. <Button variant="ghost" size="sm" onClick={() => void history.refetch()}>Retry</Button></p>}
      </div>
    </>}
  </div></div></MainLayout>;
}
