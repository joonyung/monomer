import type { QueryClient } from "@tanstack/react-query";
import { queryKeys } from "@/lib/query/keys";

export async function refreshDashboardDefinitionCaches(client: QueryClient, workspaceId: string, principalId: string, dashboardId: string, deleted: boolean): Promise<void> {
  const root = queryKeys.dashboards.all(workspaceId, principalId);
  if (deleted) {
    const sessionIds = new Set<string>();
    for (const query of client.getQueryCache().findAll({ queryKey: root })) {
      if (query.queryKey[4] === "session" && (query.state.data as { dashboardId?: string } | undefined)?.dashboardId === dashboardId) sessionIds.add(String(query.queryKey[5]));
      if (query.queryKey[4] === "sessions" && query.queryKey[5] === dashboardId) {
        const data = query.state.data as { pages?: { items: { dashboardSessionId: string }[] }[] } | undefined;
        for (const page of data?.pages ?? []) for (const session of page.items) sessionIds.add(session.dashboardSessionId);
      }
    }
    const belongsToDeletedDashboard = ({ queryKey: key }: { queryKey: readonly unknown[] }) =>
      key[0] === "member-pages-entry" && key[1] === workspaceId && key[2] === principalId && key[3] === dashboardId ||
      key[0] === "protected" && key[1] === workspaceId && key[2] === principalId && (
      key[3] === "dashboards" && key[4] !== "history" && (key[5] === dashboardId || ["session", "session-query-runs"].includes(String(key[4])) && sessionIds.has(String(key[5]))) ||
      key[3] === "dashboard-draft-result" && key[4] === dashboardId ||
      ["dashboard-session-run", "dashboard-result"].includes(String(key[3])) && sessionIds.has(String(key[4]))
    );
    await client.cancelQueries({ predicate: belongsToDeletedDashboard });
    client.removeQueries({ predicate: belongsToDeletedDashboard });
  }
  await client.invalidateQueries({ predicate: ({ queryKey: key }) =>
    key[0] === "protected" && key[1] === workspaceId && key[2] === principalId && ["dashboards", "pages", "overview", "search", "lineage"].includes(String(key[3])) ||
    key[0] === "overview" && key[1] === workspaceId && key[2] === principalId,
  });
}
