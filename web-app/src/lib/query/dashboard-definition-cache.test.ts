import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { refreshDashboardDefinitionCaches } from "./dashboard-definition-cache";
import { queryKeys } from "./keys";

describe("Dashboard definition cache", () => {
  it("removes the deleted definition, Draft, Sessions and result artifacts while refreshing discovery", async () => {
    const client = new QueryClient();
    const sessions = queryKeys.dashboards.sessions("ws", "user", "deleted", null, 20);
    client.setQueryData(sessions, { pages: [{ items: [{ dashboardSessionId: "session" }] }] });
    const removed = [["member-pages-entry", "ws", "user", "deleted", "visit", "default"],queryKeys.dashboards.detail("ws", "user", "deleted"), [...queryKeys.dashboards.detail("ws", "user", "deleted"), "delete-confirmation"], queryKeys.dashboards.draft("ws", "user", "deleted"), queryKeys.dashboards.draftResult("ws", "user", "deleted", "run"), queryKeys.dashboards.session("ws", "user", "session"), queryKeys.dashboards.sessionQueryRuns("ws", "user", "session", null, 50), queryKeys.dashboards.sessionRun("ws", "user", "session", "run"), queryKeys.dashboards.result("ws", "user", "session", "run"), queryKeys.dashboards.schedules("ws", "user", "deleted", false)];
    const refreshed = [queryKeys.dashboards.history("ws", "user", "deleted"), queryKeys.dashboards.list("ws", "user"), queryKeys.pages.directory("ws", "user"), ["overview", "ws", "user", "recent"], queryKeys.search.resources("ws", "user", "deleted"), queryKeys.lineage.focus("ws", "user", "METRIC", "dependent")];
    const untouched = [queryKeys.dashboards.detail("other-ws", "user", "deleted"), queryKeys.dashboards.detail("ws", "other-user", "deleted"), queryKeys.dashboards.session("ws", "user", "other-session"), queryKeys.dashboards.result("ws", "user", "other-session", "retained"), queryKeys.explore.run("ws", "user", "retained")];
    for (const key of [...removed, ...refreshed, ...untouched]) client.setQueryData(key, "cached");
    await refreshDashboardDefinitionCaches(client, "ws", "user", "deleted", true);
    for (const key of [sessions, ...removed]) expect(client.getQueryData(key)).toBeUndefined();
    for (const key of refreshed) expect(client.getQueryState(key)?.isInvalidated).toBe(true);
    for (const key of untouched) expect(client.getQueryData(key)).toBe("cached");
    expect(client.getQueryState(queryKeys.explore.run("ws", "user", "retained"))?.isInvalidated).toBe(false);
  });
  it("finds directly opened Sessions even when no Session list was loaded", async () => {
    const client = new QueryClient();
    const session = queryKeys.dashboards.session("ws", "user", "direct");
    const result = queryKeys.dashboards.result("ws", "user", "direct", "run");
    client.setQueryData(session, { dashboardId: "deleted" });
    client.setQueryData(result, "artifact");
    await refreshDashboardDefinitionCaches(client, "ws", "user", "deleted", true);
    expect(client.getQueryData(session)).toBeUndefined(); expect(client.getQueryData(result)).toBeUndefined();
  });
  it("refreshes History and current reads after restoring the original id", async () => {
    const client = new QueryClient();
    const keys = [queryKeys.dashboards.detail("ws", "user", "restored"), queryKeys.dashboards.history("ws", "user", "restored")];
    for (const key of keys) client.setQueryData(key, "old");
    await refreshDashboardDefinitionCaches(client, "ws", "user", "restored", false);
    for (const key of keys) expect(client.getQueryState(key)?.isInvalidated).toBe(true);
  });
});
