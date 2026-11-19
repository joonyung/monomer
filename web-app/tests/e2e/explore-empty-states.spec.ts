import { mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const workspaceId = "019e8000-0000-7000-8000-000000000b21";
const connectorId = "019e8000-0000-7000-8000-000000000c21";
const evidenceDirectory = resolve(process.cwd(), "..");
const screenshotDirectory = evidencePath(resolve(evidenceDirectory, "screenshots"));

function json(body: unknown, status = 200) {
  return {
    status,
    contentType: status >= 400 ? "application/problem+json" : "application/json",
    body: JSON.stringify(body),
  };
}

async function authenticateExploreMember(page: Page, returnTarget: string): Promise<void> {
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Explore timing QA",
    organizationId: "organization-qa",
    role: "EXPLORER",
    user: { id: "explore-timing-user", email: "explore-timing@example.test", displayName: "Explore timing QA" },
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, returnTarget);
}

async function fulfillExploreBootstrap(route: Route, path: string): Promise<boolean> {
  if (path.endsWith("/connectors")) {
    await route.fulfill(json({
      connectors: [{
        id: connectorId,
        workspaceId,
        name: "Explore timing connector",
        type: "POSTGRESQL",
        properties: { host: "fixture.internal", port: 5432, database: "warehouse" },
        createdAtEpochMs: 0,
        updatedAtEpochMs: 0,
      }],
    }));
    return true;
  }
  if (path.endsWith("/semantic/metrics")) {
    await route.fulfill(json([]));
    return true;
  }
  if (path.endsWith("/semantic/semantic-models")) {
    await route.fulfill(json([]));
    return true;
  }
  if (path.endsWith("/semantic/query/group-bys")) {
    await route.fulfill(json({ groupBys: [], excludedSemanticModels: [], dimensionFilterTypes: [] }));
    return true;
  }
  return false;
}


test("distinguishes no metric from a pending group-by lookup", async ({ page }) => {
  mkdirSync(screenshotDirectory, { recursive: true });
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/v2/workspaces/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/semantic/metrics")) {
      await route.fulfill(json([{ sourcePartitionInfo: [], metricId: "metric-revenue", workspaceId, definition: { schemaVersion: 3, name: "revenue", label: "Revenue", type: "simple", semanticModelName: "orders", measure: "revenue" }, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" }]));
    } else if (path.endsWith("/semantic/semantic-models")) {
      await route.fulfill(json([{ sourcePartitionInfo: [],
        semanticModelId: "model-orders", workspaceId,
        definition: { schemaVersion: 3, name: "orders", label: "Orders", sourceRelations: [{ connectorId, schema: "analytics", relation: "orders" }], entities: [], dimensions: [], measures: [] },
        createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z",
      }]));
    } else if (path.endsWith("/semantic/query/group-bys")) {
      await pending;
      await route.fulfill(json({ groupBys: [], excludedSemanticModels: [], dimensionFilterTypes: [] }));
    } else if (await fulfillExploreBootstrap(route, path)) return;
    else await route.fulfill(json({ runs: [] }));
  });
  try {
    await authenticateExploreMember(page, `/workspaces/${workspaceId}/explore`);
    // With no run in view the Metrics and Group by lists are open below the question.
    const groupByList = page.getByRole("region", { name: "Group by list" });
    await expect(groupByList.getByText("Select a metric to see available group bys.")).toBeVisible();
    await expect(groupByList.getByText("Loading group bys…")).toHaveCount(0);
    await page.screenshot({ path: resolve(screenshotDirectory, "explore-no-metric.png") });

    await page.getByRole("region", { name: "Metrics list" }).getByRole("button", { name: /^Revenue/ }).click();
    await expect(page.getByText("Explore timing connector", { exact: true })).toBeVisible();
    await expect(groupByList.getByText("Loading group bys…")).toBeVisible();
    await page.screenshot({ path: resolve(screenshotDirectory, "explore-group-by-loading.png") });
    release();
    await expect(groupByList.getByText("Loading group bys…")).toHaveCount(0);
    await expect(groupByList.getByText("No group bys are available for the selected metrics.")).toBeVisible();

    await page.getByRole("button", { name: "Remove Revenue" }).click();
    await expect(groupByList.getByText("Select a metric to see available group bys.")).toBeVisible();
  } finally { release(); }
});

test("links an empty connector inventory to the current workspace", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/v2/workspaces/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/connectors")) await route.fulfill(json({ connectors: [] }));
    else if (await fulfillExploreBootstrap(route, path)) return;
    else await route.fulfill(json({ runs: [] }));
  });
  await authenticateExploreMember(page, `/workspaces/${workspaceId}/explore`);
  await expect(page.getByRole("link", { name: "Go to Connectors" })).toHaveAttribute("href", `/workspaces/${workspaceId}/connectors`);
  await expect(page.getByRole("button", { name: "Run", exact: true })).toBeDisabled();
  await expect(page.getByText("Sort", { exact: true })).toHaveCount(0);

  const metricsList = page.getByRole("region", { name: "Metrics list" });
  await expect(metricsList.getByText("No metrics yet", { exact: true })).toBeVisible();
  await expect(metricsList.getByRole("link", { name: "Go to Semantics" })).toHaveAttribute("href", `/workspaces/${workspaceId}/semantics`);
  await page.screenshot({ path: resolve(screenshotDirectory, "explore-no-connectors-mobile.png") });

  await page.getByRole("button", { name: "Add filter", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "Filter" }).getByText("Select a metric to filter by its dimensions.")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
});

for (const [byteSize, formatted] of [["546", "546 B"], ["1200000", "1.2 MB"]]) {
  test(`shows ${formatted} in the result caption and preserves expiry`, async ({ page }) => {
    const runId = "019e8000-0000-7000-8000-000000000d21";
    const run = {
      kind: "SQL", runId, workspaceId, connectorId,
      requestDigest: `sha256:${"a".repeat(64)}`, state: "SUCCEEDED", revision: 2,
      resultAvailable: true, failureCode: null, failureDetail: null,
      createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:03Z", sql: "SELECT 12.50 AS value",
      resultMetadata: { byteSize, mediaType: "application/vnd.apache.arrow.file", rowCount: "1", columnCount: 1, expiresAt: byteSize === "546" ? "2099-01-02T00:00:00Z" : "2026-01-02T00:00:00Z" },
      result: { columns: [{ ordinal: 1, name: "value", logicalType: "DECIMAL", nullable: false }], rows: [["12.50"]], returnedRowCount: 1, stopReason: "ROW_LIMIT" },
    };
    await page.route("**/v2/workspaces/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (await fulfillExploreBootstrap(route, path)) return;
      await route.fulfill(json(path.endsWith("/explore/runs") ? { runs: [run] } : run));
    });
    await authenticateExploreMember(page, `/workspaces/${workspaceId}/explore?run=${runId}`);
    const viewed = page.getByRole("region", { name: "SELECT 12.50 AS value" });
    await expect(viewed.getByRole("tablist", { name: "Result view" }).locator("..")).toContainText("1 row");
    if (byteSize === "546") {
      await viewed.getByRole("button", { name: "Download result" }).click();
      await expect(page.getByRole("menuitem", { name: /^Download Arrow original/ })).toContainText(formatted);
      await expect(page.getByRole("menu")).toContainText("Available until");
      await page.screenshot({ path: resolve(screenshotDirectory, "explore-result-available.png") });
      await page.keyboard.press("Escape");
    } else {
      await expect(viewed.getByText("Expired", { exact: true })).toBeVisible();
      await expect(viewed.getByRole("button", { name: "Download unavailable: this result has expired" })).toBeDisabled();
    }
  });
}
