import { mkdirSync } from "node:fs";
import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import type { SemanticGroupBy } from "../../src/api/explore";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const workspaceId = "019e8000-0000-7000-8000-000000000b01";
const connectorId = "019e8000-0000-7000-8000-000000000c01";
const deniedConnectorId = "019e8000-0000-7000-8000-000000000c02";
const runId = "019e8000-0000-7000-8000-000000000d11";
const runUrl = `/workspaces/${workspaceId}/explore`;

const relatedDimension = {
  kind: "DIMENSION",
  semanticModelName: "customers",
  name: "segment",
  granularity: null,
} as const;

const directDimension = {
  kind: "DIMENSION",
  semanticModelName: "orders",
  name: "country",
  granularity: null,
} as const;

type SemanticQueryRequest = {
  connectorId: string;
  query: {
    schemaVersion: number;
    metrics: string[];
    groupBys: unknown[];
    metricTimeRange: unknown;
    filters: unknown[];
    orderBy: unknown[];
    rowLimit: number;
  };
};

interface FixtureState {
  groupByRequests: Array<Record<string, unknown>>;
  renderRequests: SemanticQueryRequest[];
  startRequests: SemanticQueryRequest[];
}

function json(body: unknown, status = 200) {
  return {
    status,
    contentType: status >= 400 ? "application/problem+json" : "application/json",
    body: JSON.stringify(body),
  };
}

function metricResource() {
  return { sourcePartitionInfo: [],
    metricId: "metric-1",
    workspaceId,
    definition: {
      schemaVersion: 3,
      name: "qa_amount_sum",
      label: "Revenue",
      type: "simple",
      semanticModelName: "orders",
      measure: "amount",
    },
    createdAt: "2026-09-07T00:00:00Z",
    updatedAt: "2026-09-07T00:00:00Z",
  };
}

function semanticModelResources() {
  return [
    { sourcePartitionInfo: [],
      semanticModelId: "model-orders",
      workspaceId,
      definition: {
        schemaVersion: 3,
        name: "orders",
        label: "Orders",
        sourceRelations: [
          { connectorId, schema: "analytics", relation: "orders" },
          { connectorId: deniedConnectorId, schema: "analytics", relation: "orders" },
        ],
        entities: [{ name: "order", type: "primary", expression: "order_id" }],
        dimensions: [{ isPartition: false, name: "country", type: "categorical", expression: "country", label: "Country" }],
        measures: [{ name: "amount", aggregation: "sum", expression: "amount" }],
      },
      createdAt: "2026-09-07T00:00:00Z",
      updatedAt: "2026-09-07T00:00:00Z",
    },
    { sourcePartitionInfo: [],
      semanticModelId: "model-customers",
      workspaceId,
      definition: {
        schemaVersion: 3,
        name: "customers",
        label: "Customers",
        sourceRelations: [
          { connectorId, schema: "analytics", relation: "customers" },
          { connectorId: deniedConnectorId, schema: "analytics", relation: "customers" },
        ],
        entities: [{ name: "customer", type: "primary", expression: "customer_id" }],
        dimensions: [{ isPartition: false, name: "segment", type: "categorical", expression: "segment", label: "Segment" }],
        measures: [],
      },
      createdAt: "2026-09-07T00:00:00Z",
      updatedAt: "2026-09-07T00:00:00Z",
    },
  ];
}

function semanticRunView(query: SemanticQueryRequest["query"]) {
  const hasRelatedGroupBy = query.groupBys.some((groupBy) =>
    JSON.stringify(groupBy) === JSON.stringify(relatedDimension));
  return {
    kind: "SEMANTIC",
    runId,
    workspaceId,
    connectorId,
    requestDigest: "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    state: "SUCCEEDED",
    revision: 1,
    resultAvailable: true,
    failureCode: null,
    failureDetail: null,
    createdAt: "2026-09-07T00:00:00Z",
    updatedAt: "2026-09-07T00:00:04Z",
    sql: "SELECT customers.segment, SUM(orders.amount) AS amount FROM analytics.orders JOIN analytics.customers USING (customer_id) GROUP BY customers.segment",
    query,
    resultMetadata: {
      byteSize: "96",
      mediaType: "application/vnd.apache.arrow.file",
      rowCount: "1",
      columnCount: hasRelatedGroupBy ? 2 : 1,
      expiresAt: "2026-09-14T00:00:00Z",
    },
    result: hasRelatedGroupBy
      ? {
        columns: [
          { ordinal: 1, name: "dimension.customers.segment", logicalType: "TEXT", nullable: false },
          { ordinal: 2, name: "metric.qa_amount_sum", logicalType: "DECIMAL", nullable: false },
        ],
        rows: [["A", "90.00"]],
        returnedRowCount: 1,
        stopReason: "ROW_LIMIT",
      }
      : {
        columns: [{ ordinal: 1, name: "metric.qa_amount_sum", logicalType: "DECIMAL", nullable: false }],
        rows: [["90.00"]],
        returnedRowCount: 1,
        stopReason: "ROW_LIMIT",
      },
  };
}

async function authenticateExploreMember(page: Page): Promise<void> {
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Explore QA Workspace",
    organizationId: "organization-1",
    role: "EXPLORER",
    user: { id: "explore-qa-user", email: "explore-qa@example.test", displayName: "Explore QA" },
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, runUrl);
}

async function installExploreFixtures(
  page: Page,
  groupByResponses: Array<readonly SemanticGroupBy[]> = [[directDimension, relatedDimension]],
  longLabels = false,
): Promise<FixtureState> {
  const state: FixtureState = { groupByRequests: [], renderRequests: [], startRequests: [] };
  let groupByReadCount = 0;

  await page.route("**/v2/workspaces/**", async (route: Route) => {
    const requestUrl = new URL(route.request().url());
    const path = requestUrl.pathname;
    const requestBody = route.request().postData() ? JSON.parse(route.request().postData() as string) as Record<string, unknown> : {};

    if (path.endsWith("/connectors")) {
      await route.fulfill(json({
        connectors: [
          {
            id: connectorId,
            workspaceId,
            name: "Explore QA Connector",
            type: "POSTGRESQL",
            properties: { host: "fixture.internal", port: 5432, database: "warehouse" },
            createdAtEpochMs: 0,
            updatedAtEpochMs: 0,
          },
          {
            id: deniedConnectorId,
            workspaceId,
            name: "Permission-limited Connector",
            type: "POSTGRESQL",
            properties: { host: "fixture.internal", port: 5432, database: "warehouse" },
            createdAtEpochMs: 0,
            updatedAtEpochMs: 0,
          },
          {
            id: "019e8000-0000-7000-8000-000000000c03",
            workspaceId,
            name: "Unrelated warehouse",
            type: "POSTGRESQL",
            properties: { host: "fixture.internal", port: 5432, database: "unrelated" },
            createdAtEpochMs: 0,
            updatedAtEpochMs: 0,
          },
        ],
      }));
      return;
    }
    if (path.endsWith("/semantic/metrics")) {
      const metric = metricResource();
      if (longLabels) metric.definition.label = "Revenue from exceptionally long named customer transactions";
      await route.fulfill(json([metric]));
      return;
    }
    if (path.endsWith("/semantic/semantic-models")) {
      const models = semanticModelResources();
      if (longLabels) models[1]!.definition.dimensions[0]!.label = "Segment with an exceptionally long customer classification";
      await route.fulfill(json(models));
      return;
    }
    if (path.endsWith("/semantic/query/group-bys")) {
      state.groupByRequests.push(requestBody);
      const response = groupByResponses[Math.min(groupByReadCount, groupByResponses.length - 1)] ?? [];
      groupByReadCount += 1;
      await route.fulfill(json({
        groupBys: response,
        excludedSemanticModels: [],
        dimensionFilterTypes: response.flatMap((groupBy) => groupBy.kind === "DIMENSION" && !groupBy.granularity
          ? [{ dimension: { semanticModelName: groupBy.semanticModelName, name: groupBy.name }, kind: "TEXT" }]
          : []),
      }));
      return;
    }
    if (path.endsWith("/semantic/query/render-sql")) {
      state.renderRequests.push(requestBody as unknown as SemanticQueryRequest);
      await route.fulfill(json({
        dialect: "POSTGRESQL",
        sql: "SELECT customers.segment, SUM(orders.amount) AS amount FROM analytics.orders JOIN analytics.customers USING (customer_id) GROUP BY customers.segment",
      }));
      return;
    }
    if (path.endsWith("/explore/semantic-runs")) {
      state.startRequests.push(requestBody as unknown as SemanticQueryRequest);
      await route.fulfill(json(semanticRunView((requestBody.query ?? {}) as SemanticQueryRequest["query"])));
      return;
    }
    if (path.endsWith("/explore/runs")) {
      await route.fulfill(json({ runs: [] }));
      return;
    }
    if (path.endsWith(`/explore/runs/${runId}`)) {
      await route.fulfill(json(semanticRunView({
        schemaVersion: 4,
        metrics: ["qa_amount_sum"],
        groupBys: [relatedDimension],
        metricTimeRange: null,
        filters: [],
        orderBy: [],
        rowLimit: 1000,
      })));
      return;
    }
    await route.fulfill(json({}));
  });

  await authenticateExploreMember(page);
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/explore$`));
  await expect(page.getByRole("tabpanel", { name: "Metrics" })).toBeVisible();
  return state;
}

// With no run in view the Group by list below the question is the picker.
function groupByPanel(page: Page) {
  return page.getByRole("region", { name: "Group by list" });
}

function questionChip(page: Page, name: string) {
  return page.getByLabel("Query", { exact: true }).getByText(name, { exact: true });
}

test("keeps long metric, group by, and filter chips inside main at 390px", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installExploreFixtures(page, [[directDimension, relatedDimension]], true);
  await chooseMetric(page);
  await relatedGroupByRow(page).click();
  await page.getByRole("button", { name: "Add filter", exact: true }).click();
  await chooseOption(page, "Filter dimension", /Segment.*Customers/i);
  const filterPanel = page.getByRole("dialog", { name: "Filter" });
  await filterPanel.getByLabel("Filter value").fill("An exceptionally long customer segment value");
  await filterPanel.getByRole("button", { name: "Add filter", exact: true }).click();

  const question = page.getByLabel("Query", { exact: true });
  const main = page.getByRole("main").last();
  for (const state of ["draft", "viewed run"]) {
    await expect.poll(() => main.evaluate((element) => element.scrollWidth - element.clientWidth)).toBe(0);
    for (const group of ["metrics", "group-bys", "filters"]) {
      const chip = question.locator(`[data-token-group="${group}"] > span`).first();
      const label = chip.locator(".truncate").first();
      await expect(label).toHaveCSS("text-overflow", "ellipsis");
      expect(await label.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
      const remove = chip.getByRole("button", { name: /^Remove / });
      await expect(remove).toBeVisible();
      const bounds = await remove.boundingBox();
      const mainBounds = await main.boundingBox();
      expect(bounds!.width).toBe(24);
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(mainBounds!.x + mainBounds!.width);
    }
    await page.screenshot({ path: evidencePath(`screenshots/joo-150/long-chips-${state.replace(" ", "-")}-390.png`), fullPage: true });
    if (state === "draft") await page.getByRole("button", { name: "Run", exact: true }).click();
    await expect(page.getByRole("region", { name: /Revenue .* by Segment/ })).toBeVisible();
  }
  for (const group of ["filters", "group-bys", "metrics"]) {
    await question.locator(`[data-token-group="${group}"]`).getByRole("button", { name: /^Remove / }).click();
    await expect(question.locator(`[data-token-group="${group}"] [data-question-remove]`)).toHaveCount(0);
  }
});

async function chooseOption(page: Page, combobox: string, option: string | RegExp): Promise<void> {
  await page.getByRole("combobox", { name: combobox, exact: true }).click();
  await page.getByRole("listbox", { name: combobox }).getByRole("option", { name: option }).click();
}

function relatedGroupByRow(page: Page) {
  return groupByPanel(page).getByRole("button", { name: /Segment.*Customers/i });
}

async function expectExploreToolbarLayout(page: Page, viewport: string): Promise<void> {
  const width = page.viewportSize()?.width ?? 0;
  const controls = [
    ["query tabs", page.getByRole("tablist", { name: "Query type" })],
    ["connector", page.getByRole("button", { name: "Select connector" })],
    ["render SQL", page.getByRole("button", { name: "View rendered SQL" })],
    ["run", page.getByRole("button", { name: "Run", exact: true })],
    ["runs", page.getByRole("button", { name: /^Runs/ })],
  ] as const;
  for (const [label, control] of controls) {
    const box = await control.boundingBox();
    expect(box, `${viewport}: ${label} must render`).not.toBeNull();
    expect(box!.x, `${viewport}: ${label} starts inside the viewport`).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width, `${viewport}: ${label} ends inside the viewport`).toBeLessThanOrEqual(width + 1);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth), `${viewport}: no horizontal page scroll`).toBe(width);
}

async function chooseMetric(page: Page): Promise<void> {
  await page.getByRole("region", { name: "Metrics list" }).getByRole("button", { name: /^Revenue/ }).click();
  await chooseConnector(page, connectorId);
}

async function chooseConnector(page: Page, id: string): Promise<void> {
  await page.getByRole("button", { name: "Select connector" }).click();
  const options = page.getByRole("listbox", { name: "Connectors" }).getByRole("option");
  await expect(options.filter({ hasText: "Permission-limited Connector" })).toHaveCount(1);
  await options.filter({ hasText: id === deniedConnectorId ? "Permission-limited Connector" : "Explore QA Connector" }).click();
}

test.beforeAll(() => {
  mkdirSync(evidencePath("screenshots/qa-product-chain/related-dimensions-ui"), { recursive: true });
});

test("leaves compatible connectors unselected until a keyboard choice in Metrics", async ({ page }) => {
  const fixture = await installExploreFixtures(page);
  const connectorTrigger = page.getByRole("button", { name: "Select connector" });
  await expect(connectorTrigger).toHaveCount(0);
  await page.getByRole("region", { name: "Metrics list" }).getByRole("button", { name: /^Revenue/ }).click();
  await expect(connectorTrigger).toContainText("Choose a connector");
  await expect(page.getByRole("button", { name: "Run", exact: true })).toBeDisabled();
  expect(fixture.groupByRequests).toHaveLength(0);

  await connectorTrigger.focus();
  await connectorTrigger.press("Enter");
  const connectorListbox = page.getByRole("listbox", { name: "Connectors" });
  await expect(connectorListbox).toBeFocused();
  const options = connectorListbox.getByRole("option");
  await expect(options).toHaveCount(2);
  await expect(options.filter({ hasText: "Explore QA Connector" })).toHaveCount(1);
  await expect(options.filter({ hasText: "Permission-limited Connector" })).toHaveCount(1);
  await expect(options.filter({ hasText: "Unrelated warehouse" })).toHaveCount(0);
  await expect(connectorListbox.getByRole("option", { selected: true })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(connectorListbox).toBeHidden();
  await expect(connectorTrigger).toBeFocused();
  await expect(connectorTrigger).toContainText("Choose a connector");

  await connectorTrigger.press("Enter");
  await expect(connectorListbox).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(connectorListbox).toBeHidden();
  await expect(connectorTrigger).toBeFocused();
  await expect(connectorTrigger).toContainText("Permission-limited Connector");
  await expect.poll(() => fixture.groupByRequests.map((request) => request.connectorId)).toEqual([deniedConnectorId]);
  await expect(page.getByRole("button", { name: "Run", exact: true })).toBeEnabled();
});

test("renders a returned related dimension, sends its selector, and labels the canonical result key", async ({ page }) => {
  const fixture = await installExploreFixtures(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await chooseMetric(page);

  const relatedRow = relatedGroupByRow(page);
  await expect(relatedRow).toBeVisible();
  await relatedRow.click();
  await expect(relatedRow).toHaveAttribute("aria-pressed", "true");
  await page.screenshot({ path: evidencePath("screenshots/qa-product-chain/related-dimensions-ui/related-groupby-selected-desktop.png"), fullPage: true });
  await expect(questionChip(page, "Segment")).toBeVisible();

  await page.getByRole("button", { name: "View rendered SQL" }).click();
  await expect(page.getByTestId("rendered-sql-dialog")).toBeVisible();
  await expect.poll(() => fixture.renderRequests.length).toBe(1);
  expect(fixture.renderRequests[0]).toMatchObject({
    connectorId,
    query: { groupBys: [relatedDimension] },
  });
  await page.getByRole("button", { name: "Close rendered SQL" }).click();

  const runButton = page.getByRole("button", { name: "Run", exact: true });
  await runButton.focus();
  await runButton.press("Enter");
  await expect.poll(() => fixture.startRequests.length).toBe(1);
  expect(fixture.startRequests[0]).toMatchObject({
    connectorId,
    query: { groupBys: [relatedDimension] },
  });

  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/explore\\?run=${runId}$`));
  const resultGrid = page.getByRole("region", { name: "Revenue by Segment" }).getByTestId("run-sample-grid");
  await expect(resultGrid).toBeVisible();
  const segmentHeader = resultGrid.getByRole("columnheader", { name: "Segment", exact: true });
  await expect(segmentHeader.getByRole("button")).toHaveAttribute("title", "dimension.customers.segment · TEXT");
  const revenueHeader = resultGrid.getByRole("columnheader", { name: "Revenue", exact: true });
  await expect(revenueHeader.getByRole("button")).toHaveCSS("justify-content", "flex-end");
  await expect(resultGrid.locator("tbody td:not([aria-hidden])").last()).toHaveCSS("text-align", "right");
  await expect(resultGrid).toContainText("A");
  await page.screenshot({ path: evidencePath("screenshots/qa-product-chain/related-dimensions-ui/related-result-desktop.png"), fullPage: true });
});

test("uses the related model dimension as the target for a filter-only semantic query", async ({ page }) => {
  const fixture = await installExploreFixtures(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await chooseMetric(page);
  await expectExploreToolbarLayout(page, "390px");
  await page.setViewportSize({ width: 900, height: 900 });
  await expectExploreToolbarLayout(page, "900px");
  await page.setViewportSize({ width: 390, height: 844 });

  await page.getByRole("button", { name: "Add filter", exact: true }).click();
  const filterPanel = page.getByRole("dialog", { name: "Filter" });
  await chooseOption(page, "Filter dimension", /Segment.*Customers/i);
  await expect(filterPanel.getByRole("combobox", { name: "Compare as", exact: true })).toHaveCount(0);
  await filterPanel.getByLabel("Filter value").fill("A");
  await filterPanel.getByRole("button", { name: "Add filter", exact: true }).click();
  await expect(page.getByRole("button", { name: "Segment is A", exact: true })).toBeVisible();
  await page.screenshot({ path: evidencePath("screenshots/qa-product-chain/related-dimensions-ui/related-filter-mobile.png"), fullPage: true });

  const renderedSqlButton = page.getByRole("button", { name: "View rendered SQL" });
  await renderedSqlButton.focus();
  await renderedSqlButton.press("Enter");
  await expect.poll(() => fixture.renderRequests.length).toBe(1);
  expect(fixture.renderRequests[0]).toMatchObject({
    connectorId,
    query: {
      groupBys: [],
      filters: [{
        kind: "TEXT",
        dimension: { semanticModelName: "customers", name: "segment" },
        operator: "EQUALS",
        values: ["A"],
      }],
    },
  });
  await page.getByRole("button", { name: "Close rendered SQL" }).click();

  const runButton = page.getByRole("button", { name: "Run", exact: true });
  await runButton.focus();
  await runButton.press("Enter");
  await expect.poll(() => fixture.startRequests.length).toBe(1);
  expect(fixture.startRequests[0]).toMatchObject({
    connectorId,
    query: {
      groupBys: [],
      filters: [{
        kind: "TEXT",
        dimension: { semanticModelName: "customers", name: "segment" },
        operator: "EQUALS",
        values: ["A"],
      }],
    },
  });
});

test("removes a permission-denied related dimension after a later group-by response", async ({ page }) => {
  const fixture = await installExploreFixtures(page, [[directDimension, relatedDimension], [directDimension]]);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await chooseMetric(page);

  const relatedRow = relatedGroupByRow(page);
  await relatedRow.click();
  await expect(relatedRow).toHaveAttribute("aria-pressed", "true");
  await expect(questionChip(page, "Segment")).toBeVisible();

  await chooseConnector(page, deniedConnectorId);
  await expect.poll(() => fixture.groupByRequests.length).toBe(2);
  expect(fixture.groupByRequests[1]).toMatchObject({ connectorId: deniedConnectorId });

  // The question keeps Segment, marked, and Run waits until the person removes it.
  await expect(questionChip(page, "Segment")).toBeVisible();
  await expect(page.getByText("Not available for these metrics on Permission-limited Connector. Remove to run.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Run", exact: true })).toBeDisabled();
  await expect(relatedGroupByRow(page)).toHaveCount(0);
  await expect(groupByPanel(page).getByRole("button", { name: /Country.*Orders/i })).toHaveAttribute("aria-pressed", "false");
  await page.getByRole("button", { name: "Add filter", exact: true }).click();
  await page.getByRole("dialog", { name: "Filter" }).getByRole("combobox", { name: "Filter dimension" }).click();
  await expect(page.getByRole("listbox", { name: "Filter dimension" }).getByRole("option", { name: /Segment.*Customers/i })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Remove Segment" }).click();
  await expect(page.getByRole("button", { name: "Run", exact: true })).toBeEnabled();
  await page.screenshot({ path: evidencePath("screenshots/qa-product-chain/related-dimensions-ui/related-permission-denied-desktop.png"), fullPage: true });
});

test("searches metrics and group bys and sends the ordered targets in their listed order", async ({ page }) => {
  const fixture = await installExploreFixtures(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  const metricsPanel = page.getByRole("region", { name: "Metrics list" });
  const metricSearch = metricsPanel.getByRole("searchbox", { name: "Search metrics" });
  await metricSearch.fill("profit");
  await expect(metricsPanel.getByText("No metrics match this search.")).toBeVisible();
  await expect(metricsPanel.getByRole("button", { name: /^Revenue/ })).toHaveCount(0);
  await metricSearch.fill("QA_AMOUNT");
  await metricsPanel.getByRole("button", { name: /^Revenue/ }).click();
  await chooseConnector(page, connectorId);

  const groupBySearch = groupByPanel(page).getByRole("searchbox", { name: "Search group bys" });
  await groupBySearch.fill("segment");
  await expect(relatedGroupByRow(page)).toBeVisible();
  await expect(groupByPanel(page).getByRole("button", { name: /Country.*Orders/i })).toHaveCount(0);
  await groupBySearch.fill("channel");
  await expect(groupByPanel(page).getByText("No group bys match this search.")).toBeVisible();
  await groupBySearch.fill("");
  await relatedGroupByRow(page).click();
  await expect(relatedGroupByRow(page)).toHaveAttribute("aria-pressed", "true");
  await expect(groupByPanel(page).getByRole("button", { name: /Country.*Orders/i })).toHaveAttribute("aria-pressed", "false");

  const sortPanel = page.getByRole("dialog", { name: "Sort" });
  await page.getByRole("button", { name: "Add sort", exact: true }).click();
  await chooseOption(page, "Order direction", "Descending");
  await sortPanel.getByRole("button", { name: "Add sort", exact: true }).click();
  await page.getByRole("button", { name: "Add sort", exact: true }).click();
  await sortPanel.getByRole("combobox", { name: "Order target" }).click();
  await expect(page.getByRole("listbox", { name: "Order target" }).getByRole("option")).toHaveText(["Segment"]);
  await page.keyboard.press("Escape");
  await chooseOption(page, "Order direction", "Ascending");
  await sortPanel.getByRole("button", { name: "Add sort", exact: true }).click();
  // Sort tokens show an arrow and read their direction as a word.
  const orderTokens = page.getByRole("tabpanel", { name: "Metrics" }).getByRole("button", { name: /^(Revenue|Segment) (ascending|descending)$/ });
  await expect(orderTokens).toHaveCount(2);
  await expect(orderTokens.first()).toHaveAccessibleName("Revenue descending");
  await expect(orderTokens.last()).toHaveAccessibleName("Segment ascending");
  await orderTokens.first().click();
  await expect(sortPanel.getByRole("button", { name: "Sort earlier" })).toBeDisabled();
  await page.keyboard.press("Escape");
  await orderTokens.last().click();
  await expect(sortPanel.getByRole("button", { name: "Sort later" })).toBeDisabled();
  await sortPanel.getByRole("button", { name: "Sort earlier" }).click();
  await expect(orderTokens.first()).toHaveAccessibleName("Segment ascending");
  await expect(orderTokens.last()).toHaveAccessibleName("Revenue descending");

  await page.getByRole("button", { name: "View rendered SQL" }).click();
  await expect.poll(() => fixture.renderRequests.length).toBe(1);
  expect(fixture.renderRequests[0].query.orderBy).toEqual([
    { target: { kind: "GROUP_BY", groupBy: relatedDimension }, direction: "ASC" },
    { target: { kind: "METRIC", metricName: "qa_amount_sum" }, direction: "DESC" },
  ]);
});
