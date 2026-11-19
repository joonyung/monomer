import { describe, expect, it } from "vitest";
import type { DashboardDefinition } from "@/api/dashboard";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { queryGroupingText, widgetMetricSources } from "./widget-metrics";

type DashboardQuery = DashboardDefinition["queries"][string];

function query(metrics: string[], groupBys: Extract<DashboardQuery, { type: "SEMANTIC" }>["query"]["groupBys"] = []): DashboardQuery {
  return {
    type: "SEMANTIC",
    connectorId: "00000000-0000-4000-8000-000000000001",
    query: { schemaVersion: 4, metrics, groupBys, metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 },
  };
}

const byMonth = { kind: "METRIC_TIME", granularity: "MONTH" } as const;
const byChannel = { kind: "DIMENSION", semanticModelName: "orders", name: "channel" } as const;

const queries: DashboardDefinition["queries"] = {
  revenue_total: query(["gross_revenue"]),
  revenue_by_month: query(["gross_revenue", "orders"], [byMonth, byChannel]),
  targets: query(["revenue_target"], [byMonth]),
  warehouse_total: { type: "SQL", connectorId: "connector", sql: "select count(*) as total", bindings: {}, columns: ["total"], rowLimit: 10 },
};

describe("widget metric sources", () => {
  it("lists each metric a widget's queries use once, in query order", () => {
    expect(widgetMetricSources(queries, ["revenue_by_month", "revenue_total", "targets"])).toEqual({
      metricNames: ["gross_revenue", "orders", "revenue_target"],
      queries: [
        { queryName: "revenue_by_month", groupBys: [byMonth, byChannel] },
        { queryName: "revenue_total", groupBys: [] },
        { queryName: "targets", groupBys: [byMonth] },
      ],
    });
  });

  it("skips query names the definition does not declare, including inherited object keys", () => {
    expect(widgetMetricSources(queries, ["missing", "toString", "revenue_total"])).toEqual({
      metricNames: ["gross_revenue"],
      queries: [{ queryName: "revenue_total", groupBys: [] }],
    });
    expect(widgetMetricSources(queries, [])).toEqual({ metricNames: [], queries: [] });
  });

  it("keeps Semantic definitions for mixed widgets and skips SQL templates", () => {
    expect(widgetMetricSources(queries, ["warehouse_total", "revenue_total"])).toEqual({
      metricNames: ["gross_revenue"], queries: [{ queryName: "revenue_total", groupBys: [] }],
    });
  });
});

describe("query grouping text", () => {
  const ordersModel = {
    id: "model-orders", name: "orders", label: "Orders", sourceRelations: [], entities: [], measures: [],
    dimensions: [{ isPartition: false, name: "channel", type: "categorical", expression: "channel", label: "Sales channel" }],
    createdAt: "", updatedAt: "",
  } as SemanticModelResponse;

  it("says a query without group-bys returns one total", () => {
    expect(queryGroupingText([], [])).toBe("One total, not grouped");
  });

  it("names time grains and dimension labels in authored order", () => {
    expect(queryGroupingText([byMonth, byChannel], [ordersModel])).toBe("By Month, Sales channel");
  });

  it("falls back to the dimension's name before models load", () => {
    expect(queryGroupingText([byChannel, byMonth], [])).toBe("By channel, Month");
  });
});
