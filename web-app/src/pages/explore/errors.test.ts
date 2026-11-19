import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api/client";
import { toExploreErrorPresentation } from "@/api/explore-errors";

describe("toExploreErrorPresentation", () => {
  it("retains canonical partition repair details on a start rejection", () => {
    const partitionFilters = [{ kind: "SEMANTIC_MODEL" as const, connectorId: "bq", relation: { catalog: "project", schema: "analytics", name: "orders" }, partitionKeys: ["order_date"], semanticModelName: "orders", dimensions: ["ordered_at"], timeRangeApplies: true }];
    const error = new ApiError("Partition filter required", 409, "{}", { type: "about:blank", title: "Conflict", status: 409, detail: "Partition filter required", instance: "/explore/semantic-runs", code: "PARTITION_FILTER_REQUIRED", requestId: "request", traceId: "trace", retryable: false, partitionFilters });
    expect(toExploreErrorPresentation(error)).toMatchObject({ code: "PARTITION_FILTER_REQUIRED", retryable: false, partitionFilters });
  });
  it("keeps safe Problem Details metadata needed for recovery and support", () => {
    const requestId = "srv_5ac15c33-f9ae-4e8a-9b01-73c80be5daa7";
    const error = new ApiError(
      "The warehouse is temporarily unavailable.",
      503,
      "{}",
      {
        type: "https://monomer.cc/problems/service-unavailable",
        title: "Service Unavailable",
        status: 503,
        detail: "The warehouse is temporarily unavailable.",
        instance: "/v2/workspaces/workspace-1/explore/sql-runs",
        code: "SERVICE_UNAVAILABLE",
        requestId,
        traceId: requestId,
        retryable: true,
      },
    );

    expect(toExploreErrorPresentation(error)).toEqual({
      code: "SERVICE_UNAVAILABLE",
      detail: "The warehouse is temporarily unavailable.",
      retryable: true,
      traceId: requestId,
      violations: [],
    });
  });

  it("keeps the violations that name broken definitions", () => {
    const error = new ApiError("METRICFLOW_MANIFEST_INVALID", 400, "{}", {
      type: "https://monomer.cc/problems/invalid-request",
      title: "Bad Request",
      status: 400,
      detail: "METRICFLOW_MANIFEST_INVALID",
      instance: "/v2/workspaces/workspace-1/explore/semantic-runs",
      code: "INVALID_REQUEST",
      requestId: "srv_test",
      traceId: "trace-1",
      retryable: false,
      violations: [{ field: "workspace/semantic-models/orders", code: "METRICFLOW_MANIFEST_INVALID", message: "Semantic model `orders` has no primary entity." }],
    });

    expect(toExploreErrorPresentation(error)?.violations).toEqual([
      { field: "workspace/semantic-models/orders", code: "METRICFLOW_MANIFEST_INVALID", message: "Semantic model `orders` has no primary entity." },
    ]);
  });

  it("does not display an API error message without valid Problem Details", () => {
    const error = new ApiError(
      "jdbc:postgresql://warehouse?password=secret",
      500,
      "{}",
    );

    expect(toExploreErrorPresentation(error)).toEqual({
      code: null,
      detail: "Request failed with 500.",
      retryable: false,
      traceId: null,
      violations: [],
    });
  });

  it("does not display an arbitrary runtime error message", () => {
    expect(toExploreErrorPresentation(new Error("credential=secret"))).toEqual({
      code: null,
      detail: "The request could not be completed.",
      retryable: false,
      traceId: null,
      violations: [],
    });
  });
});
