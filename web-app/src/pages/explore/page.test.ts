import { afterEach, describe, expect, it, vi } from "vitest";
import { normalizeSemanticDraft, questionForMetric, selectActiveExploreRun } from "@/pages/explore/page";
import { draftFromRun, emptyExploreDraft, NO_UNAVAILABLE_SELECTIONS, unavailableSelections } from "@/pages/explore/explore-draft";
import { exploreAPI, type ExploreRun } from "@/api/explore";

const run = (runId: string): ExploreRun => ({
  kind: "SQL",
  runId,
  workspaceId: "workspace-1",
  connectorId: "connector-1",
  requestDigest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  state: "SUCCEEDED",
  failureCode: null,
  failureDetail: null,
  createdAt: "2026-08-26T00:00:00Z",
  updatedAt: "2026-08-26T00:01:00Z",
  resultMetadata: null,
  result: null,
  sql: "SELECT 1",
  resultState: null,
  startedBy: { id: "user-1", displayName: "Sam Lee" },
  credentialSurface: null, agentApp: null,
});

describe("selectActiveExploreRun", () => {
  it("keeps the response for the currently selected run", () => {
    const selected = run("run-1");

    expect(selectActiveExploreRun("run-1", selected, null)).toBe(selected);
  });

  it("drops a late response that belongs to a previous selection", () => {
    expect(selectActiveExploreRun("run-2", run("run-1"), null)).toBeNull();
  });

  it("drops retained data when the selected run read is no longer authorized", () => {
    expect(selectActiveExploreRun("run-1", run("run-1"), new Error("403"))).toBeNull();
  });
});

describe("questionForMetric", () => {
  it("asks a fresh question with only the linked metric and no chosen connector", () => {
    expect(questionForMetric("revenue")).toEqual({ ...emptyExploreDraft().semantic, metrics: ["revenue"] });
  });
});

describe("Semantic draft maintenance", () => {
  const region = { kind: "DIMENSION" as const, semanticModelName: "orders", name: "region", granularity: null };
  const month = { kind: "METRIC_TIME" as const, granularity: "MONTH" as const };
  const draft = {
    ...emptyExploreDraft().semantic,
    metrics: ["revenue"],
    groupBys: [month, region],
    filters: [{ kind: "TEXT" as const, dimension: { semanticModelName: "orders", name: "region" }, operator: "EQUALS" as const, values: ["EU"] }],
    orderBy: [
      { target: { kind: "METRIC" as const, metricName: "revenue" }, direction: "DESC" as const },
      { target: { kind: "GROUP_BY" as const, groupBy: region }, direction: "ASC" as const },
    ],
  };

  it("drops sort orders whose metric or group-by left the question", () => {
    expect(normalizeSemanticDraft(draft)).toBe(draft);
    expect(normalizeSemanticDraft({ ...draft, metrics: [] }).orderBy).toEqual([draft.orderBy[1]]);
    expect(normalizeSemanticDraft({ ...draft, groupBys: [month] }).orderBy).toEqual([draft.orderBy[0]]);
  });

  it("names the group-bys and filters discovery no longer offers without removing them", () => {
    expect(unavailableSelections(draft, [month, region])).toBe(NO_UNAVAILABLE_SELECTIONS);
    expect(unavailableSelections(draft, [month])).toEqual({ groupBys: [region], filters: draft.filters });
  });
});

describe("Semantic run restore against group-by discovery", () => {
  afterEach(() => vi.restoreAllMocks());

  const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
  // Control writes a run's group-by with only its own fields, and a discovery option with every field.
  const recordedRun = {
    kind: "SEMANTIC",
    runId: "run-1",
    workspaceId: "workspace-1",
    connectorId: "connector-1",
    requestDigest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    state: "SUCCEEDED",
    resultState: "EXPIRED",
    query: {
      schemaVersion: 4,
      metrics: ["revenue"],
      groupBys: [{ kind: "METRIC_TIME", granularity: "MONTH" }],
      metricTimeRange: null,
      filters: [],
      orderBy: [{ target: { kind: "GROUP_BY", groupBy: { kind: "METRIC_TIME", granularity: "MONTH" } }, direction: "ASC" }],
      rowLimit: 1000,
    },
  };
  const metricTimeOption = (granularity: string) => ({ kind: "METRIC_TIME", semanticModelName: null, name: null, granularity });
  const regionOption = { kind: "DIMENSION", semanticModelName: "orders", name: "region", granularity: null };

  it("keeps a restored Metric time · Month runnable when discovery lists metric time grains", async () => {
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(json(recordedRun))
      .mockResolvedValueOnce(json({ groupBys: [regionOption, ...["DAY", "MONTH", "YEAR"].map(metricTimeOption)], excludedSemanticModels: [], dimensionFilterTypes: [] }));

    const run = await exploreAPI.readRun({ workspaceId: "workspace-1", runId: "run-1" });
    const discovery = await exploreAPI.listSemanticGroupBys({ workspaceId: "workspace-1", connectorId: "connector-1", metrics: ["revenue"] });
    const restored = draftFromRun(emptyExploreDraft(), run).semantic;

    expect(restored.groupBys).toEqual([{ kind: "METRIC_TIME", granularity: "MONTH" }]);
    expect(normalizeSemanticDraft(restored)).toBe(restored);
    expect(unavailableSelections(restored, discovery.groupBys)).toBe(NO_UNAVAILABLE_SELECTIONS);
  });

  it("marks the restored grain only when discovery doesn't offer it", async () => {
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(json(recordedRun))
      .mockResolvedValueOnce(json({ groupBys: [regionOption, metricTimeOption("DAY")], excludedSemanticModels: [], dimensionFilterTypes: [] }));

    const run = await exploreAPI.readRun({ workspaceId: "workspace-1", runId: "run-1" });
    const discovery = await exploreAPI.listSemanticGroupBys({ workspaceId: "workspace-1", connectorId: "connector-1", metrics: ["revenue"] });

    expect(unavailableSelections(draftFromRun(emptyExploreDraft(), run).semantic, discovery.groupBys))
      .toEqual({ groupBys: [{ kind: "METRIC_TIME", granularity: "MONTH" }], filters: [] });
  });
});
