import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import type { DashboardDefinition } from "../../src/api/dashboard";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { mockSignedInWorkspace } from "./support/session";
import { evidencePath } from "./evidence";

const workspaceId = "019e8000-0000-7000-8000-000000000b01";
const dashboardId = "019e8000-0000-7000-8000-000000000b05";
const principalId = "019e8000-0000-7000-8000-000000000b02";
const runId = "019e8000-0000-7000-8000-000000000b06";
const json = (value: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(value) });

async function setup(page: Page, observationOnly = false, withChart = false, withChoice = false) {
  const definition: DashboardDefinition = { schemaVersion: 1, meta: { title: "Shared orders" }, parameters: {},
    variables: { region: { type: "TEXT", nullable: false, initial: "North", label: "Region" }, city: { type: "TEXT", nullable: false, initial: "Toronto", label: "City" } },
    queries: {}, widgets: { region: { type: "INPUT", variableName: "region" }, city: { type: "INPUT", variableName: "city" } },
    layout: { sections: [{ id: "inputs", height: { mode: "CONTENT" }, root: { type: "STACK", id: "row", direction: "ROW", children: [
      { weight: 1, node: { type: "WIDGET", id: "region", widgetId: "region" } }, { weight: 1, node: { type: "WIDGET", id: "city", widgetId: "city" } },
    ] } }] } };
  if (withChart) {
    definition.queries.orders = { type: "SEMANTIC" as const, connectorId: workspaceId, query: { schemaVersion: 4, metrics: ["orders"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 } };
    definition.widgets.chart = { type: "CHART", queryNames: ["orders"], module: { inputs: { parameters: [], variables: ["region", "city"] },
      source: 'export default defineChart({ setup({chart}) { chart.on("click", (event, frame) => frame.setVariables({region:"Canada",city:"Calgary"})); }, render({variables}) { return {animation:false,xAxis:{type:"category",data:["Canada"]},yAxis:{type:"value",max:100},series:[{type:"bar",data:[80]}]}; } });' } };
    definition.layout.sections.push({ id: "chart", height: { mode: "SCALED", scale: 1 }, root: { type: "WIDGET", id: "chart-item", widgetId: "chart" } });
  }
  if (withChoice) {
    definition.variables.status = { type: "TEXT", nullable: false, initial: "OPEN", label: "Status" };
    definition.widgets.status = { type: "INPUT", variableName: "status", control: { options: [{ value: "OPEN", label: "Open" }, { value: "CLOSED", label: "Closed" }] } };
    definition.layout.sections.push({ id: "status", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "status-item", widgetId: "status" } });
  }
  let snapshot = { workspaceId, dashboardId, definition, variables: { region: "North", city: "Toronto", ...(withChoice ? { status: "OPEN" } : {}) }, parameterBindings: {}, authoringRevision: 1, targetQueryRuns: withChart ? { orders: runId } : {}, readyResults: withChart ? { chart: { authoringRevision: 1, parameterBindings: {}, variables: { region: "North", city: "Toronto" }, queryRuns: { orders: runId } } } : {}, recentQueryRuns: [] as unknown[], createdAt: "2026-09-14T00:00:00Z", updatedAt: "2026-09-14T00:00:00Z" };
  const writes: { expectedRevision: number; variables: Record<string, unknown> }[] = [];
  let rejectNext = false;
  let failNext = false;
  let pause: Promise<void> | null = null;
  let release: (() => void) | null = null;
  let reads = 0;
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (!url.endsWith("/draft/events")) return originalFetch(input, init);
      const state = window as unknown as { draftStreams: number };
      state.draftStreams = (state.draftStreams ?? 0) + 1;
      return new Response(new ReadableStream<Uint8Array>({ start(controller) {
        const emit = () => controller.enqueue(new TextEncoder().encode('data: {"reason":"DRAFT_CHANGED","observedAt":"2026-09-14T00:00:00Z"}\n\n'));
        window.addEventListener("draft-fixture-update", emit);
        init?.signal?.addEventListener("abort", () => { window.removeEventListener("draft-fixture-update", emit); controller.close(); }, { once: true });
      } }), { headers: { "Content-Type": "text/event-stream" } });
    };
  });
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Commerce",
    organizationId: "organization-1",
    role: "OWNER",
    user: { id: principalId, email: "draft@example.test", displayName: "Draft editor" },
  });
  await page.route("**/v2/workspaces/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/draft/variables:set")) {
      const body = route.request().postDataJSON(); writes.push(body);
      expect(route.request().headers()["idempotency-key"]).toBeTruthy();
      if (failNext) {
        failNext = false;
        return route.fulfill({ status: 500, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/fixture", title: "Write failed", code: "FIXTURE_ERROR", status: 500, detail: "The fixture could not save the Draft.", instance: path, requestId: "fixture-request", traceId: "fixture-trace", retryable: false }) });
      }
      if (rejectNext || body.expectedRevision !== snapshot.authoringRevision) {
        rejectNext = false;
        snapshot = { ...snapshot, authoringRevision: snapshot.authoringRevision + 1, variables: { ...snapshot.variables, region: "Remote" } };
        return route.fulfill({ status: 409, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/draft-conflict", title: "Draft changed", code: "DRAFT_CONFLICT", status: 409, detail: "The Draft changed.", instance: path, requestId: "fixture-request", traceId: "fixture-trace", retryable: false }) });
      }
      snapshot = { ...snapshot, authoringRevision: snapshot.authoringRevision + 1, variables: { ...snapshot.variables, ...body.variables } };
      const response = structuredClone(snapshot);
      if (pause) await pause;
      return route.fulfill(json(response));
    }
    if (path.endsWith(`/draft/query-runs/${runId}/result`)) return route.fulfill(json({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: runId, columns: [{ name: "n", logicalType: "NUMBER", nullable: false }], rows: [["80"]] }));
    if (path.endsWith("/draft")) { reads += 1; return route.fulfill(json(snapshot)); }
    if (path.endsWith(`/draft/query-runs/${runId}`)) return route.fulfill(json({ queryType: "SEMANTIC" as const, queryRunId: runId, queryName: "orders", connectorId: runId, state: "FAILED", resultAvailable: false, failureCode: "TEST", failureMessage: "Fixture", outputColumnNames: [], createdAt: snapshot.createdAt, updatedAt: snapshot.updatedAt }));
    if (path.endsWith(`/dashboards/${dashboardId}`)) return route.fulfill(json({ workspaceId, dashboardId, definition, createdAt: snapshot.createdAt, updatedAt: snapshot.updatedAt }));
    return route.fulfill(json({ items: [], nextCursor: null }));
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}/dashboards/${dashboardId}/builder${observationOnly ? `?run=${runId}` : ""}`);
  if (observationOnly) await page.getByRole("button", { name: "Close run detail", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Region", exact: true })).toHaveValue("North");
  await expect.poll(() => page.evaluate(() => (window as unknown as { draftStreams: number }).draftStreams)).toBe(1);
  return {
    writes,
    reject: () => { rejectNext = true; },
    fail: () => { failNext = true; },
    hold: () => { pause = new Promise<void>(resolve => { release = resolve; }); },
    release: () => { release?.(); pause = null; },
    settlement: async () => {
      snapshot = { ...snapshot, recentQueryRuns: [{ queryType: "SEMANTIC" as const, queryRunId: runId, queryName: "Settled orders", connectorId: workspaceId, state: "FAILED", resultAvailable: false, failureCode: "TEST", failureMessage: "Fixture", outputColumnNames: [], createdAt: snapshot.createdAt, updatedAt: snapshot.updatedAt }] };
      await page.evaluate(() => window.dispatchEvent(new Event("draft-fixture-update")));
    },
    remote: async (variables: Partial<typeof snapshot.variables>, revision = snapshot.authoringRevision + 1) => {
      snapshot = { ...snapshot, authoringRevision: revision, variables: { ...snapshot.variables, ...variables } };
      const before = reads;
      await page.evaluate(() => window.dispatchEvent(new Event("draft-fixture-update")));
      await expect.poll(() => reads).toBeGreaterThan(before);
      await expect(page.getByText(`Revision ${revision}`, { exact: true })).toBeVisible();
    },
  };
}

test("dirty shared input preserves intent, requires explicit rebase, and writes only its variable", async ({ page }) => {
  const fixture = await setup(page);
  const region = page.getByRole("textbox", { name: "Region", exact: true });
  await region.fill("Local");
  await fixture.remote({ region: "Remote", city: "Vancouver" });
  await expect(region).toHaveValue("Local");
  await expect(page.getByText("The shared Draft changed. Your input is kept.")).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/shared-draft-dirty-remote.png"), fullPage: true });
  await expect(page.getByRole("button", { name: "Apply Region", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "Keep my input" }).click();
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect.poll(() => fixture.writes.length).toBe(1);
  expect(fixture.writes[0]).toEqual({ expectedRevision: 2, variables: { region: "Local" } });
  await expect(page.getByRole("textbox", { name: "City", exact: true })).toHaveValue("Vancouver");
  await expect(page.getByText("Revision 3", { exact: true })).toBeVisible();
  await region.fill("Unsaved");
  await fixture.remote({ region: "Latest" });
  await page.getByRole("button", { name: "Use latest" }).click();
  await expect(region).toHaveValue("Latest");
  expect(fixture.writes).toHaveLength(1);
  expect(await page.evaluate(() => (window as unknown as { draftStreams: number }).draftStreams)).toBe(1);
});

test("conflict refetches authority and never automatically retries", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 409, url: /\/draft\/variables:set$/, reason: "The fixture rejects the Draft write with a revision conflict." });
  const fixture = await setup(page);
  fixture.reject();
  const region = page.getByRole("textbox", { name: "Region", exact: true });
  await region.fill("Local");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Review the latest values and try again");
  await expect(region).toHaveValue("Local");
  await expect(page.getByRole("button", { name: "Apply Region", exact: true })).toBeDisabled();
  await page.getByRole("button", { name: "Use latest" }).click();
  await expect(region).toHaveValue("Remote");
  expect(fixture.writes).toHaveLength(1);
});

test("a failed choice write shows the shared Draft value again and the choice can be retried", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 500, url: /\/draft\/variables:set$/, reason: "The fixture fails the first Draft choice write." });
  const fixture = await setup(page, false, false, true);
  const status = page.getByRole("combobox", { name: "Status", exact: true });
  await expect(status).toHaveText("Open");
  fixture.fail();
  await status.click();
  await page.getByRole("option", { name: "Closed", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Monomer couldn't load this right now. Try again in a moment.");
  await expect(status).toBeEnabled();
  await expect(status).toHaveText("Open");
  expect(fixture.writes).toEqual([{ expectedRevision: 1, variables: { status: "CLOSED" } }]);
  await status.click();
  await page.getByRole("option", { name: "Closed", exact: true }).click();
  await expect.poll(() => fixture.writes.length).toBe(2);
  await expect(page.getByText("Revision 2", { exact: true })).toBeVisible();
  await expect(status).toHaveText("Closed");
});

test("late write response cannot replace a newer SSE snapshot and concurrent local input stays disabled", async ({ page }) => {
  const fixture = await setup(page);
  fixture.hold();
  const region = page.getByRole("textbox", { name: "Region", exact: true });
  await region.fill("Local");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect.poll(() => fixture.writes.length).toBe(1);
  await expect(page.getByRole("textbox", { name: "City", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Apply", exact: true })).toBeDisabled();
  await fixture.remote({ region: "Newer", city: "Montreal" }, 3);
  fixture.release();
  await expect(page.getByRole("textbox", { name: "City", exact: true })).toBeEnabled();
  await expect(page.getByText("Revision 3", { exact: true })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "City", exact: true })).toHaveValue("Montreal");
  expect(fixture.writes).toHaveLength(1);
});

test("linked Draft run remains observation only", async ({ page }) => {
  const fixture = await setup(page, true);
  await expect(page.getByRole("textbox", { name: "Region", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Apply", exact: true })).toHaveCount(0);
  await expect(page.getByTestId("draft-status")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Refresh", exact: true })).toHaveCount(0);
  expect(fixture.writes).toHaveLength(0);
});

test("chart submits one atomic map at current revision and rejects its old input frame", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const fixture = await setup(page, false, true);
  const canvas = page.getByTestId("dashboard-chart-widget").locator("canvas");
  await expect.poll(() => canvas.evaluate((element: HTMLCanvasElement) => element.width)).toBeGreaterThan(400);
  await fixture.remote({}, 2);
  const clickChart = async () => { await canvas.scrollIntoViewIfNeeded(); const box = (await canvas.boundingBox())!; await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2); };
  await clickChart();
  await expect.poll(() => fixture.writes.length).toBe(1);
  expect(fixture.writes[0]).toEqual({ expectedRevision: 2, variables: { region: "Canada", city: "Calgary" } });
  await expect(page.getByRole("textbox", { name: "Region", exact: true })).toHaveValue("Canada");
  await expect(page.getByRole("textbox", { name: "Region", exact: true })).toBeEnabled();
  await clickChart();
  await expect(page.getByText("This chart is showing an older input or result. Wait for the latest result, then try again.")).toBeVisible();
  expect(fixture.writes).toHaveLength(1);
});

test("same revision settlement is adopted without replacing the SSE subscription", async ({ page }) => {
  const fixture = await setup(page);
  await fixture.settlement();
  await expect(page.getByText("Settled orders", { exact: true })).toBeVisible();
  await expect(page.getByText("Revision 1", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { draftStreams: number }).draftStreams)).toBe(1);
});

test("authorization failure clears the displayed Draft and blocks further inputs", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/draft\/variables:set$/, reason: "The fixture revokes Draft access on the write." });
  consoleErrors.allow({ status: 403, url: /\/draft$/, reason: "The fixture revokes Draft access on the refetch." });
  await setup(page);
  const forbidden = { status: 403, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/forbidden", title: "Access denied", detail: "Draft access is no longer available.", code: "DASHBOARD_FORBIDDEN", status: 403, instance: "/draft", requestId: "fixture", traceId: "fixture", retryable: false }) };
  await page.route("**/draft", route => route.fulfill(forbidden));
  await page.route("**/draft/variables:set", route => route.fulfill(forbidden));
  await page.getByRole("textbox", { name: "Region", exact: true }).fill("Local");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Region", exact: true })).toHaveCount(0);
  await expect(page.getByText("Draft access is no longer available.", { exact: true })).toBeVisible();
});
