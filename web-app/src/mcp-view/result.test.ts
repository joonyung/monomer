import { describe, expect, it } from "vitest";
import { namedResultFromAgent, ResultNotShownError } from "./result";
import type { QueryResultBody } from "./contract";

const RUN = "workspace/dashboards/01920000-0000-7000-8000-0000000000d1/draft/query-runs/01920000-0000-7000-8000-000000000101";
const body: QueryResultBody = {
  queryType: "SEMANTIC",
  columns: [
    { name: "dimension.region", logicalType: "TEXT", nullable: false, display: null },
    { name: "metric.revenue", logicalType: "DECIMAL", nullable: true, display: { style: "CURRENCY", decimals: 2, currency: "USD" } },
  ],
  rows: [["EMEA", "1200.5"], [null, null]],
  totalRowCount: "2",
  returnedRowCount: 2,
  truncated: false,
  reportingTimeZone: "UTC",
};

describe("agent result conversion", () => {
  it("produces the same frozen named result the Builder and Viewer render", () => {
    const result = namedResultFromAgent(RUN, body);
    expect(result.queryRunId).toBe(RUN);
    expect(result.queryType).toBe("SEMANTIC");
    expect(result.columns).toEqual([
      { name: "dimension.region", logicalType: "TEXT", nullable: false, numberFormat: null },
      { name: "metric.revenue", logicalType: "DECIMAL", nullable: true, numberFormat: { style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay: "negative" } },
    ]);
    expect(result.rows).toEqual([{ "dimension.region": "EMEA", "metric.revenue": "1200.5" }, { "dimension.region": null, "metric.revenue": null }]);
    expect(Object.isFrozen(result.rows[0])).toBe(true);
  });

  it("shows the returned rows of a truncated result, as the Web does", () => {
    const truncated = namedResultFromAgent(RUN, { ...body, totalRowCount: "5000", truncated: true });
    expect(truncated.rows).toHaveLength(2);
  });

  it("does not show inconsistent results", () => {
    expect(() => namedResultFromAgent(RUN, { ...body, returnedRowCount: 3 })).toThrow(ResultNotShownError);
    expect(() => namedResultFromAgent(RUN, { ...body, rows: [["EMEA"]], returnedRowCount: 1 })).toThrow("row width");
  });
});
