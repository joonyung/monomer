import { afterEach, describe, expect, it, vi } from "vitest";
import {
  buildMetricDsl,
  createMetric,
  definitionDependentMetrics,
  referencedModelNames,
  getMetric,
  listMetrics,
  parseMetricDsl,
  type WriteMetricRequest,
  updateMetric,
} from "@/api/metric";

const WORKSPACE_ID = "workspace_1";

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

const request: WriteMetricRequest = {
  name: "revenue_per_customer",
  type: "ratio",
  typeParams: {
    numerator: {
      metricName: "revenue",
      filter: {
        or: [
          { dimension: { semanticModelName: "orders", name: "channel" }, operator: "eq", values: ["web"] },
          { dimension: { semanticModelName: "orders", name: "channel" }, operator: "eq", values: ["app"] },
        ],
      },
      alias: "revenue_web_or_app",
    },
    denominator: { metricName: "customers", offsetWindow: "30 days" },
  },
};

describe("metric DSL", () => {
  it("round-trips display independently of the calculation and omits Default", () => {
    const display = { style: "PERCENT" as const, decimals: 1 };
    const definition = buildMetricDsl({ ...request, display });
    expect(definition.display).toEqual(display);
    expect(parseMetricDsl("metric-display", definition, "created", "updated").display).toEqual(display);
    expect(buildMetricDsl({ ...request, display: null })).not.toHaveProperty("display");
    expect(parseMetricDsl("metric-default", buildMetricDsl(request), "created", "updated").display).toBeNull();
  });
  it("keeps recursive filters and omits publication/version fields", () => {
    expect(buildMetricDsl(request)).toEqual({
      schemaVersion: 3,
      name: request.name,
      type: request.type,
      numerator: {
        metricName: "revenue",
        alias: "revenue_web_or_app",
        filter: {
          or: [
            { dimension: { semanticModelName: "orders", name: "channel" }, operator: "eq", values: ["web"] },
            { dimension: { semanticModelName: "orders", name: "channel" }, operator: "eq", values: ["app"] },
          ],
        },
      },
      denominator: { metricName: "customers", offsetWindow: "30 days" },
    });
  });

  it("round-trips null predicates with an explicit empty values array", async () => {
    const definition = {
      ...buildMetricDsl({
        name: "missing_channel",
        type: "simple",
        typeParams: { semanticModelName: "orders", measure: "order_count" },
        filters: [
          {
            dimension: { semanticModelName: "orders", name: "channel" },
            operator: "is_null",
            values: [],
          },
        ],
      }),
    };
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse({
        metricId: "metric-null-filter",
        workspaceId: WORKSPACE_ID,
        definition,
        createdAt: "2026-08-19T00:00:00Z",
        updatedAt: "2026-08-19T00:00:00Z",
      }),
    );

    const metric = await getMetric(WORKSPACE_ID, "metric-null-filter");

    expect((definition.filters as Array<Record<string, unknown>>)[0]).toHaveProperty("values", []);
    expect(metric.filters?.[0]).toEqual({
      dimension: { semanticModelName: "orders", name: "channel" },
      operator: "is_null",
      values: [],
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    fetchMock.mockRestore();
  });
});

describe("metric API routes", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
  });

  it("lists metrics through the Control workspace API", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse([]),
    );

    await expect(listMetrics(WORKSPACE_ID)).resolves.toEqual([]);

    const [url] = fetchMock.mock.calls[0];
    expect(String(url)).toBe(
      `http://127.0.0.1:11077/v2/workspaces/${WORKSPACE_ID}/semantic/metrics`,
    );
  });

  it("creates and updates using the current DSL and LWW", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse({ sourcePartitionInfo: [], metricId: "metric-1", workspaceId: WORKSPACE_ID, definition: { schemaVersion: 3, ...buildMetricDsl(request) }, createdAt: "2026-08-19T00:00:00Z", updatedAt: "2026-08-19T00:00:00Z" }))
      .mockResolvedValueOnce(jsonResponse({ sourcePartitionInfo: [], metricId: "metric-1", workspaceId: WORKSPACE_ID, definition: { schemaVersion: 3, ...buildMetricDsl(request) }, createdAt: "2026-08-19T00:00:00Z", updatedAt: "2026-08-19T00:00:00Z" }));

    await createMetric(WORKSPACE_ID, request, "metric-create-intent-1");
    await updateMetric(WORKSPACE_ID, "metric-1", request, "metric-update-intent-1");

    const updateBody = JSON.parse((fetchMock.mock.calls[1][1] as RequestInit).body as string);
    expect(new Headers((fetchMock.mock.calls[1][1] as RequestInit).headers).get("Idempotency-Key")).toBe("metric-update-intent-1");
    expect(updateBody.definition).toEqual({ schemaVersion: 3, ...buildMetricDsl(request) });
    expect(updateBody).not.toHaveProperty("expectedDefinitionDigest");
    expect(updateBody).not.toHaveProperty("publication");
  });

});


describe("definition delete impact", () => {
  const metric = (name: string, definition: Record<string, unknown>) => parseMetricDsl(name, { name, schemaVersion: 3, ...definition }, "", "");
  const condition = (semanticModelName: string) => ({ dimension: { semanticModelName, name: "region" }, operator: "eq", values: ["US"] });

  it("counts recursive conditions on every metric input and the definition itself", () => {
    const current = metric("ratio", { type: "ratio", numerator: { metricName: "sales", filter: { not: { or: [condition("customers"), condition("regions")] } } }, denominator: { metricName: "orders", filter: condition("markets") }, filters: [{ and: [condition("accounts")] }] });
    expect([...referencedModelNames(current)]).toEqual(["accounts", "customers", "regions", "markets"]);
  });

  it("includes filter-only model reads and their indirect metric dependents without cycles or duplicates", () => {
    const sales = metric("sales", { type: "simple", semanticModelName: "orders", measure: "amount" });
    const filtered = metric("filtered", { type: "cumulative", inputMetric: { metricName: "sales", filter: condition("customers") } });
    const indirect = metric("indirect", { type: "derived", expression: "filtered", inputMetrics: [{ metricName: "filtered" }, { metricName: "cycle" }] });
    const cycle = metric("cycle", { type: "cumulative", inputMetric: { metricName: "indirect" } });
    const unrelated = metric("unrelated", { type: "simple", semanticModelName: "unrelated", measure: "count" });
    const metrics = [sales, filtered, indirect, cycle, unrelated];
    expect(definitionDependentMetrics("semantic-models", "customers", metrics).map((item) => item.name)).toEqual(["filtered", "indirect", "cycle"]);
    expect(definitionDependentMetrics("metrics", "sales", metrics).map((item) => item.name)).toEqual(["filtered", "indirect", "cycle"]);
  });
});
