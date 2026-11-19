import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { reconcileDocumentReplacement } from "@/lib/query/invalidation";
import { refreshSemanticDefinitionReads } from "@/lib/query/semantic-definition-cache";
import { queryKeys } from "@/lib/query/keys";

function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
}

describe("resource mutation invalidation", () => {
  it.each(["create", "update"])(
    "refreshes a cached metric list before navigation after %s",
    async () => {
      const queryClient = createQueryClient();
      const listKey = queryKeys.metrics.all("workspace-a", "principal-a");
      let rows = ["before"];
      await queryClient.fetchQuery({
        queryKey: listKey,
        queryFn: () => Promise.resolve(rows),
      });
      rows = ["after"];

      await refreshSemanticDefinitionReads(queryClient, "workspace-a", "principal-a");

      expect(queryClient.getQueryData(listKey)).toEqual(["after"]);
    },
  );

  it.each(["create", "update"])(
    "refreshes a cached semantic-model list before navigation after %s",
    async () => {
      const queryClient = createQueryClient();
      const listKey = queryKeys.semanticModels.all("workspace-a", "principal-a");
      let rows = ["before"];
      await queryClient.fetchQuery({
        queryKey: listKey,
        queryFn: () => Promise.resolve(rows),
      });
      rows = ["after"];

      await refreshSemanticDefinitionReads(queryClient, "workspace-a", "principal-a");

      expect(queryClient.getQueryData(listKey)).toEqual(["after"]);
    },
  );

  it("marks Explore group-by options stale after a definition save", async () => {
    const queryClient = createQueryClient();
    const groupByKey = queryKeys.explore.semanticGroupBys("workspace-a", "principal-a", "connector-a", ["revenue", "orders"]);
    const otherActorKey = queryKeys.explore.semanticGroupBys("workspace-a", "principal-b", "connector-a", ["orders", "revenue"]);
    queryClient.setQueryData(groupByKey, ["order_date"]);
    queryClient.setQueryData(otherActorKey, ["order_date"]);

    await refreshSemanticDefinitionReads(queryClient, "workspace-a", "principal-a");

    expect(queryClient.getQueryState(groupByKey)?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(otherActorKey)?.isInvalidated).toBe(false);
  });

  it("marks lineage, search, semantic checks and activity stale after a definition save", async () => {
    const queryClient = createQueryClient();
    const derivedKeys = [
      queryKeys.lineage.focus("workspace-a", "principal-a", "METRIC", "metric-1"),
      queryKeys.search.resources("workspace-a", "principal-a", "revenue"),
      queryKeys.semanticCheck("workspace-a", "principal-a", "draft-1"),
      ["overview", "workspace-a", "principal-a", "recent", "all"],
      ["overview", "workspace-a", "principal-a", "activity-page", "live", ""],
    ];
    const untouchedKeys = [
      queryKeys.lineage.focus("workspace-a", "principal-b", "METRIC", "metric-1"),
      queryKeys.lineage.focus("workspace-b", "principal-a", "METRIC", "metric-1"),
      ["overview", "workspace-a", "principal-b", "recent", "all"],
      queryKeys.explore.run("workspace-a", "principal-a", "run-1"),
    ];
    for (const key of [...derivedKeys, ...untouchedKeys]) queryClient.setQueryData(key, "cached");

    await refreshSemanticDefinitionReads(queryClient, "workspace-a", "principal-a");

    for (const key of derivedKeys) expect(queryClient.getQueryState(key)?.isInvalidated).toBe(true);
    for (const key of untouchedKeys) expect(queryClient.getQueryState(key)?.isInvalidated).toBe(false);
  });

  it("refreshes latest document content and history without evicting immutable versions", async () => {
    const queryClient = createQueryClient();
    const latestKey = queryKeys.pages.documentContent(
      "workspace-a",
      "principal-a",
      "page-1",
      null,
    );
    const immutableKey = queryKeys.pages.documentContent(
      "workspace-a",
      "principal-a",
      "page-1",
      1,
    );
    const historyKey = queryKeys.pages.documentVersions(
      "workspace-a",
      "principal-a",
      "page-1",
      100,
    );
    let latest = "version-1";
    let history = [1];
    await Promise.all([
      queryClient.fetchQuery({ queryKey: latestKey, queryFn: () => latest }),
      queryClient.fetchQuery({ queryKey: historyKey, queryFn: () => history }),
    ]);
    queryClient.setQueryData(immutableKey, "immutable-version-1");
    latest = "version-2";
    history = [2, 1];

    await reconcileDocumentReplacement(
      queryClient,
      "workspace-a",
      "principal-a",
      "page-1",
      2,
    );

    expect(queryClient.getQueryData(latestKey)).toBe("version-2");
    expect(queryClient.getQueryData(historyKey)).toEqual([2, 1]);
    expect(queryClient.getQueryData(immutableKey)).toBe("immutable-version-1");
  });

});
