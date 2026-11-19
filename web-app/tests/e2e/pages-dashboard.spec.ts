import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import type { DashboardDefinition } from "../../src/api/dashboard";
import type { PagesTreeResponse } from "../../src/api/pages";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const workspaceId = "019e8000-0000-7000-8000-000000000b01";
const dashboardId = "019e8000-0000-7000-8000-000000000d01";
const sessionId = "019e8000-0000-7000-8000-000000000e01";
const pageId = "019e8000-0000-7000-8000-000000000f01";
const secondPageId = "019e8000-0000-7000-8000-000000000f02";
const folderId = "019e8000-0000-7000-8000-000000000f03";
const otherFolderId = "019e8000-0000-7000-8000-000000000f04";
const base = `/workspaces/${workspaceId}`;
const runId = "019e8000-0000-7000-8000-000000000a01";
const filteredRunId = "019e8000-0000-7000-8000-000000000a02";
const timestamp = "2026-09-12T00:00:00Z";
const initialRun = { queryType: "SEMANTIC" as const, queryRunId: runId, connectorId: workspaceId, queryName: "revenue", state: "SUCCEEDED", resultAvailable: true, outputColumnNames: ["metric.revenue"], failureCode: null, failureMessage: null, createdAt: timestamp, updatedAt: timestamp };
const definition: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "Revenue dashboard" }, parameters: {},
  variables: { region: { type: "TEXT", nullable: false, initial: "North", label: "Region" } },
  queries: { revenue: { type: "SEMANTIC" as const, connectorId: workspaceId, query: { schemaVersion: 4, metrics: ["revenue"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 } } },
  widgets: { region: { type: "INPUT", variableName: "region" }, revenue: { type: "TABLE", queryName: "revenue", columns: [{ field: "metric.revenue", header: "Revenue" }] } },
  layout: { sections: [{ id: "inputs", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "region-node", widgetId: "region" } }, { id: "Revenue", height: { mode: "SCALED", scale: 1 }, root: { type: "WIDGET", id: "revenue-node", widgetId: "revenue" } }] },
};

async function fixture(page: Page, options: { editable?: boolean; linked?: boolean; listError?: boolean; empty?: boolean; conflict?: boolean; noSession?: boolean; denied?: boolean; wrongSession?: boolean; holdCreate?: boolean; createError?: boolean; extraFolder?: boolean } = {}) {
  const tree: PagesTreeResponse = { workspaceId, revision: 1, pages: [{ pageId: folderId, kind: "FOLDER", dashboardId: null, documentFormat: null, title: "Team reports", displayTitle: "Team reports", description: null, createdAt: timestamp, updatedAt: timestamp }], placements: [{ pageId: folderId, children: [] }], updatedAt: timestamp, updatedBy: { kind: "USER", id: "fixture-user" } };
  if (options.extraFolder) {
    tree.pages.push({ ...tree.pages[0], pageId: otherFolderId, title: "Other reports", displayTitle: "Other reports" });
    tree.placements.push({ pageId: otherFolderId, children: [] });
  }
  if (options.linked) for (const [id, title] of [[pageId, "Weekly revenue"], [secondPageId, "Monthly revenue"]]) {
    tree.pages.push({ pageId: id, kind: "DASHBOARD", dashboardId, documentFormat: null, title, displayTitle: title, description: null, createdAt: timestamp, updatedAt: timestamp });
    tree.placements.push({ pageId: id, children: [] });
  }
  const mutations: Record<string, unknown>[] = [];
  const resolutions: Record<string, unknown>[] = [];
  let opens = 0;
  let entries = 0;
  page.on("requestfinished", request => {
    if (new URL(request.url()).pathname.endsWith("/sessions/entry") && request.url().includes("/v2/workspaces/")) entries++;
  });
  let releaseCreate!: () => void;
  const createResponseGate = new Promise<void>(resolve => { releaseCreate = resolve; });
  await page.route("**/v2/public/**", route => route.fulfill({ status: 404, body: "" }));
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Revenue workspace",
    organizationId: "organization-1",
    role: "EXPLORER",
    user: { id: "fixture-user", email: "pages@example.test", displayName: "Pages tester" },
  });
  await page.route("**/v2/workspaces/**", async route => {
    const path = new URL(route.request().url()).pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: status >= 400 ? "application/problem+json" : "application/json", body: JSON.stringify(body) });
    const problem = (detail: string, status: number, code = "FIXTURE_ERROR") => json({ type: "https://monomer.cc/problems/fixture", instance: path, requestId: "fixture-request", traceId: "fixture-trace", retryable: false, title: "Request unavailable", detail, status, code, ...(code === "REVISION_CONFLICT" ? { current: tree } : {}) }, status);
    if (path.endsWith("/pages/access")) return json({ mode: "MEMBER" });
    if (path.endsWith("/sessions/entry")) { return json({ session: options.noSession && opens === 0 ? null : { workspaceId, dashboardId: options.wrongSession ? "other-dashboard" : dashboardId, dashboardSessionId: sessionId, definition, parameterBindings: {}, initialVariables: { region: "North" }, runningQueryRuns: [], createdAt: timestamp, updatedAt: timestamp }, initialQueryRuns: { revenue: initialRun } }); }
    if (path.endsWith("/pages/tree/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: snapshot\ndata: ${JSON.stringify(tree)}\n\n` });
    if (path.endsWith("/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: "" });
    if (path.endsWith("/pages/tree")) return json(tree);
    if (path.endsWith("/pages") && route.request().method() === "POST") {
      const body = route.request().postDataJSON(); mutations.push(body);
      if (options.holdCreate) await createResponseGate;
      if (options.createError) return problem("The dashboard could not be linked.", 403);
      if (options.conflict) { options.conflict = false; tree.revision++; return problem("Pages changed", 409, "REVISION_CONFLICT"); }
      tree.revision++;
      tree.pages.push({ pageId, kind: "DASHBOARD", dashboardId: body.dashboardId, documentFormat: null, title: body.title, displayTitle: body.title, description: body.description, createdAt: timestamp, updatedAt: timestamp });
      const children = body.parentPageId ? tree.placements.find(item => item.pageId === body.parentPageId)!.children : tree.placements;
      children.push({ pageId, children: [] });
      return json({ result: "APPLIED", snapshot: tree, change: { type: "dashboard-page-created", pageId, appliedOperationCount: null } }, 201);
    }
    if (path.endsWith(`/pages/${pageId}`) && route.request().method() === "DELETE") {
      mutations.push({ deletePage: pageId }); tree.revision++; tree.pages = tree.pages.filter(item => item.pageId !== pageId); tree.placements = tree.placements.filter(item => item.pageId !== pageId);
      for (const item of tree.placements) item.children = item.children.filter(child => child.pageId !== pageId);
      return json({ result: "APPLIED", snapshot: tree, change: { type: "page-deleted", pageId, appliedOperationCount: null } });
    }
    if (path.endsWith("/dashboards")) return options.listError ? problem("Dashboard list is temporarily unavailable.", 403) : json({ items: options.empty ? [] : [{ dashboardId, title: definition.meta.title, description: "Workspace revenue by region", updatedAt: timestamp }, ...Array.from({ length: 12 }, (_, index) => ({ dashboardId: `other-${index}`, title: `Report ${index + 1}`, description: null, updatedAt: timestamp }))] });
    if (path.endsWith(`/dashboards/${dashboardId}`)) return options.denied ? problem("Dashboard access denied.", 403) : json({ workspaceId, dashboardId, definition, createdAt: timestamp, updatedAt: timestamp });
    if (path.endsWith("/sessions")) {
      if (route.request().method() === "POST") { opens++; return json({ workspaceId, dashboardId, dashboardSessionId: sessionId, definition, parameterBindings: {}, initialVariables: { region: "North" }, runningQueryRuns: [] }, 202); }
      return json({ items: options.noSession && opens === 0 ? [] : [{ dashboardId, dashboardSessionId: sessionId, parameterBindings: {}, createdAt: timestamp, updatedAt: timestamp, runningQueryRuns: [] }], nextCursor: null });
    }
    if (path.endsWith(`/dashboard-sessions/${sessionId}`)) return json({ workspaceId, dashboardId: options.wrongSession ? "other-dashboard" : dashboardId, dashboardSessionId: sessionId, definition, parameterBindings: {}, initialVariables: { region: "North" }, runningQueryRuns: [] });
    if (path.endsWith(":resolve")) { const body = route.request().postDataJSON(); resolutions.push(body); return json({ variables: body.variables, queryRuns: { revenue: body.variables.region === "South" ? filteredRunId : runId } }); }
    if (path.endsWith(`/query-runs/${filteredRunId}/result`)) return json({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: filteredRunId, columns: [{ name: "metric.revenue", logicalType: "DECIMAL", nullable: false }], rows: [["51000"]] });
    if (path.endsWith(`/query-runs/${filteredRunId}`)) return json({ ...initialRun, queryRunId: filteredRunId });
    if (path.endsWith(`/query-runs/${runId}/result`)) return json({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: runId, columns: [{ name: "metric.revenue", logicalType: "DECIMAL", nullable: false }], rows: [["42500"]] });
    if (path.endsWith(`/query-runs/${runId}`)) return json({ queryType: "SEMANTIC" as const, queryRunId: runId, connectorId: workspaceId, queryName: "revenue", state: "SUCCEEDED", resultAvailable: true, outputColumnNames: ["metric.revenue"], failureCode: null, failureMessage: null, createdAt: timestamp, updatedAt: timestamp });
    if (path.endsWith("/query-runs")) return json({ items: [], nextCursor: null });
    if (path.includes("/query-runs/")) return problem("The linked query run is unavailable.", 404);
    return json({ items: [] });
  });
  return { mutations, resolutions, opens: () => opens, entries: () => entries, options, releaseCreate };
}

async function enter(page: Page, target = `${base}/pages`) {
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, target);
}

test.use({ viewport: { width: 1440, height: 1000 } });

test("links an existing Dashboard with independent title and placement, then removes only the Page", async ({ page }) => {
  const state = await fixture(page);
  await enter(page);
  await page.getByRole("button", { name: "Add dashboard", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("button", { name: "Add to Pages" })).toBeDisabled();
  await page.screenshot({ path: evidencePath("../screenshots/pages-dashboard/picker-overflow.png"), fullPage: true });
  await dialog.getByLabel("Search dashboards").fill("Revenue");
  await dialog.getByRole("radio").check();
  await dialog.getByLabel("Name in Pages").fill("Weekly revenue");
  await dialog.getByLabel("Destination").selectOption(folderId);
  await dialog.getByRole("button", { name: "Add to Pages" }).click();
  await expect(page).toHaveURL(new RegExp(`/pages/${pageId}\\?session=${sessionId}`));
  await expect(page.getByRole("heading", { name: "Weekly revenue", exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Region", exact: true })).toHaveValue("North");
  expect(state.mutations[0]).toMatchObject({ kind: "DASHBOARD", dashboardId, title: "Weekly revenue", parentPageId: folderId, baseRevision: 1 });
  expect(state.mutations[0]).not.toHaveProperty("uploadRef");
  await expect(page.getByRole("link", { name: "Open dashboard", exact: true })).toHaveAttribute("href", `${base}/dashboards/${dashboardId}?session=${sessionId}`);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
  await expect(page.getByRole("group", { name: "Inputs and results", exact: true })).toContainText(/^Results from /);
  await page.screenshot({ path: evidencePath("../screenshots/pages-dashboard/viewer-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: evidencePath("../screenshots/pages-dashboard/viewer-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "Actions for Weekly revenue", exact: true }).last().click();
  await page.getByRole("button", { name: /^Remove from Pages/ }).click();
  await expect(page.getByRole("dialog")).toContainText("The dashboard and its results will remain available.");
  await page.screenshot({ path: evidencePath("../screenshots/pages-dashboard/remove-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "Remove from Pages", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
  await page.goto(`${base}/dashboards/${dashboardId}?session=${sessionId}`);
  await expect(page.getByRole("textbox", { name: "Region", exact: true })).toHaveValue("North");
});

test("a member's open Pages directory survives an expired access token", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 401, url: /\/pages\/access$/, reason: "The fixture expires the member's first access token." });
  await fixture(page);
  let expired = false;
  let refreshesAfterExpiry = 0;
  await page.route("**/auth/refresh", (route) => {
    if (expired) refreshesAfterExpiry += 1;
    return route.fulfill({ json: { access_token: expired ? "renewed-token" : "fixture-token", expires_at: "2099-01-01T00:00:00Z" } });
  });
  // In an idle member tab the access poll is the first request to meet the expired token.
  await page.route("**/pages/access", (route) => {
    const authorization = route.request().headers().authorization;
    if (expired && authorization !== "Bearer renewed-token") return route.fulfill({ status: 401, json: { title: "Access token expired" } });
    return route.fallback();
  });
  await page.clock.install();
  await enter(page);
  await expect(page.getByRole("link", { name: "Team reports" }).first()).toBeVisible();

  expired = true;
  // The Pages access check polls every 15 seconds; the next poll meets the expired token.
  await page.clock.fastForward(16_000);
  await expect.poll(() => refreshesAfterExpiry).toBe(1);
  await page.clock.fastForward(16_000);
  await expect(page.getByRole("heading", { name: "Page unavailable" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Team reports" }).first()).toBeVisible();
});

test("remounts linked views with initial variables while retaining shared Session identity", async ({ page }) => {
  const state = await fixture(page, { linked: true });
  await enter(page, `${base}/pages/${pageId}?session=${sessionId}`);
  const input = page.getByRole("textbox", { name: "Region", exact: true });
  await expect(input).toHaveValue("North");
  await input.fill("South");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect.poll(() => state.resolutions.length).toBe(1);
  await page.getByRole("button", { name: "Open sidebar", exact: true }).click();
  await page.getByRole("link", { name: "Monthly revenue", exact: true }).click();
  await expect(input).toHaveValue("North");
  await expect.poll(() => state.resolutions.length).toBe(1);
  expect(state.resolutions.map(item => item.variables)).toEqual([{ region: "South" }]);
  expect(state.resolutions.every(item => !Object.hasOwn(item, "pageId"))).toBe(true);
  await page.getByRole("link", { name: "Weekly revenue", exact: true }).click();
  await expect(input).toHaveValue("North");
  await expect.poll(() => state.entries()).toBe(3);
});

test("validates search, preserves query-run deep links, and rejects a foreign Session", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 404, url: /\/query-runs\/[^/]+$/, reason: "The deep link names a run the Session does not have." });
  const state = await fixture(page, { linked: true });
  await enter(page, `${base}/pages/${pageId}?session=${sessionId}&run=${secondPageId}`);
  await expect(page.getByRole("link", { name: "Open dashboard", exact: true })).toHaveAttribute("href", `${base}/dashboards/${dashboardId}?session=${sessionId}&run=${secondPageId}`);
  state.options.wrongSession = true;
  await page.reload();
  await expect(page.getByText("These results belong to a different dashboard.")).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toHaveCount(0);
  state.options.wrongSession = false;
  await page.goto(`${base}/pages/${pageId}?session=invalid&run=invalid`);
  await expect(page.getByRole("link", { name: "Open dashboard", exact: true })).not.toHaveAttribute("href", /invalid/);
});

test("shows empty and failed picker states, and recovers a revision conflict", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/dashboards$/, reason: "The fixture fails the Dashboard picker list." });
  consoleErrors.allow({ status: 409, url: /\/pages$/, reason: "The fixture rejects the first Page create with a revision conflict." });
  const state = await fixture(page, { empty: true });
  await enter(page);
  await page.getByRole("button", { name: "Add dashboard", exact: true }).click();
  await expect(page.getByText("No dashboards yet.")).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/pages-dashboard/picker-empty.png"), fullPage: true });
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  state.options.empty = false; state.options.listError = true;
  await page.getByRole("button", { name: "Add dashboard", exact: true }).click();
  await expect(page.getByText("Dashboard list is temporarily unavailable.")).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/pages-dashboard/picker-error.png"), fullPage: true });
  state.options.listError = false; state.options.conflict = true;
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  await page.getByRole("radio").first().check();
  await page.getByRole("button", { name: "Add to Pages", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Pages changed in another session.");
  await page.getByRole("button", { name: "Add to Pages", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Region", exact: true })).toHaveValue("North");
  expect(state.mutations.map(item => item.baseRevision)).toEqual([1, 2]);
});

test("guides the publisher to open a Session or schedule without automatic execution", async ({ page }) => {
  const state = await fixture(page, { linked: true, noSession: true });
  await enter(page, `${base}/pages/${pageId}`);
  await expect(page.getByText("No results yet", { exact: true })).toBeVisible();
  await expect(page.getByText("Open the dashboard to run it. Its latest results will appear here.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Open dashboard", exact: true }).last()).toBeVisible();
  expect(state.opens()).toBe(0);
  await page.screenshot({ path: evidencePath("screenshots/publisher-page-preparing.png"), fullPage: true });
});


for (const reusedFolder of [false, true]) for (const createError of [false, true]) {
  test(`late create ${createError ? "failure" : "success"} cannot redirect after ${reusedFolder ? "Folder change" : "unmount"}`, async ({ page, consoleErrors }) => {
    if (createError) consoleErrors.allow({ status: 403, url: /\/pages$/, reason: "The fixture fails the held Page create." });
    const state = await fixture(page, { holdCreate: true, createError, extraFolder: reusedFolder });
    await enter(page);
    const expand = page.getByRole("button", { name: "Open sidebar", exact: true });
    await expect(expand).toBeVisible();
    await expand.click();
    await page.getByRole("link", { name: "Team reports", exact: true }).last().click();
    await expect(page.getByRole("heading", { name: "Team reports", exact: true })).toBeVisible();
    if (reusedFolder) {
      await page.getByRole("link", { name: "Other reports", exact: true }).click();
      await expect(page.getByRole("heading", { name: "Other reports", exact: true })).toBeVisible();
    }
    const returnUrl = reusedFolder ? `${base}/pages/${folderId}` : `${base}/pages`;
    await page.getByRole("button", { name: "Add dashboard", exact: true }).click();
    await page.getByRole("radio").first().check();
    await page.getByRole("button", { name: "Add to Pages", exact: true }).click();
    await expect.poll(() => state.mutations.length).toBe(1);
    await page.goBack();
    await expect(page).toHaveURL(returnUrl);
    await expect(page.getByRole("heading", { name: reusedFolder ? "Team reports" : "All pages", exact: true })).toBeVisible();
    const response = page.waitForResponse(result => result.url().endsWith(`/workspaces/${workspaceId}/pages`) && result.request().method() === "POST");
    state.releaseCreate();
    await (await response).finished();
    await page.getByRole("button", { name: "Add dashboard", exact: true }).click();
    await expect(page.getByRole("dialog")).toBeVisible();
    await expect(page).toHaveURL(returnUrl);
    await expect(page.getByRole("dialog").getByRole("alert")).toHaveCount(0);
    await expect(page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true })).toBeEnabled();
    if (!createError) {
      await page.getByRole("button", { name: "Cancel", exact: true }).click();
      await page.getByRole("link", { name: reusedFolder ? "Other reports" : "Team reports", exact: true }).last().click();
      await expect(page.getByRole("link", { name: /Revenue dashboard/ }).last()).toBeVisible();
    }
  });
}


test("member entry renders its authorized snapshot while detail loads without fetching closed history", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/dashboard-sessions\/[^/]+$/, reason: "The fixture denies the closed Session history read." });
  await fixture(page, { linked: true });
  const emptySessionId = "019e8000-0000-7000-8000-000000000e02";
  await page.route("**/query-runs?*", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [{ queryType: "SEMANTIC" as const, queryRunId: runId, connectorId: workspaceId, queryName: "revenue", state: "SUCCEEDED", resultAvailable: true, outputColumnNames: ["metric.revenue"], failureCode: null, failureMessage: null, createdAt: timestamp, updatedAt: timestamp }], nextCursor: null }) }));
  await page.route("**/sessions?*", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [
    { dashboardId, dashboardSessionId: sessionId, parameterBindings: {}, createdAt: timestamp, updatedAt: timestamp, runningQueryRuns: [], lastQueryRunAt: timestamp },
    { dashboardId, dashboardSessionId: emptySessionId, parameterBindings: {}, createdAt: "2026-09-11T00:00:00Z", updatedAt: timestamp, runningQueryRuns: [], lastQueryRunAt: null },
  ], nextCursor: null }) }));
  await page.route("**/v2/public/**", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(new URL(route.request().url()).pathname.endsWith("/tree")
    ? { workspaceName: "Public workspace", revision: 1, pages: [{ pageId, kind: "DASHBOARD", displayTitle: "Public-only title" }], placements: [{ pageId, children: [] }] }
    : { session: null, initialQueryRuns: {} }) }));
  let releaseDefinition!: () => void;
  const definitionGate = new Promise<void>(resolve => { releaseDefinition = resolve; });
  await page.route("**/events", async route => { await definitionGate; await route.fallback(); });
  await page.route(`**/dashboards/${dashboardId}`, async route => {
    await definitionGate;
    await route.fallback();
  });
  const reads: string[] = [];
  page.on("request", request => { if (request.method() === "GET") reads.push(new URL(request.url()).pathname); });
  await enter(page, `${base}/pages/${pageId}?session=${sessionId}`);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
  await expect(page.getByText("Public-only title")).toHaveCount(0);
  expect(reads.filter(path => path.endsWith(`/dashboard-sessions/${sessionId}`))).toHaveLength(0);
  expect(reads.filter(path => path.endsWith(`/query-runs/${runId}`))).toHaveLength(0);
  expect(reads.filter(path => path.endsWith("/sessions") || path.endsWith("/query-runs"))).toHaveLength(0);
  await page.getByRole("button", { name: "Open history", exact: true }).click();
  await expect.poll(() => reads.some(path => path.endsWith("/query-runs"))).toBe(true);
  const history = page.getByRole("dialog");
  await expect(history.locator("footer").getByText("1 loaded runs", { exact: true })).toBeVisible();
  await expect(history.getByRole("button", { name: "Query revenue", exact: true })).toBeVisible();
  await expect(history.getByRole("button", { name: /^Refresh / })).toHaveCount(0);
  await expect(history.getByRole("button", { name: "Back to history", exact: true })).not.toContainText("No runs yet");
  await history.getByRole("button", { name: "Back to history", exact: true }).click();
  await expect(history.getByRole("button", { name: /Opened Sep 11, 2026/ })).toContainText("No runs yet");
  await expect(history.getByRole("button", { name: /Opened Sep 12, 2026/ })).not.toContainText("No runs yet");
  await page.route(`**/dashboard-sessions/${sessionId}`, route => route.fulfill({ status: 403, contentType: "application/problem+json", body: JSON.stringify({ status: 403, title: "Session access denied" }) }));
  releaseDefinition();
  await expect.poll(() => reads.some(path => path.endsWith(`/dashboard-sessions/${sessionId}`))).toBe(true);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toHaveCount(0);
});


test("prepared member entry skips metadata reads and filter targets keep the normal lookup", async ({ page }) => {
  const state = await fixture(page, { linked: true });
  const reads: string[] = [];
  page.on("request", request => { if (request.method() === "GET") reads.push(new URL(request.url()).pathname); });
  await enter(page, `${base}/pages/${pageId}`);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
  expect(reads.some(path => path.endsWith(`/query-runs/${runId}/result`))).toBe(true);
  expect(reads.filter(path => path.endsWith(`/query-runs/${runId}`))).toHaveLength(0);
  expect(state.resolutions).toHaveLength(0);
  await page.clock.install();
  await page.clock.fastForward(6_000);
  expect(reads.filter(path => path.endsWith(`/query-runs/${runId}`))).toHaveLength(0);
  await page.getByRole("textbox", { name: /Region/ }).fill("South");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect(page.getByRole("gridcell", { name: "51,000", exact: true })).toBeVisible();
  expect(reads.filter(path => path.endsWith(`/query-runs/${filteredRunId}`))).toHaveLength(1);
  expect(state.resolutions).toHaveLength(1);
});

test("successful member entry metadata never authorizes a denied result", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/query-runs\/[^/]+\/result$/, reason: "The fixture denies the result read." });
  const state = await fixture(page, { linked: true });
  const reads: string[] = [];
  page.on("request", request => { if (request.method() === "GET") reads.push(new URL(request.url()).pathname); });
  await page.route("**/query-runs/*/result", route => route.fulfill({ status: 403, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/fixture", status: 403, title: "Result access denied", detail: "Result access denied", instance: "/fixture", requestId: "fixture", traceId: "fixture", retryable: false, code: "FORBIDDEN" }) }));
  await enter(page, `${base}/pages/${pageId}`);
  await expect(page.getByText(/You do not have access to this result/).first()).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toHaveCount(0);
  expect(reads.filter(path => path.endsWith(`/query-runs/${runId}`))).toHaveLength(0);
  expect(state.resolutions).toHaveLength(0);
});


test("member filter result denial erases the previous ready bundle", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/query-runs\/[^/]+\/result$/, reason: "The fixture denies the filtered result read." });
  await fixture(page, { linked: true });
  await enter(page, `${base}/pages/${pageId}`);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
  await page.route(`**/query-runs/${filteredRunId}/result`, route => route.fulfill({ status: 403, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/fixture", status: 403, title: "Result access denied", detail: "Result access denied", instance: "/fixture", requestId: "fixture", traceId: "fixture", retryable: false, code: "FORBIDDEN" }) }));
  await page.getByRole("textbox", { name: /Region/ }).fill("South");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect(page.getByText(/You do not have access to this result/).first()).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toHaveCount(0);
});

test("Search separates failures from no matches and follows the owner's retryable signal while Go to works", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 400, url: /\/search\?/, reason: "The Search fixture exercises both owner retryable values on its declared 400 Problem response." });
  await fixture(page, { linked: true });
  let retryable = false;
  let recovered = false;
  const searches: string[] = [];
  await page.route(`**/v2/workspaces/${workspaceId}/search?**`, route => {
    const url = new URL(route.request().url());
    const query = url.searchParams.get("query") ?? "";
    searches.push(query);
    if (recovered) return route.fulfill({ status: 200, json: { dashboards: [], metrics: [], semanticModels: [], tables: [] } });
    const status = 400;
    return route.fulfill({ status, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/fixture", title: "Search unavailable", detail: "Search results are unavailable.", status, instance: url.pathname, code: "FIXTURE_ERROR", requestId: "search-error-fixture", traceId: "search-error-fixture", retryable }) });
  });
  await enter(page, `${base}/pages/${pageId}`);
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
  await page.getByTestId("desktop-sidebar").getByRole("button", { name: "Search", exact: true }).click();
  const palette = page.getByRole("dialog", { name: "Search" });
  const input = palette.getByRole("combobox", { name: "Search or go to" });
  await input.fill("unmatchedresource");
  await expect(palette.getByRole("alert")).toHaveText("Search couldn't load. Go to still works.");
  await expect(palette.getByText("No matches.", { exact: true })).toHaveCount(0);
  await expect(palette.getByRole("button", { name: "Retry", exact: true })).toHaveCount(0);
  await expect(palette.getByText(/Try again in a moment/)).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/pages-dashboard/search-permanent-error.png") });

  retryable = true;
  await input.fill("explore");
  const retry = palette.getByRole("button", { name: "Retry", exact: true });
  await expect(retry).toBeVisible();
  await expect(palette.getByRole("alert")).toContainText("Search couldn't load. Go to still works.");
  await expect(palette.getByText("No matches.", { exact: true })).toHaveCount(0);
  const explore = palette.getByRole("group", { name: "Go to" }).getByRole("option", { name: /^Explore/ });
  await expect(explore).toContainText("Run Metrics and SQL queries");
  await expect(palette.getByText("Semantic queries", { exact: true })).toHaveCount(0);
  recovered = true;
  await retry.click();
  await expect(palette.getByRole("alert")).toHaveCount(0);
  expect(searches).toEqual(["unmatchedresource", "explore", "explore"]);
  await input.fill("unmatchedresource");
  await expect(palette.getByText("No matches.", { exact: true })).toBeVisible();
  await expect(palette.getByRole("alert")).toHaveCount(0);

  recovered = false;
  await input.fill("pages");
  await expect(palette.getByRole("alert")).toContainText("Search couldn't load. Go to still works.");
  await palette.getByRole("group", { name: "Go to" }).getByRole("option", { name: /^Pages/ }).click();
  await expect(page).toHaveURL(`${base}/pages`);
  await expect(page.getByRole("heading", { name: "All pages", exact: true })).toBeVisible();
});

test("a permanent Page entry failure offers Back to Pages without transient copy or Retry", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 503, url: /\/sessions\/entry$/, reason: "The Page entry fixture returns a permanent failure despite its 503 status." });
  const state = await fixture(page, { linked: true });
  await page.route(`**/v2/workspaces/${workspaceId}/dashboards/${dashboardId}/sessions/entry`, route => route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/fixture", title: "Page results unavailable", detail: "These dashboard results are unavailable.", status: 503, instance: new URL(route.request().url()).pathname, code: "FIXTURE_ERROR", requestId: "page-permanent-error-fixture", traceId: "page-permanent-error-fixture", retryable: false }) }));
  await enter(page, `${base}/pages/${pageId}`);
  const error = page.getByTestId("dashboard-viewer-scroll").getByRole("alert");
  await expect(error).toContainText("The dashboard results could not be loaded.");
  await expect(error).toContainText("These dashboard results are unavailable.");
  await expect(error.getByRole("button", { name: "Retry", exact: true })).toHaveCount(0);
  await expect(error.getByText(/Try again in a moment|The analysis entry could not be loaded/)).toHaveCount(0);
  const back = error.getByRole("link", { name: "Back to Pages", exact: true });
  await expect(back).toHaveAttribute("href", `${base}/pages`);
  expect(state.opens()).toBe(0);
  await page.screenshot({ path: evidencePath("screenshots/pages-dashboard/permanent-entry-error.png"), fullPage: true });
  await back.click();
  await expect(page).toHaveURL(`${base}/pages`);
  await expect(page.getByRole("heading", { name: "All pages", exact: true })).toBeVisible();
  const linkedPage = page.getByRole("list", { name: "All pages contents" }).getByRole("link", { name: /^Weekly revenue Dashboard/ });
  await expect(linkedPage).toBeVisible();
  await expect(linkedPage).toHaveAttribute("href", `${base}/pages/${pageId}`);
});

test("a retryable Page entry failure offers Retry and restores the prepared results", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 409, url: /\/sessions\/entry$/, reason: "The Page entry fixture returns a retryable failure despite its 409 status." });
  const state = await fixture(page, { linked: true });
  let recovered = false;
  let entries = 0;
  await page.route(`**/v2/workspaces/${workspaceId}/dashboards/${dashboardId}/sessions/entry`, route => {
    entries++;
    if (recovered) return route.fallback();
    return route.fulfill({ status: 409, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/fixture", title: "Page results unavailable", detail: "These dashboard results are temporarily unavailable.", status: 409, instance: new URL(route.request().url()).pathname, code: "FIXTURE_ERROR", requestId: "page-retryable-error-fixture", traceId: "page-retryable-error-fixture", retryable: true }) });
  });
  await enter(page, `${base}/pages/${pageId}`);
  const error = page.getByTestId("dashboard-viewer-scroll").getByRole("alert");
  await expect(error).toContainText("The dashboard results could not be loaded.");
  await expect(error).toContainText("These dashboard results are temporarily unavailable.");
  await expect(error.getByRole("link", { name: "Back to Pages", exact: true })).toHaveAttribute("href", `${base}/pages`);
  const retry = error.getByRole("button", { name: "Retry", exact: true });
  await expect(retry).toBeVisible();
  expect(state.opens()).toBe(0);
  await page.screenshot({ path: evidencePath("screenshots/pages-dashboard/retryable-entry-error.png"), fullPage: true });
  const entriesBeforeRetry = entries;
  recovered = true;
  await retry.click();
  await expect(page.getByRole("gridcell", { name: "42,500", exact: true })).toBeVisible();
  await expect(page.getByTestId("dashboard-viewer-scroll").getByRole("alert")).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: "Region", exact: true })).toHaveValue("North");
  expect(entries).toBeGreaterThan(entriesBeforeRetry);
  expect(state.opens()).toBe(0);
});
