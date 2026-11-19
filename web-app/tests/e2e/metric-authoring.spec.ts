import { mockSemanticLineage } from "./support/semantic-lineage";
import type { Page, Route } from "@playwright/test";
import type { components } from "../../src/api/control/generated";
import { controlUuidV7 } from "../../src/api/control/constraints";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { json, mockSignedInWorkspace, problem } from "./support/session";
import { evidencePath } from "./evidence";
import { expect, test } from "./support/test";

// Contract pattern for the Idempotency-Key header on semantic writes.
const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{1,200}$/;

type MetricResponse = components["schemas"]["MetricResponse"];
type MetricWriteRequest = components["schemas"]["MetricWriteRequest"];
type SemanticModelResponse = components["schemas"]["SemanticModelResponse"];

const workspaceId = "019e8000-0000-7000-8000-000000000a81";
const connectorId = "019e8000-0000-7000-8000-000000000c81";
const ordersModelId = "019e8000-0000-7000-8000-000000000e81";
const netRevenueId = "019e8000-0000-7000-8000-000000000f81";
const createdMetricId = "019e8000-0000-7000-8000-000000000f82";
const semanticPath = `/v2/workspaces/${workspaceId}/semantic`;
const metricDetailUrl = (metricId: string) => new RegExp(`/workspaces/${workspaceId}/metrics/${metricId}(?:\\?edit=false)?$`);

const ordersModel = { sourcePartitionInfo: [],
  semanticModelId: ordersModelId,
  workspaceId,
  definition: {
    schemaVersion: 3,
    name: "orders",
    label: "Orders",
    sourceRelations: [{ connectorId: controlUuidV7(connectorId), schema: "analytics", relation: "orders" }],
    entities: [{ name: "order_id", type: "primary", expression: "order_id" }],
    dimensions: [{ isPartition: false, name: "region", type: "categorical", expression: "region" }],
    measures: [
      { name: "revenue", label: "Revenue", aggregation: "sum", expression: "amount" },
      { name: "order_count", label: "Order count", aggregation: "count", expression: "order_id" },
    ],
  },
  createdAt: "2026-09-01T00:00:00Z",
  updatedAt: "2026-09-01T00:00:00Z",
} satisfies SemanticModelResponse;

const netRevenue = { sourcePartitionInfo: [],
  metricId: netRevenueId,
  workspaceId,
  definition: {
    schemaVersion: 3,
    name: "net_revenue",
    label: "Net Revenue",
    description: "Revenue after refunds.",
    type: "simple",
    semanticModelName: "orders",
    measure: "revenue",
    filters: [{ dimension: { semanticModelName: "orders", name: "region" }, operator: "eq", values: ["EMEA"] }],
    timeGranularity: "month",
  },
  createdAt: "2026-09-02T00:00:00Z",
  updatedAt: "2026-09-03T00:00:00Z",
} satisfies MetricResponse;

interface MetricWrite {
  method: string;
  path: string;
  idempotencyKey: string | undefined;
  body: MetricWriteRequest;
}

// Control's current Semantic reads and writes for one EDITOR. Metric writes answer from the stored
// list; a create whose name already exists gets the declared 409 Problem Details.
type SemanticCheckView = components["schemas"]["SemanticCheckView"];

const connectorsList = {
  connectors: [{
    id: connectorId,
    workspaceId,
    name: "Production Postgres",
    type: "POSTGRESQL",
    properties: { host: "warehouse.internal", port: 5432, database: "analytics" },
    createdAtEpochMs: 1,
    updatedAtEpochMs: 1,
  }],
} satisfies components["schemas"]["ConnectorListResponse"];

const resolvesCheck = { partitionFilters: [],
  ok: true,
  runsOn: [controlUuidV7(connectorId)],
  preview: {
    connectorId: controlUuidV7(connectorId),
    dialect: "POSTGRESQL",
    sql: "SELECT\n  DATE_TRUNC('day', orders_src.ordered_at) AS metric_time__day\n  , COUNT(orders_src.order_id) AS order_count\nFROM analytics.orders orders_src\nGROUP BY\n  DATE_TRUNC('day', orders_src.ordered_at)",
    groupBys: [
      { kind: "DIMENSION", semanticModelName: "orders", name: "region" },
      { kind: "ENTITY", semanticModelName: "orders", name: "order_id" },
      { kind: "METRIC_TIME", granularity: "DAY" },
    ],
  },
  problems: [],
} satisfies SemanticCheckView;

const problemCheck = { partitionFilters: [],
  ok: false,
  runsOn: [],
  preview: null,
  problems: [
    { code: "METRICFLOW_MANIFEST_INVALID", field: null, connectorId: null, definition: null, message: "Measure order_cnt referenced in metric order_count is not defined in the model!" },
    { code: "TIME_SPINE_UNAVAILABLE", field: null, connectorId: controlUuidV7(connectorId), definition: null, message: "This metric needs a Calendar table on this connector. Set one up in Semantics under Setup." },
  ],
} satisfies SemanticCheckView;

async function openMetricRoute(page: Page, target: string, initialMetrics: MetricResponse[], checkResult: SemanticCheckView = resolvesCheck) {
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Metric authoring",
    organizationId: "organization-metric-authoring",
    role: "EDITOR",
    user: { id: "metric-editor", email: "metric-editor@example.test", displayName: "Metric editor" },
  });
  const metrics = [...initialMetrics];
  let model: SemanticModelResponse = ordersModel;
  const modelWrites: Array<{body: components["schemas"]["SemanticModelWriteRequest"]; idempotencyKey: string | undefined}> = [];
  const writes: MetricWrite[] = [];
  const checks: Array<{ kind: string }> = [];
  const unexpected: string[] = [];
  await mockSemanticLineage(page);
  await page.route("**/v2/workspaces/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (method === "GET" && path === `${semanticPath}/semantic-models`) return json(route, [model]);
    if (method === "GET" && path === `${semanticPath}/semantic-models/${ordersModelId}`) return json(route, model);
    if (method === "PUT" && path === `${semanticPath}/semantic-models/${ordersModelId}`) {
      const body = request.postDataJSON() as components["schemas"]["SemanticModelWriteRequest"];
      modelWrites.push({body, idempotencyKey: request.headers()["idempotency-key"]});
      model = {...model, definition: body.definition, updatedAt: "2026-10-07T00:00:00Z"};
      const aggregateMetrics = body.aggregateMetrics.map(({ measure, ...metric }) => ({ sourcePartitionInfo: [], metricId: createdMetricId, workspaceId, definition: { schemaVersion: 3, ...metric, type: "simple", semanticModelName: model.definition.name, measure }, createdAt: model.updatedAt, updatedAt: model.updatedAt } satisfies MetricResponse));
      metrics.push(...aggregateMetrics);
      return json(route, { model, aggregateMetrics } satisfies components["schemas"]["SemanticModelWriteResponse"]);
    }
    if (method === "GET" && path === `/v2/workspaces/${workspaceId}/catalog/connectors/${connectorId}/relations/orders`) return json(route, { requiresPartitionFilter: null,
      name: "orders", schemaName: "analytics", kind: "TABLE",
      columns: ["order_id", "amount", "region"].map((name, index) => ({name, nativeType: "text", nullable: false, ordinalPosition: index + 1})),
      clusteringKeys: [], foreignKeys: [], partitionKeys: [],
      lineageResourceRef: {id: "019e8000-0000-7000-8000-000000000b81", kind: "catalog_table"},
    } satisfies components["schemas"]["CatalogRelation"]);
    if (method === "POST" && path === `${semanticPath}/query/group-bys`) return json(route, {groupBys: resolvesCheck.preview.groupBys, excludedSemanticModels: []});
    if (method === "GET" && path === `${semanticPath}/metrics`) return json(route, metrics);
    if (method === "GET" && path === `${semanticPath}/time-spines`) return json(route, []);
    if (method === "GET" && path === `/v2/workspaces/${workspaceId}/connectors`) return json(route, connectorsList);
    if (method === "POST" && path === `${semanticPath}/check`) {
      checks.push(request.postDataJSON() as { kind: string });
      return json(route, checkResult);
    }
    const metricMatch = /^\/v2\/workspaces\/[^/]+\/semantic\/metrics(?:\/([^/]+))?$/.exec(path);
    if (metricMatch && method === "GET" && metricMatch[1]) {
      const metric = metrics.find((candidate) => candidate.metricId === metricMatch[1]);
      return metric ? json(route, metric) : problem(route, 404, "NOT_FOUND", "Metric was not found.", "Not Found");
    }
    if (metricMatch && (method === "POST" || method === "PUT")) {
      const body = request.postDataJSON() as MetricWriteRequest;
      writes.push({ method, path, idempotencyKey: request.headers()["idempotency-key"], body });
      if (method === "POST" && metrics.some((candidate) => candidate.definition.name === body.definition.name)) {
        return nameConflict(route);
      }
      const metricId = metricMatch[1] ?? createdMetricId;
      const current = metrics.find((candidate) => candidate.metricId === metricId);
      const saved = {
        sourcePartitionInfo: [],
        metricId,
        workspaceId,
        definition: body.definition,
        createdAt: current?.createdAt ?? "2026-09-30T00:00:00Z",
        updatedAt: "2026-09-30T00:00:00Z",
      } satisfies MetricResponse;
      metrics.splice(0, metrics.length, ...metrics.filter((candidate) => candidate.metricId !== metricId), saved);
      return json(route, saved, method === "POST" ? 201 : 200);
    }
    unexpected.push(`${method} ${path}`);
    return problem(route, 404, "NOT_FOUND", `No fixture for ${path}.`, "Not Found");
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  await Promise.all([
    page.waitForResponse((response) => response.request().method() === "GET" && new URL(response.url()).pathname === `${semanticPath}/semantic-models` && response.status() === 200),
    page.waitForResponse((response) => response.request().method() === "GET" && new URL(response.url()).pathname === `${semanticPath}/metrics` && response.status() === 200),
    completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, target),
  ]);
  return { writes, modelWrites, checks, unexpected };
}

// Control's declared name conflict: a safe detail plus the field to repair.
function nameConflict(route: Route): Promise<void> {
  return route.fulfill({
    status: 409,
    contentType: "application/problem+json",
    body: JSON.stringify({
      type: "https://monomer.cc/problems/semantic-name-conflict",
      title: "Conflict",
      status: 409,
      detail: "Another definition in this workspace already uses this name.",
      instance: new URL(route.request().url()).pathname,
      code: "SEMANTIC_NAME_CONFLICT",
      requestId: "e2e-request",
      traceId: "e2e-trace",
      retryable: false,
      violations: [{ field: "name", code: "SEMANTIC_NAME_CONFLICT", message: "This name is already used. Choose another name." }],
    }),
  });
}

function editor(page: Page) {
  return {
    name: page.getByRole("textbox", { name: /^Name/ }),
    label: page.getByRole("textbox", { name: /^Label/ }),
    semanticModel: page.getByLabel("Model", { exact: true }),
    measure: page.getByRole("combobox", { name: "Aggregate metric" }),
    save: page.getByRole("button", { name: "Save", exact: true }),
    error: page.getByRole("alert").filter({ hasText: "already uses this name" }),
  };
}

test("edits only metric display, searches currency codes, and saves the explicit format", async ({ page }) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/${netRevenueId}?edit=true`, [netRevenue]);
  await page.getByRole("combobox", { name: "Style", exact: true }).selectOption("CURRENCY");
  const currency = page.getByRole("combobox", { name: "Currency", exact: true });
  await currency.press("j");
  await currency.press("p");
  await currency.press("y");
  await currency.press("Enter");
  await expect(currency).toHaveText(/JPY/);
  await page.getByRole("combobox", { name: "Decimals", exact: true }).selectOption("0");
  await expect(page.getByRole("complementary", { name: "Affects if saved" })).toContainText("Display only — numbers don’t change. New runs use this format.");
  await page.screenshot({ path: evidencePath("metric-display-currency-editor.png"), fullPage: true });
  await editor(page).save.click();
  await expect(page).toHaveURL(metricDetailUrl(netRevenueId));
  expect(fixture.writes).toHaveLength(1);
  expect(fixture.writes[0].body.definition).toEqual({ ...netRevenue.definition, display: { style: "CURRENCY", currency: "JPY", decimals: 0 } });
  await expect(page.locator("main")).toContainText("Currency · JPY · 0 decimals");
  expect(fixture.unexpected).toEqual([]);
});

test("Percent previews a ratio and Default removes its stored display without changing calculation", async ({ page }) => {
  const formatted = { ...netRevenue, definition: { ...netRevenue.definition, display: { style: "PERCENT", decimals: 1 } } } satisfies MetricResponse;
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/${netRevenueId}?edit=true`, [formatted]);
  await expect(page.getByLabel("Display preview")).toContainText("123,450.0%");
  await page.getByRole("combobox", { name: "Style", exact: true }).selectOption("DEFAULT");
  await expect(page.getByLabel("Display preview")).toContainText("1,234.5");
  await editor(page).save.click();
  await expect(page).toHaveURL(metricDetailUrl(netRevenueId));
  expect(fixture.writes).toHaveLength(1);
  expect(fixture.writes[0].body.definition).toEqual(netRevenue.definition);
  await expect(page.locator("main")).toContainText("Default");
  expect(fixture.unexpected).toEqual([]);
});

test("creates an Aggregate metric from an existing aggregate and opens its detail", async ({ page }) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/new`, []);
  const form = editor(page);
  await expect(page.getByRole("heading", { name: "New metric" })).toBeVisible();

  await form.name.fill("gross_revenue");
  await form.label.fill("Gross Revenue");
  await form.semanticModel.selectOption({ label: "Orders · orders" });
  await expect(form.measure.getByRole("option", { name: "Revenue · Sum of amount" })).toHaveCount(1);
  await form.measure.selectOption("revenue");
  await form.save.click();

  await expect(page).toHaveURL(metricDetailUrl(createdMetricId));
  await expect(page.getByRole("heading", { name: "Gross Revenue", level: 1 })).toBeVisible();
  await expect(page.getByRole("main").getByRole("link", { name: "Explore", exact: true })).toHaveAttribute("href", `/workspaces/${workspaceId}/explore?metric=gross_revenue`);
  expect(fixture.writes).toEqual([{
    method: "POST",
    path: `${semanticPath}/metrics`,
    idempotencyKey: expect.any(String),
    body: {
      definition: {
        schemaVersion: 3,
        name: "gross_revenue",
        type: "simple",
        label: "Gross Revenue",
        semanticModelName: "orders",
        measure: "revenue",
      },
    } satisfies MetricWriteRequest,
  }]);
  await expect(page.getByRole("region", { name: "Preview", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Run preview", exact: true })).toHaveCount(0);
  expect(fixture.unexpected).toEqual([]);
});

test("choosing the already selected method keeps the Aggregate definition", async ({ page }) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/new`, []);
  const form = editor(page);
  await form.semanticModel.selectOption({ label: "Orders · orders" });
  await form.measure.selectOption("revenue");

  const simple = page.getByRole("radio", { name: /^Add up a column\b/ });
  await simple.click();
  await simple.press("Home");
  await expect(simple).toHaveAttribute("aria-checked", "true");
  await expect(form.semanticModel).toHaveValue("orders");
  await expect(form.measure).toHaveValue("revenue");
  expect(fixture.unexpected).toEqual([]);
});

test("removes an extra Formula input and keeps each remaining input's own options", async ({ page }) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/new`, []);
  await page.getByRole("radio", { name: /^Write a formula\b/ }).click();
  await expect(page.getByRole("button", { name: "Remove input 1" })).toHaveCount(0);
  await page.getByRole("button", { name: "Add input" }).click();
  await page.getByRole("button", { name: "Add input" }).click();
  const advanced = page.getByRole("button", { name: "Advanced options" });
  await advanced.nth(2).click();
  await expect(advanced.nth(2)).toHaveAttribute("aria-expanded", "true");

  await page.getByRole("button", { name: "Remove input 2" }).click();
  await expect(page.getByLabel("Input 3", { exact: true })).toHaveCount(0);
  await expect(advanced).toHaveCount(2);
  await expect(advanced.nth(0)).toHaveAttribute("aria-expanded", "false");
  await expect(advanced.nth(1)).toHaveAttribute("aria-expanded", "true");

  await page.getByRole("button", { name: "Remove input 2" }).click();
  await expect(page.getByLabel("Input 2", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Remove input 1" })).toHaveCount(0);
  expect(fixture.unexpected).toEqual([]);
});

test("shows a Metric's definition facts and saves an edit that keeps its filters", async ({ page }) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/${netRevenueId}`, [netRevenue]);

  const header = page.locator("header").filter({ has: page.getByRole("heading", { name: "Net Revenue", level: 1 }) });
  await expect(header.getByText("net_revenue", { exact: true })).toBeVisible();
  await expect(header).toContainText("Aggregate metric");
  await expect(page.getByText("Revenue after refunds.", {exact: true})).toBeVisible();
  const calculation = page.getByRole("region", { name: "How it's calculated", exact: true });
  const definition = calculation.locator("dl");
  await expect(definition.locator("dt")).toHaveText(["Aggregation", "Expression", "From"]);
  await expect(definition.locator("dd").nth(0)).toHaveText("Sum");
  await expect(definition.locator("dd").nth(1)).toHaveText("amount");
  await expect(definition.getByRole("link", {name: "Orders", exact: true})).toHaveAttribute("href", `/workspaces/${workspaceId}/semantic-models/${ordersModelId}`);
  await expect(header).not.toContainText("Sum of amount");
  const applied = page.getByRole("region", { name: "Applied to every query", exact: true });
  await expect(applied).toContainText("orders.region");
  await expect(applied).toContainText("EMEA");
  await expect(applied.locator("dt")).toHaveText(["Filter", "Default grain"]);
  await expect(applied.locator("dd").nth(1)).toHaveText("Month");
  const slice = page.getByRole("region", { name: "Slice by", exact: true });
  await expect(slice.getByRole("link", {name: "region"})).toHaveAttribute("href", `/workspaces/${workspaceId}/explore?metric=net_revenue&dimensionModel=orders&dimension=region`);

  await page.getByRole("button", { name: "Edit", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/metrics/${netRevenueId}\\?edit=true$`));
  const form = editor(page);
  await expect(page.getByRole("complementary", {name: "Affects if saved"})).toBeVisible();
  await expect(page.getByRole("button", {name: "Cancel", exact: true})).toBeVisible();
  await expect(page.getByRole("heading", { name: "Net Revenue", level: 1 })).toBeVisible();
  await expect(page.locator("#metric-name")).toHaveText("net_revenue");
  await expect(form.name).toHaveCount(0);
  await expect(form.semanticModel).toHaveValue("orders");
  await expect(form.measure).toHaveValue("revenue");
  await expect(page.getByRole("combobox", { name: "Condition dimension" })).toHaveValue("region");
  await expect(page.getByRole("combobox", { name: "Condition operator" })).toHaveValue("eq");
  await expect(page.getByRole("combobox", { name: "Condition operator" }).locator("option:checked")).toHaveText("is");
  await expect(page.getByRole("textbox", { name: "Condition value 1" })).toHaveValue("EMEA");
  await form.label.fill("Net Revenue (EMEA)");
  await form.save.click();

  await expect(page).toHaveURL(metricDetailUrl(netRevenueId));
  await expect(page.getByRole("heading", { name: "Net Revenue (EMEA)", level: 1 })).toBeVisible();
  expect(fixture.writes).toHaveLength(1);
  expect(fixture.writes[0].idempotencyKey).toMatch(IDEMPOTENCY_KEY);
  expect(fixture.writes[0]).toMatchObject({ method: "PUT", path: `${semanticPath}/metrics/${netRevenueId}` });
  expect(fixture.writes[0].body).toEqual({
    definition: { ...netRevenue.definition, label: "Net Revenue (EMEA)" },
  } satisfies MetricWriteRequest);
  expect(fixture.unexpected).toEqual([]);
});

test("blocks Save until the name and definition are valid", async ({ page }) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/new`, []);
  const form = editor(page);
  await expect(page.getByRole("heading", { name: "New metric" })).toBeVisible();

  await form.save.click();
  const errors = page.getByRole("alert", { name: "Metric validation errors" });
  await expect(errors).toBeFocused();
  await expect(errors.getByRole("listitem")).toHaveText(["Name: enter a name.", "Model: select a model.", "Column or expression: enter a column or expression."]);
  await expect(form.name).toHaveAttribute("aria-invalid", "true");
  await form.name.fill("Gross Revenue");
  await expect(errors.getByRole("listitem").first()).toHaveText("Name: use lowercase letters, numbers, and underscores, starting with a letter (e.g. gross_revenue).");
  await form.name.fill("gross_revenue");
  await form.semanticModel.selectOption("orders");
  await form.measure.selectOption("");
  await expect(errors.getByRole("listitem")).toHaveText(["Aggregate metric: select an aggregate."]);
  await expect(form.measure).toHaveAttribute("aria-invalid", "true");
  await page.getByRole("radio", { name: /^Divide one metric by another\b/ }).click();
  await expect(page.getByRole("radio", { name: /^Divide one metric by another\b/ })).toHaveAttribute("aria-checked", "true");
  await expect(errors).toHaveCount(0);
  await form.save.click();
  await expect(errors.getByRole("listitem")).toHaveText(["Numerator: select a numerator metric.", "Denominator: select a denominator metric."]);

  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/metrics/new$`));
  await expect(form.name).toHaveValue("gross_revenue");
  expect(fixture.writes).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test("keeps the Metric input after Control rejects a duplicate name", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 409, url: /\/semantic\/metrics$/, reason: "Control rejects a Metric create whose name already exists." });
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/new`, [netRevenue]);
  const form = editor(page);
  await expect(page.getByRole("heading", { name: "New metric" })).toBeVisible();

  await form.name.fill("net_revenue");
  await form.label.fill("Net Revenue");
  await form.semanticModel.selectOption("orders");
  await form.measure.selectOption("order_count");
  await form.save.click();

  await expect(form.error).toHaveText("Another definition in this workspace already uses this name.");
  await expect(page.getByText("This name is already used. Choose another name.")).toBeVisible();
  await expect(form.name).toHaveAttribute("aria-invalid", "true");
  await page.screenshot({ path: evidencePath("../screenshots/metric-editor-save-problem.png"), fullPage: false });
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/metrics/new$`));
  await expect(form.name).toHaveValue("net_revenue");
  await expect(form.label).toHaveValue("Net Revenue");
  await expect(form.semanticModel).toHaveValue("orders");
  await expect(form.measure).toHaveValue("order_count");

  await form.save.click();
  await expect.poll(() => fixture.writes.length).toBe(2);
  await expect(form.error).toHaveText("Another definition in this workspace already uses this name.");
  await form.name.fill("order_total");
  await form.save.click();

  await expect(page).toHaveURL(metricDetailUrl(createdMetricId));
  expect(fixture.writes.map((write) => write.body.definition.name)).toEqual(["net_revenue", "net_revenue", "order_total"]);
  for (const write of fixture.writes) expect(write.idempotencyKey).toMatch(IDEMPOTENCY_KEY);
  expect(fixture.writes[1].idempotencyKey).toBe(fixture.writes[0].idempotencyKey);
  expect(fixture.writes[2].idempotencyKey).not.toBe(fixture.writes[0].idempotencyKey);
  expect(fixture.unexpected).toEqual([]);
});

test("Create metric on an aggregate opens a prefilled Aggregate metric that is checked before saving", async ({ page }) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/new?model=orders&measure=order_count`, []);
  const form = editor(page);
  await expect(page.getByRole("heading", { name: "Order count", level: 1 })).toBeVisible();
  await expect(page.getByRole("radio", { name: /^Add up a column\b/ })).toHaveAttribute("aria-checked", "true");
  await expect(form.semanticModel).toHaveValue("orders");
  await expect(form.measure).toHaveValue("order_count");
  await expect(form.label).toHaveValue("Order count");
  await expect(form.name).toHaveValue("order_count");
  await expect.poll(() => fixture.checks.length).toBeGreaterThan(0);
  expect(fixture.checks[0]).toMatchObject({ kind: "METRIC", definition: { name: "order_count", type: "simple", semanticModelName: "orders", measure: "order_count" } });
  const check = page.getByRole("region", { name: "Check" });
  await expect(check.getByRole("status")).toHaveText("");
  await expect(check).toContainText("Runs on Production Postgres");
  await expect(check).not.toContainText("Group by");
  await expect(check.getByLabel("SQL preview")).toContainText("COUNT(orders_src.order_id)");
  await expect(check.getByText("You can still save with problems.")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/metric-editor-check-resolves.png"), fullPage: false });
  expect(fixture.writes).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test("the Check panel lists problems and still allows saving", async ({ page }) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/new?model=orders&measure=order_count`, [], problemCheck);
  const check = page.getByRole("region", { name: "Check" });
  await expect(check.getByRole("status")).toHaveText("2 problems");
  await expect(check).toContainText("Measure order_cnt referenced in metric order_count is not defined in the model!");
  await expect(check).toContainText("Production Postgres: This metric needs a Calendar table on this connector.");
  await expect(check.getByLabel("SQL preview")).toHaveCount(0);
  await expect(check).toContainText("You can still save with problems.");
  await page.screenshot({ path: evidencePath("../screenshots/metric-editor-check-problem.png"), fullPage: false });

  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(metricDetailUrl(createdMetricId));
  expect(fixture.writes).toHaveLength(1);
  expect(fixture.unexpected).toEqual([]);
});

test("Add up a column saves the model and aggregate metric in one atomic request", async ({page}) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/new?model=orders`, []);
  const form = editor(page);
  await expect(form.semanticModel).toHaveValue("orders");
  await expect(form.measure).toHaveValue("__new__");
  await form.label.fill("Average order amount");
  await page.getByLabel("Column or expression", {exact: true}).fill("amount");
  await page.getByRole("combobox", {name: "Aggregation", exact: true}).selectOption("average");
  await expect(form.name).toHaveValue("average_order_amount");
  await form.save.click();
  await expect(page).toHaveURL(metricDetailUrl(createdMetricId));
  expect(fixture.modelWrites).toHaveLength(1);
  expect(fixture.modelWrites[0].idempotencyKey).toMatch(IDEMPOTENCY_KEY);
  expect(fixture.modelWrites[0].body).toEqual({definition: {...ordersModel.definition, measures: [...ordersModel.definition.measures, {name: "average_order_amount", label: "Average order amount", aggregation: "average", expression: "amount"}]}, aggregateMetrics: [{ measure: "average_order_amount", name: "average_order_amount", label: "Average order amount" }]});
  expect(fixture.writes).toEqual([]);
  const savedDefinition = page.getByRole("region", { name: "How it's calculated", exact: true }).locator("dl");
  await expect(savedDefinition.locator("dd").nth(0)).toHaveText("Average");
  await expect(savedDefinition.locator("pre")).toHaveText("amount");
  expect(fixture.unexpected).toEqual([]);
});

for (const scenario of [
  {name: "simple division", expression: "current ÷ prior", type: "ratio"},
  {name: "other formula", expression: "(current - prior) / prior", type: "derived"},
] as const) {
  test(`Write a formula stores ${scenario.name} as ${scenario.type} and preserves qualified inputs`, async ({page}) => {
    const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/new`, [netRevenue]);
    await page.getByRole("radio", {name: /^Write a formula\b/}).click();
    await page.getByRole("textbox", {name: /^Label/}).fill("Revenue comparison");
    await page.getByLabel("Input 1", {exact: true}).selectOption("net_revenue");
    await page.getByRole("button", {name: "Add input"}).click();
    await page.getByLabel("Input 2", {exact: true}).selectOption("net_revenue");
    await page.getByRole("button", {name: "Advanced options"}).nth(0).click();
    await page.getByRole("button", {name: "Advanced options"}).nth(1).click();
    await page.getByLabel("Alias (optional)", {exact: true}).nth(0).fill("current");
    await page.getByLabel("Alias (optional)", {exact: true}).nth(1).fill("prior");
    await page.getByLabel("Offset window", {exact: true}).nth(1).fill("1 month");
    await page.getByRole("button", {name: "Add input condition", exact: true}).nth(1).click();
    await page.getByRole("combobox", {name: "Condition model"}).selectOption("orders");
    await page.getByRole("combobox", {name: "Condition dimension"}).selectOption("region");
    await page.getByRole("textbox", {name: "Condition value 1"}).fill("APAC");
    await page.getByRole("textbox", {name: /^Expression/}).fill(scenario.expression);
    if (scenario.type === "ratio") await expect(page.getByText("This simple division will be saved as a ratio. Input conditions and offsets are kept.")).toBeVisible();
    await page.getByRole("button", {name: "Save", exact: true}).click();
    await expect(page).toHaveURL(metricDetailUrl(createdMetricId));
    const current = {metricName: "net_revenue", alias: "current"};
    const prior = {metricName: "net_revenue", alias: "prior", offsetWindow: "1 month", filter: {dimension: {semanticModelName: "orders", name: "region"}, operator: "eq", values: ["APAC"]}};
    expect(fixture.writes).toHaveLength(1);
    expect(fixture.writes[0].body.definition).toEqual({
      schemaVersion: 3, name: "revenue_comparison", label: "Revenue comparison", type: scenario.type,
      ...(scenario.type === "ratio" ? {numerator: current, denominator: prior} : {expression: scenario.expression, inputMetrics: [current, prior]}),
    });
    const calculation = page.getByRole("region", { name: "How it's calculated", exact: true });
    await expect(calculation).toContainText("Offset: 1 month");
    await expect(calculation).toContainText("APAC");
    await expect(page.getByRole("main").getByRole("link", { name: "Explore", exact: true })).toHaveAttribute("href", `/workspaces/${workspaceId}/explore?metric=revenue_comparison`);
    expect(fixture.unexpected).toEqual([]);
  });
}

test("the old edit URL redirects into inline editing and Cancel keeps the saved definition", async ({page}) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/${netRevenueId}/edit`, [netRevenue]);
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/metrics/${netRevenueId}\\?edit=true$`));
  await expect(page.getByRole("heading", {name: "Net Revenue", level: 1})).toBeVisible();
  await page.getByRole("textbox", {name: /^Label/}).fill("Unsaved label");
  await page.getByRole("button", {name: "Cancel", exact: true}).click();
  await expect(page).toHaveURL(metricDetailUrl(netRevenueId));
  await expect(page.getByRole("heading", {name: "Net Revenue", level: 1})).toBeVisible();
  expect(fixture.writes).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

for (const response of ["http", "empty", "malformed"] as const) {
  test(`a ${response} Check failure stays in the saved metric panel and Retry recovers`, async ({ page, consoleErrors }) => {
    const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/${netRevenueId}`, [netRevenue]);
    await expect(page.getByRole("region", { name: "Status", exact: true }).getByText("Runs on Production Postgres", { exact: true })).toBeVisible();
    let requests = 0;
    if (response === "http") consoleErrors.allow({ status: 400, url: /\/semantic\/check$/, reason: "The Check service rejects the first request; the saved metric must remain visible." });
    await page.route("**/semantic/check", (route) => {
      requests += 1;
      if (requests > 1) return json(route, resolvesCheck);
      if (response === "http") return problem(route, 400, "INVALID_REQUEST", "Check unavailable in this fixture.", "Bad Request");
      if (response === "empty") return route.fulfill({ status: 204 });
      return json(route, []);
    });
    await page.reload();
    await expect(page.getByRole("heading", { name: "Net Revenue", exact: true })).toBeVisible();
    await expect(page.getByText("Revenue after refunds.", { exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "How it's calculated", exact: true })).toBeVisible();
    const status = page.getByRole("region", { name: "Status", exact: true });
    await expect(status).toContainText("Check unavailable");
    await expect(status.getByLabel("SQL preview")).toHaveCount(0);
    await status.getByRole("button", { name: "Retry", exact: true }).click();
    await expect(status.getByText("Runs on Production Postgres", { exact: true })).toBeVisible();
    expect(requests).toBe(2);
    await expect(status.getByLabel("SQL preview")).toBeHidden();
    await status.getByText("Show SQL", { exact: true }).click();
    await expect(status.getByLabel("SQL preview")).toBeVisible();
    await expect(page.getByText("You can still save with problems.")).toHaveCount(0);
    expect(fixture.writes).toEqual([]);
  });
}

test("the catalog clamps a stored aggregate formula with a full tooltip, reads its filters below, and keeps the detail tree", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/semantics`, [netRevenue]);
  await expect(page.getByRole("table", { name: "Metrics catalog" })).toBeVisible();
  const expression = "CASE WHEN order_status = 'completed' AND fulfillment_channel IN ('warehouse', 'store_pickup', 'partner_marketplace') AND refunded_at IS NULL THEN order_id END";
  const model = { ...ordersModel, definition: { ...ordersModel.definition, measures: [{ name: "revenue", label: "Completed orders", aggregation: "count_distinct" as const, expression }] } };
  await page.route("**/semantic/semantic-models**", (route) => json(route, new URL(route.request().url()).pathname.endsWith(ordersModelId) ? model : [model]));
  await page.reload();
  const row = page.getByRole("table", { name: "Metrics catalog" }).getByRole("row").filter({ has: page.getByRole("link", { name: "Net Revenue (net_revenue)", exact: true }) });
  const formula = `COUNT(DISTINCT ${expression})`;
  const code = row.getByRole("cell").nth(1).locator("code");
  await expect(code).toHaveText(formula);
  await expect(code).toHaveAttribute("title", formula);
  await expect(row.getByRole("cell").nth(1).locator("p")).toHaveText("Filter: orders.region is EMEA · Default grain: Month");
  await expect(row).not.toContainText("semanticModelName");
  const styles = await code.evaluate((element) => {
    const computed = getComputedStyle(element);
    return { fontFamily: computed.fontFamily, lineClamp: computed.webkitLineClamp, lineHeight: Number.parseFloat(computed.lineHeight), height: element.getBoundingClientRect().height, scrollHeight: element.scrollHeight };
  });
  expect(styles.fontFamily).toContain("Geist Mono");
  expect(styles.lineClamp).toBe("2");
  expect(styles.height).toBeLessThanOrEqual(styles.lineHeight * 2 + 1);
  expect(styles.scrollHeight).toBeGreaterThan(styles.height);
  await row.getByRole("link", { name: "Net Revenue (net_revenue)", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Net Revenue", exact: true })).toBeVisible();
  await expect(page.getByRole("main")).not.toContainText("Count distinct of");
  const detailDefinition = page.getByRole("region", { name: "How it's calculated", exact: true }).locator("dl");
  await expect(detailDefinition.locator("dd").nth(0)).toHaveText("Count distinct");
  await expect(detailDefinition.locator("pre")).toHaveText(expression);
  expect(fixture.unexpected).toEqual([]);
});

test("a saved model keeps its singular definition counts visible through Check failure and Retry", async ({ page, consoleErrors }) => {
  await openMetricRoute(page, `/workspaces/${workspaceId}/semantic-models/${ordersModelId}`, [netRevenue]);
  const model = { ...ordersModel, definition: { ...ordersModel.definition, measures: ordersModel.definition.measures.slice(0, 1) } };
  await page.route("**/semantic/semantic-models**", (route) => json(route, new URL(route.request().url()).pathname.endsWith(ordersModelId) ? model : [model]));
  let requests = 0;
  consoleErrors.allow({ status: 400, url: /\/semantic\/check$/, reason: "The first model Check is unavailable; the model definition remains readable." });
  await page.route("**/semantic/check", (route) => {
    requests += 1;
    return requests === 1 ? problem(route, 400, "INVALID_REQUEST", "Check unavailable in this fixture.", "Bad Request") : json(route, { partitionFilters: [], ok: true, runsOn: [connectorId], problems: [], preview: null });
  });
  await page.reload();
  await expect(page.getByRole("region", { name: "How it's calculated", exact: true })).toHaveCount(0);
  await expect(page.getByRole("table", { name: "Model columns" }).getByRole("rowheader", { name: "Aggregates 1" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Check definition" })).toHaveCount(0);
  const status = page.getByRole("region", { name: "Status", exact: true });
  await expect(status).toContainText("Check unavailable");
  await expect(page.getByRole("heading", { name: "Orders", exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Model fields", exact: true }).getByRole("table")).toBeVisible();
  await status.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(status.getByText("Runs on Production Postgres", { exact: true })).toBeVisible();
  expect(requests).toBe(2);
});

test("time Slice by has one labelled chip and opens Explore at the model grain", async ({ page }) => {
  const fixture = await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/${netRevenueId}`, [netRevenue]);
  const model = { ...ordersModel, definition: { ...ordersModel.definition, dimensions: [{ isPartition: false, name: "ordered_at", label: "Order date", type: "time" as const, expression: "ordered_at", granularity: "day" as const }] } };
  await page.route("**/semantic/semantic-models**", (route) => json(route, new URL(route.request().url()).pathname.endsWith(ordersModelId) ? model : [model]));
  await page.route("**/semantic/query/group-bys", (route) => json(route, { groupBys: ["DAY", "WEEK", "MONTH", "QUARTER", "YEAR"].map((granularity) => ({ kind: "DIMENSION", semanticModelName: "orders", name: "ordered_at", granularity })), excludedSemanticModels: [] }));
  await page.reload();
  const slice = page.getByRole("region", { name: "Slice by", exact: true });
  await expect(slice.getByRole("link", { name: "Explore by Order date", exact: true })).toHaveCount(1);
  await expect(slice.getByRole("link")).toHaveCount(1);
  await expect(slice.getByRole("link")).toHaveAttribute("href", /dimension=ordered_at&granularity=DAY$/);
  expect(fixture.unexpected).toEqual([]);
});

test("the calculation rail groups a ratio's parallel metric inputs in one stage", async ({ page }) => {
  const orderCount = { ...netRevenue, metricId: createdMetricId, definition: { schemaVersion: 3 as const, name: "order_count", label: "Completed orders", type: "simple" as const, semanticModelName: "orders", measure: "order_count" } };
  const ratioId = "019e8000-0000-7000-8000-000000000f83";
  const ratio = { ...netRevenue, metricId: ratioId, definition: { schemaVersion: 3 as const, name: "gross_aov", label: "Gross AOV", type: "ratio" as const, numerator: { metricName: "net_revenue" }, denominator: { metricName: "order_count" } } };
  await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/${ratioId}`, [netRevenue, orderCount, ratio]);
  await page.route("**/v2/graphql", (route) => {
    const body = route.request().postDataJSON() as { query: string };
    if (!body.query.includes("query LineageFocusCone")) return route.fallback();
    const entry = (id: string, label: string) => ({ resource: { kind: "METRIC", id }, label, address: label, description: null, connector: null, missing: false });
    const focus = entry(ratioId, "Gross AOV"), revenue = entry(netRevenueId, "Net Revenue"), count = entry(createdMetricId, "Completed orders");
    return json(route, { data: { lineageFocusCone: { workspaceId, focus, ancestors: [revenue, count], descendants: [], siblings: [], missingDefinitions: [], edges: [{ parent: revenue.resource, dependent: focus.resource }, { parent: count.resource, dependent: focus.resource }], siblingEdges: [], entityPeers: [], dashboardUsages: [], detail: { relation: null, semanticModel: null, metric: { type: "RATIO", inputs: [], timeGranularity: null } } } } });
  });
  await page.reload();
  const stage = page.getByTestId("calculation-path-stage").filter({ has: page.getByRole("link", { name: "Completed orders", exact: true }) });
  await expect(stage).toHaveCount(1);
  await expect(stage).toHaveAttribute("data-depth", "1");
  await expect(stage).toContainText("Metrics");
  await expect(stage.getByRole("link", { name: "Net Revenue", exact: true })).toBeVisible();
  await expect(stage.getByRole("link", { name: "Completed orders", exact: true })).toBeVisible();
  const path = page.getByRole("region", { name: "Calculation path", exact: true });
  await expect(path).toContainText("This metric");
  await expect(path.getByRole("link", { name: "Gross AOV", exact: true })).toHaveCount(0);
  const definition = page.getByRole("region", { name: "How it's calculated", exact: true });
  const numerator = definition.getByRole("heading", { name: "Net Revenue", exact: true });
  const role = definition.getByText("Numerator", { exact: true });
  expect((await role.boundingBox())!.y).toBeLessThan((await numerator.boundingBox())!.y);
  const inputLink = numerator.getByRole("link");
  expect(await inputLink.evaluate((element) => getComputedStyle(element).textDecorationLine)).toBe("underline");
  expect(await definition.locator("li").first().evaluate((element) => ({ left: getComputedStyle(element).borderLeftWidth, right: getComputedStyle(element).borderRightWidth, top: getComputedStyle(element).borderTopWidth }))).toEqual({ left: "0px", right: "0px", top: "0px" });
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  for (const label of ["Numerator", "Denominator"]) {
    const input = page.getByLabel(label, { exact: true });
    await expect(input.locator('option[value="gross_aov"]')).toHaveCount(0);
    await expect(input.locator('option[value="net_revenue"]')).toHaveCount(1);
  }
});


test("Default grain offers Day and coarser grains until a finer model time axis is selected", async ({ page }) => {
  const savedFine = { ...netRevenue, definition: { ...netRevenue.definition, timeGranularity: "hour" as const } };
  await openMetricRoute(page, `/workspaces/${workspaceId}/metrics/new`, [savedFine]);
  const model = { ...ordersModel, semanticModelId: "019e8000-0000-7000-8000-000000000e82", definition: { ...ordersModel.definition, name: "orders_hourly", label: "Hourly orders", aggregationTimeDimension: "ordered_at", dimensions: [{ isPartition: false,name: "ordered_at", label: "Order time", type: "time" as const, expression: "ordered_at", granularity: "hour" as const}] } };
  await page.route("**/semantic/semantic-models", (route) => json(route, [ordersModel, model]));
  await page.reload();
  await expect(page.getByText("The aggregate is saved on the model before this metric.", {exact: true})).toHaveCount(0);
  const countedBy = page.getByRole("combobox", {name: "Default grain", exact: true});
  await expect(countedBy.getByRole("option", {name: "Day", exact: true})).toHaveCount(1);
  await expect(countedBy.getByRole("option", {name: "Hour", exact: true})).toHaveCount(0);
  const modelSelect = page.getByRole("combobox", {name: "Model", exact: true});
  await modelSelect.selectOption("orders_hourly");
  await expect(countedBy.getByRole("option", {name: "Hour", exact: true})).toHaveCount(1);
  await expect(countedBy.getByRole("option", {name: "Minute", exact: true})).toHaveCount(0);
  await countedBy.selectOption("hour");
  await modelSelect.selectOption("orders");
  await expect(countedBy).toHaveValue("");
  await expect(countedBy.getByRole("option", {name: /^Hour/})).toHaveCount(0);
  await modelSelect.selectOption("orders_hourly");
  await countedBy.selectOption("hour");
  await page.getByRole("radio", {name: /^Divide one metric by another/}).click();
  await expect(countedBy).toHaveValue("");
  await expect(countedBy.getByRole("option", {name: /^Hour/})).toHaveCount(0);
  await page.goto(`/workspaces/${workspaceId}/metrics/${netRevenueId}?edit=true`);
  await expect(countedBy).toHaveValue("hour");
  await expect(countedBy.getByRole("option", {name: "Hour (saved)", exact: true})).toHaveCount(1);
});
