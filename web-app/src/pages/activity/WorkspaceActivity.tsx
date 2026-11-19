import { useSearch } from "@tanstack/react-router";
import { History } from "lucide-react";
import type { ActivityItem } from "@/api/overview";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { Button } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import { useChangeHighlight } from "@/lib/change-highlight";
import { useRouter } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { ActivityScopeTabs, ActivityTimeline, activityHighlightItems } from "@/pages/overview/OverviewActivity";
import { activityId } from "@/pages/overview/observation";
import type { ActivityScope } from "@/pages/overview/use-activity";
import { activityRows } from "./activity-model";
import { useWorkspaceActivity } from "./use-workspace-activity";
import "@/pages/overview/style.css";

export function WorkspaceActivity({ workspaceId, principalId }: { workspaceId: string; principalId: string }) {
  const { scope } = useSearch({ from: "/workspaces/$workspaceId/activity" });
  const router = useRouter();
  const { workspace } = useWorkspaceRouteContext();
  const setScope = (next: ActivityScope) => router.replace(webPaths.activity(workspaceId, next), { scroll: false });
  const { running, recent } = useWorkspaceActivity(workspaceId, principalId, scope);
  const items = activityRows(running.data?.items ?? [], recent.data?.pages ?? []);
  const loading = recent.isPending || running.isPending;
  const arrivals = new Set([...(running.data?.items ?? []), ...(recent.data?.pages[0]?.items ?? [])].map(activityId));
  const highlightContainer = useChangeHighlight(JSON.stringify([workspaceId, principalId, scope]),
    loading ? null : activityHighlightItems(items, arrivals), { duration: 1_800, animateArrival: true });
  const unavailable = [...(running.data?.unavailableSources ?? []), ...(recent.data?.pages.flatMap((page) => page.unavailableSources) ?? [])].length > 0;
  const retry = () => { void running.refetch(); void recent.refetch(); };
  return <section ref={highlightContainer} className="overview-activity ov-main" aria-label="Workspace activity">
    <ActivityView
      scope={scope}
      onScopeChange={setScope}
      items={items}
      clock={Math.max(running.dataUpdatedAt, recent.dataUpdatedAt)}
      sampleWorkspace={Boolean(workspace.sample)}
      status={loading ? "loading" : recent.isError && !recent.data ? "error" : "ready"}
      notice={recent.isError || running.isError ? "Activity could not be refreshed." : unavailable ? "Some activity sources are unavailable." : null}
      moreRunning={Boolean(running.data?.nextCursor)}
      hasMore={recent.hasNextPage}
      loadingMore={recent.isFetchingNextPage}
      onRetry={retry}
      onLoadMore={() => void recent.fetchNextPage()}
    />
  </section>;
}

export function ActivityView({ scope, onScopeChange, items, clock, sampleWorkspace, status, notice, moreRunning, hasMore, loadingMore, onRetry, onLoadMore }: {
  scope: ActivityScope;
  onScopeChange: (scope: ActivityScope) => void;
  items: ActivityItem[];
  clock: number;
  sampleWorkspace: boolean;
  status: "loading" | "error" | "ready";
  notice: string | null;
  moreRunning: boolean;
  hasMore: boolean;
  loadingMore: boolean;
  onRetry: () => void;
  onLoadMore: () => void;
}) {
  const filtered = scope !== "all";
  return <>
    <div className="ov-section-heading ov-recent-heading">
      <h2 className="sr-only">Runs and changes</h2>
      <ActivityScopeTabs scope={scope} onScopeChange={onScopeChange} />
    </div>
    {notice && status === "ready" && <p className="ov-notice" role="status">{notice} <button type="button" className="ov-link" onClick={onRetry}>Retry</button></p>}
    {status === "loading" ? <StateBlock kind="loading" layout="inline" title="Loading activity…" />
      : status === "error" ? <StateBlock kind="error" layout="inline" title="Activity couldn’t load." action={<Button variant="secondary" onClick={onRetry}>Retry</Button>} />
        : items.length ? <>
          {moreRunning && <p className="ov-meta ov-activity-running-more">More work is running than this page lists.</p>}
          <ActivityTimeline items={items} clock={clock} sampleWorkspace={sampleWorkspace} />
        </>
          : <StateBlock kind="empty" layout="inline" icon={History}
            title={hasMore || notice ? "No activity in loaded results" : filtered ? "No activity matches these filters" : "No activity yet"}
            description={filtered || hasMore || notice ? undefined : "Runs and changes by people and their agents appear here."}
            action={filtered && !hasMore ? <Button variant="secondary" onClick={() => onScopeChange("all")}>Show all activity</Button> : undefined} />}
    {status === "ready" && hasMore && <Button variant="secondary" className="ov-load-more" disabled={loadingMore} onClick={onLoadMore}>{loadingMore ? "Loading…" : "Load more activity"}</Button>}
  </>;
}
