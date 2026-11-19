import { mockSemanticLineage } from "./support/semantic-lineage";
import type { Page } from "@playwright/test";
import type { components } from "../../src/api/control/generated";
import { controlUuidV7 } from "../../src/api/control/constraints";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { json, mockSignedInWorkspace, problem } from "./support/session";
import { evidencePath } from "./evidence";
import { expect, test } from "./support/test";

// Contract pattern for the Idempotency-Key header on semantic writes.
const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{1,200}$/;

type SemanticModelResponse = components["schemas"]["SemanticModelResponse"];
type SemanticModelWriteRequest = components["schemas"]["SemanticModelWriteRequest"];
type MetricResponse = components["schemas"]["MetricResponse"];
type MetricWriteRequest = components["schemas"]["MetricWriteRequest"];

const workspaceId = "019e8000-0000-7000-8000-000000000a91";
const connectorId = "019e8000-0000-7000-8000-000000000c91";
const replicaConnectorId = "019e8000-0000-7000-8000-000000000c92";
const createdMetricId = "019e8000-0000-7000-8000-000000000f91";
const createdModelId = "019e8000-0000-7000-8000-000000000e91";
const catalogPath = `/v2/workspaces/${workspaceId}/catalog/connectors/${connectorId}`;
const semanticPath = `/v2/workspaces/${workspaceId}/semantic`;
const detailUrl = new RegExp(`/workspaces/${workspaceId}/semantic-models/${createdModelId}(?:\\?edit=false)?$`);

const connectors = {
  connectors: [{
    id: connectorId,
    workspaceId,
    name: "Warehouse",
    type: "POSTGRESQL",
    properties: { host: "warehouse.internal", port: 5432, database: "analytics" },
    createdAtEpochMs: 1,
    updatedAtEpochMs: 1,
  }],
} satisfies components["schemas"]["ConnectorListResponse"];

const ordersDefinition = {
  schemaVersion: 3,
  name: "orders",
  label: "Orders",
  sourceRelations: [{ connectorId: controlUuidV7(connectorId), schema: "analytics", relation: "orders" }],
  aggregationTimeDimension: "ordered_at",
  entities: [{ name: "order_id", type: "primary", expression: "order_id" }],
  dimensions: [{ isPartition: false, name: "ordered_at", type: "time", expression: "ordered_at", granularity: "day" }],
  measures: [{ name: "revenue", aggregation: "sum", expression: "amount", label: "Revenue" }],
} satisfies components["schemas"]["SemanticModelDocument"];

interface ModelWrite {
  method: string;
  path: string;
  idempotencyKey: string | undefined;
  body: SemanticModelWriteRequest;
}

// Control's Semantic Model, Catalog, and Definitions reads for one EDITOR. `failNextWrites` answers
// that many model writes with the declared 503 before the store accepts one.
async function openSemanticModelRoute(page: Page, target: string, initialModels: SemanticModelResponse[], failNextWrites = 0, loseNextCommittedResponse = false, availableConnectors: components["schemas"]["ConnectorListResponse"] = connectors) {
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Semantic authoring",
    organizationId: "organization-semantic-authoring",
    role: "EDITOR",
    user: { id: "semantic-editor", email: "semantic-editor@example.test", displayName: "Semantic editor" },
  });
  const models = [...initialModels];
  const writes: ModelWrite[] = [];
  const commits: ModelWrite[] = [];
  const receipts = new Map<string, { body: SemanticModelWriteRequest; response: components["schemas"]["SemanticModelWriteResponse"] }>();
  const metricWrites: Array<{ body: MetricWriteRequest; idempotencyKey: string | undefined }> = [];
  const metrics: MetricResponse[] = initialModels.flatMap((model) => model.definition.measures.map((measure) => ({ sourcePartitionInfo: [],
    metricId: createdMetricId, workspaceId,
    definition: { schemaVersion: 3, name: measure.name, ...(measure.label ? { label: measure.label } : {}), type: "simple", semanticModelName: model.definition.name, measure: measure.name },
    createdAt: model.createdAt, updatedAt: model.updatedAt,
  })));
  const unexpected: string[] = [];
  const metricReads: string[][] = [];
  let remainingFailures = failNextWrites;
  let responseLossPending = loseNextCommittedResponse;
  await mockSemanticLineage(page);
  await page.route("**/v2/workspaces/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    if (method === "GET" && path === `/v2/workspaces/${workspaceId}/connectors`) return json(route, availableConnectors);
    if (method === "GET" && path === `/v2/workspaces/${workspaceId}/catalog/table-search`) {
      return json(route, {
        tables: [{
          connectorId: controlUuidV7(connectorId),
          connectorName: "Warehouse",
          connectorType: "POSTGRESQL",
          catalogName: null,
          schemaName: "analytics",
          tableName: "orders",
          relationKind: "TABLE",
          matchedColumns: [],
        }],
      } satisfies components["schemas"]["CatalogTableSearchResult"]);
    }
    if (method === "GET" && (path === `${catalogPath}/relations/orders` || path === `/v2/workspaces/${workspaceId}/catalog/connectors/${replicaConnectorId}/relations/orders_copy`) && url.searchParams.get("schema") === "analytics") {
      return json(route, { requiresPartitionFilter: null,
        name: path.endsWith("orders_copy") ? "orders_copy" : "orders",
        schemaName: "analytics",
        kind: "TABLE",
        columns: ["order_id", "ordered_at", "amount"].map((name, index) => ({ name, nativeType: ["bigint", "timestamp", "numeric"][index], nullable: false, ordinalPosition: index + 1 })),
        primaryKey: { columns: ["order_id"] },
        clusteringKeys: [],
        foreignKeys: [],
        partitionKeys: [],
        lineageResourceRef: { id: "019e8000-0000-7000-8000-000000000b91", kind: "catalog_table" },
      } satisfies components["schemas"]["CatalogRelation"]);
    }
    if (method === "GET" && path === `${semanticPath}/semantic-models`) return json(route, models);
    if (method === "GET" && path === `${semanticPath}/metrics`) {
      metricReads.push(metrics.map((metric) => metric.definition.name));
      return json(route, metrics);
    }
    if (method === "POST" && path === `${semanticPath}/query/group-bys`) return json(route, { groupBys: [], excludedSemanticModels: [] });
    const metricMatch = /^\/v2\/workspaces\/[^/]+\/semantic\/metrics\/([^/]+)$/.exec(path);
    if (method === "GET" && metricMatch) {
      const metric = metrics.find((candidate) => candidate.metricId === metricMatch[1]);
      return metric ? json(route, metric) : problem(route, 404, "NOT_FOUND", "Metric was not found.", "Not Found");
    }
    if (method === "POST" && path === `${semanticPath}/metrics`) {
      const body = request.postDataJSON() as MetricWriteRequest;
      metricWrites.push({ body, idempotencyKey: request.headers()["idempotency-key"] });
      unexpected.push(`${method} ${path}`);
      return problem(route, 400, "INVALID_REQUEST", "Use the atomic model write for aggregate creation.", "Bad Request");
    }
    if (method === "GET" && path === `${semanticPath}/time-spines`) return json(route, []);
    if (method === "POST" && path === `${semanticPath}/check`) return json(route, { partitionFilters: [], ok: true, runsOn: [controlUuidV7(connectorId)], preview: null, problems: [] });
    const modelMatch = /^\/v2\/workspaces\/[^/]+\/semantic\/semantic-models(?:\/([^/]+))?$/.exec(path);
    if (modelMatch && method === "GET" && modelMatch[1]) {
      const model = models.find((candidate) => candidate.semanticModelId === modelMatch[1]);
      return model ? json(route, model) : problem(route, 404, "NOT_FOUND", "Semantic model was not found.", "Not Found");
    }
    if (modelMatch && (method === "POST" || method === "PUT")) {
      const write = { method, path, idempotencyKey: request.headers()["idempotency-key"], body: request.postDataJSON() as SemanticModelWriteRequest };
      writes.push(write);
      const receipt = receipts.get(write.idempotencyKey ?? "");
      if (receipt) {
        if (JSON.stringify(receipt.body) !== JSON.stringify(write.body)) return problem(route, 409, "SEMANTIC_NAME_CONFLICT", "The write key belongs to a different request.", "Conflict");
        return json(route, receipt.response, method === "POST" ? 201 : 200);
      }
      if (remainingFailures > 0) {
        remainingFailures -= 1;
        return problem(route, 503, "SERVICE_UNAVAILABLE", "Semantic definitions are temporarily unavailable.", "Service Unavailable");
      }
      const semanticModelId = modelMatch[1] ?? createdModelId;
      const current = models.find((candidate) => candidate.semanticModelId === semanticModelId);
      const saved = {
        sourcePartitionInfo: [],
        semanticModelId,
        workspaceId,
        definition: write.body.definition,
        createdAt: current?.createdAt ?? "2026-09-30T00:00:00Z",
        updatedAt: "2026-09-30T00:00:00Z",
      } satisfies SemanticModelResponse;
      const aggregateMetrics = write.body.aggregateMetrics.map(({ measure, ...metric }, index) => ({ sourcePartitionInfo: [],
        metricId: `019e8000-0000-7000-8000-${(0xf91 + metrics.length + index).toString(16).padStart(12, "0")}`, workspaceId,
        definition: { schemaVersion: 3, ...metric, type: "simple", semanticModelName: saved.definition.name, measure },
        createdAt: saved.updatedAt, updatedAt: saved.updatedAt,
      } satisfies MetricResponse));
      models.splice(0, models.length, ...models.filter((candidate) => candidate.semanticModelId !== semanticModelId), saved);
      metrics.push(...aggregateMetrics);
      const response = { model: saved, aggregateMetrics } satisfies components["schemas"]["SemanticModelWriteResponse"];
      commits.push(write);
      receipts.set(write.idempotencyKey ?? "", { body: write.body, response });
      if (responseLossPending) {
        responseLossPending = false;
        return problem(route, 503, "SERVICE_UNAVAILABLE", "Semantic definitions are temporarily unavailable.", "Service Unavailable");
      }
      return json(route, response, method === "POST" ? 201 : 200);
    }
    unexpected.push(`${method} ${path}`);
    return problem(route, 404, "NOT_FOUND", `No fixture for ${path}.`, "Not Found");
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, target);
  return { writes, commits, metricWrites, models, metrics, metricReads, unexpected };
}

async function setFieldLabel(page: Page, field: number, label: string) {
  await expect(page.getByRole("textbox", { name: `Field ${field} label`, exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: `Field ${field} options` }).click();
  await page.getByRole("menuitem", { name: /^Name(?:,| and)/ }).click();
  const details = page.getByRole("dialog");
  await details.getByRole("textbox", { name: "Label (optional)", exact: true }).fill(label);
  await details.getByRole("button", { name: "Done" }).click();
}

async function setRevenueName(page: Page) {
  await page.getByRole("combobox", { name: "Field 3 use as" }).selectOption("aggregate:sum");
  await page.getByRole("textbox", { name: "Field 3 name", exact: true }).fill("revenue");
  await page.getByRole("button", { name: "Field 3 options" }).click();
  await page.getByRole("menuitem", { name: "Name, description, and options" }).click();
  const details = page.getByRole("dialog");
  await expect(details.getByRole("textbox", { name: "Name", exact: true })).toHaveValue("revenue");
  await details.getByRole("textbox", { name: "Label (optional)", exact: true }).fill("Revenue");
  await expect(details.getByRole("combobox", { name: "Aggregation", exact: true })).toHaveValue("sum");
  await expect(details.getByRole("combobox", { name: "Aggregation", exact: true }).getByRole("option", { name: "Median", exact: true })).toHaveCount(1);
  await expect(details.getByRole("combobox", { name: "Aggregation", exact: true }).getByRole("option", { name: "Sum of true values", exact: true })).toHaveCount(1);
  await details.getByRole("button", { name: "Done" }).click();
}

test("Models offers Describe a table and catalog navigation omits default search state", async ({ page }) => {
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantics?view=models`, []);
  await page.getByRole("button", { name: "Describe a table", exact: true }).click();
  await page.getByRole("combobox", { name: "Find a table", exact: true }).fill("orders");
  await page.getByRole("option").filter({ hasText: "orders" }).click();
  await expect(page.getByRole("heading", { name: "New model", exact: true })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Field 1 use as", exact: true })).toHaveValue("unused");
  await expect(page.getByRole("table").locator("tbody tr").first().locator("td").first()).toHaveText("order_id");
  expect(new URL(page.url()).searchParams.get("table")).toBe("orders");
  await page.getByRole("main").getByRole("link", { name: "Semantics", exact: true }).click();
  await page.getByRole("tab", { name: "Metrics", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/semantics$`));
  const search = page.getByRole("searchbox", { name: "Search metrics" });
  await search.fill("revenue");
  await expect(page).toHaveURL(/\?q=revenue$/);
  await search.fill("");
  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/semantics$`));
  expect(fixture.unexpected).toEqual([]);
});

test("multiple sources stay in a disclosure and explain one-connector execution without changing saved data", async ({ page }) => {
  const replicaSource = { connectorId: controlUuidV7(replicaConnectorId), catalog: "replica", schema: "analytics", relation: "orders_copy" };
  const existing = { sourcePartitionInfo: [], semanticModelId: createdModelId, workspaceId, definition: { ...ordersDefinition, sourceRelations: [...ordersDefinition.sourceRelations, replicaSource] }, createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-09-01T00:00:00Z" } satisfies SemanticModelResponse;
  const availableConnectors = { connectors: [...connectors.connectors, { ...connectors.connectors[0], id: replicaConnectorId, name: "Analytics replica" }] } satisfies components["schemas"]["ConnectorListResponse"];
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/${createdModelId}`, [existing], 0, false, availableConnectors);
  await page.route("**/v2/graphql", async (route) => {
    const body = route.request().postDataJSON() as { query: string };
    if (!body.query.includes("query LineageFocusCone")) return route.fallback();
    const focus = { resource: { kind: "SEMANTIC_MODEL", id: createdModelId }, label: "Orders", address: "orders", description: null, connector: null, missing: false };
    const ancestors = availableConnectors.connectors.map((connector, index) => ({ resource: { kind: "RELATION", id: `019e8000-0000-7000-8000-000000000b9${index + 1}` }, label: index === 0 ? "orders" : "orders_copy", address: index === 0 ? "analytics.orders" : "replica.analytics.orders_copy", description: null, connector: { id: connector.id, displayName: connector.name, type: connector.type }, missing: false }));
    return json(route, { data: { lineageFocusCone: { workspaceId, focus, ancestors, descendants: [], siblings: [], missingDefinitions: [], edges: ancestors.map((source) => ({ parent: source.resource, dependent: focus.resource })), siblingEdges: [], entityPeers: [], dashboardUsages: [], detail: { relation: null, semanticModel: { entities: [], dimensions: [], measures: [] }, metric: null } } } });
  });
  await page.route(`**${semanticPath}/check`, (route) => json(route, { partitionFilters: [], ok: false, runsOn: [], preview: null, problems: [
    { code: "SOURCE_RELATION_NOT_FOUND", field: null, connectorId, definition: null, message: "Source table is missing." },
    { code: "SOURCE_RELATION_NOT_FOUND", field: null, connectorId: replicaConnectorId, definition: null, message: "Source table is missing." },
  ] }));
  await page.reload();
  const header = page.locator("header").filter({ has: page.getByRole("heading", { name: "Orders", level: 1 }) });
  const sourceFact = header.getByRole("definition").first();
  const disclosure = sourceFact.getByRole("button", { name: "Also on 1 more connector", exact: true });
  await expect(sourceFact.getByRole("link")).toHaveText(["Analytics replica · replica.analytics.orders_copy"]);
  await expect(disclosure).toHaveAttribute("aria-expanded", "false");
  await disclosure.click();
  await expect(disclosure).toHaveAttribute("aria-expanded", "true");
  await expect(sourceFact.getByRole("link")).toHaveText(["Analytics replica · replica.analytics.orders_copy", "Warehouse · analytics.orders"]);
  await expect(sourceFact.getByRole("link").first()).toHaveAttribute("href", new RegExp(`connector=${replicaConnectorId}.*table=orders_copy`));
  await expect(page.getByTestId("calculation-path-stage")).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Used in", exact: true }).getByRole("link", { name: "Open map" })).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/semantic-model-multiple-sources-read.png"), fullPage: false });
  await disclosure.click();
  await expect(disclosure).toHaveAttribute("aria-expanded", "false");
  await expect(sourceFact.getByRole("link")).toHaveCount(1);
  const status = page.getByLabel("Definition status", { exact: true });
  await expect(status.getByRole("listitem")).toHaveText(["Warehouse: Source table is missing.", "Analytics replica: Source table is missing."]);
  await header.getByRole("link", { name: "Edit", exact: true }).click();
  const explanation = "Same table through another connection, such as a separate compute warehouse or a migrated copy. Each query runs on one connector.";
  const warning = "Numbers match only when these tables hold the same data.";
  await expect(page.getByText(explanation, { exact: true })).toBeVisible();
  await expect(page.getByText(warning, { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Check", exact: true }).getByRole("listitem")).toHaveText(["Warehouse: Source table is missing.", "Analytics replica: Source table is missing."]);
  await expect(page.getByRole("button", { name: "Change source table 2", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Show columns", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Field 3 expression", exact: true })).toHaveValue("amount");
  await page.screenshot({ path: evidencePath("../screenshots/semantic-model-multiple-sources-edit.png"), fullPage: false });
  await page.getByRole("button", { name: "Remove source table 2", exact: true }).click();
  await expect(page.getByText(warning, { exact: true })).toHaveCount(0);
  await expect(page.getByText(explanation, { exact: true })).toBeVisible();
  const addSource = page.getByRole("button", { name: "Use another connector", exact: true });
  await expect(addSource).toHaveClass(/border-transparent bg-transparent/);
  await addSource.click();
  await expect(page.getByRole("combobox", { name: "Find a table", exact: true })).toBeVisible();
  await page.getByRole("combobox", { name: "Find a table", exact: true }).press("Escape");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(header.getByRole("button", { name: "Also on 1 more connector", exact: true })).toBeVisible();
  expect(fixture.writes).toEqual([]);
  expect(fixture.models[0].definition.sourceRelations).toEqual(existing.definition.sourceRelations);
  expect(fixture.unexpected).toEqual([]);
});

test("creates a Model and its Aggregate metric, reopens the same Model page, and saves inline", async ({ page }) => {
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/new`, []);
  await expect(page.getByRole("heading", { name: "New model", exact: true })).toBeVisible();
  await page.getByRole("textbox", { name: /^Name/ }).fill("orders");
  await page.getByRole("textbox", { name: /^Label/ }).fill("Orders");
  await page.getByRole("button", { name: "Choose source table" }).click();
  await page.getByRole("combobox", { name: "Find a table" }).fill("orders");
  await page.getByRole("option", { name: /orders/ }).click();
  await expect(page.getByRole("combobox", { name: "Field 1 use as" })).toHaveValue("unused");
  await expect(page.getByRole("combobox", { name: "Field 2 use as" })).toHaveValue("unused");
  await expect(page.getByRole("combobox", { name: "Field 3 use as" })).toHaveValue("unused");
  await page.getByRole("button", { name: "Apply suggestions" }).click();
  await expect(page.getByRole("combobox", { name: "Field 1 use as" })).toHaveValue("primary");
  await expect(page.getByRole("combobox", { name: "Field 2 use as" })).toHaveValue("time");
  await expect(page.getByRole("combobox", { name: "Field 3 use as" })).toHaveValue("aggregate:sum");
  await page.getByRole("radio", { name: "Use ordered_at as Metric time", exact: true }).check();
  await setRevenueName(page);
  await expect(page.getByRole("button", { name: "Preview", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Save", exact: true }).click();

  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/metrics/${createdMetricId}(?:\\?edit=false)?$`));
  await expect(page.getByRole("heading", { name: "Revenue", level: 1 })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Preview", exact: true })).toHaveCount(0);
  await expect(page.getByRole("main").getByRole("link", { name: "Explore", exact: true })).toHaveAttribute("href", `/workspaces/${workspaceId}/explore?metric=revenue`);
  expect(fixture.writes).toEqual([{
    method: "POST", path: `${semanticPath}/semantic-models`, idempotencyKey: expect.any(String),
    body: { definition: ordersDefinition, aggregateMetrics: [{ measure: "revenue", name: "revenue", label: "Revenue" }] } satisfies SemanticModelWriteRequest,
  }]);
  expect(fixture.metricWrites).toEqual([]);

  await page.goto(`/workspaces/${workspaceId}/semantic-models/${createdModelId}`);
  await expect(page).toHaveURL(detailUrl);
  const header = page.locator("header").filter({ has: page.getByRole("heading", { name: "Orders", level: 1 }) });
  await expect(header.getByRole("term")).toHaveText(["Source table", "Row key", "Metric time", "Created", "Updated"]);
  await expect(header.getByRole("definition").nth(0)).toHaveText("Warehouse · analytics.orders");
  await expect(header.getByRole("definition").nth(1)).toHaveText("order_id");
  await expect(header.getByRole("definition").nth(2)).toHaveText("ordered_at");
  for (const section of ["Model fields", "Used in", "Status"]) await expect(page.getByRole("heading", { name: section, exact: true })).toBeVisible();
  for (const removed of ["How it's calculated", "Slice by", "Calculation path"]) await expect(page.getByRole("heading", { name: removed, exact: true })).toHaveCount(0);
  await expect(page.getByRole("table", { name: "Model columns" }).getByRole("rowheader")).toHaveText(["Keys 1", "Time 1", "Aggregates 1"]);
  await expect(page.getByRole("link", { name: "Revenue", exact: true })).toHaveAttribute("href", `/workspaces/${workspaceId}/metrics/${createdMetricId}`);

  await header.getByRole("link", { name: "Edit", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/semantic-models/${createdModelId}\\?edit=true$`));
  await expect(page.getByRole("heading", { name: "Orders", level: 1 })).toBeVisible();
  await expect(page.getByRole("textbox", { name: /^Name/ })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Affects if saved", exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Field 3 expression" })).toHaveValue("amount");
  await page.getByRole("textbox", { name: /^Label/ }).fill("Customer orders");
  await page.getByRole("textbox", { name: /^Description/ }).fill("One row per placed order.");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(detailUrl);
  await expect(page.getByRole("heading", { name: "Customer orders", level: 1 })).toBeVisible();
  expect(fixture.writes).toHaveLength(2);
  expect(fixture.writes[1]).toMatchObject({ method: "PUT", path: `${semanticPath}/semantic-models/${createdModelId}` });
  expect(fixture.writes[1].idempotencyKey).toMatch(IDEMPOTENCY_KEY);
  expect(fixture.writes[1].body).toEqual({ definition: { ...ordersDefinition, label: "Customer orders", description: "One row per placed order." }, aggregateMetrics: [] } satisfies SemanticModelWriteRequest);
  expect(fixture.metricWrites).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test("Catalog entry shows recommendations, multiple uses, and calculated fields in the column table", async ({ page }) => {
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/new?connector=${connectorId}&schema=analytics&table=orders`, []);
  await expect(page.getByRole("heading", { name: "New model", exact: true })).toBeVisible();
  await expect(page.getByRole("list", { name: "Source tables" })).toContainText("Warehouse");
  await expect(page.getByRole("list", { name: "Source tables" })).toContainText("analytics.orders");
  await expect(page.getByRole("textbox", { name: /^Label/ })).toHaveValue("Orders");
  await expect(page.getByRole("textbox", { name: /^Name/ })).toHaveValue("orders");
  await expect(page.getByRole("button", { name: "Dismiss suggestions" })).toBeVisible();
  await page.getByRole("button", { name: "Dismiss suggestions" }).click();
  await expect(page.getByRole("button", { name: "Apply suggestions" })).toHaveCount(0);
  await expect(page.getByRole("combobox", { name: "Field 1 use as" })).toHaveValue("unused");
  await page.getByRole("combobox", { name: "Field 1 use as" }).selectOption("primary");
  await page.getByRole("button", { name: "Field 1 options" }).click();
  await page.getByRole("menuitem", { name: "Add another use", exact: true }).click();
  await page.getByRole("combobox", { name: "Field 2 use as" }).selectOption("aggregate:count");
  await expect(page.getByRole("textbox", { name: "Field 1 expression" })).toHaveValue("order_id");
  await expect(page.getByRole("textbox", { name: "Field 2 expression" })).toHaveValue("order_id");
  await page.getByRole("combobox", { name: "Field 3 use as" }).selectOption("time");
  await page.getByRole("radio", { name: "Use ordered_at as Metric time", exact: true }).check();
  await page.getByRole("button", { name: "Add calculated field", exact: true }).click();
  await setFieldLabel(page, 5, "Net revenue");
  await page.getByRole("combobox", { name: "Field 5 use as" }).selectOption("aggregate:sum");
  await page.getByRole("textbox", { name: "Field 5 expression" }).fill("amount - refunded_amount");
  await expect(page.getByRole("heading", { name: "Columns · 4 of 5 in use" })).toBeVisible();
  const check = page.getByRole("region", { name: "Check" });
  await expect(check.getByRole("status")).toHaveText("");
  await expect(check).toContainText("Runs on Warehouse");
  await expect(check).not.toContainText("You can still save");
  await page.screenshot({ path: evidencePath("../screenshots/semantic-model-editor-fields.png"), fullPage: false });
  expect(fixture.writes).toEqual([]);
  expect(fixture.metricWrites).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test("the In use view shows applied suggestions, and Metric time can be cleared or flagged as soon as it breaks", async ({ page }) => {
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/new?connector=${connectorId}&schema=analytics&table=orders`, []);
  const columns = page.getByRole("group", { name: "Show columns" });
  await columns.getByRole("button", { name: /^In use/ }).click();
  await expect(page.getByText("No columns are in use yet.")).toBeVisible();
  await page.getByRole("button", { name: "Apply suggestions" }).click();
  await expect(page.getByRole("combobox", { name: "Field 1 use as" })).toHaveValue("primary");
  await expect(page.getByRole("combobox", { name: "Field 3 use as" })).toHaveValue("aggregate:sum");
  await expect(columns.getByRole("button", { name: /^In use/ })).toHaveAttribute("aria-pressed", "true");
  await columns.getByRole("button", { name: /^All/ }).click();

  const metricTime = page.getByRole("radio", { name: "Use ordered_at as Metric time", exact: true });
  await metricTime.check();
  await page.getByRole("button", { name: "Clear Metric time", exact: true }).click();
  await expect(metricTime).not.toBeChecked();
  await metricTime.check();
  await page.getByRole("combobox", { name: "Field 2 use as" }).selectOption("categorical");
  await expect(page.getByRole("alert").filter({ hasText: "Metric time: ordered_at is not a Time field." })).toBeVisible();
  await page.getByRole("alert").getByRole("button", { name: "Clear Metric time" }).click();
  await expect(page.getByText("is not a Time field.")).toHaveCount(0);
  expect(fixture.writes).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test("redirects the old edit URL and retries a rejected save with the same key", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 503, url: /\/semantic-models\/[^/]+$/, reason: "Control answers the first Model save with 503." });
  const existing = { sourcePartitionInfo: [], semanticModelId: createdModelId, workspaceId, definition: ordersDefinition, createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-09-01T00:00:00Z" } satisfies SemanticModelResponse;
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/${createdModelId}/edit`, [existing], 1);
  await expect(page).toHaveURL(new RegExp(`/semantic-models/${createdModelId}\\?edit=true$`));
  await expect(page.getByRole("heading", { name: "Orders", level: 1 })).toBeVisible();
  await page.getByRole("textbox", { name: /^Label/ }).fill("Net orders");
  await page.getByRole("textbox", { name: "Field 3 expression" }).fill("amount - refunded_amount");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveText("Semantic definitions are temporarily unavailable.");
  await expect(page.getByRole("textbox", { name: /^Label/ })).toHaveValue("Net orders");
  await expect(page.getByRole("textbox", { name: "Field 3 expression" })).toHaveValue("amount - refunded_amount");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(detailUrl);
  await expect(page.getByRole("heading", { name: "Net orders", level: 1 })).toBeVisible();
  expect(fixture.writes).toHaveLength(2);
  expect(fixture.writes[0].idempotencyKey).toMatch(IDEMPOTENCY_KEY);
  expect(fixture.writes[1].idempotencyKey).toBe(fixture.writes[0].idempotencyKey);
  expect(fixture.writes[1].body).toEqual(fixture.writes[0].body);
  expect(fixture.writes[1].body.definition).toMatchObject({ label: "Net orders", measures: [{ name: "revenue", aggregation: "sum", expression: "amount - refunded_amount", label: "Revenue" }] });
  expect(fixture.metricWrites).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test("a Time field's time kind round-trips, Check warns when it's undeclared, and Go to field opens the control", async ({ page }) => {
  const declared = { ...ordersDefinition, dimensions: [{ ...ordersDefinition.dimensions[0], timeKind: "instant" as const }] };
  const existing = { sourcePartitionInfo: [], semanticModelId: createdModelId, workspaceId, definition: declared, createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-09-01T00:00:00Z" } satisfies SemanticModelResponse;
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/${createdModelId}?edit=true`, [existing]);
  await expect(page.getByRole("heading", { name: "Orders", level: 1 })).toBeVisible();
  const timeKind = page.getByRole("combobox", { name: "Field 2 time kind", exact: true });
  await expect(timeKind).toHaveValue("instant");
  await expect(timeKind.getByRole("option")).toHaveText(["Auto (from warehouse type)", "Date", "Local time", "Instant"]);
  await expect(page.getByText("Instant converts to the Reporting time zone. Local time is shown as stored.", { exact: true })).toBeVisible();

  await page.route(`**${semanticPath}/check`, (route) => json(route, { partitionFilters: [], ok: false, runsOn: [controlUuidV7(connectorId)], preview: null, problems: [
    { code: "TIME_KIND_UNDECLARED", field: "dimensions[0]", connectorId: controlUuidV7(connectorId), definition: null, message: "Declare timeKind for this time dimension." },
  ] }));
  await timeKind.selectOption("");
  const check = page.getByRole("region", { name: "Check" });
  await expect(check.getByText("Declare timeKind for this time dimension so times convert to the Reporting time zone correctly.", { exact: true })).toBeVisible();
  await expect(check.getByText("Warning:", { exact: true })).toBeAttached();
  await page.screenshot({ path: evidencePath("screenshots/semantic-model-time-kind-warning.png"), fullPage: true });
  await check.getByRole("button", { name: "Go to field", exact: true }).click();
  await expect(timeKind).toBeFocused();

  await timeKind.selectOption("local");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(detailUrl);
  expect(fixture.writes).toHaveLength(1);
  expect(fixture.writes[0].body.definition.dimensions).toEqual([{ ...ordersDefinition.dimensions[0], timeKind: "local" }]);
  await expect(page.getByRole("table", { name: "Model columns" })).toContainText("Local time");
  expect(fixture.unexpected).toEqual([]);
});

test("a rejected atomic save keeps the whole draft and repeats one model write with the same key", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 503, url: /\/semantic\/semantic-models$/, reason: "Control rejects the first atomic model and aggregate write before committing it." });
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/new?connector=${connectorId}&schema=analytics&table=orders`, [], 1);
  await expect(page.getByRole("button", { name: "Apply suggestions" })).toBeVisible();
  await page.getByRole("button", { name: "Apply suggestions" }).click();
  await page.getByRole("radio", { name: "Use ordered_at as Metric time", exact: true }).check();
  await setRevenueName(page);
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveText("Semantic definitions are temporarily unavailable.");
  await expect(page.getByRole("textbox", { name: "Field 3 name", exact: true })).toHaveValue("revenue");
  await expect(page.getByText("Model saved.", { exact: false })).toHaveCount(0);
  expect(fixture.models).toEqual([]);
  expect(fixture.metrics).toEqual([]);
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/metrics/${createdMetricId}(?:\\?edit=false)?$`));
  expect(fixture.writes).toHaveLength(2);
  expect(fixture.writes[0].idempotencyKey).toMatch(IDEMPOTENCY_KEY);
  expect(fixture.writes[1].idempotencyKey).toBe(fixture.writes[0].idempotencyKey);
  expect(fixture.writes[1].body).toEqual(fixture.writes[0].body);
  expect(fixture.models).toHaveLength(1);
  expect(fixture.metrics).toHaveLength(1);
  expect(fixture.metricWrites).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

for (const mode of ["create", "update"] as const) {
  test(`a ${mode} retry replays the complete atomic request after its committed response is lost`, async ({ page, consoleErrors }) => {
    consoleErrors.allow({ status: 503, url: /\/semantic\/semantic-models(?:\/[^/]+)?$/, reason: "The success response is unavailable after Control committed the model and aggregate metric." });
    const initialModel = { sourcePartitionInfo: [], semanticModelId: createdModelId, workspaceId, definition: ordersDefinition, createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z" };
    const fixture = await openSemanticModelRoute(page,
      mode === "create" ? `/workspaces/${workspaceId}/semantic-models/new?connector=${connectorId}&schema=analytics&table=orders` : `/workspaces/${workspaceId}/semantic-models/${createdModelId}?edit=true`,
      mode === "create" ? [] : [initialModel], 0, true);
    const originalMetric = fixture.metrics[0];
    if (mode === "create") {
      await expect(page.getByRole("button", { name: "Apply suggestions" })).toBeVisible();
      await page.getByRole("button", { name: "Apply suggestions" }).click();
      await page.getByRole("radio", { name: "Use ordered_at as Metric time", exact: true }).check();
      await setRevenueName(page);
    } else {
      await page.getByRole("button", { name: "Field 3 options" }).click();
      await page.getByRole("menuitem", { name: "Name, description, and options" }).click();
      await expect(page.getByRole("dialog").getByRole("textbox", { name: "Label (optional)", exact: true })).toHaveValue("Revenue");
      await page.getByRole("dialog").getByRole("button", { name: "Done" }).click();
      await page.getByRole("button", { name: "Add calculated field", exact: true }).click();
      await setFieldLabel(page, 4, "Order count");
      await page.getByRole("combobox", { name: "Field 4 use as" }).selectOption("aggregate:count");
      await page.getByRole("textbox", { name: "Field 4 expression", exact: true }).fill("order_id");
    }
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByRole("alert")).toHaveText("Semantic definitions are temporarily unavailable.");
    await expect(page.getByRole("button", { name: "Save", exact: true })).toBeEnabled();
    const metricName = mode === "create" ? "revenue" : "order_count";
    expect(fixture.metricReads.some((names) => names.includes(metricName))).toBe(true);
    const committedMetric = fixture.metrics.find((metric) => metric.definition.name === metricName)!;
    expect(fixture.commits).toHaveLength(1);
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/metrics/${committedMetric.metricId}(?:\\?edit=false)?$`));
    await expect(page.getByRole("heading", { name: mode === "create" ? "Revenue" : "Order count", level: 1 })).toBeVisible();
    expect(fixture.writes).toHaveLength(2);
    expect(fixture.writes[1].body).toEqual(fixture.writes[0].body);
    expect(fixture.writes[1].body.aggregateMetrics).toHaveLength(1);
    expect(fixture.writes[1].idempotencyKey).toBe(fixture.writes[0].idempotencyKey);
    expect(fixture.writes[1].idempotencyKey).toMatch(IDEMPOTENCY_KEY);
    expect(fixture.commits).toHaveLength(1);
    expect(fixture.models).toHaveLength(1);
    expect(fixture.metrics).toHaveLength(mode === "create" ? 1 : 2);
    expect(fixture.metrics.find((metric) => metric.definition.name === metricName)).toEqual(committedMetric);
    if (originalMetric) expect(fixture.metrics[0]).toEqual(originalMetric);
    expect(fixture.metricWrites).toEqual([]);
    expect(fixture.unexpected).toEqual([]);
  });
}

test("a second aggregate collision stays beside its field and saves neither definition until corrected", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 409, url: /\/semantic\/semantic-models$/, reason: "The second aggregate metric name collides with a concurrently saved definition." });
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/new?connector=${connectorId}&schema=analytics&table=orders`, []);
  let rejected = false;
  await page.route("**/semantic/semantic-models", async (route) => {
    if (route.request().method() !== "POST" || rejected) return route.fallback();
    rejected = true;
    const body = route.request().postDataJSON() as SemanticModelWriteRequest;
    expect(body.aggregateMetrics.map((metric) => metric.name)).toEqual(["revenue", "order_count"]);
    return route.fulfill({ status: 409, contentType: "application/problem+json", body: JSON.stringify({ type: "about:blank", title: "Conflict", status: 409, detail: "A metric with this name already exists.", code: "SEMANTIC_NAME_CONFLICT", instance: "/semantic-models", requestId: "atomic-test", traceId: "atomic-trace", retryable: false, violations: [{ field: "aggregateMetrics[1].name", code: "SEMANTIC_NAME_CONFLICT", message: "Choose another metric name." }] }) });
  });
  await expect(page.getByRole("button", { name: "Apply suggestions" })).toBeVisible();
  await page.getByRole("button", { name: "Apply suggestions" }).click();
  await page.getByRole("radio", { name: "Use ordered_at as Metric time", exact: true }).check();
  await setRevenueName(page);
  await page.getByRole("button", { name: "Add calculated field", exact: true }).click();
  await setFieldLabel(page, 4, "Order count");
  await page.getByRole("combobox", { name: "Field 4 use as" }).selectOption("aggregate:count");
  await page.getByRole("textbox", { name: "Field 4 expression", exact: true }).fill("order_id");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  const name = page.getByRole("textbox", { name: "Field 4 name", exact: true });
  await expect(name).toHaveAttribute("aria-invalid", "true");
  await expect(name.locator("..")).toContainText("Choose another metric name.");
  expect(fixture.models).toEqual([]);
  expect(fixture.metrics).toEqual([]);
  await name.fill("new_order_count");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Revenue", level: 1 })).toBeVisible();
  expect(fixture.writes).toHaveLength(1);
  expect(fixture.writes[0].body.aggregateMetrics.map((metric) => metric.name)).toEqual(["revenue", "new_order_count"]);
  expect(fixture.models).toHaveLength(1);
  expect(fixture.metrics).toHaveLength(2);
  expect(fixture.metricWrites).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test("model Columns remain beside the rail and fit the body at 1440px and scroll only inside the table at 390px", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const model = { sourcePartitionInfo: [], semanticModelId: createdModelId, workspaceId, definition: ordersDefinition, createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z" };
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/${createdModelId}`, [model]);
  const table = page.getByRole("table");
  const columnsFit = async () => {
    await expect(table.getByRole("columnheader", { name: "Details", exact: true })).toBeVisible();
    const widths = await table.evaluate((element) => {
      const wrapper = element.parentElement!;
      const wrapperBounds = wrapper.getBoundingClientRect();
      return {
        tableWidth: element.scrollWidth,
        wrapperWidth: wrapper.clientWidth,
        scrollWidth: wrapper.scrollWidth,
        headersInside: Array.from(element.querySelectorAll("th")).every((header) => {
          const bounds = header.getBoundingClientRect();
          return bounds.left >= wrapperBounds.left && bounds.right <= wrapperBounds.right;
        }),
      };
    });
    expect(widths.tableWidth).toBeLessThanOrEqual(widths.wrapperWidth);
    expect(widths.scrollWidth).toBeLessThanOrEqual(widths.wrapperWidth);
    expect(widths.headersInside).toBe(true);
  };
  const mobileTableScroll = async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await table.locator("..").evaluate((wrapper) => wrapper.scrollWidth > wrapper.clientWidth)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  };
  await columnsFit();
  const columns = page.getByRole("region", { name: "Model fields", exact: true });
  const rail = page.getByRole("region", { name: "Used in", exact: true });
  const [columnsBox, railBox] = await Promise.all([columns.boundingBox(), rail.boundingBox()]);
  expect(columnsBox!.x + columnsBox!.width).toBeLessThan(railBox!.x);
  expect(Math.abs(columnsBox!.y - railBox!.y)).toBeLessThanOrEqual(1);
  await expect(page.getByRole("region", { name: "How it's calculated", exact: true })).toHaveCount(0);
  await mobileTableScroll();
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole("link", { name: "Edit", exact: true }).click();
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeVisible();
  await expect(page.getByLabel("Field 1 name", { exact: true })).toBeVisible();
  await columnsFit();
  await mobileTableScroll();
  expect(fixture.writes).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test("37 model Columns preserve whole words at 1440px, read by role and edited with a Type column", async ({ page }) => {
  const columnNames = ["order_id", "ordered_at", "amount", ..."order_channel order_status promised_delivery_date delivered_date on_time_delivery order_ts order_date customer_id customer_name signup_date cohort_month region acquisition_channel loyalty_tier product_id sku product_name category subcategory brand quantity unit_list_price unit_selling_price discount_amount gross_revenue product_cost gross_margin returned_quantity return_amount return_date return_reason".split(" ")];
  expect(columnNames).toHaveLength(34);
  const model = { sourcePartitionInfo: [],
    semanticModelId: createdModelId, workspaceId,
    definition: { ...ordersDefinition, dimensions: [...ordersDefinition.dimensions,
      { isPartition: false, name: "order_weekday", type: "categorical", expression: "MOD(EXTRACT(DAYOFWEEK FROM ordered_at) + 5, 7) + 1" },
      { isPartition: false, name: "order_hour", type: "categorical", expression: "EXTRACT(HOUR FROM ordered_at)" },
      { isPartition: false, name: "completed_order", type: "categorical", expression: "CASE WHEN order_status = 'Completed' THEN order_id ELSE NULL END" },
    ] },
    createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z",
  } satisfies SemanticModelResponse;
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/${createdModelId}`, [model]);
  await page.route(`**${catalogPath}/relations/orders**`, (route) => json(route, { requiresPartitionFilter: null,
    name: "orders", schemaName: "analytics", kind: "TABLE",
    columns: columnNames.map((name, index) => ({ name, nativeType: name === "ordered_at" ? "TIMESTAMP WITHOUT TIME ZONE" : "VARCHAR", nullable: false, ordinalPosition: index + 1 })),
    primaryKey: { columns: ["order_id"] }, clusteringKeys: [], foreignKeys: [], partitionKeys: [],
    lineageResourceRef: { id: "019e8000-0000-7000-8000-000000000b91", kind: "catalog_table" },
  } satisfies components["schemas"]["CatalogRelation"]));
  await page.reload();
  const table = page.getByRole("table");
  await expect(table.getByRole("rowheader")).toHaveText(["Keys 1", "Time 1", "Categories 3", "Aggregates 1", "Not used 31"]);
  await expect(table.locator("tbody tr:not(:has(th))")).toHaveCount(6);
  await table.getByRole("button", { name: "Not used 31" }).click();
  await expect(table.getByRole("button", { name: "Not used 31" })).toHaveAttribute("aria-expanded", "true");
  await expect(table.locator("tbody tr:not(:has(th))")).toHaveCount(37);
  const words = await table.evaluate((element) => {
    const cells = new Set<Element>();
    const splitCells = new Set<Element>();
    for (const identifier of element.querySelectorAll("[data-field-identifier]")) {
      const cell = identifier.closest("td")!;
      cells.add(cell);
      if (identifier.textContent !== identifier.getAttribute("title")) throw new Error("An identifier lost its full title.");
      const walker = document.createTreeWalker(identifier, NodeFilter.SHOW_TEXT);
      let node: Node | null;
      let previous = { character: "", top: 0 };
      while ((node = walker.nextNode())) {
        const text = node.textContent ?? "";
        for (let index = 0; index < text.length; index += 1) {
          const range = document.createRange();
          range.setStart(node, index);
          range.setEnd(node, index + 1);
          const top = range.getBoundingClientRect().top;
          if (Math.abs(top - previous.top) > 1 && /[\p{L}\p{N}]/u.test(previous.character) && /[\p{L}\p{N}]/u.test(text[index])) splitCells.add(cell);
          previous = { character: text[index], top };
        }
      }
    }
    return { identifierCellCount: cells.size, splitCells: [...splitCells].map((cell) => cell.textContent) };
  });
  expect(words.identifierCellCount).toBe(37);
  expect(words.splitCells).toEqual([]);
  const layout = async () => table.evaluate((element) => {
    const wrapper = element.parentElement!;
    return { tableWidth: element.scrollWidth, wrapperWidth: wrapper.clientWidth, scrollWidth: wrapper.scrollWidth };
  });
  for (const mode of ["read", "edit"]) {
    if (mode === "edit") {
      await page.getByRole("link", { name: "Edit", exact: true }).click();
      await expect(table.locator("tbody tr")).toHaveCount(37);
      await expect(table.getByRole("columnheader", { name: "Type", exact: true })).toBeVisible();
      await expect(table.locator("tbody tr").first().locator("td").nth(1)).toHaveText("VARCHAR");
      await expect(page.getByRole("textbox", { name: "Field 35 expression" })).toHaveValue("MOD(EXTRACT(DAYOFWEEK FROM ordered_at) + 5, 7) + 1");
    }
    const result = await layout();
    expect(result.scrollWidth).toBeLessThanOrEqual(result.wrapperWidth);
    expect(result.tableWidth).toBeLessThanOrEqual(result.wrapperWidth);
    await page.screenshot({ path: evidencePath(`../screenshots/semantic-model-identifiers-${mode}-1440.png`), fullPage: false });
  }
  expect(fixture.writes).toEqual([]);
  expect(fixture.unexpected).toEqual([]);
});

test("a 1440px model edit rail wraps a long affected metric name and counts what it affects", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const metricLabel = "Gross revenue after completed order adjustments";
  const model = { sourcePartitionInfo: [], semanticModelId: createdModelId, workspaceId, definition: { ...ordersDefinition, measures: [{ ...ordersDefinition.measures[0], label: metricLabel }] }, createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z" };
  const fixture = await openSemanticModelRoute(page, `/workspaces/${workspaceId}/semantic-models/${createdModelId}?edit=true`, [model]);
  await page.route("**/v2/graphql", async (route) => {
    const body = route.request().postDataJSON() as { query: string };
    if (!body.query.includes("query LineageFocusCone")) return route.fallback();
    const focus = { resource: { kind: "SEMANTIC_MODEL", id: createdModelId }, label: "Orders", address: "orders", description: null, connector: null, missing: false };
    const metric = { resource: { kind: "METRIC", id: createdMetricId }, label: metricLabel, address: "revenue", description: null, connector: null, missing: false };
    return json(route, { data: { lineageFocusCone: { workspaceId, focus, ancestors: [], descendants: [metric], siblings: [], missingDefinitions: [], edges: [{ parent: focus.resource, dependent: metric.resource }], siblingEdges: [], entityPeers: [], dashboardUsages: [], detail: { relation: null, semanticModel: { entities: [], dimensions: [], measures: [] }, metric: null } } } });
  });
  await page.reload();
  const affected = page.getByRole("list", { name: "Affected definitions" });
  const checkHeading = page.getByRole("heading", { name: "Check", exact: true });
  const affectsHeading = page.getByRole("heading", { name: "Affects if saved", exact: true });
  const stickyRail = page.locator('aside').filter({ has: checkHeading }).first();
  await expect(stickyRail.getByRole("heading", { level: 2 })).toHaveText(["Check", "Affects if saved"]);
  const railStyle = await stickyRail.evaluate((rail) => { const style = getComputedStyle(rail); return { width: rail.getBoundingClientRect().width, position: style.position, overflowY: style.overflowY }; });
  expect(railStyle.width).toBe(340);
  expect(railStyle.position).toBe("sticky");
  expect(railStyle.overflowY).toBe("auto");
  await expect(affectsHeading).toBeVisible();
  const link = affected.getByRole("link", { name: metricLabel, exact: true });
  await expect(link).toBeVisible();
  await expect(link).toHaveAttribute("title", metricLabel);
  await expect(affected.getByTestId("affected-relationship")).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Affects if saved" })).toContainText("1 metric");
  await expect(affected).not.toContainText("Aggregate: revenue");
  await expect(affected).not.toContainText("Depth");
  const layout = await link.evaluate((element) => ({ width: element.getBoundingClientRect().width, height: element.getBoundingClientRect().height, lineHeight: parseFloat(getComputedStyle(element).lineHeight) }));
  expect(layout.width).toBeGreaterThan(240);
  expect(layout.height).toBeGreaterThan(layout.lineHeight * 1.5);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const columnScroller = page.getByRole("table").locator("..");
  expect(await columnScroller.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
  expect(fixture.unexpected).toEqual([]);
});
