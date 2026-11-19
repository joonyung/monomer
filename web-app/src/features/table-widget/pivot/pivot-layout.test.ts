import { describe, expect, it } from "vitest";
import type { DashboardResultColumn } from "@/api/dashboard";
import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { pivotCell, pivotColumnSortable, pivotHeaderRowCount, pivotLayout, pivotRequestRows, pivotSourceRequests, pivotValueFractionDigits, sortedPivotBodyRows, type PivotGrid, type PivotLayout, type PivotRow, type TablePivot } from "./pivot-layout";

type Cell = string | boolean | null;
const column = (name: string, logicalType: string): DashboardResultColumn => ({ name, logicalType, nullable: true, display: null });
const REGION = column("dimension.orders.region", "TEXT");
const MONTH = column("metric_time.month", "DATE");
const REVENUE = column("metric.revenue", "DECIMAL");
const CUSTOMERS = column("metric.unique_customers", "INTEGER");

function result(queryRunId: string, columns: DashboardResultColumn[], rows: Cell[][]): NamedDashboardResult {
  return validateDashboardResult({ queryType: "SEMANTIC", reportingTimeZone: null, queryRunId, columns, rows });
}

const primary = result("primary", [REGION, MONTH, REVENUE, CUSTOMERS], [
  ["West", "2026-08-01", "1350.50", "42"],
  ["West", "2026-07-01", "1200.00", "40"],
  ["East", "2026-08-01", "980.00", "30"],
  ["North", "2026-07-01", null, "12"],
  ["North", "2026-08-01", "1500.00", "25"],
]);
const byRegion = result("by-region", [REGION, REVENUE, CUSTOMERS], [["North", "1500.00", "31"], ["West", "2550.50", "55"], ["East", "980.00", "30"]]);
const byMonth = result("by-month", [MONTH, REVENUE, CUSTOMERS], [["2026-08-01", "3830.50", "88"], ["2026-07-01", "1200.00", "50"]]);
const grand = result("grand", [REVENUE, CUSTOMERS], [["5030.50", "104"]]);

const pivot: TablePivot = {
  rows: [{ field: "dimension.orders.region", header: "Region", width: 160 }],
  columns: [{ field: "metric_time.month", header: "Month" }],
  values: [{ field: "metric.revenue", header: "Revenue", width: 120 }, { field: "metric.unique_customers", header: "Customers" }],
  valuesAxis: "COLUMNS",
  totals: ["revenueByRegion", "revenueByMonth", "revenueTotal"],
};
const allTotals = [{ queryName: "revenueByRegion", result: byRegion }, { queryName: "revenueByMonth", result: byMonth }, { queryName: "revenueTotal", result: grand }];

function grid(layout: PivotLayout): PivotGrid {
  if (layout.kind !== "GRID") throw new Error(layout.message);
  return layout.grid;
}

// Reads the grid as text: raw values, "" for an empty cell, and labels for headers.
function table(model: PivotGrid, rows: readonly PivotRow[] = [...model.bodyRows, ...model.totalRows]): Cell[][] {
  return rows.map((row) => model.columns.map((candidate) => {
    const cell = pivotCell(model, row, candidate);
    switch (cell.kind) {
      case "ROW_HEADER": case "VALUE": case "TOTAL": return cell.value;
      case "VALUE_NAME": return cell.label;
      case "TOTAL_LABEL": return "Total";
      case "EMPTY": return "";
    }
  }));
}

function errorOf(layout: PivotLayout) {
  if (layout.kind !== "ERROR") throw new Error("Expected a pivot error.");
  return { code: layout.code, message: layout.message };
}

describe("pivot cell placement", () => {
  it("moves every value cell from exactly one primary result address and never aggregates", () => {
    const model = grid(pivotLayout({ ...pivot, totals: undefined }, primary, []));
    expect(model.columns.map((candidate) => candidate.id)).toEqual([
      "row:dimension.orders.region",
      "value:0:metric.revenue", "value:0:metric.unique_customers",
      "value:1:metric.revenue", "value:1:metric.unique_customers",
    ]);
    expect(table(model)).toEqual([
      ["West", "1200.00", "40", "1350.50", "42"],
      ["East", "", "", "980.00", "30"],
      ["North", null, "12", "1500.00", "25"],
    ]);
    const west = model.bodyRows[0];
    expect(pivotCell(model, west, model.columns[1])).toEqual({ kind: "VALUE", field: "metric.revenue", value: "1200.00", sourceRowIndex: 1 });
    expect(pivotCell(model, west, model.columns[3])).toEqual({ kind: "VALUE", field: "metric.revenue", value: "1350.50", sourceRowIndex: 0 });
    expect(pivotCell(model, model.bodyRows[2], model.columns[0])).toEqual({ kind: "ROW_HEADER", field: "dimension.orders.region", value: "North", sourceRowIndex: 3 });
    expect(model.rowKeys[0].sourceRowIndices).toEqual([0, 1]);
  });

  it("keeps a missing combination empty and distinct from a null value", () => {
    const model = grid(pivotLayout({ ...pivot, totals: undefined }, primary, []));
    expect(pivotCell(model, model.bodyRows[1], model.columns[1])).toEqual({ kind: "EMPTY" });
    expect(pivotCell(model, model.bodyRows[2], model.columns[1])).toMatchObject({ kind: "VALUE", value: null, sourceRowIndex: 3 });
  });

  it("rejects a repeated row and column pair with the field labels instead of merging it", () => {
    const duplicate = result("duplicate", [REGION, MONTH, REVENUE, CUSTOMERS], [...primary.rows.map((row) => Object.values(row)), ["East", "2026-08-01", "1.00", "1"]]);
    expect(errorOf(pivotLayout(pivot, duplicate, allTotals))).toEqual({
      code: "DUPLICATE_KEY",
      message: 'More than one row has Region "East" and Month "2026-08-01". Each row and column pair must appear once.',
    });
    const unlabeled = { ...pivot, rows: [{ field: "dimension.orders.region" }], columns: [{ field: "metric_time.month" }] };
    expect(errorOf(pivotLayout(unlabeled, duplicate, [])).message).toContain('dimension.orders.region "East" and metric_time.month "2026-08-01"');
  });

  it("keeps row keys in first-appearance order of the result", () => {
    const model = grid(pivotLayout(pivot, primary, allTotals));
    expect(model.rowKeys.map((key) => key.values)).toEqual([["West"], ["East"], ["North"]]);
  });

  it("closes the table when a field is missing from the result", () => {
    expect(errorOf(pivotLayout({ ...pivot, values: [{ field: "metric.missing" }] }, primary, []))).toEqual({ code: "MISSING_COLUMN", message: "Column 'metric.missing' is missing from the result." });
  });
});

describe("pivot column keys", () => {
  const keyed = (keyColumns: DashboardResultColumn[], keys: Cell[][]) => grid(pivotLayout(
    { rows: [], columns: keyColumns.map((candidate) => ({ field: candidate.name })), values: [{ field: "metric.revenue" }], valuesAxis: "COLUMNS" },
    result("keys", [...keyColumns, REVENUE], keys.map((key) => [...key, "1"])),
    [],
  )).columnKeys;

  it("sorts exact decimals, instants, booleans and code points ascending with null last", () => {
    expect(keyed([column("dimension.size", "DECIMAL")], [["10"], [null], ["9007199254740993"], ["9.5"], ["9007199254740992.5"]]))
      .toEqual([["9.5"], ["10"], ["9007199254740992.5"], ["9007199254740993"], [null]]);
    expect(keyed([column("dimension.at", "TIMESTAMP")], [["2026-09-05T23:30:00Z"], ["2026-09-06T08:00:00+09:00"], ["2026-09-05T22:00:00Z"]]))
      .toEqual([["2026-09-05T22:00:00Z"], ["2026-09-06T08:00:00+09:00"], ["2026-09-05T23:30:00Z"]]);
    expect(keyed([column("dimension.active", "BOOLEAN")], [[true], [null], [false]])).toEqual([[false], [true], [null]]);
    expect(keyed([column("dimension.name", "TEXT")], [["b"], ["B"], ["a"], ["Ä"]])).toEqual([["B"], ["a"], ["b"], ["Ä"]]);
  });

  it("sorts multiple column fields lexicographically, outer field first", () => {
    expect(keyed([column("dimension.year", "INTEGER"), column("dimension.channel", "TEXT")], [["2026", "Web"], ["2025", "Web"], ["2026", null], ["2026", "Store"], ["2025", "Store"]]))
      .toEqual([["2025", "Store"], ["2025", "Web"], ["2026", "Store"], ["2026", "Web"], ["2026", null]]);
  });

  it("limits generated value columns to 200 without truncating", () => {
    const days = (count: number) => Array.from({ length: count }, (_, index) => [new Date(Date.UTC(2020, 0, 1 + index)).toISOString().slice(0, 10)]);
    const wide = (count: number) => result("wide", [MONTH, REVENUE, CUSTOMERS], days(count).map(([day]) => [day, "1", "1"]));
    const twoValues: TablePivot = { rows: [], columns: [{ field: "metric_time.month" }], values: [{ field: "metric.revenue" }, { field: "metric.unique_customers" }], valuesAxis: "COLUMNS" };
    expect(errorOf(pivotLayout(twoValues, wide(170), []))).toEqual({ code: "TOO_MANY_COLUMNS", message: "This pivot would create 340 columns. The limit is 200. Narrow the query." });
    expect(grid(pivotLayout(twoValues, wide(100), [])).columns).toHaveLength(200);
    expect(grid(pivotLayout({ ...twoValues, valuesAxis: "ROWS" }, wide(200), [])).columns).toHaveLength(201);
    expect(errorOf(pivotLayout({ ...twoValues, valuesAxis: "ROWS" }, wide(201), [])).code).toBe("TOO_MANY_COLUMNS");
  });
});

describe("pivot headers", () => {
  it("spans the row field over a column key row and a value row, and groups each key over its values", () => {
    const model = grid(pivotLayout(pivot, primary, allTotals));
    expect(model.headerRows.map((cells) => cells.map((cell) => ({ kind: cell.kind, colSpan: "colSpan" in cell ? cell.colSpan : 1, rowSpan: "rowSpan" in cell ? cell.rowSpan : 1, columnIndex: cell.columnIndex })))).toEqual([
      [
        { kind: "ROW_FIELD", colSpan: 1, rowSpan: 2, columnIndex: 0 },
        { kind: "COLUMN_KEY", colSpan: 2, rowSpan: 1, columnIndex: null },
        { kind: "COLUMN_KEY", colSpan: 2, rowSpan: 1, columnIndex: null },
        { kind: "TOTAL", colSpan: 2, rowSpan: 1, columnIndex: null },
      ],
      [
        { kind: "VALUE", colSpan: 1, rowSpan: 1, columnIndex: 1 },
        { kind: "VALUE", colSpan: 1, rowSpan: 1, columnIndex: 2 },
        { kind: "VALUE", colSpan: 1, rowSpan: 1, columnIndex: 3 },
        { kind: "VALUE", colSpan: 1, rowSpan: 1, columnIndex: 4 },
        { kind: "TOTAL_VALUE", colSpan: 1, rowSpan: 1, columnIndex: 5 },
        { kind: "TOTAL_VALUE", colSpan: 1, rowSpan: 1, columnIndex: 6 },
      ],
    ]);
    expect(model.columns.map((candidate) => candidate.width)).toEqual([160, 120, 180, 120, 180, 120, 180]);
    expect(pivotHeaderRowCount(pivot)).toBe(2);
    expect(pivotHeaderRowCount({ ...pivot, valuesAxis: "ROWS" })).toBe(1);
    expect(pivotHeaderRowCount({ ...pivot, values: pivot.values.slice(0, 1) })).toBe(1);
  });

  it("adds one header row per column field and makes the innermost key the leaf for a single value", () => {
    const channel = column("dimension.orders.channel", "TEXT");
    const source = result("two-levels", [REGION, MONTH, channel, REVENUE], [
      ["West", "2026-07-01", "Web", "1"], ["West", "2026-07-01", "Store", "2"], ["West", "2026-08-01", "Web", "3"],
    ]);
    const model = grid(pivotLayout({ rows: [{ field: REGION.name }], columns: [{ field: MONTH.name }, { field: channel.name, header: "Channel" }], values: [{ field: REVENUE.name }], valuesAxis: "COLUMNS" }, source, []));
    expect(model.columnKeys).toEqual([["2026-07-01", "Store"], ["2026-07-01", "Web"], ["2026-08-01", "Web"]]);
    expect(model.headerRows.map((cells) => cells.map((cell) => cell.kind === "COLUMN_KEY" ? [cell.level, cell.columnKeyIndex, cell.colSpan, cell.columnIndex] : cell.kind))).toEqual([
      ["ROW_FIELD", [0, 0, 2, null], [0, 2, 1, null]],
      [[1, 0, 1, 1], [1, 1, 1, 2], [1, 2, 1, 3]],
    ]);
    expect(table(model)).toEqual([["West", "2", "1", "3"]]);
  });
});

describe("pivot values on rows", () => {
  const scorecard: TablePivot = { rows: [], columns: [{ field: MONTH.name, header: "Month" }], values: [{ field: REVENUE.name, header: "Revenue", width: 140 }, { field: CUSTOMERS.name }], valuesAxis: "ROWS", totals: ["revenueTotal"] };
  const months = result("months", [MONTH, REVENUE, CUSTOMERS], [["2026-08-01", "3830.50", "88"], ["2026-07-01", "1200.00", "50"], ["2026-09-01", "2600.00", "61"]]);
  const monthsTotal = result("months-total", [REVENUE, CUSTOMERS], [["7630.50", "140"]]);

  it("lists one row per value in declared order with a Values column and the empty-set total as the total column", () => {
    const model = grid(pivotLayout(scorecard, months, [{ queryName: "revenueTotal", result: monthsTotal }]));
    expect(model.columns.map((candidate) => [candidate.kind, candidate.width])).toEqual([["VALUE_NAME", 180], ["VALUE", 180], ["VALUE", 180], ["VALUE", 180], ["TOTAL", 180]]);
    expect(model.headerRows.map((cells) => cells.map((cell) => cell.kind))).toEqual([["VALUE_NAME", "COLUMN_KEY", "COLUMN_KEY", "COLUMN_KEY", "TOTAL"]]);
    expect(model.totalRows).toEqual([]);
    expect(table(model)).toEqual([
      ["Revenue", "1200.00", "3830.50", "2600.00", "7630.50"],
      ["metric.unique_customers", "50", "88", "61", "140"],
    ]);
  });

  it("repeats row field values on each value row and places column totals as one total row per value", () => {
    const model = grid(pivotLayout({ ...pivot, valuesAxis: "ROWS" }, primary, allTotals));
    expect(model.columns.map((candidate) => candidate.kind)).toEqual(["ROW_FIELD", "VALUE_NAME", "VALUE", "VALUE", "TOTAL"]);
    expect(model.headerRows.map((cells) => cells.map((cell) => [cell.kind, "rowSpan" in cell ? cell.rowSpan : 1]))).toEqual([[["ROW_FIELD", 1], ["VALUE_NAME", 1], ["COLUMN_KEY", 1], ["COLUMN_KEY", 1], ["TOTAL", 1]]]);
    expect(table(model)).toEqual([
      ["West", "Revenue", "1200.00", "1350.50", "2550.50"],
      ["West", "Customers", "40", "42", "55"],
      ["East", "Revenue", "", "980.00", "980.00"],
      ["East", "Customers", "", "30", "30"],
      ["North", "Revenue", null, "1500.00", "1500.00"],
      ["North", "Customers", "12", "25", "31"],
      ["Total", "Revenue", "1200.00", "3830.50", "5030.50"],
      ["Total", "Customers", "50", "88", "104"],
    ]);
  });

  it("sorts only by row fields and keeps each row key's values together in declared order", () => {
    const model = grid(pivotLayout({ ...pivot, valuesAxis: "ROWS" }, primary, allTotals));
    expect(model.columns.map((candidate) => pivotColumnSortable(model, candidate))).toEqual([true, false, false, false, false]);
    expect(sortedPivotBodyRows(model, { columnId: "row:dimension.orders.region", direction: "ascending" }).map((row) => row.id)).toEqual([
      "row:1:metric.revenue", "row:1:metric.unique_customers", "row:2:metric.revenue", "row:2:metric.unique_customers", "row:0:metric.revenue", "row:0:metric.unique_customers",
    ]);
    expect(sortedPivotBodyRows(model, { columnId: "value:1", direction: "ascending" })).toBe(model.bodyRows);
  });
});

describe("pivot totals", () => {
  it("places row, column and grand totals from their query results, which differ from summed cells", () => {
    const model = grid(pivotLayout(pivot, primary, allTotals));
    expect(model.columns.slice(5).map((candidate) => candidate.id)).toEqual(["total:metric.revenue", "total:metric.unique_customers"]);
    expect(table(model)).toEqual([
      ["West", "1200.00", "40", "1350.50", "42", "2550.50", "55"],
      ["East", "", "", "980.00", "30", "980.00", "30"],
      ["North", null, "12", "1500.00", "25", "1500.00", "31"],
      ["Total", "1200.00", "50", "3830.50", "88", "5030.50", "104"],
    ]);
    expect(pivotCell(model, model.totalRows[0], model.columns[6])).toEqual({ kind: "TOTAL", result: grand, field: "metric.unique_customers", value: "104" });
  });

  it("shows only the totals that were declared and leaves the corner empty without a grand total", () => {
    const model = grid(pivotLayout(pivot, primary, allTotals.slice(0, 2)));
    expect(table(model).at(-1)).toEqual(["Total", "1200.00", "50", "3830.50", "88", "", ""]);
    const rowOnly = grid(pivotLayout(pivot, primary, allTotals.slice(0, 1)));
    expect(rowOnly.totalRows).toEqual([]);
    expect(rowOnly.columns).toHaveLength(7);
    const grandOnly = grid(pivotLayout(pivot, primary, allTotals.slice(2)));
    expect(table(grandOnly).at(0)).toEqual(["West", "1200.00", "40", "1350.50", "42", "", ""]);
    expect(table(grandOnly).at(-1)).toEqual(["Total", "", "", "", "", "5030.50", "104"]);
  });

  it("places a totals result by its grouped result columns and ignores extra metric columns", () => {
    const extra = result("extra", [column("metric.order_count", "INTEGER"), REGION, CUSTOMERS, REVENUE], [["1", "West", "55", "2550.50"], ["2", "East", "30", "980.00"], ["3", "North", "31", "1500.00"]]);
    const model = grid(pivotLayout(pivot, primary, [{ queryName: "revenueByRegion", result: extra }]));
    expect(table(model).map((row) => row.slice(5))).toEqual([["2550.50", "55"], ["980.00", "30"], ["1500.00", "31"]]);
  });

  it("rejects totals that do not fit, collide, repeat a key, or miss a displayed key", () => {
    const both = result("both", [REGION, MONTH, REVENUE, CUSTOMERS], [["West", "2026-07-01", "1", "1"]]);
    expect(errorOf(pivotLayout(pivot, primary, [{ queryName: "byBoth", result: both }]))).toEqual({
      code: "TOTALS_PLACEMENT", message: 'The total query "byBoth" does not fit this pivot. Group it by all row fields, all column fields, or nothing.',
    });
    expect(errorOf(pivotLayout(pivot, primary, [allTotals[0], { queryName: "regionAgain", result: byRegion }]))).toEqual({
      code: "TOTALS_PLACEMENT", message: 'The total queries "revenueByRegion" and "regionAgain" group by the same fields. Each total query must fill a different place.',
    });
    const repeated = result("repeated", [REGION, REVENUE, CUSTOMERS], [...byRegion.rows.map((row) => Object.values(row)), ["East", "1", "1"]]);
    expect(errorOf(pivotLayout(pivot, primary, [{ queryName: "revenueByRegion", result: repeated }]))).toEqual({
      code: "TOTALS_DUPLICATE_KEY", message: 'The total query "revenueByRegion" has more than one row for Region "East". Each total must appear once.',
    });
    const truncated = result("truncated", [REGION, REVENUE, CUSTOMERS], [["West", "2550.50", "55"], ["East", "980.00", "30"]]);
    expect(errorOf(pivotLayout(pivot, primary, [{ queryName: "revenueByRegion", result: truncated }]))).toEqual({
      code: "TOTALS_MISSING_KEY", message: 'The total query "revenueByRegion" has no row for Region "North". Raise its rowLimit or match its filters.',
    });
    const oneMonth = result("one-month", [MONTH, REVENUE, CUSTOMERS], [["2026-07-01", "1200.00", "50"]]);
    expect(errorOf(pivotLayout(pivot, primary, [{ queryName: "revenueByMonth", result: oneMonth }])).message)
      .toBe('The total query "revenueByMonth" has no row for Month "2026-08-01". Raise its rowLimit or match its filters.');
    const twoGrand = result("two-grand", [REVENUE, CUSTOMERS], [["1", "1"], ["2", "2"]]);
    expect(errorOf(pivotLayout(pivot, primary, [{ queryName: "revenueTotal", result: twoGrand }]))).toEqual({
      code: "TOTALS_ROW_COUNT", message: 'The total query "revenueTotal" must return exactly one row. It returned 2.',
    });
    const noCustomers = result("no-customers", [REVENUE], [["1"]]);
    expect(errorOf(pivotLayout(pivot, primary, [{ queryName: "revenueTotal", result: noCustomers }]))).toEqual({
      code: "MISSING_COLUMN", message: "Column 'metric.unique_customers' is missing from the total query \"revenueTotal\".",
    });
  });

  it("treats a column-grouped total as not fitting when there are no row fields", () => {
    const noRows: TablePivot = { rows: [], columns: [{ field: MONTH.name }], values: [{ field: REVENUE.name }], valuesAxis: "COLUMNS" };
    const months = result("months", [MONTH, REVENUE], [["2026-07-01", "1"], ["2026-08-01", "2"]]);
    expect(errorOf(pivotLayout(noRows, months, [{ queryName: "byMonth", result: result("by-month", [MONTH, REVENUE], [["2026-07-01", "1"]]) }])).code).toBe("TOTALS_PLACEMENT");
    const model = grid(pivotLayout(noRows, months, [{ queryName: "total", result: result("total", [REVENUE], [["3"]]) }]));
    expect(table(model)).toEqual([["1", "2", "3"]]);
  });
});

describe("pivot sorting", () => {
  const model = grid(pivotLayout(pivot, primary, allTotals));
  const order = (columnId: string, direction: "ascending" | "descending") => sortedPivotBodyRows(model, { columnId, direction }).map((row) => model.rowKeys[row.rowKeyIndex].values[0]);

  it("sorts body rows by a value leaf with the typed comparator and keeps empty and null cells last", () => {
    expect(order("value:1:metric.revenue", "ascending")).toEqual(["East", "West", "North"]);
    expect(order("value:1:metric.revenue", "descending")).toEqual(["North", "West", "East"]);
    expect(order("value:0:metric.revenue", "ascending")).toEqual(["West", "East", "North"]);
    expect(order("value:0:metric.revenue", "descending")).toEqual(["West", "East", "North"]);
    expect(sortedPivotBodyRows(model, null)).toBe(model.bodyRows);
  });

  it("sorts by row fields and total columns while total rows stay outside the body", () => {
    expect(order("row:dimension.orders.region", "descending")).toEqual(["West", "North", "East"]);
    expect(order("total:metric.unique_customers", "descending")).toEqual(["West", "North", "East"]);
    expect(order("total:metric.revenue", "ascending")).toEqual(["East", "North", "West"]);
    expect(sortedPivotBodyRows(model, { columnId: "total:metric.revenue", direction: "ascending" }).every((row) => row.kind === "BODY")).toBe(true);
    expect(model.columns.every((candidate) => pivotColumnSortable(model, candidate))).toBe(true);
  });
});

describe("pivot request rows", () => {
  it("keeps the visible rows array itself while the selection stays within visible row keys", () => {
    const model = grid(pivotLayout(pivot, primary, allTotals));
    const visible = model.bodyRows.slice(0, 2);
    expect(pivotRequestRows(visible, undefined)).toBe(visible);
    expect(pivotRequestRows(visible, model.bodyRows[0])).toBe(visible);
    expect(pivotRequestRows(visible, model.bodyRows[1])).toBe(visible);
    expect(pivotRequestRows(visible, model.bodyRows[2])).toEqual([...visible, model.bodyRows[2]]);
    expect(pivotSourceRequests(model, pivotRequestRows(visible, model.bodyRows[2]))[1]).toEqual({ rowIndices: [0, 2, 3], fields: ["dimension.orders.region"] });
  });

  it("treats an off-screen value row of a visible row key as already requested in ROWS mode", () => {
    const rowsModel = grid(pivotLayout({ ...pivot, valuesAxis: "ROWS" }, primary, allTotals));
    const visible = rowsModel.bodyRows.slice(0, 3);
    expect(pivotRequestRows(visible, rowsModel.bodyRows[3])).toBe(visible);
    expect(pivotRequestRows(visible, rowsModel.bodyRows[4])).toEqual([...visible, rowsModel.bodyRows[4]]);
  });
});

describe("pivot source requests", () => {
  it("requests value cells at their source rows and row headers at each row key's first source row", () => {
    const model = grid(pivotLayout(pivot, primary, allTotals));
    expect(pivotSourceRequests(model, [model.bodyRows[0], model.bodyRows[2]])).toEqual([
      { rowIndices: [0, 1, 3, 4], fields: ["metric.revenue", "metric.unique_customers"] },
      { rowIndices: [0, 3], fields: ["dimension.orders.region"] },
    ]);
    expect(pivotSourceRequests(model, [model.bodyRows[1]])).toEqual([
      { rowIndices: [2], fields: ["metric.revenue", "metric.unique_customers"] },
      { rowIndices: [2], fields: ["dimension.orders.region"] },
    ]);
    expect(pivotSourceRequests(model, [])).toEqual([]);
  });

  it("requests each row key once in ROWS mode and no row headers without row fields", () => {
    const rowsModel = grid(pivotLayout({ ...pivot, valuesAxis: "ROWS" }, primary, allTotals));
    expect(pivotSourceRequests(rowsModel, rowsModel.bodyRows.slice(0, 3))).toEqual([
      { rowIndices: [0, 1, 2], fields: ["metric.revenue", "metric.unique_customers"] },
      { rowIndices: [0, 2], fields: ["dimension.orders.region"] },
    ]);
    const months = result("months", [MONTH, REVENUE], [["2026-07-01", "1"], ["2026-08-01", "2"]]);
    const noRows = grid(pivotLayout({ rows: [], columns: [{ field: MONTH.name }], values: [{ field: REVENUE.name }], valuesAxis: "COLUMNS" }, months, []));
    expect(pivotSourceRequests(noRows, noRows.bodyRows)).toEqual([{ rowIndices: [0, 1], fields: ["metric.revenue"] }]);
  });
});

describe("pivot value fraction digits", () => {
  it("measures each value field over its body and total cells together", () => {
    const COST = column("metric.cost", "DECIMAL");
    const source = result("digits", [REGION, MONTH, REVENUE, COST], [["West", "2026-07-01", "10.125", "1.5"], ["West", "2026-08-01", "20.5", "2.25"]]);
    const regionTotals = result("digits-by-region", [REGION, REVENUE, COST], [["West", "31", "3.754"]]);
    const digitsPivot: TablePivot = { rows: [{ field: REGION.name }], columns: [{ field: MONTH.name }], values: [{ field: REVENUE.name }, { field: COST.name }], valuesAxis: "COLUMNS" };
    expect(pivotValueFractionDigits(grid(pivotLayout(digitsPivot, source, [{ queryName: "byRegion", result: regionTotals }]))))
      .toEqual(new Map([["metric.revenue", 3], ["metric.cost", 3]]));
    expect(pivotValueFractionDigits(grid(pivotLayout(digitsPivot, source, [])))).toEqual(new Map([["metric.revenue", 3], ["metric.cost", 2]]));
  });
});
