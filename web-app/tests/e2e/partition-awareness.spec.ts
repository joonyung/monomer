import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";
import { openExploreRuns, selectMetric, NORTHSTAR_CONNECTOR_ID } from "./explore-runs-api-fixture";

const source = { semanticModelName: "orders", connectorId: NORTHSTAR_CONNECTOR_ID, relation: { catalog: "northstar", schema: "analytics", name: "orders" }, requiresPartitionFilter: true, partitionKeys: ["order_date"], dimensions: ["order_date"], timeRangeApplies: true };

test("partition guidance keeps Run available and opens the existing time range editor", async ({ page }) => {
  await openExploreRuns(page, { partitionSource: source });
  await selectMetric(page, "Gross revenue");
  const hint = page.getByLabel("Partition filter guidance");
  await expect(hint).toContainText("Orders reads a table that needs a filter on order_date.");
  await expect(page.getByRole("button", { name: "Run", exact: true })).toBeEnabled();
  await hint.getByRole("button", { name: "Set time range" }).click();
  const editor = page.getByRole("dialog", { name: "Metric time range", exact: true });
  await expect(editor.getByLabel("Metric time range start")).toBeFocused();
  await editor.getByLabel("Metric time range start").fill("2026-10-01");
  await editor.getByLabel("Metric time range end").fill("2026-10-08");
  await editor.getByRole("button", { name: "Apply range" }).click();
  await expect(hint).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Run", exact: true })).toBeEnabled();
  await page.screenshot({ path: evidencePath("partition-guidance-time-range.png"), fullPage: true });
});

test("canonical partition rejection receives focus without adding a Run", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 409, url: /\/explore\/semantic-runs$/, reason: "This scenario intentionally returns the canonical partition-filter validation rejection." });
  const api = await openExploreRuns(page, { partitionSource: source, startRejection: { status: 409, title: "Conflict", code: "PARTITION_FILTER_REQUIRED", detail: "This query needs a partition filter.", partitionFilters: [{ kind: "SEMANTIC_MODEL", connectorId: source.connectorId, relation: source.relation, partitionKeys: source.partitionKeys, semanticModelName: source.semanticModelName, dimensions: source.dimensions, timeRangeApplies: true }] } });
  await selectMetric(page, "Gross revenue");
  const runsBefore = await page.locator('aside[aria-label="Explore runs"] button').count();
  await page.getByRole("button", { name: "Run", exact: true }).click();
  const notice = page.getByRole("alert").filter({ hasText: "Orders reads a table that needs a filter on order_date." });
  await expect(notice).toBeFocused();
  await expect(notice).toContainText("PARTITION_FILTER_REQUIRED");
  await expect(page.locator('aside[aria-label="Explore runs"] button')).toHaveCount(runsBefore);
  expect(api.startRequests).toHaveLength(1);
  await notice.getByRole("button", { name: "Set time range" }).click();
  await expect(page.getByRole("dialog", { name: "Metric time range", exact: true })).toBeVisible();
  await page.screenshot({ path: evidencePath("partition-rejection-before-run.png"), fullPage: true });
});

test("partition filter action opens a selected dimension and dismisses the hint after applying it", async ({ page }) => {
  await openExploreRuns(page, { partitionSource: { ...source, partitionKeys: ["region"], dimensions: ["region"], timeRangeApplies: false } });
  await selectMetric(page, "Gross revenue");
  const hint = page.getByLabel("Partition filter guidance");
  await hint.getByRole("button", { name: "Add filter", exact: true }).click();
  const editor = page.getByRole("dialog", { name: "Filter", exact: true });
  await expect(editor.getByRole("combobox", { name: "Filter dimension", exact: true })).toContainText("Region");
  await editor.getByRole("textbox", { name: "Filter value", exact: true }).fill("Europe");
  await editor.getByRole("button", { name: "Add filter", exact: true }).click();
  await expect(hint).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Run", exact: true })).toBeEnabled();
});

test("missing partition dimensions remain nonblocking and explain the editor action", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openExploreRuns(page, { partitionSource: { ...source, dimensions: [] } });
  await selectMetric(page, "Gross revenue");
  const hint = page.getByLabel("Partition filter guidance");
  await expect(hint).toContainText("The model has no dimension for it.");
  await expect(hint).toContainText("Ask an editor to add it.");
  await expect(page.getByRole("button", { name: "Run", exact: true })).toBeEnabled();
  await expect(hint.getByRole("link", { name: "Open model" })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("partition-guidance-mobile.png"), fullPage: true });
});
