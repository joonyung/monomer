import { afterEach, describe, expect, it, vi } from "vitest";
import {
  buildSemanticModelDsl,
  formatSourceRelation,
  semanticModelAPI,
  type CreateSemanticModelRequest,
} from "@/api/semantic-model";

const WORKSPACE_ID = "workspace_1";

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

const request: CreateSemanticModelRequest = {
  name: "orders",
  label: "Orders",
  sourceRelations: [
    { connectorId: "postgres_analytics", schema: "main", relation: "orders" },
    { connectorId: "trino_prod", catalog: "lakehouse", schema: "mart", relation: "orders" },
  ],
  entities: [{ name: "order_id", type: "primary", expression: "order_id" }],
  dimensions: [{ isPartition: false, name: "ordered_at", type: "time", granularity: "day", expression: "ordered_at" }],
  measures: [{ name: "order_count", aggregation: "count", expression: "order_id" }],
  aggregationTimeDimension: "ordered_at",
};

describe("semantic model DSL", () => {
  it("keeps a required snapshot partition value and omits read metadata from writes", () => {
    const draft = { ...request, sourcePartitionInfo: [], dimensions: [{ ...request.dimensions[0], isPartition: true }] };
    expect(buildSemanticModelDsl(draft).dimensions[0].isPartition).toBe(true);
    expect(buildSemanticModelDsl(draft)).not.toHaveProperty("sourcePartitionInfo");
  });
  it("sends a declared timeKind for time dimensions only", () => {
    const draft: CreateSemanticModelRequest = {
      ...request,
      dimensions: [
        { isPartition: false, name: "ordered_at", type: "time", expression: "ordered_at", granularity: "day", timeKind: "local" },
        { isPartition: false, name: "status", type: "categorical", expression: "status", timeKind: "instant" },
      ],
    };
    const dimensions = buildSemanticModelDsl(draft).dimensions;
    expect(dimensions[0].timeKind).toBe("local");
    expect(dimensions[1]).not.toHaveProperty("timeKind");
  });

  it("keeps source relations as a connector-keyed list and required expressions", () => {
    expect(buildSemanticModelDsl(request)).toEqual({ schemaVersion: 3, ...request });
    expect(formatSourceRelation(request.sourceRelations[1])).toBe("lakehouse.mart.orders");
  });

  it("projects editor-only keys out of the persisted definition", () => {
    const uiDraft = {
      ...request,
      entities: request.entities.map((entity, position) => ({ ...entity, position })),
      dimensions: request.dimensions.map((dimension, position) => ({ ...dimension, position })),
      measures: request.measures.map((measure, position) => ({ ...measure, position })),
    };

    const definition = buildSemanticModelDsl(uiDraft);

    expect(definition.entities[0]).not.toHaveProperty("position");
    expect(definition.dimensions[0]).not.toHaveProperty("position");
    expect(definition.measures[0]).not.toHaveProperty("position");
  });
});

describe("semantic model API routes", () => {
  afterEach(() => vi.restoreAllMocks());

  it("writes the current DSL with LWW update semantics", async () => {
    const model = { sourcePartitionInfo: [], semanticModelId: "model-1", workspaceId: WORKSPACE_ID, definition: { schemaVersion: 3, ...request }, createdAt: "2026-08-19T00:00:00Z", updatedAt: "2026-08-19T00:00:00Z" };
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse({ model, aggregateMetrics: [] }),
    );

    await expect(semanticModelAPI.update(WORKSPACE_ID, "model-1", { definition: request, aggregateMetrics: [] }, "model-update-intent-1")).resolves.toMatchObject({ model: { id: "model-1" }, aggregateMetrics: [] });

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe(
      `http://127.0.0.1:11077/v2/workspaces/${WORKSPACE_ID}/semantic/semantic-models/model-1`,
    );
    expect((init as RequestInit).method).toBe("PUT");
    const body = JSON.parse((init as RequestInit).body as string);
    expect(body.definition).toEqual({ schemaVersion: 3, ...request });
    expect(body.aggregateMetrics).toEqual([]);
    expect(new Headers((init as RequestInit).headers).get("Idempotency-Key")).toBe("model-update-intent-1");
    expect(body).not.toHaveProperty("expectedDefinitionDigest");
    expect(body).not.toHaveProperty("publication");
  });

  it("creates a model and explicitly requested metrics in one write and reads their IDs", async () => {
    const aggregateMetrics = [{ measure: "order_count", name: "placed_orders", label: "Placed orders", description: "All placed orders." }];
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      model: { sourcePartitionInfo: [], semanticModelId: "model-1", workspaceId: WORKSPACE_ID, definition: { schemaVersion: 3, ...request }, createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z" },
      aggregateMetrics: [{ sourcePartitionInfo: [], metricId: "metric-1", workspaceId: WORKSPACE_ID, definition: { schemaVersion: 3, name: "placed_orders", label: "Placed orders", description: "All placed orders.", type: "simple", semanticModelName: "orders", measure: "order_count" }, createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z" }],
    }));
    const saved = await semanticModelAPI.create(WORKSPACE_ID, { definition: request, aggregateMetrics }, "atomic-model-create");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(JSON.parse(fetchMock.mock.calls[0][1]!.body as string)).toEqual({ definition: { schemaVersion: 3, ...request }, aggregateMetrics });
    expect(saved).toMatchObject({ model: { id: "model-1" }, aggregateMetrics: [{ id: "metric-1", name: "placed_orders", label: "Placed orders", typeParams: { semanticModelName: "orders", measure: "order_count" } }] });
  });

  it("preserves an atomic write rejection's indexed field instead of starting a metric write", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ type: "about:blank", title: "Conflict", status: 409, detail: "The metric name is already in use.", code: "SEMANTIC_NAME_CONFLICT", instance: "/semantic-models", requestId: "atomic-test", traceId: "atomic-trace", retryable: false, violations: [{ field: "aggregateMetrics[0].name", code: "SEMANTIC_NAME_CONFLICT", message: "Choose another metric name." }] }), { status: 409, headers: { "Content-Type": "application/problem+json" } }));
    await expect(semanticModelAPI.create(WORKSPACE_ID, { definition: request, aggregateMetrics: [{ measure: "order_count", name: "placed_orders" }] }, "atomic-collision")).rejects.toMatchObject({ problem: { code: "SEMANTIC_NAME_CONFLICT", instance: "/semantic-models", requestId: "atomic-test", traceId: "atomic-trace", retryable: false, violations: [{ field: "aggregateMetrics[0].name", code: "SEMANTIC_NAME_CONFLICT", message: "Choose another metric name." }] } });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("reads the current collection as raw resources without server pagination", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse([
        { sourcePartitionInfo: [],
          semanticModelId: "model-1",
          workspaceId: WORKSPACE_ID,
          definition: { schemaVersion: 3, ...request },
          createdAt: "2026-08-19T00:00:00Z",
          updatedAt: "2026-08-19T00:00:00Z",
        },
      ]),
    );

    await expect(semanticModelAPI.list(WORKSPACE_ID)).resolves.toEqual([
      expect.objectContaining({ id: "model-1", name: "orders" }),
    ]);
    expect(String(fetchMock.mock.calls[0][0])).toBe(
      `http://127.0.0.1:11077/v2/workspaces/${WORKSPACE_ID}/semantic/semantic-models`,
    );
  });
});
