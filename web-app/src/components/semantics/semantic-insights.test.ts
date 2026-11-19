import { describe, expect, it } from "vitest";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric, MetricFilter } from "@/lib/types/metric";
import type { MeasureAggregation } from "@/lib/types/semantic-model";
import { aggregateFormula, metricCatalogFormula, metricCatalogQualifiers, metricFilterText, needsCalculationDescription } from "./semantic-insights";

const model: SemanticModelResponse = {
  id: "orders-model", name: "orders", label: "Orders", sourceRelations: [], entities: [], dimensions: [],
  measures: [{ name: "customers", aggregation: "count_distinct", expression: "customer_id" }],
  createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z",
};
const metric: Metric = {
  id: "customers-metric", name: "customers", label: "Customers", type: "simple",
  typeParams: { semanticModelName: "orders", measure: "customers" }, createdAt: model.createdAt, updatedAt: model.updatedAt,
};

const filter: MetricFilter = { dimension: { semanticModelName: "orders", name: "region" }, operator: "eq", values: ["US"] };

describe("metric catalog stored formulas", () => {
  it("shows an aggregate as its stored formula, never through its Label", () => {
    const labelled = { ...model, measures: [{ ...model.measures[0], label: "Customers" }] };
    expect(metricCatalogFormula(metric, [labelled])).toBe("COUNT(DISTINCT customer_id)");
    expect(aggregateFormula("count_distinct", "customer_id")).toBe("COUNT(DISTINCT customer_id)");
  });
  it.each<[MeasureAggregation, string]>([
    ["sum", "SUM(customer_id)"], ["count", "COUNT(customer_id)"],
    ["count_distinct", "COUNT(DISTINCT customer_id)"], ["average", "AVG(customer_id)"],
    ["min", "MIN(customer_id)"], ["max", "MAX(customer_id)"],
    ["median", "MEDIAN(customer_id)"], ["sum_boolean", "SUM_BOOLEAN(customer_id)"],
  ])("renders the saved %s aggregation with a SQL-style function name", (aggregation, expected) => {
    const aggregate = { ...model, measures: [{ ...model.measures[0], aggregation }] };
    expect(metricCatalogFormula(metric, [aggregate])).toBe(expected);
  });

  it("preserves a calculated measure expression verbatim", () => {
    const expression = " CASE WHEN order_status = 'Completed'\nTHEN order_id ELSE NULL END ";
    const calculated = { ...model, measures: [{ ...model.measures[0], label: "Completed orders", expression }] };
    expect(metricCatalogFormula(metric, [calculated])).toBe(`COUNT(DISTINCT ${expression})`);
  });

  it("uses saved metric names for division", () => {
    const ratio: Metric = { ...metric, type: "ratio", typeParams: { numerator: { metricName: "gross_revenue" }, denominator: { metricName: "completed_orders" } } };
    expect(metricCatalogFormula(ratio, [model])).toBe("gross_revenue / completed_orders");
  });

  it("names ratio input filters in reading form instead of serialized JSON", () => {
    const ratio: Metric = { ...metric, type: "ratio", typeParams: { numerator: { metricName: "gross_revenue", filter }, denominator: { metricName: "completed_orders" } } };
    expect(metricCatalogFormula(ratio, [model])).toBe("gross_revenue(filter: orders.region is US) / completed_orders");
  });

  it("renders a derived expression verbatim without replacing identifiers with Labels", () => {
    const expression = "customers * 100.0 / NULLIF(total_customers, 0)";
    const formula: Metric = { ...metric, type: "derived", typeParams: { expression, inputMetrics: [{ metricName: "customers" }, { metricName: "total_customers" }] } };
    expect(metricCatalogFormula(formula, [model])).toBe(expression);
  });

  it("keeps a Formula as written and lists its qualified inputs on the qualifier line", () => {
    const formula: Metric = { ...metric, type: "derived", typeParams: { expression: "current_customers - prior_customers", inputMetrics: [{ metricName: "customers", alias: "current_customers", filter }, { metricName: "customers", alias: "prior_customers", offsetWindow: "30 days", offsetToGrain: "month" }, { metricName: "orders" }] } };
    expect(metricCatalogFormula(formula, [model])).toBe("current_customers - prior_customers");
    expect(metricCatalogQualifiers(formula)).toEqual(["current_customers = customers(filter: orders.region is US)", "prior_customers = customers(offset: 30 days, offset to: month)"]);
  });

  it("renders a cumulative definition without inventing omitted settings", () => {
    const cumulative: Metric = { ...metric, type: "cumulative", typeParams: { inputMetric: { metricName: "gross_revenue" }, window: "30 days" } };
    expect(metricCatalogFormula(cumulative, [model])).toBe("CUMULATIVE(gross_revenue, window: 30 days)");
    expect(metricCatalogFormula({ ...cumulative, typeParams: { inputMetric: { metricName: "gross_revenue" } } }, [model])).toBe("CUMULATIVE(gross_revenue)");
  });

  it("retains cumulative grain, period aggregation, and input offsets", () => {
    const cumulative: Metric = { ...metric, type: "cumulative", typeParams: { inputMetric: { metricName: "gross_revenue", offsetWindow: "1 month" }, grainToDate: "year", periodAggregation: "last" } };
    expect(metricCatalogFormula(cumulative, [model])).toBe("CUMULATIVE(gross_revenue(offset: 1 month), grainToDate: year, periodAggregation: last)");
  });

  it("renders conversion names, entity, calculation, window, and constant properties from saved values", () => {
    const conversion: Metric = { ...metric, type: "conversion", typeParams: { baseMetric: { metricName: "visits" }, conversionMetric: { metricName: "orders" }, entity: "user", calculation: "conversion_rate", window: "7 days", constantProperties: [{ baseProperty: "campaign", conversionProperty: "campaign" }] } };
    expect(metricCatalogFormula(conversion, [model])).toBe("CONVERSION(visits → orders, entity: user, calculation: conversion_rate, window: 7 days, constantProperties: campaign = campaign)");
    expect(metricCatalogFormula({ ...conversion, typeParams: { baseMetric: { metricName: "visits", filter }, conversionMetric: { metricName: "orders", alias: "purchases" }, entity: "user", calculation: "conversions" } }, [model])).toBe("CONVERSION(visits(filter: orders.region is US) → orders(alias: purchases), entity: user, calculation: conversions)");
  });

  it("keeps metric filters and default grain off the formula, in reading form on the qualifier line", () => {
    const filtered: Metric = { ...metric, filters: [{ not: { or: [filter, { ...filter, operator: "is_null", values: [] }] } }], timeGranularity: "month" };
    expect(metricCatalogFormula(filtered, [model])).toBe("COUNT(DISTINCT customer_id)");
    expect(metricCatalogQualifiers(filtered)).toEqual(["Filter: not (orders.region is US or orders.region is empty)", "Default grain: Month"]);
    expect(metricFilterText({ dimension: { semanticModelName: "orders", name: "ordered_at", granularity: "month" }, operator: "between", values: ["2026-01", "2026-06"] })).toBe("orders.ordered_at by month is between 2026-01 and 2026-06");
    expect(metricCatalogQualifiers(metric)).toEqual([]);
  });

  it("keeps the stored model and measure reference when its aggregate cannot be resolved", () => {
    expect(metricCatalogFormula(metric, [])).toBe("orders.customers");
    expect(metricCatalogFormula(metric, [{ ...model, measures: [] }])).toBe("orders.customers");
  });
});

describe("calculated aggregate descriptions", () => {
  const expressionModel = { ...model, measures: [{ ...model.measures[0], expression: "CASE WHEN status = 'Completed' THEN order_id END" }] };
  it("asks for a description only when an aggregate over a calculated expression has none", () => {
    expect(needsCalculationDescription(metric, [expressionModel])).toBe(true);
    expect(needsCalculationDescription({ ...metric, description: "Completed order IDs." }, [expressionModel])).toBe(false);
    expect(needsCalculationDescription(metric, [model])).toBe(false);
  });
});
