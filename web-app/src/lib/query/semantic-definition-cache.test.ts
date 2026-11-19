import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { refreshSemanticDefinitionCaches, refreshSemanticDefinitionReads } from "./semantic-definition-cache";
import { queryKeys } from "./keys";

it("clears only the deleted detail and refreshes definition, activity, search and lineage reads", async () => {
  const client = new QueryClient();
  const detail = queryKeys.metrics.detail("ws", "user", "deleted");
  const history = queryKeys.metrics.history("ws", "user", "deleted");
  const related = [history, queryKeys.metrics.all("ws", "user"), queryKeys.semanticModels.all("ws", "user"), ["overview", "ws", "user", "recent"], queryKeys.search.resources("ws", "user", "deleted"), queryKeys.lineage.focus("ws", "user", "METRIC", "dependent"), queryKeys.explore.semanticGroupBys("ws", "user", "connector", ["deleted"])];
  const untouched = [queryKeys.metrics.detail("other-workspace", "user", "deleted"), queryKeys.metrics.detail("ws", "other-user", "deleted"), queryKeys.explore.run("ws", "user", "retained-run")];
  for (const key of [detail, ...related, ...untouched]) client.setQueryData(key, "cached");
  await refreshSemanticDefinitionCaches(client, "ws", "user", "metrics", "deleted", true);
  expect(client.getQueryData(detail)).toBeUndefined();
  for (const key of related) expect(client.getQueryState(key)?.isInvalidated).toBe(true);
  for (const key of untouched) expect(client.getQueryState(key)?.isInvalidated).toBe(false);
});

describe("restore cache refresh", () => {
  it("invalidates the current detail and its history after an original-id restore", async () => {
    const client = new QueryClient();
    const detail = queryKeys.semanticModels.detail("ws", "user", "restored");
    const history = queryKeys.semanticModels.history("ws", "user", "restored");
    client.setQueryData(detail, "old"); client.setQueryData(history, "old");
    await refreshSemanticDefinitionCaches(client, "ws", "user", "semantic-models", "restored", false);
    expect(client.getQueryState(detail)?.isInvalidated).toBe(true);
    expect(client.getQueryState(history)?.isInvalidated).toBe(true);
  });
});

describe("saved definition cache refresh", () => {
  it("refreshes inactive definition lists and marks derived lineage, search, check and activity reads stale", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const listKeys = [queryKeys.metrics.all("ws", "user"), queryKeys.semanticModels.all("ws", "user"), queryKeys.overview.counts("ws", "user")];
    let version = "before";
    for (const key of listKeys) await client.fetchQuery({ queryKey: key, queryFn: () => Promise.resolve(version) });
    const derived = [queryKeys.lineage.focus("ws", "user", "SEMANTIC_MODEL", "model"), queryKeys.search.resources("ws", "user", "orders"), queryKeys.semanticCheck("ws", "user", "draft"), ["overview", "ws", "user", "live"], queryKeys.explore.semanticGroupBys("ws", "user", "connector", ["orders"])];
    const untouched = [queryKeys.lineage.focus("ws", "other-user", "SEMANTIC_MODEL", "model"), queryKeys.catalog.all("ws", "user"), queryKeys.dashboards.list("ws", "user")];
    for (const key of [...derived, ...untouched]) client.setQueryData(key, "cached");
    version = "after";
    await refreshSemanticDefinitionReads(client, "ws", "user");
    for (const key of listKeys) expect(client.getQueryData(key)).toBe("after");
    for (const key of derived) expect(client.getQueryState(key)?.isInvalidated).toBe(true);
    for (const key of untouched) expect(client.getQueryState(key)?.isInvalidated).toBe(false);
  });
});
