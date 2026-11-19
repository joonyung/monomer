import type { Page } from "@playwright/test";
import type { DashboardDefinition } from "../../src/api/dashboard";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { json, mockSignedInWorkspace, problem } from "./support/session";
import { evidencePath } from "./evidence";
import { expect, test } from "./support/test";

const workspaceId = "019e8000-0000-7000-8000-000000000138";
const dashboardId = "019e8000-0000-7000-8000-000000001138";
const sessionId = "019e8000-0000-7000-8000-000000002138";
const changeId = "019e8000-0000-7000-8000-000000003138";
const olderChangeId = "019e8000-0000-7000-8000-000000004138";
const pageId = "019e8000-0000-7000-8000-000000005138";
const unplacedPageId = "019e8000-0000-7000-8000-000000006138";
const root = `/v2/workspaces/${workspaceId}`;
const detailPath = `/workspaces/${workspaceId}/dashboards/${dashboardId}`;
const at = "2026-10-08T02:00:00Z";
const actor = { id: "dashboard-editor", displayName: "Sam Kim" };
const agentApp = { clientId: "https://claude.ai", name: "Claude", verifiedHost: "claude.ai" };
const definition: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "Current revenue", description: "Definition lifecycle" }, parameters: {}, variables: {}, queries: {}, widgets: {}, layout: { sections: [] },
  schedules: { daily: { cron: "0 9 * * *", timeZone: "UTC", parameters: {} }, weekly: { cron: "0 9 * * 1", timeZone: "UTC", parameters: {} } },
};

async function install(page: Page, options: { deleted?: boolean; pagesFailure?: boolean; restoreFailure?: boolean; loseDeleteResponse?: boolean; role?: "EXPLORER" | "VIEWER"; noSessions?: boolean } = {}) {
  await mockSignedInWorkspace(page, { workspaceId, workspaceName: "Dashboard history", organizationId: "organization-138", role: options.role ?? "EXPLORER", user: { id: actor.id, displayName: actor.displayName, email: "sam@example.test" } });
  await page.route("**/v2/graphql", async route => {
    const body = route.request().postDataJSON() as { query: string };
    if (!body.query.includes("AccessibleWorkspaces")) return route.fallback();
    return json(route, { data: { accessibleWorkspaces: [{ id: workspaceId, organizationId: "organization-138", name: "Dashboard history", publicPages: true, reportingTimeZone: "UTC", role: options.role ?? "EXPLORER" }] } });
  });
  await page.addInitScript(() => {
    const original = window.fetch.bind(window);
    window.fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (!url.endsWith("/draft/events") && !url.includes("/dashboard-sessions/") || !url.endsWith("/events")) return original(input, init);
      const state = window as unknown as { dashboardStreams: number };
      state.dashboardStreams = (state.dashboardStreams ?? 0) + 1;
      return new Response(new ReadableStream<Uint8Array>({ start(controller) {
        let ended = false;
        const close = () => { if (ended) return; ended = true; window.removeEventListener("dashboard-fixture-deleted", close); controller.close(); };
        window.addEventListener("dashboard-fixture-deleted", close);
        init?.signal?.addEventListener("abort", close, { once: true });
      } }), { headers: { "Content-Type": "text/event-stream" } });
    };
  });
  let deleted = options.deleted ?? false;
  let pagesFail = options.pagesFailure ?? false;
  let droppedResponse = false;
  let treeReads = 0;
  const writes: { method: string; path: string; key?: string }[] = [];
  const unexpected: string[] = [];
  const current = () => ({ workspaceId, dashboardId, definition: options.noSessions ? { ...definition, parameters: { region: { type: "TEXT", nullable: false, label: "Region" } } } : definition, createdAt: at, updatedAt: at, updatedBy: actor, credentialSurface: "AGENT", agentApp });
  await page.route("**/v2/workspaces/**", async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    if (path === `${root}/pages/tree`) {
      treeReads++;
      if (pagesFail) return problem(route, 503, "SERVICE_UNAVAILABLE", "Pages are temporarily unavailable.", "Unavailable");
      return json(route, { workspaceId, revision: deleted ? 2 : 1, pages: deleted ? [] : [
        { pageId: "folder", kind: "FOLDER", dashboardId: null, displayTitle: "Sales", title: "Sales", documentFormat: null, description: null, createdAt: at, updatedAt: at },
        { pageId, kind: "DASHBOARD", dashboardId, displayTitle: "Weekly review", title: "Weekly review", documentFormat: null, description: null, createdAt: at, updatedAt: at },
        { pageId: unplacedPageId, kind: "DASHBOARD", dashboardId, displayTitle: "Unplaced review", title: "Unplaced review", documentFormat: null, description: null, createdAt: at, updatedAt: at },
      ], placements: deleted ? [] : [{ pageId: "folder", children: [{ pageId, children: [] }] }], updatedAt: at, updatedBy: { kind: "USER", id: actor.id } });
    }
    if (path === `${root}/dashboards` && method === "GET") return json(route, { items: deleted ? [] : [{ dashboardId, workspaceId, title: definition.meta.title, description: definition.meta.description, updatedAt: at, updatedBy: actor, credentialSurface: "AGENT", agentApp, latestResultsAt: at, schedules: [], draftHasChanges: false, linkedPageCount: 2 }] });
    if (path === `${root}/dashboards/${dashboardId}/history`) {
      const change = { changeId, action: deleted ? "DELETED" : "APPLIED", restorable: deleted, definition, occurredAt: at, actor, credentialSurface: "AGENT", agentApp };
      return json(route, { definitionId: dashboardId, name: definition.meta.title, deleted, changes: [change, { ...change, changeId: olderChangeId, action: "CREATED", restorable: deleted, definition: { ...definition, meta: { title: "Original revenue" } }, actor: null, credentialSurface: null, agentApp: null }], nextCursor: null });
    }
    if (path.endsWith("/restore") && method === "POST") {
      writes.push({ method, path, key: request.headers()["idempotency-key"] });
      if (options.restoreFailure) return problem(route, 409, "CONFLICT", "This version cannot be restored now.", "Conflict");
      deleted = false;
      return json(route, current());
    }
    if (path === `${root}/dashboards/${dashboardId}`) {
      if (method === "DELETE") {
        writes.push({ method, path });
        if (deleted) return problem(route, 404, "NOT_FOUND", "Dashboard was not found.", "Not Found");
        deleted = true;
        if (options.loseDeleteResponse && !droppedResponse) { droppedResponse = true; return route.abort("failed"); }
        return route.fulfill({ status: 204 });
      }
      return deleted ? problem(route, 404, "NOT_FOUND", "Dashboard was not found.", "Not Found") : json(route, current());
    }
    if (deleted && (path.includes("/dashboard-sessions/") || path.includes(`/dashboards/${dashboardId}/`))) return problem(route, 404, "NOT_FOUND", "Dashboard was not found.", "Not Found");
    if (path === `${root}/dashboards/${dashboardId}/sessions`) return json(route, { items: options.noSessions ? [] : [{ dashboardId, dashboardSessionId: sessionId, createdAt: at, updatedAt: at, lastQueryRunAt: null, parameterBindings: {}, runningQueryRuns: [] }], nextCursor: null });
    if (path === `${root}/dashboards/${dashboardId}/draft`) return json(route, { ...current(), variables: {}, parameterBindings: {}, authoringRevision: 1, targetQueryRuns: {}, readyResults: {}, recentQueryRuns: [] });
    if (path === `${root}/dashboard-sessions/${sessionId}`) return json(route, { ...current(), dashboardSessionId: sessionId, definition: { ...definition, meta: { title: "Past revenue" }, schedules: {} }, parameterBindings: {}, initialVariables: {}, runningQueryRuns: [] });
    if (path === `${root}/dashboard-sessions/${sessionId}:resolve`) return json(route, { variables: {}, queryRuns: {} });
    if (path === `${root}/dashboard-sessions/${sessionId}/query-runs`) return json(route, { items: [], nextCursor: null });
    if (path === `${root}/dashboards/${dashboardId}/schedules`) return json(route, { schedules: [], preview: [], previewError: null });
    if (path === `${root}/dashboards/${dashboardId}/occurrences`) return json(route, { items: [], nextCursor: null });
    unexpected.push(`${method} ${path}`);
    return problem(route, 404, "NOT_FOUND", `No fixture for ${path}.`, "Not Found");
  });
  return { writes, unexpected, treeReads: () => treeReads, allowPages: () => { pagesFail = false; }, markDeleted: async () => { deleted = true; await page.evaluate(() => window.dispatchEvent(new Event("dashboard-fixture-deleted"))); } };
}

async function openDelete(page: Page) {
  await page.getByRole("button", { name: "More actions", exact: true }).click();
  await page.getByRole("menuitem", { name: "Delete dashboard", exact: true }).click();
  return page.getByRole("alertdialog");
}

test("Explorer deletes with current impact, reads deleted History and restores the original id", async ({ page }) => {
  const fixture = await install(page);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, detailPath);
  await expect(page.getByRole("heading", { name: "Current revenue", exact: true })).toBeVisible();
  expect(fixture.treeReads()).toBe(0);
  const dialog = await openDelete(page);
  await expect(dialog).toHaveAccessibleName("Delete “Current revenue”?");
  await expect(dialog).toContainText("Its 2 schedules stop.");
  await expect(dialog).toContainText("This workspace’s pages are public");
  await expect(dialog).toContainText("2 linked pages are deleted:");
  await expect(dialog.getByRole("link", { name: "Sales / Weekly review" })).toHaveAttribute("href", `/workspaces/${workspaceId}/pages/${pageId}`);
  await expect(dialog.getByRole("link", { name: "Unplaced review", exact: true })).toHaveAttribute("href", `/workspaces/${workspaceId}/pages/${unplacedPageId}`);
  const remove = dialog.getByRole("button", { name: "Delete dashboard", exact: true });
  await dialog.getByRole("textbox").fill("Past revenue"); await expect(remove).toBeDisabled();
  await dialog.getByRole("textbox").fill("Current revenue"); await expect(remove).toBeEnabled();
  await page.screenshot({ path: evidencePath("../screenshots/joo-138-dashboard-delete.png") });
  await remove.click();
  await expect(page).toHaveURL(`/workspaces/${workspaceId}/dashboards`);
  await expect(page.getByRole("link", { name: /Current revenue/ })).toHaveCount(0);
  expect(fixture.writes).toHaveLength(1);
  expect(fixture.writes[0].key).toBeUndefined();
  await page.goto(`${detailPath}/history`);
  await expect(page.getByRole("heading", { name: "Current revenue history", exact: true })).toBeVisible();
  await expect(page.getByRole("status")).toContainText("Pages and past results don’t come back.");
  await expect(page.getByText("Person not recorded", { exact: true })).toBeVisible();
  await expect(page.getByText("via Claude", { exact: false }).first()).toBeVisible();
  await page.getByText("View definition", { exact: true }).first().click();
  await expect(page.locator("pre").first()).toContainText('"schedules"');
  await expect(page.getByRole("button", { name: "Restore this version", exact: true })).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/joo-138-dashboard-history.png") });
  await page.getByRole("button", { name: "Restore", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`${detailPath}(?:\\?|$)`));
  expect(fixture.writes.at(-1)?.key).toMatch(/^dashboard-restore-/);
  expect(fixture.unexpected).toEqual([]);
});

test("linked Page query failure blocks deletion until an explicit successful retry", async ({ page, consoleErrors }) => {
  const fixture = await install(page, { pagesFailure: true });
  consoleErrors.allow({ status: 503, url: /\/pages\/tree$/, reason: "The deletion impact fixture fails the linked Pages query once." });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, detailPath);
  const dialog = await openDelete(page);
  await expect(dialog).toContainText("Couldn’t load linked pages.");
  await dialog.getByRole("textbox").fill("Current revenue");
  await expect(dialog.getByRole("button", { name: "Delete dashboard", exact: true })).toBeDisabled();
  fixture.allowPages();
  await dialog.getByRole("button", { name: "Retry linked pages" }).click();
  await expect(dialog.getByRole("link", { name: "Sales / Weekly review" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Delete dashboard", exact: true })).toBeEnabled();
});

for (const surface of ["Viewer", "Builder"] as const) {
  test(`an open ${surface} verifies current deletion and stops stream reconnection`, async ({ page, consoleErrors }) => {
    await page.clock.install();
    const fixture = await install(page);
    consoleErrors.allow({ status: 404, url: /\/v2\/workspaces\/.*\/(dashboards|dashboard-sessions)\//, reason: "The Dashboard is removed remotely while its Viewer or Builder is open." });
    await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `${detailPath}${surface === "Builder" ? "/builder" : `?session=${sessionId}`}`);
    await expect.poll(() => page.evaluate(() => (window as unknown as { dashboardStreams: number }).dashboardStreams)).toBe(1);
    await fixture.markDeleted();
    await expect(page.getByText("This dashboard was deleted.", { exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "View history", exact: true })).toHaveAttribute("href", `${detailPath}/history`);
    const streams = await page.evaluate(() => (window as unknown as { dashboardStreams: number }).dashboardStreams);
    await page.clock.fastForward(4_500);
    expect(await page.evaluate(() => (window as unknown as { dashboardStreams: number }).dashboardStreams)).toBe(streams);
  });
}

test("live History remains read only and renders Applied independently of Created", async ({ page }) => {
  await install(page);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `${detailPath}/history`);
  await expect(page.getByRole("heading", { name: "Applied", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Created", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: /^Restore/ })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "View current dashboard" })).toHaveAttribute("href", detailPath);
});

test("restore failure keeps History and reuses the Idempotency-Key on retry", async ({ page, consoleErrors }) => {
  const fixture = await install(page, { deleted: true, restoreFailure: true });
  consoleErrors.allow({ status: 409, url: /\/history\/[^/]+\/restore$/, reason: "The fixture rejects restore to verify the retry key." });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `${detailPath}/history`);
  const restore = page.getByRole("button", { name: "Restore", exact: true });
  await restore.click(); await expect(page.getByRole("alert")).toContainText("This version cannot be restored now.");
  await restore.click(); await expect.poll(() => fixture.writes.length).toBe(2);
  expect(fixture.writes[0].key).toBe(fixture.writes[1].key);
});

test("a lost delete response followed by 404 routes back as already deleted", async ({ page, consoleErrors }) => {
  await install(page, { loseDeleteResponse: true });
  consoleErrors.allow({ message: /Failed to load resource: net::ERR_FAILED/, url: /\/dashboards\/[^/]+$/, reason: "The fixture commits deletion but loses the response." });
  consoleErrors.allow({ status: 404, url: /\/dashboards\/[^/]+$/, reason: "A repeat deletion after the lost response returns Not Found." });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, detailPath);
  const dialog = await openDelete(page);
  await dialog.getByRole("textbox").fill("Current revenue");
  await dialog.getByRole("button", { name: "Delete dashboard", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  await dialog.getByRole("button", { name: "Delete dashboard", exact: true }).click();
  await expect(dialog.getByRole("alert")).toHaveText("This dashboard is already deleted.");
  await dialog.getByRole("button", { name: "Back to Dashboards" }).click();
  await expect(page).toHaveURL(`/workspaces/${workspaceId}/dashboards`);
});

test("a Viewer without a selected view detects current deletion on window return", async ({ page, consoleErrors }) => {
  const fixture = await install(page, { noSessions: true });
  consoleErrors.allow({ status: 404, url: /\/dashboards\/[^/]+(?:\/sessions)?(?:\?|$)/, reason: "The Dashboard is deleted while the Viewer has not selected a view." });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, detailPath);
  await expect(page.getByRole("heading", { name: "Choose inputs", exact: true })).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { dashboardStreams?: number }).dashboardStreams ?? 0)).toBe(0);
  await fixture.markDeleted();
  await page.evaluate(() => {
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" }); window.dispatchEvent(new Event("visibilitychange"));
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" }); window.dispatchEvent(new Event("visibilitychange"));
  });
  await expect(page.getByText("This dashboard was deleted.", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "View history", exact: true })).toHaveAttribute("href", `${detailPath}/history`);
});
