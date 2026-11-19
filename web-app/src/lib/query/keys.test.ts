import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { queryKeys } from "@/lib/query/keys";

describe("query key ownership", () => {
  it("keeps workspace and principal list identities distinct", () => {
    expect(queryKeys.connectors.list("workspace-a", "principal-a")).not.toEqual(
      queryKeys.connectors.list("workspace-b", "principal-a"),
    );
    expect(queryKeys.connectors.list("workspace-a", "principal-a")).not.toEqual(
      queryKeys.connectors.list("workspace-a", "principal-b"),
    );
  });

  it("scopes every protected resource under workspace and principal", () => {
    const workspaceId = "workspace-a";
    const principalId = "principal-a";
    const protectedKeys = [
      queryKeys.overview.counts(workspaceId, principalId),
      queryKeys.connectors.list(workspaceId, principalId),
      queryKeys.explore.run(workspaceId, principalId, "run-1"),
      queryKeys.semanticModels.detail(workspaceId, principalId, "model-a"),
      queryKeys.metrics.detail(workspaceId, principalId, "metric-a"),
      queryKeys.catalog.catalogs(workspaceId, principalId, "connector-a"),
      queryKeys.catalogIngest.active(workspaceId, principalId, "connector-a", null),
      queryKeys.pages.directory(workspaceId, principalId),
      queryKeys.workspaceSettings.authorizationAssignments(workspaceId, principalId),
    ];

    for (const protectedKey of protectedKeys) {
      expect(protectedKey.slice(0, 3)).toEqual(["protected", workspaceId, principalId]);
    }
  });

  it("separates ordinary resources for actors in the same workspace", () => {
    expect(
      queryKeys.metrics.detail("workspace-a", "principal-a", "metric-a"),
    ).not.toEqual(
      queryKeys.metrics.detail("workspace-a", "principal-b", "metric-a"),
    );
  });

  it("keeps Dashboard session lists and selected-session QueryRun history separate", () => {
    const sessions = queryKeys.dashboards.sessions("workspace-a", "principal-a", "dashboard-a", null, 20);
    const runs = queryKeys.dashboards.sessionQueryRuns("workspace-a", "principal-a", "session-a", null, 50);
    expect(sessions).not.toEqual(runs);
    expect(sessions.slice(0, 3)).toEqual(["protected", "workspace-a", "principal-a"]);
  });

  it("invalidates the Workspace role inventory without crossing actor keys", async () => {
    const queryClient = new QueryClient();
    const actorAKey = queryKeys.workspaceSettings.authorizationAssignments(
      "workspace-a",
      "actor-a",
    );
    const actorBKey = queryKeys.workspaceSettings.authorizationAssignments(
      "workspace-a",
      "actor-b",
    );
    queryClient.setQueryData(actorAKey, { authorizationRevision: "1" });
    queryClient.setQueryData(actorBKey, { authorizationRevision: "1" });

    await queryClient.invalidateQueries({
      queryKey: actorAKey,
      refetchType: "none",
    });

    expect(queryClient.getQueryCache().find({ queryKey: actorAKey })?.state.isInvalidated).toBe(true);
    expect(queryClient.getQueryCache().find({ queryKey: actorBKey })?.state.isInvalidated).toBe(false);
  });

  it("keeps a late old-principal response out of the new-principal key", async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const actorAKey = queryKeys.metrics.detail(
      "workspace-a",
      "actor-a",
      "metric-a",
    );
    const actorBKey = queryKeys.metrics.detail(
      "workspace-a",
      "actor-b",
      "metric-a",
    );
    let resolveActorA!: (value: { owner: string }) => void;
    const actorARequest = queryClient.fetchQuery({
      queryKey: actorAKey,
      queryFn: () => new Promise<{ owner: string }>((resolve) => {
        resolveActorA = resolve;
      }),
    });
    queryClient.setQueryData(actorBKey, { owner: "actor-b" });

    resolveActorA({ owner: "actor-a" });
    await actorARequest;

    expect(queryClient.getQueryData(actorAKey)).toEqual({ owner: "actor-a" });
    expect(queryClient.getQueryData(actorBKey)).toEqual({ owner: "actor-b" });
  });
});
