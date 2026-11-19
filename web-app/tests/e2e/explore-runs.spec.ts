import { expect, test } from "./support/test";
import type { Locator, Page } from "@playwright/test";
import type { SemanticDimensionFilterType } from "../../src/api/explore";
import { evidencePath } from "./evidence";
import { EXPLORE_PATH, exploreRunView, FINANCE_CONNECTOR_ID, NORTHSTAR_CONNECTOR_ID, openExploreRuns, REGION_GROUP_BY, runPanel, selectMetric } from "./explore-runs-api-fixture";

const SANDSTONE = "rgb(193, 116, 45)";
const SQL_KEYWORD = "rgb(36, 102, 195)";
const EIGHT_LINE_SQL = [
  "SELECT",
  "  region,",
  "  SUM(gross_revenue) AS gross_revenue,",
  "  COUNT(*) AS orders,",
  "  AVG(order_value) AS avg_order_value",
  "FROM analytics.orders",
  "GROUP BY region",
  "ORDER BY gross_revenue DESC",
].join("\n");

async function enableFilterDiscoveryRefresh(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const nativeNow = Date.now;
    let clockOffset = 0;
    (window as typeof window & { refreshFilterDiscovery: () => void }).refreshFilterDiscovery = () => {
      clockOffset += 31_000;
      Date.now = () => nativeNow() + clockOffset;
      window.dispatchEvent(new Event("offline"));
      window.dispatchEvent(new Event("online"));
    };
  });
}

async function refreshFilterDiscovery(page: Page): Promise<void> {
  const response = page.waitForResponse((candidate) => new URL(candidate.url()).pathname.endsWith("/semantic/query/group-bys"));
  await page.evaluate(() => (window as typeof window & { refreshFilterDiscovery: () => void }).refreshFilterDiscovery());
  await (await response).finished();
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
}

// The fixture numbers started runs from 0xf10 (3856), padded in decimal.
const STARTED_RUN_ID = "019e8000-0000-7000-8000-000000003856";
const desktopRail = (page: Page) => page.getByRole("complementary", { name: "Explore runs" });
const runRow = (page: Page, title: string) => page.locator('aside[aria-label="Explore runs"]:visible button').filter({ hasText: title });
const question = (page: Page) => page.getByRole("tabpanel", { name: "Metrics" });

async function expectInkPulse(dot: Locator): Promise<void> {
  await expect(dot).toHaveAttribute("data-running", "true");
  await expect(dot).toHaveCSS("animation-name", "pulse-dot");
  const ink = await dot.evaluate(() => {
    const probe = document.createElement("span");
    probe.style.backgroundColor = "var(--foreground)";
    document.body.append(probe);
    const color = getComputedStyle(probe).backgroundColor;
    probe.remove();
    return color;
  });
  await expect(dot).toHaveCSS("background-color", ink);
}

async function chooseSqlConnector(page: Page, name: string): Promise<void> {
  await page.getByRole("button", { name: "Select connector" }).click();
  await page.getByRole("listbox", { name: "Connectors" }).getByRole("option", { name: new RegExp(name) }).click();
}

async function typeSql(page: Page, sql: string): Promise<void> {
  const editor = page.getByTestId("sql-editor").filter({ visible: true }).first();
  await expect(editor.locator(".monaco-editor")).toBeVisible();
  await editor.locator(".view-lines").click({ position: { x: 80, y: 16 } });
  await page.keyboard.insertText(sql);
}

for (const width of [1440, 390]) {
  test(`aligns the connector type mark with the question rows in Metrics and SQL at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await openExploreRuns(page);
    await selectMetric(page, "Gross revenue");

    const metricsConnector = question(page).getByTitle("Connector with the source tables of these metrics", { exact: true });
    await expect(metricsConnector).toContainText("Northstar warehouse");
    await expect(metricsConnector).toHaveCSS("height", "36px");
    const metricsMark = metricsConnector.locator("[data-connector-type-mark]");
    await expect(metricsMark).toBeVisible();
    const metricsMarkBox = await metricsMark.boundingBox();
    expect(metricsMarkBox).not.toBeNull();
    for (const label of ["Metrics", "Group by"]) {
      const rowLabel = question(page).getByLabel("Query", { exact: true }).getByText(label, { exact: true });
      await expect(rowLabel).toBeVisible();
      const rowLabelBox = await rowLabel.boundingBox();
      expect(rowLabelBox).not.toBeNull();
      expect(Math.abs(metricsMarkBox!.x - rowLabelBox!.x), `${label} shares the connector's content column`).toBeLessThanOrEqual(1);
    }

    await page.getByRole("tab", { name: "SQL", exact: true }).click();
    await chooseSqlConnector(page, "Northstar warehouse");
    const sqlConnector = page.getByRole("button", { name: "Select connector" });
    await expect(sqlConnector).toHaveCSS("height", "36px");
    const sqlMark = sqlConnector.locator("[data-connector-type-mark]");
    await expect(sqlMark).toBeVisible();
    const sqlMarkBox = await sqlMark.boundingBox();
    expect(sqlMarkBox).not.toBeNull();
    expect(Math.abs(sqlMarkBox!.x - metricsMarkBox!.x), "both tabs share the connector's content column").toBeLessThanOrEqual(1);
  });
}

for (const width of [1440, 390]) {
  test(`choosing a metric from another connector switches the question in place at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await openExploreRuns(page);
    const list = page.getByRole("region", { name: "Metrics list" });
    const order = async () => (await list.getByRole("button", { pressed: false }).or(list.getByRole("button", { pressed: true })).allInnerTexts()).map((text) => text.split("\n")[0]);
    await expect(list.getByRole("button", { name: /^Churned accounts/ })).toBeVisible();
    await expect(list.getByRole("group", { name: "Orders", exact: true }).getByRole("button", { name: /^Gross revenue/ })).toBeVisible();
    await expect(list.getByRole("group", { name: "Finance orders", exact: true }).getByRole("button", { name: /^Retained accounts/ })).toBeVisible();
    const before = await order();
    expect(before).toHaveLength(8);
    await selectMetric(page, "Gross revenue");

    await expect(list).toContainText("1 selected · 8 metrics");
    expect(await order(), "choosing a metric does not move the list").toEqual(before);
    const finance = list.getByRole("button", { name: /^Retained accounts/ });
    await expect(finance).toHaveAccessibleDescription("Switches to Finance warehouse and removes Gross revenue.");
    await expect(finance).toContainText("Finance orders");
    await expect(finance).toHaveCSS("color", await list.getByRole("button", { name: /^Active customers/ }).evaluate((element) => getComputedStyle(element).color));
    await finance.focus();
    await expect(page.getByRole("tooltip").filter({ hasText: "Switches to Finance warehouse and removes Gross revenue." })).toBeVisible();

    await finance.click();
    const question = page.getByLabel("Query", { exact: true });
    await expect(question.getByRole("status")).toHaveText("Switched to Finance warehouse. Removed Gross revenue.");
    await expect(question.getByText("Switched to Finance warehouse. Removed Gross revenue.", { exact: true }).and(page.locator(":not([role=status])"))).toBeVisible();
    await expect(page.getByTitle("Connector with the source tables of these metrics", { exact: true })).toContainText("Finance warehouse");
    expect(await order(), "switching connectors does not move the list").toEqual(before);
    await list.screenshot({ path: evidencePath(`../screenshots/explore-metrics-connector-switch-${width}.png`) });

    await question.getByRole("button", { name: "Undo", exact: true }).click();
    await expect(question.getByRole("status")).toHaveText("");
    await expect(page.getByTitle("Connector with the source tables of these metrics", { exact: true })).toContainText("Northstar warehouse");
    await expect(list.getByRole("button", { name: /^Gross revenue/ })).toHaveAttribute("aria-pressed", "true");
    await expect(finance).toBeFocused();
  });
}

test("a cross-connector switch note ends with the next edit, and Undo restores the whole question", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openExploreRuns(page);
  const list = page.getByRole("region", { name: "Metrics list" });
  await selectMetric(page, "Gross revenue");
  await selectMetric(page, "Active customers");
  await list.getByRole("button", { name: /^Gross margin/ }).click();
  const question = page.getByLabel("Query", { exact: true });
  await expect(question.getByRole("status")).toHaveText("Switched to Finance warehouse. Removed Gross revenue and Active customers.");
  await question.getByRole("button", { name: "Undo", exact: true }).click();
  await expect(list.getByRole("button", { name: /^Gross revenue/ })).toHaveAttribute("aria-pressed", "true");
  await expect(list.getByRole("button", { name: /^Active customers/ })).toHaveAttribute("aria-pressed", "true");

  await list.getByRole("button", { name: /^Gross margin/ }).click();
  await expect(question.getByRole("button", { name: "Undo", exact: true })).toBeVisible();
  await list.getByRole("button", { name: /^Retained accounts/ }).click();
  await expect(question.getByRole("button", { name: "Undo", exact: true })).toHaveCount(0);
});

test("filter values are typed, checked before Run, and read the way the engine applies them", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openExploreRuns(page, { dimensionFilterTypes: [] });
  await selectMetric(page, "Gross revenue");
  const question = page.getByLabel("Query", { exact: true });

  await question.getByRole("button", { name: "Add filter", exact: true }).click();
  const panel = page.getByRole("dialog", { name: "Filter" });
  await panel.getByRole("combobox", { name: "Filter operator" }).click();
  await page.getByRole("option", { name: "is one of", exact: true }).click();
  const values = panel.getByLabel("Filter value", { exact: true });
  await values.fill("Seoul, KR");
  await values.press("Enter");
  await values.fill("EU");
  await values.press("Enter");
  await expect(panel.getByRole("button", { name: "Remove Seoul, KR", exact: true })).toBeVisible();
  await expect(panel.getByText("Matches the exact text, including upper and lower case.", { exact: true })).toBeVisible();
  await panel.getByRole("button", { name: "Add filter", exact: true }).click();
  await expect(question.getByRole("button", { name: "Region is one of “Seoul, KR”, EU", exact: true })).toBeVisible();

  await question.getByRole("button", { name: "Add filter", exact: true }).click();
  await panel.getByRole("combobox", { name: "Filter operator" }).click();
  await page.getByRole("option", { name: "is not", exact: true }).click();
  await expect(panel.getByText("Includes rows with no value.", { exact: false })).toBeVisible();
  await panel.getByRole("combobox", { name: "Compare as", exact: true }).click();
  await page.getByRole("option", { name: "Number", exact: true }).click();
  await panel.getByLabel("Filter value", { exact: true }).fill("1,234");
  await expect(panel.getByRole("alert")).toHaveText("Enter numbers like 1234.5, without commas or spaces.");
  await expect(panel.getByRole("button", { name: "Add filter", exact: true })).toBeDisabled();
  await panel.getByLabel("Filter value", { exact: true }).fill("1234.50");
  await panel.getByRole("button", { name: "Add filter", exact: true }).click();
  await expect(question.getByRole("button", { name: "Region is not 1234.5", exact: true })).toBeVisible();
  await expect(question.getByText("and", { exact: true })).toBeVisible();
});

for (const [kind, input, sentValue] of [
  ["TEXT", "EU", "EU"],
  ["NUMBER", "1234.50", "1234.5"],
  ["BOOLEAN", "false", false],
] as const) {
  test(`known ${kind} columns restrict comparison selection and send typed values`, async ({ page }) => {
    const dimension = { semanticModelName: "orders", name: "region" };
    const api = await openExploreRuns(page, { dimensionFilterTypes: [{ dimension, kind }] });
    await selectMetric(page, "Gross revenue");
    await page.getByLabel("Query", { exact: true }).getByRole("button", { name: "Add filter", exact: true }).click();
    const panel = page.getByRole("dialog", { name: "Filter" });
    await expect(panel.getByRole("combobox", { name: "Compare as", exact: true })).toHaveCount(0);
    if (kind === "BOOLEAN") {
      await panel.getByRole("combobox", { name: "Filter value" }).click();
      await page.getByRole("option", { name: input, exact: true }).click();
    } else {
      await panel.getByLabel("Filter value", { exact: true }).fill(input);
    }
    await panel.getByRole("button", { name: "Add filter", exact: true }).click();
    await page.getByRole("button", { name: "Run", exact: true }).first().click();
    await expect.poll(() => api.startRequests.length).toBe(1);
    expect(api.startRequests[0]).toMatchObject({ query: { filters: [{ kind, dimension, operator: "EQUALS", values: [sentValue] }] } });
    expect(api.startRequests[0]?.query).not.toHaveProperty("dimensionFilterTypes");
  });
}

test("a restored comparison mismatch blocks Run until the filter is repaired without converting its values", async ({ page }) => {
  const dimension = { semanticModelName: "orders", name: "region" };
  const initial = exploreRunView(0xe30, {
    state: "FAILED",
    query: {
      schemaVersion: 4, metrics: ["gross_revenue"], groupBys: [], metricTimeRange: null,
      filters: [{ kind: "TEXT", dimension, operator: "IN", values: ["123", "456"] }], orderBy: [], rowLimit: 1000,
    },
  });
  const api = await openExploreRuns(page, { extraRuns: [initial], dimensionFilterTypes: [{ dimension, kind: "NUMBER" }] });
  await page.goto(`${EXPLORE_PATH}?run=${initial.runId}`);
  const query = page.getByLabel("Query", { exact: true });
  const run = page.getByRole("button", { name: "Run", exact: true }).first();
  await expect(query.getByText("A filter’s comparison type doesn’t match its column. Edit or remove it to run.")).toBeVisible();
  await expect(run).toBeDisabled();
  await query.getByRole("button", { name: "Region is one of 123, 456, comparison type doesn’t match the column", exact: true }).click();
  const panel = page.getByRole("dialog", { name: "Filter" });
  await expect(panel.getByRole("combobox", { name: "Compare as", exact: true })).toHaveCount(0);
  await expect(panel.getByText("This column uses Number comparisons. Enter a new value to update the filter.")).toBeVisible();
  await expect(panel.getByRole("button", { name: "Remove 123", exact: true })).toHaveCount(0);
  await expect(panel.getByLabel("Filter value", { exact: true })).toBeEmpty();
  await expect(panel.getByRole("button", { name: "Update filter", exact: true })).toBeDisabled();
  await panel.getByLabel("Filter value", { exact: true }).fill("789");
  await panel.getByLabel("Filter value", { exact: true }).press("Enter");
  await panel.getByRole("button", { name: "Update filter", exact: true }).click();
  await expect(query.getByText("Edit or remove it to run.", { exact: false })).toHaveCount(0);
  await expect(run).toBeEnabled();
  await run.click();
  await expect.poll(() => api.startRequests.length).toBe(1);
  expect(api.startRequests[0]).toMatchObject({ query: { filters: [{ kind: "NUMBER", dimension, operator: "IN", values: ["789"] }] } });
});

test("discovery refreshes preserve an unsaved filter edit and Run applies it", async ({ page }) => {
  await enableFilterDiscoveryRefresh(page);
  const dimension = { semanticModelName: "orders", name: "region" };
  const dimensionFilterTypes: SemanticDimensionFilterType[] = [
    { dimension, kind: "TEXT" },
    { dimension: { semanticModelName: "orders", name: "amount" }, kind: "NUMBER" },
  ];
  const initial = exploreRunView(0xe32, {
    state: "FAILED",
    query: {
      schemaVersion: 4, metrics: ["gross_revenue"], groupBys: [], metricTimeRange: null,
      filters: [{ kind: "TEXT", dimension, operator: "EQUALS", values: ["EU"] }], orderBy: [], rowLimit: 1000,
    },
  });
  const api = await openExploreRuns(page, { extraRuns: [initial], dimensionFilterTypes });
  await page.goto(`${EXPLORE_PATH}?run=${initial.runId}`);
  await page.getByLabel("Query", { exact: true }).getByRole("button", { name: "Region is EU", exact: true }).click();
  const panel = page.getByRole("dialog", { name: "Filter" });
  await panel.getByRole("combobox", { name: "Filter operator" }).click();
  await page.getByRole("option", { name: "is not", exact: true }).click();
  await panel.getByLabel("Filter value", { exact: true }).fill("UK");

  for (const change of [
    () => { dimensionFilterTypes[1]!.kind = "BOOLEAN"; },
    () => { dimensionFilterTypes.reverse(); },
    () => { dimensionFilterTypes.push({ dimension: { semanticModelName: "orders", name: "profile" }, kind: "TEXT" }); },
  ]) {
    change();
    await refreshFilterDiscovery(page);
    await expect(panel.getByRole("combobox", { name: "Filter dimension" })).toHaveText("Region · Orders");
    await expect(panel.getByRole("combobox", { name: "Filter operator" })).toHaveText("is not");
    await expect(panel.getByLabel("Filter value", { exact: true })).toHaveValue("UK");
  }

  await page.getByRole("button", { name: "Run", exact: true }).first().click();
  await expect.poll(() => api.startRequests.length).toBe(1);
  expect(api.startRequests[0]).toMatchObject({ query: { filters: [{ kind: "TEXT", dimension, operator: "NOT_EQUALS", values: ["UK"] }] } });
});

test("a changed selected column type repairs the open editor without retaining old values", async ({ page }) => {
  await enableFilterDiscoveryRefresh(page);
  const dimension = { semanticModelName: "orders", name: "region" };
  const dimensionFilterTypes: SemanticDimensionFilterType[] = [{ dimension, kind: "NUMBER" }];
  const initial = exploreRunView(0xe33, {
    state: "FAILED",
    query: {
      schemaVersion: 4, metrics: ["gross_revenue"], groupBys: [], metricTimeRange: null,
      filters: [{ kind: "NUMBER", dimension, operator: "GREATER_THAN", values: ["123"] }], orderBy: [], rowLimit: 1000,
    },
  });
  const api = await openExploreRuns(page, { extraRuns: [initial], dimensionFilterTypes });
  await page.goto(`${EXPLORE_PATH}?run=${initial.runId}`);
  await page.getByLabel("Query", { exact: true }).getByRole("button", { name: "Region is greater than 123", exact: true }).click();
  const panel = page.getByRole("dialog", { name: "Filter" });
  await panel.getByRole("combobox", { name: "Filter operator" }).click();
  await page.getByRole("option", { name: "is less than", exact: true }).click();
  await panel.getByLabel("Filter value", { exact: true }).fill("456");
  dimensionFilterTypes[0]!.kind = "BOOLEAN";
  await refreshFilterDiscovery(page);
  await expect(panel.getByRole("combobox", { name: "Filter dimension" })).toHaveText("Region · Orders");
  await expect(panel.getByRole("combobox", { name: "Filter operator" })).toHaveText("is");
  await expect(panel.getByRole("combobox", { name: "Filter value" })).toHaveText("Select value");
  await expect(panel.getByRole("combobox", { name: "Compare as", exact: true })).toHaveCount(0);
  await expect(panel.getByText("This column uses True or false comparisons. Enter a new value to update the filter.")).toBeVisible();
  await expect(panel.getByRole("button", { name: "Update filter", exact: true })).toBeDisabled();
  await panel.getByRole("combobox", { name: "Filter value" }).click();
  await page.getByRole("option", { name: "false", exact: true }).click();
  await panel.getByRole("button", { name: "Update filter", exact: true }).click();
  await page.getByRole("button", { name: "Run", exact: true }).first().click();
  await expect.poll(() => api.startRequests.length).toBe(1);
  expect(api.startRequests[0]).toMatchObject({ query: { filters: [{ kind: "BOOLEAN", dimension, operator: "EQUALS", values: [false] }] } });
});

for (const values of [
  ["2026-08-20T09:30:00Z"],
  ["2026-08-20T09:30:00.1Z"],
  ["2026-08-20T09:30:00.12Z"],
  ["2026-08-20T09:30:00.123Z"],
  ["2026-08-20T09:30:00.1234Z"],
  ["2026-08-20T09:30:00.12345Z"],
  ["2026-08-20T09:30:00.123456Z"],
  ["2026-08-20T09:30:00.123456+09:00"],
  ["2026-08-20T09:30:00.123Z", "2026-08-20T09:30:00.123456Z", "2026-08-20T09:30:00.123456+09:00"],
]) {
  test(`editing a restored timestamp filter preserves ${values.length === 1 ? values[0] : "every set value"} until the value is changed`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const dimension = { semanticModelName: "orders", name: "region" };
    const operators = values.length === 1 ? ["EQUALS", "NOT_EQUALS"] : ["IN", "NOT_IN"];
    const initial = exploreRunView(0xe31, {
      state: "FAILED",
      query: {
        schemaVersion: 4, metrics: ["gross_revenue"], groupBys: [], metricTimeRange: null,
        filters: [{ kind: "TIMESTAMP", dimension, operator: operators[0], values }], orderBy: [], rowLimit: 1000,
      },
    });
    const api = await openExploreRuns(page, { extraRuns: [initial], dimensionFilterTypes: [] });
    await page.goto(`${EXPLORE_PATH}?run=${initial.runId}`);
    const filterChip = page.getByLabel("Query", { exact: true }).getByRole("button", { name: /^Region is/ });
    const panel = page.getByRole("dialog", { name: "Filter" });

    for (const [index, operator] of operators.entries()) {
      await filterChip.click();
      if (values.length === 1) {
        await expect(panel.getByLabel("Filter value", { exact: true })).toHaveValue(values[0]!.endsWith("+09:00") ? "2026-08-20T00:30" : "2026-08-20T09:30");
      } else {
        for (const value of values) await expect(panel.getByRole("button", { name: `Remove ${value}`, exact: true })).toBeVisible();
      }
      if (index === 0 && (values.length > 1 || values[0]?.endsWith("+09:00"))) {
        await panel.screenshot({ path: evidencePath(`../screenshots/explore-timestamp-${values.length > 1 ? "set" : "offset"}-preserved.png`) });
      }
      if (index === 1) {
        await panel.getByRole("combobox", { name: "Filter operator" }).click();
        await page.getByRole("option", { name: values.length === 1 ? "is not" : "is not one of", exact: true }).click();
      }
      await panel.getByRole("button", { name: "Update filter", exact: true }).click();
      await expect(panel).toHaveCount(0);
      await question(page).getByRole("button", { name: "Run", exact: true }).click();
      await expect.poll(() => api.startRequests.length).toBe(index + 1);
      expect(api.startRequests[index]).toMatchObject({ query: { filters: [{ kind: "TIMESTAMP", dimension, operator, values }] } });
    }

    if (values.length === 1) {
      await filterChip.click();
      await panel.getByLabel("Filter value", { exact: true }).fill("2026-08-20T10:40:05");
      await expect(panel.getByLabel("Filter value", { exact: true })).toHaveValue("2026-08-20T10:40:05");
      await panel.getByRole("button", { name: "Update filter", exact: true }).click();
      await question(page).getByRole("button", { name: "Run", exact: true }).click();
      await expect.poll(() => api.startRequests.length).toBe(3);
      expect(api.startRequests[2]).toMatchObject({ query: { filters: [{ kind: "TIMESTAMP", dimension, operator: "NOT_EQUALS", values: ["2026-08-20T10:40:05Z"] }] } });
    }
  });
}

test("opens completed, running, and failed runs under the composer and restores their questions", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await openExploreRuns(page, { holdReadsAfterCancel: true });
  const rail = desktopRail(page);
  await expect(rail.getByRole("heading", { name: "Runs" })).toBeVisible();

  await rail.getByRole("button", { name: /Gross revenue/ }).click();
  await expect(page).toHaveURL(/\?run=/);
  const completed = runPanel(page, "Gross revenue by Region");
  await expect(completed).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(runRow(page, "Gross revenue by Region")).toHaveAttribute("aria-current", "true");
  await expect(question(page).getByRole("button", { name: "Gross revenue", exact: true })).toBeVisible();
  await expect(question(page).getByRole("button", { name: "Region", exact: true })).toBeVisible();
  await expect(question(page).getByText("Northstar warehouse", { exact: true })).toBeVisible();
  await expect(completed.getByText("Query edited since this run")).toHaveCount(0);
  const downloadResults = completed.getByRole("button", { name: "Download result" });
  await expect(downloadResults).toBeVisible();
  await expect(completed.getByRole("button", { name: "Copy link" })).toBeVisible();
  await expect(completed).toContainText("First 20 of 48 rows");
  await expect(completed.getByRole("tab", { name: "Chart" })).toBeVisible();
  const sampleGrid = completed.getByTestId("run-sample-grid");
  await expect(sampleGrid.locator("tbody tr")).toHaveCount(20);
  await expect(sampleGrid.getByRole("columnheader")).toHaveCount(7);
  const firstRevenue = sampleGrid.locator("tbody tr").first().locator("td").nth(1);
  await expect(firstRevenue).toHaveText("428,320.40");
  await expect(firstRevenue).toHaveAttribute("title", "428320.40");
  expect(await sampleGrid.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  await page.screenshot({ path: evidencePath("../screenshots/explore-run-result-inline.png"), fullPage: true });
  await sampleGrid.evaluate((element) => element.scrollTo({ top: 120 }));
  await expect(sampleGrid).toHaveClass(/is-scrolling/);
  await expect(sampleGrid).not.toHaveClass(/is-scrolling/, { timeout: 1_200 });
  await expect(downloadResults).toBeVisible();

  await question(page).getByRole("button", { name: "Remove Region" }).click();
  await expect(completed.getByText("Query edited since this run")).toBeVisible();

  await rail.getByRole("button", { name: /Product events/ }).click();
  const failed = runPanel(page, "Product events");
  await expect(failed.getByRole("alert")).toContainText("The warehouse stopped the statement after its execution timeout.");
  await expect(failed.getByRole("link", { name: "Open metric" })).toHaveAttribute("href", /\/metrics\/metric-product_events$/);
  await expect(failed).toContainText("EXECUTION_FAILED");
  await page.screenshot({ path: evidencePath("../screenshots/explore-run-failure-inline.png"), fullPage: true });

  await rail.getByRole("button", { name: /Active customers/ }).click();
  const running = runPanel(page, "Active customers");
  await expect(running.getByRole("button", { name: "Cancel", exact: true })).toBeVisible();

  // Run reads after the cancel are held, so Cancelled can only come from the cancel response's cache update.
  await running.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(running.getByText("This run was cancelled.")).toBeVisible();
  await expect(runRow(page, "Active customers")).toContainText("Cancelled");
  expect(api.cancelledRunIds).toHaveLength(1);
  api.releaseReadsAfterCancel();
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("shows a started run under the composer and keeps the drawer for narrower screens", async ({ page }) => {
  await page.setViewportSize({ width: 1080, height: 820 });
  await openExploreRuns(page);

  await expect(desktopRail(page)).toBeHidden();
  const runsTrigger = page.getByRole("button", { name: "Runs, activity in progress" });
  await expectInkPulse(runsTrigger.getByTestId("runs-status-dot"));
  await page.screenshot({ path: evidencePath("../screenshots/explore-toolbar-narrow.png"), fullPage: true });
  await selectMetric(page, "Gross revenue");
  await expect(question(page).getByText("Northstar warehouse", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Run", exact: true }).click();
  const started = runPanel(page, "Gross revenue");
  await expect(started).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`\\?run=${STARTED_RUN_ID}$`));
  await expect(started.getByRole("button", { name: "Cancel", exact: true })).toBeVisible();
  await expect(started.getByText("Query edited since this run")).toHaveCount(0);
  await expect(page.getByRole("dialog", { name: "Explore runs" })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/explore-started-run-narrow.png"), fullPage: true });

  await runsTrigger.click();
  const drawer = page.getByRole("dialog", { name: "Explore runs" });
  await expect(drawer).toHaveCSS("animation-name", "runs-drawer-in");
  const drawerRail = drawer.getByRole("complementary", { name: "Explore runs" });
  await expect(drawerRail.getByText("2 running", { exact: true })).toBeVisible();
  await expect(drawerRail.getByRole("button", { name: "Close runs" })).toBeVisible();
  const newRun = drawerRail.getByRole("button").filter({ hasText: "Northstar warehouse" }).first();
  await expect(newRun).toContainText("Gross revenue");
  await expect(newRun).toContainText("Running");
  await page.screenshot({ path: evidencePath("../screenshots/explore-runs-drawer-narrow.png"), fullPage: true });
});

test("records the keyboard-selected connector on a new SQL run", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await openExploreRuns(page);
  await page.getByRole("tab", { name: "SQL", exact: true }).click();

  const connectorTrigger = page.getByRole("button", { name: "Select connector" });
  await expect(connectorTrigger).toContainText("Choose a connector");
  await expect(page.getByRole("button", { name: "Run", exact: true })).toBeDisabled();
  await connectorTrigger.click();
  const connectorListbox = page.getByRole("listbox", { name: "Connectors" });
  await expect(connectorListbox).toBeFocused();
  await expect(connectorListbox.getByRole("option", { selected: true })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/explore-runs-connector-select.png"), fullPage: true });
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(connectorListbox).toBeHidden();
  await expect(connectorTrigger).toContainText("Finance warehouse");

  await typeSql(page, "SELECT 1");
  await page.getByRole("button", { name: "Run", exact: true }).click();
  await expect.poll(() => api.startRequests.map((request) => request.connectorId)).toEqual([FINANCE_CONNECTOR_ID]);
  await expect(runPanel(page, "SELECT 1")).toBeVisible();
  await expect(runRow(page, "SELECT 1").filter({ hasText: "SQL · Finance warehouse" })).toBeVisible();
});

test("highlights SQL syntax with line numbers and a sandstone caret", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openExploreRuns(page);
  await page.getByRole("tab", { name: "SQL", exact: true }).click();

  const editor = page.getByTestId("sql-editor");
  await expect(editor.locator(".monaco-editor")).toBeVisible();
  await editor.locator(".view-lines").click({ position: { x: 80, y: 16 } });
  await page.keyboard.insertText(EIGHT_LINE_SQL);
  await expect(editor.locator(".line-numbers").filter({ hasText: /^8$/ })).toBeVisible();
  const selectToken = editor.locator(".view-line").filter({ hasText: /^SELECT$/ }).locator("span span").filter({ hasText: /^SELECT$/ });
  await expect(selectToken).toHaveCSS("color", SQL_KEYWORD);
  await expect(editor.locator(".cursor").first()).toHaveCSS("background-color", SANDSTONE);
  await expect(editor).toHaveCSS("box-shadow", "none");
  await page.screenshot({ path: evidencePath("../screenshots/explore-sql-syntax-highlight.png"), fullPage: true });
});

test("previews the semantic selection as read-only rendered SQL", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await openExploreRuns(page, { holdRenderedSql: true });
  await selectMetric(page, "Gross revenue");

  await page.getByRole("button", { name: "View rendered SQL" }).click();
  const dialog = page.getByTestId("rendered-sql-dialog");
  await expect(dialog.getByText("Rendering SQL")).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Copy rendered SQL" })).toBeDisabled();
  api.releaseRenderedSql();
  await expect(dialog.getByText("Rendering SQL")).toBeHidden();
  await expect(dialog.getByRole("heading", { name: "Rendered SQL" })).toBeVisible();
  await expect(dialog.getByText("Northstar warehouse · PostgreSQL")).toBeVisible();
  const editor = dialog.getByTestId("sql-editor");
  await expect(editor.locator(".monaco-editor")).toBeVisible();
  await expect(editor.locator(".line-numbers").first()).toBeVisible();
  await expect(editor.locator(".view-lines")).toContainText("SELECT");
  await expect(dialog.getByRole("button", { name: "Copy rendered SQL" })).toBeEnabled();
  await page.screenshot({ path: evidencePath("../screenshots/explore-semantic-rendered-sql.png"), fullPage: true });
  await page.evaluate(() => {
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: () => Promise.reject(new DOMException("Clipboard write denied", "NotAllowedError")) } });
  });
  await dialog.getByRole("button", { name: "Copy rendered SQL" }).click();
  await expect(dialog.getByRole("alert")).toHaveText("Couldn’t copy the SQL. Please try again.");

  await dialog.getByRole("button", { name: "Close rendered SQL" }).click();
  await page.setViewportSize({ width: 720, height: 900 });
  await page.getByRole("button", { name: "View rendered SQL" }).click();
  await expect(dialog).toBeVisible();
  expect(await dialog.boundingBox()).toEqual({ x: 0, y: 0, width: 720, height: 900 });
  await page.screenshot({ path: evidencePath("../screenshots/explore-semantic-rendered-sql-narrow.png"), fullPage: true });
});

test("collapses the desktop runs rail into an observable status dock", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openExploreRuns(page);
  await selectMetric(page, "Gross revenue");

  const rail = desktopRail(page);
  await expect(rail).toHaveCSS("width", "320px");
  await expect(rail.getByRole("heading", { name: "Runs" })).toBeVisible();
  await expect(rail.getByText("1 running", { exact: true })).toBeVisible();
  await rail.getByRole("button", { name: "Collapse runs" }).click();
  await expect(rail).toHaveAttribute("data-collapsed", "true");
  await expect(rail).toHaveCSS("width", "56px");
  await expect(rail.getByRole("heading", { name: "Runs" })).toHaveCount(0);
  const expandRuns = rail.getByRole("button", { name: "Expand runs, activity in progress" });
  await expectInkPulse(expandRuns.getByTestId("runs-status-dot"));
  await expect(rail.getByRole("button", { name: "Open Gross revenue by Region", exact: true })).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/explore-runs-dock-collapsed.png"), fullPage: true });

  await expandRuns.click();
  await expect(rail).not.toHaveAttribute("data-collapsed", "true");
  await expect(rail).toHaveCSS("width", "320px");
  await expect(rail.getByRole("heading", { name: "Runs" })).toBeVisible();

  await rail.getByRole("button", { name: "Collapse runs" }).click();
  await expect(rail).toHaveCSS("width", "56px");
  const runButton = page.getByRole("button", { name: "Run", exact: true });
  await runButton.hover();
  await expect(page.getByTestId("run-shortcut-tooltip")).toHaveCSS("opacity", "1");
  await runButton.click();
  await expect(page.getByTestId("run-shortcut-tooltip")).toHaveCount(0);
  await expect(runPanel(page, "Gross revenue")).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`\\?run=${STARTED_RUN_ID}$`));
  await expect(rail).toHaveAttribute("data-collapsed", "true");
  await expect(rail.getByRole("button", { name: "Open Gross revenue", exact: true })).toHaveCount(1);
});

test("shows one bounded chronological run list without date groups", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openExploreRuns(page);

  const rail = desktopRail(page);
  await expect(rail.getByText("8 recent runs", { exact: true })).toBeVisible();
  await expect(rail.getByText("Today", { exact: true })).toHaveCount(0);
  await expect(rail.getByText("Yesterday", { exact: true })).toHaveCount(0);
  const runRows = rail.getByRole("button", { name: /(Northstar|Finance) warehouse/ });
  await expect(runRows).toHaveCount(8);
  await expect(runRows.first()).toContainText("Active customers");
  await expect(runRows.last()).toContainText("Churned accounts");
  const oldestStart = await page.evaluate(() => new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date("2026-08-21T08:40:00Z")));
  await expect(runRows.last()).toContainText(oldestStart);
});

test("marks runs a person started through an agent connection", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openExploreRuns(page);

  await expect(runRow(page, "Retained accounts")).toContainText("via agent");
  await expect(runRow(page, "Gross revenue")).not.toContainText("via agent");

  await runRow(page, "Retained accounts").click();
  await expect(runPanel(page, "Retained accounts").getByText("via agent", { exact: true })).toBeVisible();

  await runRow(page, "Gross revenue").click();
  await expect(runPanel(page, "Gross revenue by Region")).toBeVisible();
  await expect(runPanel(page, "Gross revenue by Region").getByText("via agent", { exact: true })).toHaveCount(0);
});

// The rejection scenarios declare only the failed response itself. The console guard fails the test on
// any unhandled promise rejection, so a passing run proves the page handled the rejected mutation.
test("shows a rejected SQL run start without an unhandled rejection", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 409, url: /\/explore\/sql-runs$/, reason: "Control rejects the multi-statement SQL." });
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await openExploreRuns(page, {
    startRejection: { status: 409, title: "Conflict", code: "RAW_SQL_VALIDATION_REJECTED", detail: "BigQuery Raw SQL is outside the supported read-query subset" },
  });
  await page.getByRole("tab", { name: "SQL", exact: true }).click();
  await chooseSqlConnector(page, "Northstar warehouse");
  await typeSql(page, "SELECT 1; SELECT 2");

  await page.getByRole("button", { name: "Run", exact: true }).click();
  const rejection = page.getByRole("tabpanel", { name: "SQL" }).getByRole("alert");
  await expect(rejection).toContainText("BigQuery Raw SQL is outside the supported read-query subset");
  await expect(rejection).toContainText("RAW_SQL_VALIDATION_REJECTED");
  expect(api.startRequests).toEqual([expect.objectContaining({ connectorId: NORTHSTAR_CONNECTOR_ID, sql: "SELECT 1; SELECT 2" })]);
  await expect(desktopRail(page).locator('button[data-newly-added="true"]')).toHaveCount(0);
  await expect(page).not.toHaveURL(/\?run=/);
});

test("shows a rejected semantic run start without an unhandled rejection", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 409, url: /\/explore\/semantic-runs$/, reason: "Control rejects the Semantic run start." });
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await openExploreRuns(page, {
    startRejection: { status: 409, title: "Conflict", code: "CONFLICT", detail: "Explore Semantic run conflicts with current authority." },
  });
  await selectMetric(page, "Gross revenue");

  await page.getByRole("button", { name: "Run", exact: true }).click();
  const rejection = question(page).getByRole("alert");
  await expect(rejection).toContainText("Explore Semantic run conflicts with current authority.");
  await expect(rejection).toBeFocused();
  // With no run in view the Metrics list is the picker; searching it keeps the rejection in place.
  const metricSearch = page.getByRole("region", { name: "Metrics list" }).getByRole("searchbox", { name: "Search metrics" });
  await metricSearch.fill("gross");
  await expect(metricSearch).toBeFocused();
  await expect(rejection).toContainText("CONFLICT");
  expect(api.startRequests).toHaveLength(1);
  await expect(desktopRail(page).locator('button[data-newly-added="true"]')).toHaveCount(0);
  await expect(page).not.toHaveURL(/\?run=/);
});

test("shows a rejected run cancel without an unhandled rejection", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 409, url: /\/explore\/runs\/[^/]+:cancel$/, reason: "Control rejects the cancel because the run state changed." });
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await openExploreRuns(page, {
    cancelRejection: { status: 409, title: "Conflict", code: "CONFLICT", detail: "Explore run conflicts with current state." },
  });
  await desktopRail(page).getByRole("button", { name: /Active customers/ }).click();
  const running = runPanel(page, "Active customers");
  const cancel = running.getByRole("button", { name: "Cancel", exact: true });

  await cancel.click();
  await expect(running.getByRole("alert")).toContainText("Explore run conflicts with current state.");
  await page.screenshot({ path: evidencePath("../screenshots/explore-run-cancel-rejected.png") });
  await expect(cancel).toBeEnabled();
  await expect(running.getByText("This run was cancelled.")).toHaveCount(0);
  await expect(runRow(page, "Active customers")).not.toContainText("Cancelled");
  expect(api.cancelledRunIds).toEqual([]);
});

test("keeps an expired run open with its question, its SQL, and Run", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const expired = exploreRunView(0xe11, {
    state: "SUCCEEDED",
    createdAt: "2026-08-24T09:40:00Z",
    updatedAt: "2026-08-24T09:40:02Z",
    query: { schemaVersion: 4, metrics: ["churned_accounts"], groupBys: [REGION_GROUP_BY], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 1000 },
    resultMetadata: { byteSize: "2048", mediaType: "application/vnd.apache.arrow.file", rowCount: "12", columnCount: 2, expiresAt: "2026-08-27T09:40:02Z" },
    resultState: "EXPIRED",
    result: null,
  });
  const api = await openExploreRuns(page, { extraRuns: [expired] });

  await runRow(page, "Churned accounts by Region").click();
  const panel = runPanel(page, "Churned accounts by Region");
  await expect(panel.getByText("This result has expired. Run it again to see it.")).toBeVisible();
  await expect(panel.getByRole("tablist", { name: "Result view" }).getByRole("tab")).toHaveText(["Table", "SQL"]);
  await expect(panel.getByRole("button", { name: "Download result" })).toHaveCount(0);
  await expect(question(page).getByRole("button", { name: "Churned accounts", exact: true })).toBeVisible();
  await expect(question(page).getByRole("button", { name: "Region", exact: true })).toBeVisible();
  await expect(panel.getByText("Query edited since this run")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/explore-run-expired.png"), fullPage: true });
  await panel.getByRole("tablist", { name: "Result view" }).getByRole("tab", { name: "SQL" }).click();
  await expect(panel.getByText("Rendered for PostgreSQL")).toBeVisible();
  await panel.getByRole("tablist", { name: "Result view" }).getByRole("tab", { name: "Table" }).click();

  await panel.getByRole("button", { name: "Run", exact: true }).click();
  await expect.poll(() => api.startRequests.length).toBe(1);
  expect(api.startRequests[0]).toMatchObject({ connectorId: NORTHSTAR_CONNECTOR_ID, query: { metrics: ["churned_accounts"], groupBys: [REGION_GROUP_BY] } });
  await expect(page).toHaveURL(new RegExp(`\\?run=${STARTED_RUN_ID}$`));
});

test("keeps an unrun Metrics question restorable across a SQL run and runs again by the viewed run's kind", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const sqlRun = exploreRunView(0xe21, {
    kind: "SQL",
    state: "SUCCEEDED",
    sql: "SELECT 1 AS one",
    resultMetadata: { byteSize: "64", mediaType: "application/vnd.apache.arrow.file", rowCount: "1", columnCount: 1, expiresAt: "2099-01-01T00:00:00Z" },
    result: { columns: [{ ordinal: 1, name: "one", logicalType: "INTEGER", nullable: false }], rows: [["1"]], returnedRowCount: 1, stopReason: "END_OF_RESULT" },
  });
  const api = await openExploreRuns(page, { extraRuns: [sqlRun] });

  // An unrun Metrics question survives opening a SQL run, which leaves Metrics alone…
  await selectMetric(page, "Active customers");
  await desktopRail(page).getByRole("button", { name: /SELECT 1 AS one/ }).click();
  await expect(page.getByText("Opening this run replaced a query you hadn’t run.")).toHaveCount(0);
  // …so opening a Metrics run afterwards still offers it back, and Restore brings back only Metrics.
  await desktopRail(page).getByRole("button", { name: /Product events/ }).click();
  await expect(page.getByText("Opening this run replaced a query you hadn’t run.")).toBeVisible();
  await page.getByRole("button", { name: "Restore it" }).click();
  await expect(question(page).getByRole("button", { name: "Active customers", exact: true })).toBeVisible();

  // Run again on the failed Metrics run asks Metrics even while the SQL tab is showing.
  await desktopRail(page).getByRole("button", { name: /Product events/ }).click();
  await page.getByRole("tablist", { name: "Query type" }).getByRole("tab", { name: "SQL" }).click();
  await runPanel(page, "Product events").getByRole("button", { name: "Run again" }).click();
  await expect.poll(() => api.startRequests.length).toBe(1);
  expect(api.startRequests[0]).toMatchObject({ query: { metrics: ["product_events"] } });
  expect(api.startRequests[0]?.sql).toBeUndefined();
});
