import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { mockSignedInWorkspace } from "./support/session";
import type { DashboardDefinition } from "../../src/api/dashboard";
import { evidencePath } from "./evidence";

const workspaceId = "019e8000-0000-7000-8000-000000000a01";
const dashboardId = "019e8000-0000-7000-8000-000000000d01";
const sessionId = "019e8000-0000-7000-8000-000000000e01";
const scheduledAt = "2026-09-20T00:00:00Z";
const earlierScheduledAt = "2026-09-18T00:00:00Z";
const initialRuns = { sales: "019e8000-0000-7000-8000-000000000f01", costs: "019e8000-0000-7000-8000-000000000f02" };
const definition: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "Daily operations", description: "Sales and costs prepared each morning" }, parameters: {},
  variables: { country: { type: "TEXT", nullable: false, initial: "US", label: "Country" } },
  schedules: { daily_report: { cron: "0 9 * * *", timeZone: "Asia/Seoul", parameters: {} } },
  queries: Object.fromEntries(["sales", "costs"].map(name => [name, { type: "SEMANTIC" as const, connectorId: workspaceId, query: { schemaVersion: 4, metrics: ["value"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 } }])),
  widgets: {
    country: { type: "INPUT", variableName: "country" },
    sales: { type: "TABLE", queryName: "sales", columns: [{ field: "metric.value", header: "Sales" }] },
    costs: { type: "TABLE", queryName: "costs", columns: [{ field: "metric.value", header: "Costs" }] },
  },
  layout: { sections: [
    { id: "inputs", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "country-item", widgetId: "country" } },
    { id: "results", height: { mode: "SCALED", scale: 1.1 }, root: { type: "STACK", id: "result-stack", direction: "ROW", children: ["sales", "costs"].map(name => ({ weight: 1, node: { type: "WIDGET", id: `${name}-item`, widgetId: name } })) } },
  ] },
};

async function install(page: Page, linkedPage = false) {
  let revoked = false;
  let mutations = 0;
  let privateReads = 0;
  const run = (name: string, id: string) => ({ queryType: "SEMANTIC" as const, queryRunId: id, queryName: name, connectorId: workspaceId, state: "SUCCEEDED", resultAvailable: true, failureCode: null, failureMessage: null, outputColumnNames: ["metric.value"], createdAt: scheduledAt, updatedAt: "2026-09-20T00:00:02Z" });
  const occurrence = { scheduleKey: "daily_report", scheduledAt, decidedAt: "2026-09-20T00:00:01Z", outcome: "ACCEPTED", reason: null, definition, parameters: {}, sessionId, initialQueryRuns: initialRuns, queryRuns: Object.fromEntries(Object.entries(initialRuns).map(([name, id]) => [name, run(name, id)])), resultState: "READY" };
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Operations",
    organizationId: "organization-1",
    role: "EXPLORER",
    user: { id: "reader", email: "reader@example.test", displayName: "Reader" },
  });
  await page.route("**/v2/workspaces/**", async (route: Route) => {
    const path = decodeURIComponent(new URL(route.request().url()).pathname);
    const json = (value: unknown, status = 200) => route.fulfill({ status, contentType: status >= 400 ? "application/problem+json" : "application/json", body: JSON.stringify(status >= 400 ? { type: "https://monomer.cc/problems/fixture", title: "Access restricted", detail: "Access restricted", status, instance: path, code: "FORBIDDEN", requestId: "fixture", traceId: "fixture", retryable: false } : value) });
    if (route.request().method() === "POST") { mutations++; return json({}, 403); }
    if (path.endsWith("/pages/access")) return json({ mode: "MEMBER" });
    if (path.endsWith("/pages/tree")) return json({ workspaceId, revision: 1, pages: [{ pageId: dashboardId, kind: "DASHBOARD", dashboardId, documentFormat: null, title: "Scheduled operations", displayTitle: "Scheduled operations", description: null, createdAt: scheduledAt, updatedAt: scheduledAt }], placements: [{ pageId: dashboardId, children: [] }], updatedAt: scheduledAt, updatedBy: { kind: "USER", id: "reader" } });
    if (path.endsWith("/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: connected\ndata: ${JSON.stringify({ sessionId })}\n\n` });
    if (path.endsWith("/schedules")) return json({ schedules: [{ scheduleKey: "daily_report", nextScheduledAt: "2026-09-21T00:00:00Z" }], preview: [21, 22, 23].map(day => ({ scheduleKey: "daily_report", scheduledAt: `2026-09-${day}T00:00:00Z`, localTime: `2026-09-${day}T09:00:00+09:00[Asia/Seoul]`, parameters: {} })), previewError: null });
    if (path.endsWith("/occurrences")) return json({ items: [occurrence, { ...occurrence, scheduledAt: "2026-09-19T00:00:00Z", outcome: "SKIPPED", reason: "LATE", sessionId: null, initialQueryRuns: {}, queryRuns: {}, resultState: null }, { ...occurrence, scheduledAt: earlierScheduledAt }], nextCursor: null });
    if (path.includes("/occurrences/")) return json({ ...occurrence, scheduledAt: path.split("/").at(-1)! });
    const match = path.match(/\/query-runs\/([^/]+)(\/result)?$/);
    if (match) {
      const name = Object.entries(initialRuns).find(([, id]) => id === match[1])?.[0];
      if (!name) return json({}, 404);
      if (match[2]) {
        if (name === "costs") { privateReads++; if (revoked) return json({}, 403); }
        return json({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: match[1], columns: [{ name: "metric.value", logicalType: "TEXT", nullable: false }], rows: [[name === "sales" ? "Original sales 1200" : "Original private costs 350"]] });
      }
      return json(run(name, match[1]));
    }
    // Later manual refresh is visible in normal Session history, but must not replace the occurrence's initial Runs.
    if (path.endsWith("/query-runs")) return json({ items: [run("sales", "019e8000-0000-7000-8000-000000000f99")], nextCursor: null });
    if (path.endsWith("/sessions")) return json({ items: [{ dashboardId, dashboardSessionId: sessionId, parameterBindings: {}, createdAt: scheduledAt, updatedAt: scheduledAt, runningQueryRuns: [] }], nextCursor: null });
    if (path.endsWith(`/dashboard-sessions/${sessionId}`)) return json({ workspaceId, dashboardId, dashboardSessionId: sessionId, definition, parameterBindings: {}, initialVariables: { country: "US" }, runningQueryRuns: [] });
    if (path.endsWith(`/dashboards/${dashboardId}`)) return json({ workspaceId, dashboardId, definition, createdAt: scheduledAt, updatedAt: scheduledAt });
    return json({ items: [] });
  });
  const url = `/workspaces/${workspaceId}/${linkedPage ? "pages" : "dashboards"}/${dashboardId}?scheduleKey=daily_report&scheduledAt=${encodeURIComponent(scheduledAt)}`;
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, url);
  return { revoke: () => { revoked = true; }, mutations: () => mutations, privateReads: () => privateReads };
}

test("scheduled result fixes initial runs, removes revoked data, and exposes schedule history", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/query-runs\/[^/]+\/result$/, reason: "The fixture revokes the private result." });
  const fixture = await install(page);
  await expect(page.getByRole("gridcell", { name: "Original sales 1200", exact: true })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "Original private costs 350", exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Country", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: /^Refresh/ })).toHaveCount(0);
  expect(fixture.mutations()).toBe(0);
  await page.reload();
  await expect(page.getByRole("gridcell", { name: "Original sales 1200", exact: true })).toBeVisible();
  expect(fixture.mutations()).toBe(0);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-scheduled-result.png"), fullPage: true });
  await page.getByRole("button", { name: "Schedules", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  const dialog = page.getByRole("dialog", { name: "Schedules" });
  await expect(dialog.getByText("Schedules are part of the dashboard definition. Ask your agent to add or change one.")).toBeVisible();
  await expect(dialog.getByRole("heading", { name: "Daily · 9:00 AM Asia/Seoul" })).toBeVisible();
  await expect(dialog).toContainText(/Next run: Mon, Sep 21(, 2026)? · 9:00 AM/);
  await expect(dialog.getByRole("heading", { name: "Recent runs" })).toBeVisible();
  await expect(dialog).toContainText("Skipped · started more than 15 minutes late");
  await expect(dialog.getByText("Completed", { exact: true })).toHaveCount(2);
  await expect(dialog.getByText("daily_report", { exact: true })).toHaveCount(0);
  await expect(dialog).not.toContainText("UTC");
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-schedules.png"), fullPage: true });
  await page.keyboard.press("Escape");
  const readsBeforeRevocation = fixture.privateReads();
  fixture.revoke();
  await page.reload();
  await expect(page.getByRole("gridcell", { name: "Original sales 1200", exact: true })).toBeVisible();
  await expect(page.getByText("You do not have access to this result. Check your data permissions.", { exact: true })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "Original private costs 350", exact: true })).toHaveCount(0);
  expect(fixture.privateReads()).toBeGreaterThan(readsBeforeRevocation);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-scheduled-restricted.png"), fullPage: true });
  expect(fixture.mutations()).toBe(0);
});

test("schedule history opens an earlier accepted result", async ({ page }) => {
  const fixture = await install(page);
  await expect(page.getByRole("gridcell", { name: "Original sales 1200", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Schedules", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: /^View results for Daily · 9:00 AM at Fri, Sep 18(, 2026)? · 9:00 AM$/ }).click();
  await expect(page).toHaveURL(`/workspaces/${workspaceId}/dashboards/${dashboardId}?scheduleKey=daily_report&scheduledAt=${encodeURIComponent(earlierScheduledAt)}`);
  await expect(page.getByRole("gridcell", { name: "Original sales 1200", exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Country", exact: true })).toBeDisabled();
  expect(fixture.mutations()).toBe(0);
});

test("schedule history fits a narrow viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await install(page);
  await expect(page.getByRole("heading", { name: "Daily operations", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Schedules", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-schedules-mobile.png"), fullPage: true });
});


test("linked Dashboard Page preserves the scheduled result address", async ({ page }) => {
  const fixture = await install(page, true);
  await expect(page.getByRole("gridcell", { name: "Original sales 1200", exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Country", exact: true })).toBeDisabled();
  const scheduledAddress = `scheduleKey=daily_report&scheduledAt=${encodeURIComponent(scheduledAt)}`;
  await expect(page.getByRole("link", { name: "Open dashboard", exact: true })).toHaveAttribute("href", `/workspaces/${workspaceId}/dashboards/${dashboardId}?${scheduledAddress}`);
  await expect(page).toHaveURL(`/workspaces/${workspaceId}/pages/${dashboardId}?${scheduledAddress}`);
  expect(fixture.mutations()).toBe(0);
});
