import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { overviewAPI } from "@/api/overview";
import { activityPollInterval, IDLE_POLL_INTERVAL_MS } from "@/pages/overview/overview-model";
import { activityQuery, type ActivityScope } from "@/pages/overview/use-activity";
import { ACTIVITY_PAGE_SIZE, RUNNING_LIMIT } from "./activity-model";

/**
 * Running work comes from one Live page and finished runs and changes from Recent pages. Polling slows once older pages
 * are loaded because each refresh reads every loaded page again.
 */
export function useWorkspaceActivity(workspaceId: string, principalId: string, scope: ActivityScope) {
  const query = activityQuery(scope);
  const running = useQuery({
    queryKey: ["overview", workspaceId, principalId, "activity-page", "live", query],
    queryFn: ({ signal }) => overviewAPI.read(workspaceId, { section: "live", ...query, limit: RUNNING_LIMIT }, signal),
    refetchInterval: (current) => activityPollInterval({
      loaded: current.state.data !== undefined, liveCount: current.state.data?.items.length ?? 0, recentCount: 0,
    }),
  });
  const recent = useInfiniteQuery({
    queryKey: ["overview", workspaceId, principalId, "activity-page", "recent", query],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => overviewAPI.read(workspaceId, { section: "recent", ...query, limit: ACTIVITY_PAGE_SIZE, cursor: pageParam }, signal),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    refetchInterval: (current) => {
      const pages = current.state.data?.pages;
      if (pages && pages.length > 1) return IDLE_POLL_INTERVAL_MS;
      return activityPollInterval({ loaded: pages !== undefined, liveCount: 0, recentCount: pages?.[0]?.items.length ?? 0 });
    },
  });
  return { running, recent };
}
