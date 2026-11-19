import { mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const workspaceId = "019e8000-0000-7000-8000-000000000b31";
const connectorId = "019e8000-0000-7000-8000-000000000c31";
const runId = "019e8000-0000-7000-8000-000000000d31";
const screenshotDirectory = evidencePath(resolve(process.cwd(), "../screenshots"));

const segment = { kind: "DIMENSION", semanticModelName: "customers", name: "segment", granularity: null } as const;
const country = { kind: "DIMENSION", semanticModelName: "orders", name: "country", granularity: null } as const;

function json(body: unknown, status = 200) {
  return {
    status,
    contentType: status >= 400 ? "application/problem+json" : "application/json",
    body: JSON.stringify(body),
  };
}

function rejection(path: string, detail: string, violations: Array<{ field: string; code: string; message: string }>) {
  return json({
    type: "https://monomer.cc/problems/invalid-request",
    title: "Bad Request",
    status: 400,
    detail,
    instance: path,
    code: "INVALID_REQUEST",
    requestId: "definition-problem-request",
    traceId: "trace-definition-problem",
    retryable: false,
    violations,
  }, 400);
}

function model(name: string, label: string, dimension: { name: string; label: string }) {
  return { sourcePartitionInfo: [],
    semanticModelId: `model-${name}`,
    workspaceId,
    definition: {
      schemaVersion: 3,
      name,
      label,
      sourceRelations: [{ connectorId, schema: "analytics", relation: name }],
      entities: [{ name: name.slice(0, -1), type: "primary", expression: `${name.slice(0, -1)}_id` }],
      dimensions: [{ isPartition: false, name: dimension.name, type: "categorical", expression: dimension.name, label: dimension.label }],
      measures: name === "orders" ? [{ name: "amount", aggregation: "sum", expression: "amount" }] : [],
    },
    createdAt: "2026-10-06T00:00:00Z",
    updatedAt: "2026-10-06T00:00:00Z",
  };
}

const metric = { sourcePartitionInfo: [],
  metricId: "metric-revenue",
  workspaceId,
  definition: { schemaVersion: 3, name: "revenue", label: "Revenue", type: "simple", semanticModelName: "orders", measure: "amount" },
  createdAt: "2026-10-06T00:00:00Z",
  updatedAt: "2026-10-06T00:00:00Z",
};

const segmentRun = {
  kind: "SEMANTIC",
  runId,
  workspaceId,
  connectorId,
  requestDigest: `sha256:${"c".repeat(64)}`,
  state: "SUCCEEDED",
  revision: 2,
  resultAvailable: true,
  failureCode: null,
  failureDetail: null,
  createdAt: "2026-10-06T00:00:00Z",
  updatedAt: "2026-10-06T00:00:03Z",
  sql: "SELECT customers.segment, SUM(orders.amount) AS revenue FROM analytics.orders JOIN analytics.customers USING (customer_id) GROUP BY 1",
  query: { schemaVersion: 4, metrics: ["revenue"], groupBys: [segment], metricTimeRange: null, filters: [{ kind: "TEXT", dimension: { semanticModelName: "customers", name: "segment" }, operator: "EQUALS", values: ["Enterprise"] }], orderBy: [], rowLimit: 1000 },
  resultMetadata: { byteSize: "96", mediaType: "application/vnd.apache.arrow.file", rowCount: "1", columnCount: 2, expiresAt: "2099-01-01T00:00:00Z" },
  result: {
    columns: [
      { ordinal: 1, name: "dimension.customers.segment", logicalType: "TEXT", nullable: false },
      { ordinal: 2, name: "metric.revenue", logicalType: "DECIMAL", nullable: false },
    ],
    rows: [["Enterprise", "90.00"]],
    returnedRowCount: 1,
    stopReason: "ROW_LIMIT",
  },
};

async function openExplore(page: Page, groupBys: (route: Route, path: string) => Promise<void>, options: { run: typeof segmentRun | null; start: ((route: Route, path: string) => Promise<void>) | null }): Promise<void> {
  await page.route("**/v2/workspaces/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/connectors")) {
      await route.fulfill(json({ connectors: [{ id: connectorId, workspaceId, name: "Warehouse", type: "POSTGRESQL", properties: { host: "fixture.internal", port: 5432, database: "warehouse" }, createdAtEpochMs: 0, updatedAtEpochMs: 0 }] }));
    } else if (path.endsWith("/semantic/metrics")) {
      await route.fulfill(json([metric]));
    } else if (path.endsWith("/semantic/semantic-models")) {
      await route.fulfill(json([model("orders", "Orders", { name: "country", label: "Country" }), model("customers", "Customers", { name: "segment", label: "Segment" })]));
    } else if (path.endsWith("/semantic/query/group-bys")) {
      await groupBys(route, path);
    } else if (path.endsWith("/explore/semantic-runs") && options.start) {
      await options.start(route, path);
    } else if (path.endsWith("/explore/runs")) {
      await route.fulfill(json({ runs: options.run ? [options.run] : [] }));
    } else if (options.run && path.endsWith(`/explore/runs/${options.run.runId}`)) {
      await route.fulfill(json(options.run));
    } else {
      await route.fulfill(json({}, 404));
    }
  });
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Definition problems QA",
    organizationId: "organization-qa",
    role: "EXPLORER",
    user: { id: "definition-problems-user", email: "definition-problems@example.test", displayName: "Definition problems QA" },
  });
  const target = `/workspaces/${workspaceId}/explore${options.run ? `?run=${options.run.runId}` : ""}`;
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, target);
}

test.beforeAll(() => {
  mkdirSync(screenshotDirectory, { recursive: true });
});

test("names the broken model when group bys can't be listed", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 400, url: /\/semantic\/query\/group-bys$/, reason: "The fixture's orders model fails engine validation." });
  await page.setViewportSize({ width: 1440, height: 900 });
  await openExplore(page, async (route, path) => {
    await route.fulfill(rejection(path, "METRICFLOW_MANIFEST_INVALID", [
      { field: "workspace/semantic-models/orders", code: "METRICFLOW_MANIFEST_INVALID", message: "Semantic model `orders` declares two primary entities." },
    ]));
  }, { run: null, start: null });

  await page.getByRole("region", { name: "Metrics list" }).getByRole("button", { name: /^Revenue/ }).click();
  const problem = page.getByRole("region", { name: "Group by list" }).getByRole("alert");
  await expect(problem).toContainText("The semantic model “Orders” has a problem.");
  await expect(problem).toContainText("Semantic model `orders` declares two primary entities.");
  await expect(problem).toContainText("METRICFLOW_MANIFEST_INVALID · Reference trace-definition-problem");
  await expect(problem.getByRole("link", { name: "Open semantic model" })).toHaveAttribute("href", `/workspaces/${workspaceId}/semantic-models/model-orders`);
  await expect(problem.getByRole("link", { name: "Open metric" })).toHaveCount(0);
  await page.screenshot({ path: resolve(screenshotDirectory, "explore-definition-problem-group-bys.png") });
});

test("explains options hidden by a broken model and names it beside the selection it took away", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openExplore(page, async (route) => {
    await route.fulfill(json({
      groupBys: [country],
      dimensionFilterTypes: [{ dimension: { semanticModelName: country.semanticModelName, name: country.name }, kind: "TEXT" }],
      excludedSemanticModels: [{ semanticModelName: "customers", message: "Semantic model `customers` declares two primary entities." }],
    }));
  }, { run: segmentRun, start: null });

  const question = page.getByRole("tabpanel", { name: "Metrics" });
  await expect(question.getByText("“Customers” has a problem. Remove to run.")).toHaveCount(2);
  await expect(question.getByRole("button", { name: "Run", exact: true })).toBeDisabled();
  await expect(question.getByRole("button", { name: "Segment, not available for the chosen metrics", exact: true })).toBeVisible();
  await expect(question.getByRole("button", { name: "Segment is Enterprise, not available for the chosen metrics", exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Add group by" }).click();
  const panel = page.getByRole("dialog", { name: "Group by" });
  await expect(panel).toContainText("“Customers” has an error, so its group bys aren’t listed.");
  await expect(panel.getByText("declares two primary entities", { exact: false })).toHaveCount(0);
  await panel.getByRole("button", { name: "Details", exact: true }).click();
  await expect(panel).toContainText("Customers: Semantic model `customers` declares two primary entities.");
  await expect(panel).toContainText("Ask an editor to fix it.");
  await expect(panel.getByRole("link", { name: "Open model" }), "an Explorer can't fix a model, so no link").toHaveCount(0);
  await page.screenshot({ path: resolve(screenshotDirectory, "explore-definition-problem-excluded-model.png") });
});

test("sends a connector without loaded tables to choosing tables", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 400, url: /\/semantic\/query\/group-bys$/, reason: "The fixture's connector has no Catalog yet." });
  consoleErrors.allow({ status: 400, url: /\/explore\/semantic-runs$/, reason: "The fixture's connector has no Catalog yet." });
  await page.setViewportSize({ width: 1440, height: 900 });
  const startRequests: string[] = [];
  await openExplore(page, async (route, path) => {
    await route.fulfill(rejection(path, "CATALOG_RELATION_NOT_FOUND", [
      { field: "metrics", code: "CATALOG_RELATION_NOT_FOUND", message: "This connector has no tables loaded in Catalog yet." },
    ]));
  }, {
    run: null,
    start: async (route, path) => {
      startRequests.push(path);
      await route.fulfill(rejection(path, "CATALOG_RELATION_NOT_FOUND", [
        { field: "query", code: "CATALOG_RELATION_NOT_FOUND", message: "This connector has no tables loaded in Catalog yet." },
      ]));
    },
  });

  await page.getByRole("region", { name: "Metrics list" }).getByRole("button", { name: /^Revenue/ }).click();
  const chooseTablesHref = `/workspaces/${workspaceId}/catalog?connector=${connectorId}&choose=1`;
  const listProblem = page.getByRole("region", { name: "Group by list" }).getByRole("alert");
  await expect(listProblem).toContainText("No tables from Warehouse are loaded in Catalog yet.");
  await expect(listProblem.getByRole("link", { name: "Choose tables" })).toHaveAttribute("href", chooseTablesHref);

  await page.getByRole("button", { name: "Run", exact: true }).click();
  const startProblem = page.getByRole("tabpanel", { name: "Metrics" }).getByRole("alert").filter({ hasText: "Choose the tables these metrics read" });
  await expect(startProblem).toContainText("No tables from Warehouse are loaded in Catalog yet.");
  await expect(startProblem.getByRole("link", { name: "Choose tables" })).toHaveAttribute("href", chooseTablesHref);
  expect(startRequests).toHaveLength(1);
  await page.screenshot({ path: resolve(screenshotDirectory, "explore-definition-problem-no-catalog.png") });
});
