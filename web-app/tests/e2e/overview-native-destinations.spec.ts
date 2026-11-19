import { readFileSync } from "node:fs";
import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { mockSignedInWorkspace } from "./support/session";
const workspaceId = "019e8000-0000-7000-8000-000000000b01";
const principalId = "019e8000-0000-7000-8000-000000000b02";
const connectorId = "019e8000-0000-7000-8000-000000000b03";
const jobId = "019e8000-0000-7000-8000-000000000b04";
const dashboardId = "019e8000-0000-7000-8000-000000000b05";
const sessionId = "019e8000-0000-7000-8000-000000000b06";
const json = (value: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
async function authenticate(page: Page, destination: string) {
  await page.route("**/geist-latin-wght-normal.woff2", (route) => route.fulfill({ contentType: "font/woff2", body: readFileSync("node_modules/@fontsource-variable/geist/files/geist-latin-wght-normal.woff2") }));
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Commerce Insights",
    organizationId: "organization-1",
    role: "EXPLORER",
    user: { id: principalId, email: "overview@example.test", displayName: "Alex Morgan" },
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, destination);
}


test("Catalog native ingest keeps the explicit completed target when another job is active", async ({ page }) => {
  const writes: string[] = [];
  const requestedJobs: string[] = [];
  const job = { jobId, connectorId, state: "COMPLETED", entryKind: "CONNECTOR", catalogName: null, requestedAt: "2026-09-09T00:00:00Z", terminalAt: "2026-09-09T00:01:00Z", refreshedRelationCount: 4, runningTaskCount: 0, errorCount: 0, requestedBy: { principalId: "019e0000-0000-7000-8000-0000000000f1", displayName: "Overview User", credentialSurface: "WEB" }, tasks: [] };
  await page.route("**/v2/workspaces/**", async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() !== "GET") writes.push(url.pathname);
    if (url.pathname.endsWith("/connectors")) return route.fulfill(json({ connectors: [{ id: connectorId, workspaceId, name: "Warehouse", type: "POSTGRESQL", properties: {}, secretSet: true, createdAtEpochMs: 1, updatedAtEpochMs: 1 }] }));
    if (url.pathname.endsWith("/active")) return route.fulfill(json({ ...job, jobId: dashboardId, state: "RUNNING" }));
    if (url.pathname.endsWith(`/ingests/${jobId}`)) { requestedJobs.push(jobId); return route.fulfill(json(job)); }
    if (url.pathname.includes("/ingests/")) requestedJobs.push(url.pathname.split("/").at(-1)!);
    if (url.pathname.endsWith("/ingests")) return route.fulfill(json({ jobs: [], totalCount: 0, page: 1, pageSize: 1 }));
    if (url.pathname.endsWith("/schemas")) return route.fulfill(json({ schemas: [], totalCount: 0, page: 1, pageSize: 20 }));
    if (url.pathname.endsWith("/selection")) return route.fulfill(json({ selectedSchemas: [], committedAt: null }));
    return route.fulfill(json({ catalogs: [], options: [], selectedCatalogNames: [], totalCount: 0, page: 1, pageSize: 20, jobs: [] }));
  });
  await authenticate(page, `/workspaces/${workspaceId}/catalog?connector=${connectorId}&ingest=${jobId}`);
  await expect(page.getByText("Catalog refresh · Completed", { exact: true })).toBeVisible();
  expect(requestedJobs).toContain(jobId);
  expect(requestedJobs).not.toContain(dashboardId);
  expect(writes).toEqual([]);
});

test("Catalog native schema refresh reads exact scope and never starts a refresh", async ({ page }) => {
  const requests: Array<{ method: string; url: string }> = [];
  await page.route("**/v2/workspaces/**", async (route) => {
    const url = new URL(route.request().url());
    requests.push({ method: route.request().method(), url: url.href });
    if (url.pathname.endsWith("/connectors")) return route.fulfill(json({ connectors: [{ id: connectorId, workspaceId, name: "Warehouse", type: "SNOWFLAKE", properties: {}, secretSet: true, createdAtEpochMs: 1, updatedAtEpochMs: 1 }] }));
    if (url.pathname.endsWith(`/selection/list-refresh-jobs/${jobId}`)) return route.fulfill(json({ jobId, state: "COMPLETED_WITH_ERRORS", parentKind: "SCHEMA", childKind: "RELATION", catalogName: "analytics", schemaName: "sales ops", requestedAt: "2026-09-09T00:00:00Z", terminalAt: "2026-09-09T00:01:00Z", failureCode: "READ_FAILED", safeMessage: "Some metadata could not be read." }));
    if (url.pathname.endsWith("/active")) return route.fulfill({ status: 204 });
    if (url.pathname.endsWith("/schemas")) return route.fulfill(json({ schemas: [], totalCount: 0, page: 1, pageSize: 20 }));
    if (url.pathname.endsWith("/selection")) return route.fulfill(json({ selectedSchemas: [], committedAt: null }));
    return route.fulfill(json({ catalogs: [{ name: "analytics" }], options: [], selectedCatalogNames: ["analytics"], totalCount: 0, page: 1, pageSize: 20, jobs: [] }));
  });
  const activeIngestResponse = page.waitForResponse(response => new URL(response.url()).pathname.endsWith("/ingests/active") && new URL(response.url()).searchParams.get("catalogName") === "analytics");
  await authenticate(page, `/workspaces/${workspaceId}/catalog?connector=${connectorId}&catalog=analytics&refreshCatalog=analytics&refreshSchema=sales%20ops&refresh=${jobId}`);
  await expect(page.getByText("Catalog list refresh · Completed with errors", { exact: true })).toBeVisible();
  await expect(page.getByText("Some metadata could not be read.")).toBeVisible();
  const target = new URL(requests.find(({ url }) => url.includes(`/list-refresh-jobs/${jobId}`))!.url);
  expect(target.searchParams.get("parentKind")).toBe("SCHEMA");
  expect(target.searchParams.get("schemaName")).toBe("sales ops");
  expect(target.searchParams.get("catalogName")).toBe("analytics");
  expect(requests.every(({ method }) => method === "GET")).toBe(true);
  expect(requests.some(({ url }) => url.includes("/ingests/active?catalogName=analytics"))).toBe(true);
  await activeIngestResponse;
  await expect(page.getByText("Catalog unavailable")).toHaveCount(0);
});

test("Catalog connector-level refresh link reads no active ingest before the connector depth is known", async ({ page }) => {
  const activeReads: string[] = [];
  await page.route("**/v2/workspaces/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/connectors")) {
      await new Promise(resolve => setTimeout(resolve, 500));
      return route.fulfill(json({ connectors: [{ id: connectorId, workspaceId, name: "Warehouse", type: "SNOWFLAKE", properties: {}, secretSet: true, createdAtEpochMs: 1, updatedAtEpochMs: 1 }] }));
    }
    if (url.pathname.endsWith(`/selection/list-refresh-jobs/${jobId}`)) return route.fulfill(json({ jobId, state: "COMPLETED", parentKind: "CONNECTOR", childKind: "CATALOG", catalogName: null, schemaName: null, requestedAt: "2026-09-09T00:00:00Z", terminalAt: "2026-09-09T00:01:00Z", failureCode: null, safeMessage: null }));
    if (url.pathname.endsWith("/ingests/active")) { activeReads.push(url.search); return route.fulfill({ status: 204 }); }
    if (url.pathname.endsWith("/schemas")) return route.fulfill(json({ schemas: [], totalCount: 0, page: 1, pageSize: 20 }));
    if (url.pathname.endsWith("/selection")) return route.fulfill(json({ selectedSchemas: [], committedAt: null }));
    return route.fulfill(json({ catalogs: [], options: [], selectedCatalogNames: [], totalCount: 0, page: 1, pageSize: 20, jobs: [] }));
  });
  await authenticate(page, `/workspaces/${workspaceId}/catalog?connector=${connectorId}&refresh=${jobId}`);
  await expect(page.getByText("Catalog list refresh · Completed", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "No catalogs selected" })).toBeVisible();
  expect(activeReads).toEqual([]);
});

test("Catalog connector-level refresh link keeps its banner and target while the first catalog opens", async ({ page }) => {
  const refreshReads: string[] = [];
  await page.route("**/v2/workspaces/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/connectors")) return route.fulfill(json({ connectors: [{ id: connectorId, workspaceId, name: "Warehouse", type: "SNOWFLAKE", properties: {}, secretSet: true, createdAtEpochMs: 1, updatedAtEpochMs: 1 }] }));
    if (url.pathname.endsWith(`/selection/list-refresh-jobs/${jobId}`)) {
      refreshReads.push(url.search);
      return route.fulfill(json({ jobId, state: "COMPLETED", parentKind: "CONNECTOR", childKind: "CATALOG", catalogName: null, schemaName: null, requestedAt: "2026-09-09T00:00:00Z", terminalAt: "2026-09-09T00:01:00Z", failureCode: null, safeMessage: null }));
    }
    if (url.pathname.endsWith("/ingests/active")) return route.fulfill({ status: 204 });
    if (url.pathname.endsWith("/ingests")) return route.fulfill(json({ jobs: [], totalCount: 0, page: 1, pageSize: 1 }));
    if (url.pathname.endsWith("/schemas")) return route.fulfill(json({ schemas: [], totalCount: 0, page: 1, pageSize: 50 }));
    if (url.pathname.endsWith("/selection")) return route.fulfill(json({ selectedSchemas: [], committedAt: null }));
    return route.fulfill(json({ catalogs: [{ name: "analytics" }], options: [], selectedCatalogNames: ["analytics"], totalCount: 0, page: 1, pageSize: 20, jobs: [] }));
  });
  await authenticate(page, `/workspaces/${workspaceId}/catalog?connector=${connectorId}&refresh=${jobId}`);
  await expect(page).toHaveURL(/catalog=analytics/);
  await expect(page).toHaveURL(new RegExp(`refresh=${jobId}`));
  await expect(page.getByText("Catalog list refresh · Completed", { exact: true })).toBeVisible();
  expect(refreshReads.length).toBeGreaterThan(0);
  for (const search of refreshReads) {
    const params = new URLSearchParams(search);
    expect(params.get("parentKind")).toBe("CONNECTOR");
    expect(params.get("catalogName")).toBeNull();
  }
});

test("Catalog refresh link to a deselected Catalog shows the selection state without an active ingest read", async ({ page }) => {
  const activeReads: string[] = [];
  await page.route("**/v2/workspaces/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/connectors")) return route.fulfill(json({ connectors: [{ id: connectorId, workspaceId, name: "Warehouse", type: "SNOWFLAKE", properties: {}, secretSet: true, createdAtEpochMs: 1, updatedAtEpochMs: 1 }] }));
    if (url.pathname.endsWith(`/selection/list-refresh-jobs/${jobId}`)) return route.fulfill(json({ jobId, state: "COMPLETED", parentKind: "CATALOG", childKind: "SCHEMA", catalogName: "SALES", schemaName: null, requestedAt: "2026-09-09T00:00:00Z", terminalAt: "2026-09-09T00:01:00Z", failureCode: null, safeMessage: null }));
    if (url.pathname.endsWith("/ingests/active")) {
      activeReads.push(url.search);
      return route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ type: "about:blank", title: "Not Found", status: 404, code: "NOT_FOUND", instance: url.pathname, requestId: jobId, traceId: "overview-native-fixture", retryable: false, detail: "Not Found" }) });
    }
    if (url.pathname.endsWith("/schemas")) return route.fulfill(json({ schemas: [], totalCount: 0, page: 1, pageSize: 20 }));
    if (url.pathname.endsWith("/selection")) return route.fulfill(json({ selectedSchemas: [], committedAt: null }));
    return route.fulfill(json({ catalogs: [], options: [], selectedCatalogNames: [], totalCount: 0, page: 1, pageSize: 20, jobs: [] }));
  });
  await authenticate(page, `/workspaces/${workspaceId}/catalog?connector=${connectorId}&catalog=SALES&refreshCatalog=SALES&refresh=${jobId}`);
  await expect(page.getByText("Catalog list refresh · Completed", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "No catalogs selected" })).toBeVisible();
  await expect(page.getByText("Catalog unavailable")).toHaveCount(0);
  expect(activeReads).toEqual([]);
});

const definition = { schemaVersion: 1, meta: { title: "Revenue" }, parameters: {}, variables: {}, queries: {}, widgets: {}, layout: { sections: [] } };
async function installDashboard(page: Page, wrongDashboard = false) {
  let denied = false;
  const runRequests: string[] = [];
  const mutations: string[] = [];
  await page.route("**/v2/workspaces/**", async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() !== "GET") mutations.push(url.pathname);
    if (url.pathname.endsWith(`/dashboards/${dashboardId}`)) return route.fulfill(json({ workspaceId, dashboardId, definition, createdAt: "2026-09-09T00:00:00Z", updatedAt: "2026-09-09T00:00:00Z" }));
    if (url.pathname.endsWith(`/dashboard-sessions/${sessionId}`)) return route.fulfill(json({ workspaceId, dashboardId: wrongDashboard ? connectorId : dashboardId, dashboardSessionId: sessionId, definition, initialVariables: {}, parameterBindings: {}, runningQueryRuns: [] }));
    if (url.pathname.endsWith(":resolve")) return route.fulfill(json({ variables: {}, queryRuns: {} }));
    if (url.pathname.endsWith(`/query-runs/${jobId}`)) {
      runRequests.push(url.pathname);
      if (denied) return route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ type: "about:blank", title: "Not found", status: 404, code: "NOT_FOUND", instance: url.pathname, requestId: jobId, traceId: "overview-native-fixture", retryable: false, detail: "Query run was not found." }) });
      await new Promise(resolve => setTimeout(resolve, 600));
      return route.fulfill(json({ queryType: "SEMANTIC" as const, queryRunId: jobId, queryName: "historic_orders", connectorId, createdAt: "2026-09-09T00:00:00Z", updatedAt: "2026-09-09T00:01:00Z", state: "SUCCEEDED", resultAvailable: true, outputColumnNames: ["orders"], failureCode: null, failureMessage: null }));
    }
    if (url.pathname.endsWith("/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: "" });
    return route.fulfill(json({ items: [], nextCursor: null }));
  });
  return { runRequests, mutations, denyRun: () => { denied = true; } };
}

test("Dashboard native run opens after late retrieval beyond the loaded history page", async ({ page }) => {
  const { runRequests } = await installDashboard(page);
  await authenticate(page, `/workspaces/${workspaceId}/dashboards/${dashboardId}?session=${sessionId}&run=${jobId}`);
  const dialog = page.getByRole("dialog", { name: "historic_orders" });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(jobId);
  await expect(dialog).toContainText("Succeeded");
  expect(runRequests.length).toBeGreaterThan(0);
  expect(runRequests.every(path => path.endsWith(`/query-runs/${jobId}`))).toBe(true);
  await page.getByRole("button", { name: "Close run detail" }).click();
  await expect(dialog).toBeHidden();
});

test("Dashboard rejects another dashboard's session before fetching linked run details", async ({ page }) => {
  const { runRequests, mutations } = await installDashboard(page, true);
  await authenticate(page, `/workspaces/${workspaceId}/dashboards/${dashboardId}?session=${sessionId}&run=${jobId}`);
  await expect(page.getByText("These results belong to a different dashboard.")).toBeVisible();
  expect(runRequests).toEqual([]);
  expect(mutations).toEqual([]);
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("Dashboard run without session does not open a new session", async ({ page }) => {
  const { mutations } = await installDashboard(page);
  await authenticate(page, `/workspaces/${workspaceId}/dashboards/${dashboardId}?run=${jobId}`);
  await expect(page.getByText("This query run link is incomplete. Open the run from History.")).toBeVisible();
  expect(mutations).toEqual([]);
});


test("Dashboard re-checks linked run details at page entry after run access is revoked", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 404, url: /\/query-runs\/[^/]+$/, reason: "The fixture revokes the linked run." });
  const { denyRun, runRequests } = await installDashboard(page);
  await authenticate(page, `/workspaces/${workspaceId}/dashboards/${dashboardId}?session=${sessionId}&run=${jobId}`);
  const dialog = page.getByRole("dialog", { name: "historic_orders" });
  await expect(dialog).toBeVisible();
  const readsBeforeRevocation = runRequests.length;
  denyRun();
  await page.reload();
  await expect(page.getByText("Query run was not found.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Retry runs" })).toBeVisible();
  await expect(dialog).toHaveCount(0);
  expect(runRequests.length).toBeGreaterThan(readsBeforeRevocation);
});
