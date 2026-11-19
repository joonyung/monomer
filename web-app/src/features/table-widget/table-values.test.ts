import { describe, expect, it } from "vitest";
import { compareTableDecimals, compareTableTimestamps, defaultTableCellText, formatTableNumber, isNumericLogicalType, originalTableCellText, sortedTableRowIndices } from "./table-values";

describe("TABLE raw sorting", () => {
  it.each([
    ["9007199254740993", "9007199254740992", 1],
    ["0.00000000000000000000002", "0.0000000000000000000001", -1],
    ["-9007199254740993.000000000000000001", "-9007199254740993", -1],
    ["1e100000000000000000000", "9e99999999999999999999", 1],
    ["1e-100000000000000000000", "9e-99999999999999999999", -1],
    ["1.2300e3", "1230", 0],
    ["-0e99999", "0.0000", 0],
    ["-1e-99999", "0", -1],
  ])("compares %s and %s without decimal precision loss", (left, right, expected) => {
    expect(Math.sign(compareTableDecimals(left, right))).toBe(expected);
    expect(Math.sign(compareTableDecimals(right, left)) || 0).toBe(-expected || 0);
  });

  it("compares timestamp instants across offsets and retains fractional precision", () => {
    expect(compareTableTimestamps("2026-09-06T00:00:00.000000001Z", "2026-09-06T09:00:00.0000000010+09:00")).toBe(0);
    expect(compareTableTimestamps("2026-09-06T00:00:00.000000001Z", "2026-09-06T00:00:00.0000000011Z")).toBeLessThan(0);
    expect(compareTableTimestamps("2026-09-06T00:00:00+09:00", "2026-09-05T16:00:00Z")).toBeLessThan(0);
    expect(compareTableTimestamps("1969-12-31T23:59:59.999999999Z", "1970-01-01T00:00:00Z")).toBeLessThan(0);
  });

  it("keeps null last in either direction and ties in original result order", () => {
    const result = { queryType: "SEMANTIC" as const, reportingTimeZone: null,
      queryRunId: "result", columns: [{ name: "metric.value", logicalType: "NUMBER", nullable: true, numberFormat: null }],
      rows: [null, "1.0", "9007199254740993", "1", "9007199254740992", null].map((value) => ({ "metric.value": value })),
    };
    expect(sortedTableRowIndices(result, { field: "metric.value", direction: "ascending" })).toEqual([1, 3, 4, 2, 0, 5]);
    expect(sortedTableRowIndices(result, { field: "metric.value", direction: "descending" })).toEqual([2, 4, 1, 3, 0, 5]);
    expect(sortedTableRowIndices(result, null)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(result.rows[0]["metric.value"]).toBeNull();
  });

  it.each([
    ["INTEGER", ["10", "2", "9007199254740993", "9007199254740992", "2", null], [1, 4, 0, 3, 2, 5], [2, 3, 0, 1, 4, 5]],
    ["DECIMAL", ["10.001", "2.01", "9007199254740993.000000001", "9007199254740993.0000000001", "2.010", null], [1, 4, 0, 3, 2, 5], [2, 3, 0, 1, 4, 5]],
    ["FLOAT", ["10.5", "2.5", "1.25E10", "9.25E9", "2.50", null], [1, 4, 0, 3, 2, 5], [2, 3, 0, 1, 4, 5]],
  ])("sorts actual Dashboard result %s metadata numerically without rewriting it", (logicalType, values, ascending, descending) => {
    const result = { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: "product-result", columns: [{ name: "metric.value", logicalType, nullable: true, numberFormat: null }], rows: values.map((value) => ({ "metric.value": value })) };
    expect(sortedTableRowIndices(result, { field: "metric.value", direction: "ascending" })).toEqual(ascending);
    expect(sortedTableRowIndices(result, { field: "metric.value", direction: "descending" })).toEqual(descending);
    expect(result.columns[0].logicalType).toBe(logicalType);
    expect(result.rows.map((row) => row["metric.value"])).toEqual(values);
  });

  it.each(["TEXT", "DATE", "BOOLEAN"])("sorts canonical %s values", (logicalType) => {
    const values = logicalType === "BOOLEAN" ? [true, false] : logicalType === "DATE" ? ["2026-09-06", "2026-08-31"] : ["zebra", "apple"];
    const result = { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: "result", columns: [{ name: "field", logicalType, nullable: false, numberFormat: null }], rows: values.map((field) => ({ field })) };
    expect(sortedTableRowIndices(result, { field: "field", direction: "ascending" })).toEqual([1, 0]);
  });

  it.each(["DECIMAL", "FLOAT"])("orders canonical %s nonfinite strings and keeps null last with stable ties", (logicalType) => {
    const values = [null, "NaN", "-999999999999999999999999999999999999999999", "-Infinity", "Infinity", "0", "NaN", "-Infinity", "Infinity", null];
    const result = { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: "product-result", columns: [{ name: "metric.value", logicalType, nullable: true, numberFormat: null }], rows: values.map((value) => ({ "metric.value": value })) };
    expect(sortedTableRowIndices(result, { field: "metric.value", direction: "ascending" })).toEqual([3, 7, 2, 5, 4, 8, 1, 6, 0, 9]);
    expect(sortedTableRowIndices(result, { field: "metric.value", direction: "descending" })).toEqual([1, 6, 4, 8, 5, 2, 3, 7, 0, 9]);
  });
});

it("distinguishes null, empty text, missing, and canonical copy content", () => {
  expect([null, "", undefined].map(defaultTableCellText)).toEqual(["null", "(empty string)", "(missing)"]);
  expect([null, "", false, "9007199254740993"].map(originalTableCellText)).toEqual(["null", "", "false", "9007199254740993"]);
});

describe("TABLE numeric display", () => {
  it.each([
    ["1234567", "1,234,567"],
    ["-1234.5", "-1,234.5"],
    ["+999", "999"],
    ["9007199254740993", "9,007,199,254,740,993"],
    ["0.000000000000000000001", "0.000000000000000000001"],
    ["1.2300e3", "1.2300e3"],
    ["NaN", "NaN"],
    ["Infinity", "Infinity"],
  ])("formats %s as %s without changing digits", (raw, display) => {
    expect(formatTableNumber(raw, "en-US")).toBe(display);
  });

  it("uses the locale's grouping and decimal separators", () => {
    expect(formatTableNumber("1234567.89", "de-DE")).toBe("1.234.567,89");
  });

  it("recognises numeric logical types only", () => {
    expect(["INTEGER", "DECIMAL", "FLOAT", "NUMBER"].every(isNumericLogicalType)).toBe(true);
    expect(isNumericLogicalType("TEXT")).toBe(false);
    expect(isNumericLogicalType(undefined)).toBe(false);
  });
});
