import { readFileSync } from "node:fs";
import type { Page } from "@playwright/test";
import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { json, mockGraphql, mockSessionRefresh, problem } from "./support/session";
import type { DashboardDefinition } from "../../src/api/dashboard";
import type { AccessibleWorkspace, SampleWorkspaceMarker } from "../../src/lib/auth/types";

const workspaceId = "019e8000-0000-7000-8000-000000001201";
const sampleId = "019e8000-0000-7000-8000-000000001202";
const connectorId = "019e8000-0000-7000-8000-000000001203";
const principalId = "019e8000-0000-7000-8000-000000001204";
const organizationId = "sample-organization";
const personalOrganizationId = "personal-organization";
const personalWorkspaceId = "019e8000-0000-7000-8000-000000001207";
const timestamp = "2026-10-12T00:00:00Z";
const user = { id: principalId, email: "sample-user@example.test", displayName: "Sample User" };
const banner = "Sample data from Acme Cloud, a fictional company. The data is read-only; you can build dashboards and edit metrics.";
// API order: seeded Dashboards share a creation time, so the API lists Board metrics last.
const dashboards = [
  { id: "019e8000-0000-7000-8000-000000001205", title: "Revenue movements" },
  { id: "019e8000-0000-7000-8000-000000001206", title: "Board metrics" },
];

function loadDefinitions(name: string): Array<{ definition: Record<string, unknown> }> {
  const file = new URL(`../../../testkit/data/duckdb/acme-cloud/definitions/${name}.json`, import.meta.url);
  return JSON.parse(readFileSync(file, "utf8").replaceAll("${SAMPLE_CONNECTOR_ID}", connectorId));
}

function dashboardDefinition(title: string): DashboardDefinition {
  return {
    schemaVersion: 1, meta: { title }, parameters: {}, variables: {},
    queries: { active: { type: "SEMANTIC", connectorId, query: { schemaVersion: 4, metrics: ["active_accounts"], groupBys: [{ kind: "METRIC_TIME", granularity: "DAY" }], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 } } },
    widgets: { active: { type: "TABLE", title: "Weekly active accounts", queryName: "active", columns: [{ field: "metric.active_accounts", header: "Active accounts" }] } },
    layout: { sections: [{ id: "active", height: { mode: "SCALED", scale: 1 }, root: { type: "WIDGET", id: "active_node", widgetId: "active" } }] },
  };
}

async function install(page: Page, options: { organizationRole?: "ADMIN" | "MEMBER"; workspaceRole?: "OWNER" | "EDITOR" | "VIEWER"; initialSample?: "PROVISIONING" | "READY"; otherWorkspace?: boolean } = {}) {
  const organizationRole = options.organizationRole ?? "ADMIN";
  let state: SampleWorkspaceMarker["state"] | null = options.initialSample ?? null;
  let accessible = options.initialSample === "PROVISIONING";
  let posts = 0;
  let reads = 0;
  let activityReads = 0;
  let metricWrites = 0;
  let failingReads = false;
  const models = loadDefinitions("semantic-models").map(({ definition }, index) => ({ semanticModelId: `019e8000-0000-7000-8000-${String(index + 1300).padStart(12, "0")}`, workspaceId: sampleId, definition, sourcePartitionInfo: [], createdAt: timestamp, updatedAt: timestamp }));
  const metrics = loadDefinitions("metrics").map(({ definition }, index) => ({ metricId: `019e8000-0000-7000-8000-${String(index + 1400).padStart(12, "0")}`, workspaceId: sampleId, definition, sourcePartitionInfo: [], createdAt: timestamp, updatedAt: timestamp }));
  const active = metrics.find((metric) => metric.definition.name === "active_accounts")!;
  const sessions = new Map<string, { dashboardId: string; value: string }>();
  let nextSession = 1500;
  const marker = (): SampleWorkspaceMarker => ({ dataset: "acme-cloud@1", state: state ?? "PROVISIONING", initiator: principalId });
  const sampleResponse = () => ({ workspaceId: sampleId, organizationId, displayName: "Acme Cloud (sample)", sample: marker() });
  const sampleWorkspace = (): AccessibleWorkspace => ({ id: sampleId, organizationId, name: "Acme Cloud (sample)", publicPages: false, reportingTimeZone: "UTC", role: "OWNER", sample: marker() });
  const real: AccessibleWorkspace = { id: workspaceId, organizationId, name: "Default Workspace", publicPages: false, reportingTimeZone: "UTC", role: options.workspaceRole ?? "OWNER", sample: null };
  const personal: AccessibleWorkspace = { id: personalWorkspaceId, organizationId: personalOrganizationId, name: "My Workspace", publicPages: false, reportingTimeZone: "UTC", role: "OWNER", sample: null };
  const seededMetricActivity = { id: active.metricId, kind: "METRIC", title: "Weekly active accounts", description: "Metric created · active_accounts", state: "CREATED", startedAt: null, occurredAt: timestamp, actorId: null, actorName: null, actorKind: "UNRECORDED", credentialSurface: null, agentApp: null, failureReason: null, href: `/workspaces/${sampleId}/metrics/${active.metricId}` };
  const getSession = (sessionId: string) => {
    const stored = sessions.get(sessionId)!;
    const dashboard = dashboards.find((dashboard) => dashboard.id === stored.dashboardId)!;
    return { workspaceId: sampleId, dashboardId: stored.dashboardId, dashboardSessionId: sessionId, definition: dashboardDefinition(dashboard.title), parameterBindings: {}, initialVariables: {}, runningQueryRuns: [] };
  };
  const run = (sessionId: string) => ({ queryType: "SEMANTIC", queryRunId: sessionId, connectorId, queryName: "active", state: "SUCCEEDED", resultAvailable: true, outputColumnNames: ["metric.active_accounts"], failureCode: null, failureMessage: null, createdAt: timestamp, updatedAt: timestamp, startedBy: { id: principalId, displayName: user.displayName }, credentialSurface: "WEB", agentApp: null });
  await mockSessionRefresh(page);
  await mockGraphql(page, {
    Me: (route) => json(route, { data: { me: { user, organizationMemberships: [
      { organization: { id: personalOrganizationId, displayName: "Sample User's Organization", plan: "FREE", createdAt: timestamp }, role: "ADMIN" },
      { organization: { id: organizationId, displayName: "Sample Organization", plan: "FREE", createdAt: timestamp }, role: organizationRole },
    ], loginIdentities: [] } } }),
    // The personal Organization's My Workspace comes first and is never the sample's warehouse target.
    AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: [personal, ...(options.otherWorkspace === false ? [] : [real]), ...(accessible ? [sampleWorkspace()] : [])] } }),
    WorkspaceAccess: (route, variables) => json(route, { data: { workspaceAccess: { granted: variables.workspaceId === workspaceId || accessible, organizationId, role: variables.workspaceId === workspaceId ? real.role : "OWNER" } } }),
    WorkspaceOwners: (route) => json(route, { data: { workspaceAccess: { owners: [{ userId: principalId, displayName: user.displayName }] } } }),
    LineageFocusCone: (route, variables) => {
      const focus = variables.focus as { kind: string; id: string };
      const resource = { resource: focus, label: "Weekly active accounts", address: "active_accounts", description: null, connector: null, missing: false };
      return json(route, { data: { lineageFocusCone: { workspaceId: sampleId, focus: resource, ancestors: [], descendants: [], siblings: [], missingDefinitions: [], edges: [], siblingEdges: [], entityPeers: [], dashboardUsages: dashboards.map((dashboard) => ({ dashboardId: dashboard.id, title: dashboard.title, queryNames: ["active"], metricIds: [active.metricId] })), detail: { relation: null, semanticModel: null, metric: { type: "CUMULATIVE", inputs: [{ kind: "METRIC", name: "daily_active_accounts", semanticModelName: null }], timeGranularity: "DAY" } } } } });
    },
  });
  await page.route(`**/v2/organizations/${organizationId}/sample-workspace`, (route) => {
    if (route.request().method() === "POST") {
      posts++;
      state ??= "PROVISIONING";
      accessible = true;
      return json(route, sampleResponse(), state === "READY" ? 200 : 202);
    }
    reads++;
    if (failingReads) return problem(route, 503, "UNAVAILABLE", "Sample status is unavailable.", "Service Unavailable");
    return state ? json(route, sampleResponse()) : problem(route, 404, "NOT_FOUND", "No sample workspace exists.", "Not Found");
  });
  await page.route("**/oauth/connections?**", (route) => json(route, { connections: [], nextCursor: null }));
  await page.route("**/v2/workspaces/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    if (path.includes("/overview/activity")) {
      activityReads++;
      const seededRecent = path.includes(sampleId) && new URL(route.request().url()).searchParams.get("section") === "recent";
      return json(route, { items: seededRecent ? [seededMetricActivity] : [], watched: [], nextCursor: null, unavailableSources: [], observedAt: timestamp });
    }
    if (path.endsWith("/connectors")) return json(route, { connectors: path.includes(sampleId) ? [{ id: connectorId, workspaceId: sampleId, name: "Acme Cloud", type: "DUCKDB", properties: { sampleDataset: "acme-cloud@1" }, sample: { dataset: "acme-cloud@1", label: "Acme Cloud" }, createdAtEpochMs: 1, updatedAtEpochMs: 1 }] : [] });
    if (path.endsWith("/catalog/connector-summaries")) return json(route, { connectors: path.includes(sampleId) ? [{ connectorId, selectedCatalogCount: 0, selectedSchemaCount: 1, tableCount: 8, lastRefresh: null, lastSuccessfulRefreshAt: timestamp }] : [] });
    if (path.endsWith("/explore/runs")) return json(route, { runs: [] });
    if (path.endsWith("/semantic/semantic-models")) return json(route, path.includes(sampleId) ? models : []);
    if (path.endsWith("/semantic/metrics")) return json(route, path.includes(sampleId) ? metrics : []);
    if (path.endsWith(`/semantic/metrics/${active.metricId}`)) {
      if (method === "PUT") { active.definition = route.request().postDataJSON().definition; metricWrites++; }
      return json(route, active);
    }
    if (path.endsWith("/semantic/check")) return json(route, { ok: true, runsOn: [connectorId], partitionFilters: [], problems: [], preview: { connectorId, dialect: "DUCKDB", sql: "SELECT COUNT(DISTINCT account_id) FROM acme.daily_account_activity", groupBys: [{ kind: "METRIC_TIME", granularity: "DAY" }, { kind: "DIMENSION", semanticModelName: "daily_account_activity", name: "plan" }] } });
    if (path.endsWith("/semantic/query/group-bys")) return json(route, { groupBys: [{ kind: "METRIC_TIME", granularity: "DAY" }, { kind: "DIMENSION", semanticModelName: "daily_account_activity", name: "plan" }], dimensionFilterTypes: [], excludedSemanticModels: [] });
    if (path.endsWith("/semantic/time-spines")) return json(route, []);
    if (path.endsWith("/dashboards")) return json(route, { items: path.includes(sampleId) ? dashboards.map((dashboard) => ({ dashboardId: dashboard.id, workspaceId: sampleId, title: dashboard.title, description: null, draftHasChanges: false, latestSessionAt: null, nextScheduledAt: null, createdAt: timestamp, updatedAt: timestamp, updatedBy: null, credentialSurface: null, agentApp: null })) : [] });
    if (path.endsWith("/pages/tree")) return json(route, { workspaceId: sampleId, revision: 1, pages: [], placements: [], updatedAt: timestamp, updatedBy: { kind: "USER", id: principalId } });
    if (path.endsWith("/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: "" });
    const dashboard = dashboards.find((dashboard) => path.includes(`/dashboards/${dashboard.id}`));
    if (dashboard && path.endsWith("/sessions")) {
      if (method === "POST") {
        const id = `019e8000-0000-7000-8000-${String(nextSession++).padStart(12, "0")}`;
        const filters = active.definition.filters as Array<{ dimension: { name: string } }>;
        sessions.set(id, { dashboardId: dashboard.id, value: filters[0].dimension.name === "core_actions" ? "1071" : "1531" });
        return json(route, getSession(id), 202);
      }
      return json(route, { items: [...sessions].filter(([, stored]) => stored.dashboardId === dashboard.id).reverse().map(([id]) => ({ dashboardId: dashboard.id, dashboardSessionId: id, parameterBindings: {}, createdAt: timestamp, updatedAt: timestamp, runningQueryRuns: [] })), nextCursor: null });
    }
    const sessionId = path.match(/\/dashboard-sessions\/([^/:]+)/)?.[1];
    if (sessionId) {
      if (path.endsWith(":resolve")) {
        const filters = active.definition.filters as Array<{ dimension: { name: string } }>;
        sessions.get(sessionId)!.value = filters[0].dimension.name === "core_actions" ? "1071" : "1531";
        return json(route, { variables: {}, queryRuns: { active: `${sessionId}-${sessions.get(sessionId)!.value}` } });
      }
      if (path.endsWith("/result")) return json(route, { queryType: "SEMANTIC", reportingTimeZone: "UTC", queryRunId: path.match(/\/query-runs\/([^/]+)/)?.[1] ?? sessionId, columns: [{ name: "metric.active_accounts", logicalType: "NUMBER", nullable: false, display: { style: "NUMBER", decimals: 0 } }], rows: [[sessions.get(sessionId)!.value]] });
      if (path.endsWith("/query-runs")) return json(route, { items: [run(sessionId)], nextCursor: null });
      if (path.includes("/query-runs/")) return json(route, { ...run(sessionId), queryRunId: path.match(/\/query-runs\/([^/]+)/)?.[1] ?? sessionId });
      return json(route, getSession(sessionId));
    }
    if (dashboard && path.endsWith(`/dashboards/${dashboard.id}`)) return json(route, { workspaceId: sampleId, dashboardId: dashboard.id, definition: dashboardDefinition(dashboard.title), createdAt: timestamp, updatedAt: timestamp, updatedBy: null, credentialSurface: null, agentApp: null });
    return problem(route, 500, "FIXTURE_MISSING", `Unexpected sample fixture request: ${method} ${path}`, "Fixture missing");
  });
  return { ready: () => { state = "READY"; accessible = true; }, failReads: (failing: boolean) => { failingReads = failing; }, posts: () => posts, reads: () => reads, activityReads: () => activityReads, metricWrites: () => metricWrites, metricId: active.metricId };
}

async function openDashboard(page: Page, dashboard: typeof dashboards[number]) {
  await page.goto(`/workspaces/${sampleId}/dashboards/${dashboard.id}`);
  await expect(page.getByRole("heading", { name: dashboard.title, exact: true })).toBeVisible();
}

test("sample setup and shared metric edit resolve both dashboards on bare open", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 404, url: /\/sample-workspace$/, reason: "The initial read explicitly reports that this Organization has no sample Workspace." });
  const fixture = await install(page);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}`);
  await page.getByRole("button", { name: "Explore sample data", exact: true }).click();
  await expect(page.getByRole("status").filter({ hasText: "Setting up sample data…" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Explore sample data", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Retry setup", exact: true })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/sample-setup-in-progress.png") });
  await page.getByRole("button", { name: "Switch workspace", exact: true }).click();
  await expect(page.getByTestId(`workspace-option-${sampleId}`)).toHaveCount(0);
  await page.keyboard.press("Escape");
  fixture.ready();
  await expect(page).toHaveURL(new RegExp(`/workspaces/${sampleId}/?$`));
  await expect(page.getByText(banner, { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Connect your warehouse" })).toHaveAttribute("href", `/workspaces/${workspaceId}/connectors`);
  await expect(page.getByText("Build a dashboard of weekly active accounts by plan from the shared metrics.", { exact: true })).toBeVisible();
  expect(fixture.posts()).toBe(1);
  await expect(page.getByRole("region", { name: "Recent activity", exact: true }).getByText("Monomer", { exact: true })).toBeVisible();
  const start = page.getByRole("region", { name: "Start with a dashboard", exact: true });
  await expect(start.getByRole("listitem")).toHaveText(["Board metrics", "Revenue movements"]);
  for (const dashboard of dashboards) {
    await expect(start.getByRole("link", { name: dashboard.title })).toHaveAttribute("href", `/workspaces/${sampleId}/dashboards/${dashboard.id}`);
  }
  await expect(start.getByRole("link", { name: "All dashboards", exact: true })).toHaveAttribute("href", `/workspaces/${sampleId}/dashboards`);
  await page.screenshot({ path: evidencePath("../screenshots/sample-overview-start-with-dashboard.png"), fullPage: true });
  for (const dashboard of dashboards) {
    await openDashboard(page, dashboard);
    await expect(page.getByText("Updated by Monomer", { exact: true })).toBeVisible();
    await expect(page.getByRole("gridcell", { name: "1,531", exact: true })).toBeVisible();
  }
  await page.goto(`/workspaces/${sampleId}/metrics/${fixture.metricId}`);
  await expect(page.getByRole("heading", { name: "Used in", exact: true })).toBeVisible();
  await expect(page.getByRole("definition").filter({ hasText: "Monomer" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Dashboards" })).toContainText("2");
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  await page.getByRole("combobox", { name: "Condition dimension" }).selectOption("core_actions");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("Open a dashboard that uses this metric to see updated results.", { exact: true })).toBeVisible();
  expect(fixture.metricWrites()).toBe(1);
  for (const dashboard of dashboards) {
    await openDashboard(page, dashboard);
    await expect(page.getByRole("gridcell", { name: "1,071", exact: true })).toBeVisible();
  }
  expect(1 - 1071 / 1531).toBeGreaterThanOrEqual(0.25);
  expect(1 - 1071 / 1531).toBeLessThanOrEqual(0.35);
  await page.goto(`/workspaces/${sampleId}/connectors?connector=${connectorId}&mode=rotate`);
  const details = page.getByRole("dialog", { name: "Acme Cloud", exact: true });
  await expect(details).toBeVisible();
  await expect(details.getByText("Sample data · read-only · managed by Monomer", { exact: true })).toBeVisible();
  for (const name of ["Create connector", "Edit settings", "Replace credentials", "Delete connector"]) await expect(page.getByRole("button", { name, exact: true })).toHaveCount(0);
});

test("a setup started here offers Retry setup only once it outlasts the wait", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 404, url: /\/sample-workspace$/, reason: "The initial read explicitly reports that this Organization has no sample Workspace." });
  await page.clock.install();
  const fixture = await install(page);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}`);
  await page.getByRole("button", { name: "Explore sample data", exact: true }).click();
  await expect(page.getByRole("button", { name: "Explore sample data", exact: true })).toBeDisabled();
  await page.clock.fastForward("01:00");
  await expect(page.getByRole("button", { name: "Explore sample data", exact: true })).toBeDisabled();
  await page.clock.fastForward("00:31");
  const retry = page.getByRole("button", { name: "Retry setup", exact: true });
  await expect(retry).toBeEnabled();
  await expect(page.getByRole("status").filter({ hasText: "Setting up sample data…" })).toBeVisible();
  await retry.click();
  await expect.poll(() => fixture.posts()).toBe(2);
  await expect(page.getByRole("button", { name: "Explore sample data", exact: true })).toBeDisabled();
  fixture.ready();
  await page.clock.fastForward("00:02");
  await expect(page).toHaveURL(new RegExp(`/workspaces/${sampleId}/?$`));
});

test("a failed status read while a setup started here runs can retry at once", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 404, url: /\/sample-workspace$/, reason: "The initial read explicitly reports that this Organization has no sample Workspace." });
  consoleErrors.allow({ status: 503, url: /\/sample-workspace$/, reason: "The fixture fails one status read to show the retry it leaves." });
  const fixture = await install(page);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}`);
  await page.getByRole("button", { name: "Explore sample data", exact: true }).click();
  await expect(page.getByRole("button", { name: "Explore sample data", exact: true })).toBeDisabled();
  fixture.failReads(true);
  await expect(page.getByRole("alert").filter({ hasText: "Sample status is unavailable." })).toBeVisible();
  const retry = page.getByRole("button", { name: "Retry setup", exact: true });
  await expect(retry).toBeEnabled();
  fixture.failReads(false);
  await retry.click();
  await expect.poll(() => fixture.posts()).toBe(2);
  fixture.ready();
  await expect(page).toHaveURL(new RegExp(`/workspaces/${sampleId}/?$`));
});

test("a resumed setup stays explicit and another Admin opens the existing sample Workspace", async ({ page }) => {
  const fixture = await install(page, { initialSample: "PROVISIONING" });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}`);
  await expect(page.getByText("Setting up sample data…", { exact: true })).toBeVisible();
  expect(fixture.posts()).toBe(0);
  await page.getByRole("button", { name: "Retry setup", exact: true }).click();
  fixture.ready();
  await expect(page).toHaveURL(new RegExp(`/workspaces/${sampleId}/?$`));
  expect(fixture.posts()).toBe(1);
  await page.goto(`/workspaces/${workspaceId}`);
  await page.getByRole("button", { name: "Open sample workspace", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/workspaces/${sampleId}/?$`));
  expect(fixture.posts()).toBe(2);
});

test("Organization members do not get the sample creation action", async ({ page }) => {
  await install(page, { organizationRole: "MEMBER" });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}`);
  await expect(page.getByRole("heading", { name: "Get started", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Explore sample data", exact: true })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Open Connectors", exact: true })).toHaveAttribute("href", `/workspaces/${workspaceId}/connectors`);
});

test("the two start choices have equal weight, stack at 390px, and retain warehouse permissions", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 404, url: /\/sample-workspace$/, reason: "The initial sample read reports no provisioned Workspace." });
  await install(page, { workspaceRole: "EDITOR" });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}`);
  const choices = page.locator(".ov-start-choice");
  await expect(choices).toHaveCount(2);
  await expect(choices.first()).toContainText("Connect your warehouse");
  await expect(choices.first()).toContainText("Ask Sample User");
  await expect(choices.getByRole("link", { name: "Connect your warehouse", exact: true })).toHaveCount(0);
  await expect(choices.getByRole("button", { name: "Explore sample data", exact: true })).toBeEnabled();
  const desktop = await choices.evaluateAll((elements) => elements.map((element) => ({ width: element.getBoundingClientRect().width, height: element.getBoundingClientRect().height, top: element.getBoundingClientRect().top })));
  expect(desktop[0].width).toBe(desktop[1].width);
  expect(desktop[0].height).toBe(desktop[1].height);
  expect(desktop[0].top).toBe(desktop[1].top);
  await page.setViewportSize({ width: 390, height: 844 });
  const mobile = await choices.evaluateAll((elements) => elements.map((element) => ({ width: element.getBoundingClientRect().width, height: element.getBoundingClientRect().height, top: element.getBoundingClientRect().top })));
  expect(mobile[0].width).toBe(mobile[1].width);
  expect(mobile[0].height).toBe(mobile[1].height);
  expect(mobile[1].top).toBeGreaterThan(mobile[0].top + mobile[0].height);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("sample setup starts from 404 and polling and Activity resume when a hidden tab becomes visible", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 404, url: /\/sample-workspace$/, reason: "The initial sample read reports no provisioned Workspace." });
  await page.addInitScript(() => {
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "hidden" });
  });
  const fixture = await install(page);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}`);
  await page.getByRole("button", { name: "Explore sample data", exact: true }).click();
  await expect(page.getByText("Setting up sample data…", { exact: true })).toBeVisible();
  await expect(page.getByText("Loading recent activity…", { exact: true })).toBeVisible();
  expect(fixture.activityReads()).toBe(0);
  const hiddenReads = fixture.reads();
  await page.waitForTimeout(1200);
  expect(fixture.reads()).toBe(hiddenReads);
  fixture.ready();
  await page.evaluate(() => {
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "visible" });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect(page).toHaveURL(new RegExp(`/workspaces/${sampleId}/?$`));
  expect(fixture.reads()).toBeGreaterThan(hiddenReads);
  await expect.poll(() => fixture.activityReads()).toBeGreaterThan(0);
  expect(fixture.posts()).toBe(1);
});

test("the sample banner and warehouse link wrap within 390px", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await install(page, { initialSample: "READY" });
  fixture.ready();
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${sampleId}`);
  const notice = page.getByRole("complementary", { name: "Sample data", exact: true });
  await expect(notice).toContainText(banner);
  await expect(notice.getByRole("link", { name: "Connect your warehouse", exact: true })).toHaveAttribute("href", `/workspaces/${workspaceId}/connectors`);
  expect(await notice.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("a sample member without another Workspace in its Organization sees the banner without a warehouse link", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await install(page, { initialSample: "READY", otherWorkspace: false });
  fixture.ready();
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${sampleId}`);
  await expect(page.getByText(banner, { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Connect your warehouse", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Copy example prompt", exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
