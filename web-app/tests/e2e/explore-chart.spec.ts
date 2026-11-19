import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import { evidencePath } from "./evidence";
import { exploreRunView, openExploreRuns, REGION_GROUP_BY, runPanel } from "./explore-runs-api-fixture";

const MONTH = { kind: "METRIC_TIME", granularity: "MONTH" };
const runRow = (page: Page, title: string) => page.locator('aside[aria-label="Explore runs"]:visible button').filter({ hasText: title });

function semanticQuery(metric: string, groupBys: unknown[]) {
  return {
    schemaVersion: 4,
    metrics: [metric],
    groupBys,
    metricTimeRange: null,
    filters: [],
    orderBy: [{ target: { kind: "METRIC", metricName: metric }, direction: "DESC" }],
    rowLimit: 1000,
  };
}

function resultMetadata(rowCount: string) {
  return { byteSize: "2048", mediaType: "application/vnd.apache.arrow.file", rowCount, columnCount: 3, expiresAt: "2099-01-01T00:00:00Z" };
}

// Rows arrive sorted by revenue, not by month: the chart must still draw a forward time axis.
const lineRun = exploreRunView(0xe01, {
  state: "SUCCEEDED",
  createdAt: "2026-08-24T09:30:00Z",
  updatedAt: "2026-08-24T09:30:02Z",
  query: semanticQuery("gross_revenue", [MONTH, REGION_GROUP_BY]),
  resultMetadata: resultMetadata("6"),
  result: {
    columns: [
      { ordinal: 1, name: "metric_time.month", logicalType: "DATE", nullable: false },
      { ordinal: 2, name: "dimension.orders.region", logicalType: "TEXT", nullable: false },
      { ordinal: 3, name: "metric.gross_revenue", logicalType: "DECIMAL", nullable: false },
    ],
    rows: [
      ["2026-08-01", "EU", "498320.75"],
      ["2026-07-01", "EU", "431015.25"],
      ["2026-06-01", "EU", "412880.00"],
      ["2026-08-01", "UK", "149870.00"],
      ["2026-07-01", "UK", "141770.00"],
      ["2026-06-01", "UK", "138240.50"],
    ],
    returnedRowCount: 6,
    stopReason: "END_OF_RESULT",
  },
});

const incompleteRun = exploreRunView(0xe02, {
  state: "SUCCEEDED",
  createdAt: "2026-08-24T09:20:00Z",
  updatedAt: "2026-08-24T09:20:02Z",
  query: semanticQuery("churned_accounts", [MONTH]),
  resultMetadata: resultMetadata("12480"),
  result: {
    columns: [
      { ordinal: 1, name: "metric_time.month", logicalType: "DATE", nullable: false },
      { ordinal: 2, name: "metric.churned_accounts", logicalType: "INTEGER", nullable: false },
    ],
    rows: [["2026-06-01", "41"], ["2026-07-01", "38"], ["2026-08-01", "35"]],
    returnedRowCount: 3,
    stopReason: "ROW_LIMIT",
  },
});

const sqlRun = exploreRunView(0xe03, {
  kind: "SQL",
  state: "SUCCEEDED",
  createdAt: "2026-08-24T09:10:00Z",
  updatedAt: "2026-08-24T09:10:01Z",
  sql: "SELECT region, SUM(amount) FROM analytics.orders GROUP BY 1",
  resultMetadata: resultMetadata("2"),
  result: {
    columns: [
      { ordinal: 1, name: "region", logicalType: "TEXT", nullable: false },
      { ordinal: 2, name: "sum", logicalType: "DECIMAL", nullable: false },
    ],
    rows: [["EU", "1342216.00"], ["UK", "429880.50"]],
    returnedRowCount: 2,
    stopReason: "END_OF_RESULT",
  },
});

// No sort in the question, rows out of time order, and decimals at a warehouse's fixed scale.
const unsortedMonthRun = exploreRunView(0xe04, {
  state: "SUCCEEDED",
  createdAt: "2026-08-24T09:05:00Z",
  updatedAt: "2026-08-24T09:05:02Z",
  query: { ...semanticQuery("product_events", [MONTH]), orderBy: [] },
  resultMetadata: resultMetadata("3"),
  result: {
    columns: [
      { ordinal: 1, name: "metric_time.month", logicalType: "DATE", nullable: false },
      { ordinal: 2, name: "metric.product_events", logicalType: "DECIMAL", nullable: false },
    ],
    rows: [["2026-07-01", "1250.500000000"], ["2026-06-01", "980.250000000"], ["2026-08-01", "1100.000000000"]],
    returnedRowCount: 3,
    stopReason: "END_OF_RESULT",
  },
});

const totalsRun = exploreRunView(0xe05, {
  state: "SUCCEEDED",
  createdAt: "2026-08-24T09:04:00Z",
  updatedAt: "2026-08-24T09:04:02Z",
  query: { ...semanticQuery("refunded_orders", []), metrics: ["refunded_orders", "churned_accounts"], orderBy: [] },
  resultMetadata: resultMetadata("1"),
  result: {
    columns: [
      { ordinal: 1, name: "metric.refunded_orders", logicalType: "DECIMAL", nullable: true },
      { ordinal: 2, name: "metric.churned_accounts", logicalType: "INTEGER", nullable: true },
    ],
    rows: [["1342216.500000000", null]],
    returnedRowCount: 1,
    stopReason: "END_OF_RESULT",
  },
});

const extraRuns = [lineRun, incompleteRun, sqlRun, unsortedMonthRun, totalsRun];

// Hourly values arrive as wall-clock times in the zone the run was rendered in, not as UTC instants.
const seoulHourlyRun = exploreRunView(0xe06, {
  state: "SUCCEEDED",
  createdAt: "2026-08-24T09:40:00Z",
  updatedAt: "2026-08-24T09:40:02Z",
  query: { ...semanticQuery("gross_revenue", [{ kind: "METRIC_TIME", granularity: "HOUR" }]), orderBy: [] },
  reportingTimeZone: "Asia/Seoul",
  resultMetadata: resultMetadata("2"),
  result: {
    columns: [
      { ordinal: 1, name: "metric_time.hour", logicalType: "DATETIME", nullable: false },
      { ordinal: 2, name: "metric.gross_revenue", logicalType: "DECIMAL", nullable: false },
    ],
    rows: [["2026-08-24T09:00:00.000000", "1200.50"], ["2026-08-24T10:00:00.000000", "980.25"]],
    returnedRowCount: 2,
    stopReason: "END_OF_RESULT",
  },
});

test("names the zone a Semantic run's times are in and shows them as its wall clock", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openExploreRuns(page, { extraRuns: [seoulHourlyRun, lineRun] });

  await runRow(page, "Gross revenue by Hour").click();
  const panel = runPanel(page, "Gross revenue by Hour");
  const status = panel.getByRole("tablist", { name: "Result view" }).locator("..");
  await expect(status).toContainText("2 rows · Times in Asia/Seoul");
  const firstHour = panel.getByTestId("run-sample-grid").locator("tbody tr").first().locator("td").first();
  await expect(firstHour).toHaveText("Aug 24, 2026, 09:00");
  await expect(panel.getByTestId("run-sample-grid")).not.toContainText("UTC");
  await panel.screenshot({ path: evidencePath("../screenshots/explore-result-time-zone.png") });

  await runRow(page, "Gross revenue by Month, Region").click();
  const recordedBeforeZones = runPanel(page, "Gross revenue by Month, Region");
  await expect(recordedBeforeZones.getByRole("tablist", { name: "Result view" }).locator("..")).toContainText("6 rows");
  await expect(recordedBeforeZones).not.toContainText("Times in");
});

test("draws a complete time-axis Semantic result as a line chart with a series per category", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openExploreRuns(page, { extraRuns });

  await runRow(page, "Gross revenue by Month, Region").click();
  const panel = runPanel(page, "Gross revenue by Month, Region");
  await expect(panel.getByRole("tablist", { name: "Result view" }).locator("..")).toContainText("6 rows");
  const firstMonth = panel.getByTestId("run-sample-grid").locator("tbody tr").first().locator("td").first();
  await expect(firstMonth).toHaveText("Aug 2026");
  await expect(firstMonth).toHaveAttribute("title", "2026-08-01");
  await panel.getByRole("tab", { name: "Chart", exact: true }).click();
  await expect(panel.getByRole("tab", { name: "Chart", exact: true })).toHaveAttribute("aria-selected", "true");

  await expect(panel.getByRole("radiogroup", { name: "Metric to chart" }), "one metric needs no chart heading or switch").toHaveCount(0);
  const chart = panel.getByTestId("dashboard-chart-widget");
  await expect(chart).toHaveAttribute("data-rendered-revision", "0", { timeout: 15_000 });
  await expect(chart).toHaveAttribute("data-chart-status", "ready");
  await expect(chart.getByRole("img", { name: "Gross revenue by Metric time · Month chart" })).toBeVisible();
  await expect(panel.getByText("Charts need a time axis or one category.")).toHaveCount(0);
  await expect(panel.getByText("Rows are drawn as returned — no re-aggregation.")).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/explore-chart-line.png"), fullPage: true });
});

test("refuses to chart a result whose preview is shorter than the full result", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openExploreRuns(page, { extraRuns });

  await runRow(page, "Churned accounts by Month").click();
  const panel = runPanel(page, "Churned accounts by Month");
  await expect(panel).toContainText("First 3 of 12,480 rows");
  await panel.getByRole("tab", { name: "Chart", exact: true }).click();
  await expect(panel.getByText(
    "Chart needs the complete result — 12,480 rows is more than 3. Use a coarser granularity, fewer group-bys, or a filter.",
  )).toBeVisible();
  await expect(panel.getByTestId("dashboard-chart-widget")).toHaveCount(0);
});

test("offers only Table and SQL for a SQL run", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openExploreRuns(page, { extraRuns });

  await runRow(page, "orders · SELECT region").click();
  const panel = runPanel(page, /^orders · SELECT region/);
  const resultTabs = panel.getByRole("tablist", { name: "Result view" });
  await expect(resultTabs.getByRole("tab")).toHaveText(["Table", "SQL"]);
  await expect(resultTabs.getByRole("tab", { name: "Chart" })).toHaveCount(0);
  await expect(panel.getByTestId("run-sample-grid")).toContainText("1,342,216.00");
});

test("opens a question without a sort in time order, with decimals at the digits they need", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openExploreRuns(page, { extraRuns });

  await runRow(page, "Product events by Month").click();
  const panel = runPanel(page, "Product events by Month");
  const grid = panel.getByTestId("run-sample-grid");
  await expect(grid.getByRole("columnheader", { name: "Metric time (month)" })).toHaveAttribute("aria-sort", "ascending");
  await expect(grid.locator("tbody tr td:first-child")).toHaveText(["Jun 2026", "Jul 2026", "Aug 2026"]);
  await expect(grid.locator("tbody tr td:nth-child(2)")).toHaveText(["980.25", "1,250.50", "1,100.00"]);
  await expect(grid.locator("tbody tr").first().locator("td").nth(1)).toHaveAttribute("title", "980.250000000");
  expect(await grid.evaluate((element) => element.scrollWidth - element.clientWidth), "the filler column never widens the grid past its view").toBeLessThanOrEqual(1);
});

test("shows a question without group-bys as one number per metric", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openExploreRuns(page, { extraRuns });

  await runRow(page, "Refunded orders, Churned accounts").click();
  const panel = runPanel(page, "Refunded orders, Churned accounts");
  await panel.getByRole("tab", { name: "Chart", exact: true }).click();
  const values = panel.getByRole("region", { name: "Values" });
  await expect(values).toContainText("Refunded orders1,342,216.50");
  await expect(values).toContainText("Churned accountsNo value");
  await expect(panel.getByText("Charts need a time axis or one category.")).toHaveCount(0);
  await panel.screenshot({ path: evidencePath("../screenshots/explore-chart-values.png") });
});
