import { mkdirSync, readFileSync } from "node:fs";
import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { mockSignedInWorkspace } from "./support/session";
import { evidencePath } from "./evidence";
const workspaceId = "019e8000-0000-7000-8000-000000000b01";
const principalId = "019e8000-0000-7000-8000-000000000b02";
const connectorId = "019e8000-0000-7000-8000-000000000b03";
const runId = "019e8000-0000-7000-8000-000000000b04";
const dashboardId = "019e8000-0000-7000-8000-000000000b05";
const json = (value: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
async function authenticate(page: Page, destination: string) {
  await page.route("**/geist-mono-latin-wght-normal.woff2", (route) => route.fulfill({ contentType: "font/woff2", body: readFileSync("node_modules/@fontsource-variable/geist-mono/files/geist-mono-latin-wght-normal.woff2") }));
  await page.route("**/geist-latin-wght-normal.woff2", (route) => route.fulfill({ contentType: "font/woff2", body: readFileSync("node_modules/@fontsource-variable/geist/files/geist-latin-wght-normal.woff2") }));
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Commerce Insights",
    organizationId: "organization-1",
    role: "OWNER",
    user: { id: principalId, email: "overview@example.test", displayName: "Alex Morgan" },
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, destination);
}


test.beforeAll(() => mkdirSync(evidencePath("screenshots"), { recursive: true }));

const definition = { schemaVersion: 1, meta: { title: "Revenue" }, parameters: {}, variables: {}, queries: {}, widgets: {}, layout: { sections: [] } };
async function installDraft(page: Page, missingDraft = false, missingRun = false) {
  const writes: string[] = [];
  const reads: string[] = [];
  const draft = { workspaceId, dashboardId, authoringRevision: 1, definition, parameterBindings: {}, readyResults: {}, targetQueryRuns: {}, recentQueryRuns: [], variables: {}, createdAt: "2026-09-09T00:00:00Z", updatedAt: "2026-09-09T00:00:00Z" };
  await page.route("**/v2/workspaces/**", async (route) => {
    const url = new URL(route.request().url());
    if (route.request().method() !== "GET") writes.push(url.pathname);
    else reads.push(url.pathname);
    const notFound = () => route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ type: "about:blank", title: "Not found", status: 404, code: "NOT_FOUND", detail: "Draft run was not found.", instance: url.pathname, requestId: runId, traceId: "draft-native-fixture", retryable: false }) });
    if (url.pathname.endsWith(`/dashboards/${dashboardId}`)) return route.fulfill(json({ workspaceId, dashboardId, definition, createdAt: draft.createdAt, updatedAt: draft.updatedAt }));
    if (url.pathname.endsWith("/draft")) return missingDraft ? notFound() : route.fulfill(json(draft));
    if (url.pathname.endsWith(`/draft/query-runs/${runId}`)) {
      if (missingRun) return notFound();
      await new Promise(resolve => setTimeout(resolve, 400));
      return route.fulfill(json({ queryType: "SEMANTIC" as const, queryRunId: runId, queryName: "historical_orders", connectorId, createdAt: draft.createdAt, updatedAt: draft.updatedAt, state: "SUCCEEDED", resultAvailable: true, outputColumnNames: ["orders"], failureCode: null, failureMessage: null }));
    }
    if (url.pathname.endsWith(`/draft/query-runs/${runId}/result`)) return route.fulfill(json({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: runId, columns: [{ name: "orders", logicalType: "INTEGER" }], rows: [["42"]] }));
    if (url.pathname.endsWith("/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: "" });
    return route.fulfill(json({ items: [], nextCursor: null }));
  });
  return { writes, reads };
}

test("Draft deep link reads exact historical run and result without executing", async ({ page }) => {
  const { writes, reads } = await installDraft(page);
  await authenticate(page, `/workspaces/${workspaceId}/dashboards/${dashboardId}/builder?run=${runId}`);
  const dialog = page.getByRole("dialog", { name: "historical_orders" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByTestId("run-sample-grid")).toContainText("42");
  expect(reads.some(path => path.endsWith(`/draft/query-runs/${runId}`))).toBe(true);
  await expect(page.getByRole("button", { name: "Apply", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Refresh", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toHaveCount(0);
  await expect(page.getByTestId("dashboard-parameter-toolbar")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/overview-draft-native-detail.png"), fullPage: true });
  expect(writes).toEqual([]);
});

test("Draft deep link does not create a missing Draft, including retry", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 404, url: /\/draft$/, reason: "The fixture has no Draft for the deep link." });
  const { writes } = await installDraft(page, true);
  await authenticate(page, `/workspaces/${workspaceId}/dashboards/${dashboardId}/builder?run=${runId}`);
  await expect(page.getByText("The linked run is unavailable because the shared Draft could not be read.")).toBeVisible();
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(page.getByText("The linked run is unavailable because the shared Draft could not be read.")).toBeVisible();
  expect(writes).toEqual([]);
});

test("Draft deep link refuses a missing run instead of selecting the current target", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 404, url: /\/draft\/query-runs\/[^/]+$/, reason: "The fixture has no Draft run for the deep link." });
  const { writes } = await installDraft(page, false, true);
  await authenticate(page, `/workspaces/${workspaceId}/dashboards/${dashboardId}/builder?run=${runId}`);
  await expect(page.getByText("The linked run is unavailable in the current Draft.")).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(writes).toEqual([]);
});
