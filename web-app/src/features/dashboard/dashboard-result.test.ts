import { describe, expect, it } from "vitest";
import type { DashboardResult } from "@/api/dashboard";
import { toChartQueryResult, validateDashboardResult } from "@/features/dashboard/dashboard-result";

const validResult = { queryType: "SEMANTIC" as const, reportingTimeZone: null,
  queryRunId: "run-1",
  columns: [
    { name: "dimension.orders.status", logicalType: "TEXT", nullable: false, display: null },
    { name: "dimension.shipments.status", logicalType: "TEXT", nullable: true, display: null },
    { name: "dimension.orders.created_at.day", logicalType: "DATE", nullable: true, display: null },
    { name: "dimension.orders.created_at.month", logicalType: "DATE", nullable: true, display: null },
    { name: "entity.orders.customer", logicalType: "TEXT", nullable: true, display: null },
    { name: "metric_time.day", logicalType: "DATE", nullable: true, display: null },
    { name: "metric.order_count", logicalType: "NUMBER", nullable: false, display: null },
  ],
  rows: [[
    "COMPLETED",
    "SHIPPED",
    "2026-08-24",
    "2026-08",
    "Acme",
    "2026-08-24",
    "12345678901234567890.12345678901234567",
  ], [
    "REFUNDED",
    null,
    null,
    null,
    null,
    null,
    "0",
  ]],
} satisfies DashboardResult;

describe("dashboard result projection", () => {
  it("projects stored Semantic display to frozen worker options and never infers formats for SQL", () => {
    const wire = { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: "display-run",
      columns: [{ name: "metric.amount", logicalType: "DECIMAL", nullable: false, display: { style: "CURRENCY" as const, decimals: 2, currency: "USD" as const } }],
      rows: [["9007199254740993.125"]],
    };
    const result = validateDashboardResult(JSON.parse(JSON.stringify(wire)));
    const column = result.columns[0];
    expect(column).toEqual({ name: "metric.amount", logicalType: "DECIMAL", nullable: false,
      numberFormat: { style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay: "negative" } });
    expect(Object.isFrozen(column.numberFormat)).toBe(true);
    expect(result.rows[0]["metric.amount"]).toBe("9007199254740993.125");
    expect(validateDashboardResult({ ...wire, queryType: "SQL" }).columns[0].numberFormat).toBeNull();
  });
  it("preserves recorded SQL query type without changing the worker ABI", () => {
    const result = validateDashboardResult({ ...validResult, queryType: "SQL" });
    expect(result.queryType).toBe("SQL");
    expect(Object.keys(toChartQueryResult(result))).toEqual(["columns", "rows"]);
  });
  it("rejects result metadata that omits the recorded query type", () => {
    expect(() => validateDashboardResult({ ...validResult, queryType: undefined } as unknown as DashboardResult)).toThrow("unsupported query type");
  });
  it("validates row width and keeps exact cells in named immutable records", () => {
    const result = validateDashboardResult(validResult);

    expect(result.rows[0]).toEqual({
      "dimension.orders.status": "COMPLETED",
      "dimension.shipments.status": "SHIPPED",
      "dimension.orders.created_at.day": "2026-08-24",
      "dimension.orders.created_at.month": "2026-08",
      "entity.orders.customer": "Acme",
      "metric_time.day": "2026-08-24",
      "metric.order_count": "12345678901234567890.12345678901234567",
    });
    expect(result.rows[1]).toEqual({
      "dimension.orders.status": "REFUNDED",
      "dimension.shipments.status": null,
      "dimension.orders.created_at.day": null,
      "dimension.orders.created_at.month": null,
      "entity.orders.customer": null,
      "metric_time.day": null,
      "metric.order_count": "0",
    });
    expect(Object.isFrozen(result)).toBe(true);
    expect(Object.isFrozen(result.columns)).toBe(true);
    expect(result.columns.every(Object.isFrozen)).toBe(true);
    expect(Object.isFrozen(result.rows[0])).toBe(true);
    const chartResult = toChartQueryResult(result);
    expect(chartResult.columns).toEqual(validResult.columns.map(({ name, logicalType, nullable }) => ({ name, logicalType, nullable, numberFormat: null })));
    expect(chartResult.rows[0]?.["metric.order_count"]).toBe("12345678901234567890.12345678901234567");
    expect(chartResult.rows[1]?.["dimension.shipments.status"]).toBeNull();
  });

  it("preserves empty metadata and exact canonical scalar cells for charts", () => {
    const emptyResult = toChartQueryResult(validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null,
      queryRunId: "empty-run",
      columns: [{ name: "metric.orders.amount", logicalType: "DECIMAL", nullable: true, display: null }],
      rows: [],
    }));
    expect(emptyResult.columns).toEqual([{ name: "metric.orders.amount", logicalType: "DECIMAL", nullable: true, numberFormat: null }]);
    expect(emptyResult.rows).toEqual([]);
    expect(Object.isFrozen(emptyResult.columns)).toBe(true);
    expect(Object.isFrozen(emptyResult.rows)).toBe(true);

    const exactResult = toChartQueryResult(validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null,
      queryRunId: "exact-run",
      columns: [
        { name: "dimension.orders.identifier", logicalType: "TEXT", nullable: false, display: null },
        { name: "metric.orders.amount", logicalType: "DECIMAL", nullable: false, display: null },
        { name: "dimension.orders.enabled", logicalType: "BOOLEAN", nullable: false, display: null },
        { name: "dimension.orders.note", logicalType: "TEXT", nullable: true, display: null },
      ],
      rows: [["00123", "12345678901234567890.12345678901234567", true, null]],
    }));
    const row = exactResult.rows[0];
    expect(exactResult.columns).toEqual([
      { name: "dimension.orders.identifier", logicalType: "TEXT", nullable: false, numberFormat: null },
      { name: "metric.orders.amount", logicalType: "DECIMAL", nullable: false, numberFormat: null },
      { name: "dimension.orders.enabled", logicalType: "BOOLEAN", nullable: false, numberFormat: null },
      { name: "dimension.orders.note", logicalType: "TEXT", nullable: true, numberFormat: null },
    ]);
    expect(row?.["dimension.orders.identifier"]).toBe("00123");
    expect(row?.["metric.orders.amount"]).toBe("12345678901234567890.12345678901234567");
    expect(row?.["dimension.orders.enabled"]).toBe(true);
    expect(row?.["dimension.orders.note"]).toBeNull();
    expect(typeof row?.["dimension.orders.identifier"]).toBe("string");
    expect(typeof row?.["metric.orders.amount"]).toBe("string");
  });

  it("rejects duplicate columns, malformed positional rows, and unsupported cells", () => {
    expect(() => validateDashboardResult({
      ...validResult,
      columns: [validResult.columns[1], validResult.columns[1]],
      rows: [["COMPLETED", "SHIPPED"]],
    })).toThrow(/duplicate|empty/i);
    expect(() => validateDashboardResult({
      ...validResult,
      rows: [["12345678901234567890.12345678901234567"]],
    })).toThrow(/row width/i);
    expect(() => validateDashboardResult({
      ...validResult,
      rows: [["COMPLETED", "SHIPPED", "2026-08-24", "2026-08", "Acme", "2026-08-24", 123 as never]],
    })).toThrow(/unsupported cell/i);
  });
});


it("preserves local datetime metadata and microseconds across restored dashboard and chart data", () => {
  const source: DashboardResult = { queryType: "SEMANTIC" as const, reportingTimeZone: null,
    queryRunId: "datetime-run",
    columns: [{ name: "local_time", logicalType: "DATETIME", nullable: true, display: null }],
    rows: [["1969-12-31T23:59:59.999999"], ["2024-02-29T12:34:56.123456"], [null]],
  };
  const restored = validateDashboardResult(JSON.parse(JSON.stringify(source)));
  expect(restored.columns[0].logicalType).toBe("DATETIME");
  expect(toChartQueryResult(restored).rows.map((row) => row.local_time)).toEqual(source.rows.map((row) => row[0]));
});
