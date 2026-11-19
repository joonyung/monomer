import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import type { DashboardDefinition, DashboardQueryRun } from "../../src/api/dashboard";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const workspaceId = "019e8000-0000-7000-8000-000000000b01";
const dashboardId = "019e8000-0000-7000-8000-000000000d01";
const sessionId = "019e8000-0000-7000-8000-000000000e01";
const url = `/workspaces/${workspaceId}/dashboards/${dashboardId}?session=${sessionId}`;
const definition: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "D11 · Independent exploration" }, parameters: {},
  variables: { country: { type: "TEXT", nullable: false, initial: "JP", label: "Country" }, city: { type: "TEXT", nullable: false, initial: "Tokyo", label: "City" } },
  queries: Object.fromEntries(["orders", "daily"].map(name => [name, { type: "SEMANTIC" as const, connectorId: workspaceId, query: { schemaVersion: 4, metrics: ["value"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 } }])),
  widgets: {
    country: { type: "INPUT", variableName: "country" }, city: { type: "INPUT", variableName: "city" },
    chart: { type: "CHART", queryNames: ["orders", "daily"], module: { inputs: { parameters: [], variables: ["country", "city"] }, source: `export default defineChart({ setup({chart}) { chart.on("click", (event, frame) => frame.setVariables({country:"KR", city:"Seoul"})); }, render({variables}) { return { animation:false, title:{text:variables.country}, xAxis:{type:"category",data:["KR"]},yAxis:{type:"value",max:100},series:[{type:"bar",data:[80]}] }; } });` } },
    table: { type: "TABLE", queryName: "orders", columns: [{ field: "dimension.country", header: "Country result" }], module: { inputs: { queries: ["daily"], parameters: [], variables: ["country", "city"] }, source: 'export default defineTable({ render(){ return {}; } });' } },
  },
  layout: { sections: [
    { id: "inputs", height: { mode: "CONTENT" }, root: { type: "STACK", id: "inputs-stack", direction: "ROW", children: ["country", "city"].map(name => ({ weight: 1, node: { type: "WIDGET", id: `${name}-item`, widgetId: name } })) } },
    { id: "chart", height: { mode: "SCALED", scale: 1 }, root: { type: "WIDGET", id: "chart-item", widgetId: "chart" } },
    { id: "results", height: { mode: "SCALED", scale: 1.1 }, root: { type: "WIDGET", id: "table-item", widgetId: "table" } },
  ] },
};

function fixture() {
  let counter = 0;
  const runs = new Map<string, DashboardQueryRun & { country: string; version: number }>();
  const sets = new Map<string, Record<string, string>>();
  const requests: { page: string; variables: Record<string, string>; refresh: boolean }[] = [];
  const denied = new Set<string>();
  const unavailableSessions = new Set<string>();
  const hold = new Map<string, (() => void)[]>();
  let holdCountry: string | null = null;
  let streamReads = 0;
  function create(country: string) {
    const version = [...runs.values()].filter(run => run.country === country && run.queryName === "orders").length;
    const selected = Object.fromEntries(["orders", "daily"].map(queryName => {
      const queryRunId = `019e8000-0000-7000-8000-${String(++counter).padStart(12, "0")}`;
      runs.set(queryRunId, { queryType: "SEMANTIC" as const, country, version, connectorId: workspaceId, queryName, queryRunId, state: country === "JP" ? "SUCCEEDED" : "RUNNING", resultAvailable: country === "JP", outputColumnNames: ["dimension.country"], failureCode: null, failureMessage: null, createdAt: "2026-09-06T10:00:00Z", updatedAt: "2026-09-06T10:00:01Z", startedBy: null, credentialSurface: null, agentApp: null });
      return [queryName, queryRunId];
    }));
    sets.set(country, selected);
    return selected;
  }
  create("JP");
  function complete(country: string, queryName?: string, failed = false) {
    for (const id of Object.values(sets.get(country)!)) {
      const run = runs.get(id)!;
      if (queryName && run.queryName !== queryName) continue;
      run.state = failed ? "FAILED" : "SUCCEEDED";
      run.resultAvailable = !failed;
    }
  }
  async function install(page: Page, name: string) {
    await page.setViewportSize({ width: 1280, height: 900 });
    await mockSignedInWorkspace(page, {
      workspaceId,
      workspaceName: "D11 Workspace",
      organizationId: "organization-1",
      role: "EXPLORER",
      user: { id: name, email: `${name}@example.test`, displayName: name },
    });
    await page.route("**/v2/workspaces/**", async (route: Route) => {
      const path = new URL(route.request().url()).pathname;
      const json = (value: unknown, status = 200) => route.fulfill({ status, contentType: status >= 400 ? "application/problem+json" : "application/json", body: JSON.stringify(status >= 400 ? { type: "https://monomer.cc/problems/fixture", title: "Fixture error", status, instance: path, code: "FIXTURE_ERROR", requestId: "fixture-request", traceId: "fixture-trace", retryable: false, ...(value as object) } : value) });
      const runningQueryRuns = [...runs.values()].filter(run => run.state === "RUNNING");
      if (path.endsWith("/events")) {
        streamReads++;
        return route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: connected\ndata: ${JSON.stringify({ sessionId, reason: "CONNECTED", observedAt: "2026-09-06T10:00:01Z" })}\n\n` });
      }
      if (path.endsWith(":resolve") || path.endsWith(":refresh")) {
        const body = route.request().postDataJSON();
        const variables = body.variables as Record<string, string>;
        const refresh = path.endsWith(":refresh");
        requests.push({ page: name, variables, refresh });
        expect(Object.keys(variables).sort()).toEqual(["city", "country"]);
        expect(body.expectedRevision).toBeUndefined();
        if (variables.country === "BAD") return json({ detail: "Invalid country" }, 400);
        let selected = sets.get(variables.country);
        if (!selected || (refresh && Object.values(selected).every(id => runs.get(id)!.state !== "RUNNING"))) selected = create(variables.country);
        if (holdCountry === variables.country) await new Promise<void>(resolve => hold.set(name, [...(hold.get(name) ?? []), resolve]));
        return json({ variables, queryRuns: selected });
      }
      const match = path.match(/\/query-runs\/([^/]+)(\/result)?$/);
      if (match) {
        const run = runs.get(match[1])!;
        if (match[2] && denied.has(name)) return json({ detail: "Result permission revoked" }, 403);
        return json(match[2] ? { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: run.queryRunId, columns: [{ name: "dimension.country", logicalType: "TEXT", nullable: false }], rows: [[`${run.country}-${run.version}`]] } : run);
      }
      if (path.endsWith("/query-runs")) return json({ items: [...runs.values()], nextCursor: null });
      if (path.endsWith("/sessions")) return json({ items: [{ dashboardId, dashboardSessionId: sessionId, parameterBindings: {}, createdAt: "2026-09-06T10:00:00Z", updatedAt: "2026-09-06T10:00:00Z", runningQueryRuns }], nextCursor: null });
      if (path.endsWith(`/dashboard-sessions/${sessionId}`) && unavailableSessions.has(name)) return json({ detail: "Temporary session read failure" }, 503);
      if (path.endsWith(`/dashboard-sessions/${sessionId}`)) return json({ workspaceId, dashboardId, dashboardSessionId: sessionId, definition, parameterBindings: {}, initialVariables: { country: "JP", city: "Tokyo" }, runningQueryRuns });
      if (path.endsWith(`/dashboards/${dashboardId}`)) return json({ workspaceId, dashboardId, definition, createdAt: "2026-09-06T10:00:00Z", updatedAt: "2026-09-06T10:00:00Z" });
      return json({ items: [] });
    });
    await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, url);
    await expect(page.getByRole("gridcell", { name: "JP-0", exact: true })).toBeVisible();
  }
  return { install, requests, runs, sets, complete, denied, unavailableSessions, hold, setHold: (country: string | null) => { holdCountry = country; }, streams: () => streamReads };
}
async function input(page: Page, name: string, value: string) {
  const editor = page.getByTestId(`dashboard-variable-${name.toLowerCase()}`);
  await editor.getByRole("textbox", { name, exact: true }).fill(value);
  await editor.getByRole("button", { name: `Apply ${name}`, exact: true }).click();
}
async function visible(page: Page, value: string) {
  await expect(page.getByRole("gridcell", { name: value, exact: true })).toBeVisible({ timeout: 15_000 });
}
// History starts closed; the header History button opens it beside the results.
async function openHistory(page: Page) {
  const panel = page.getByRole("complementary", { name: "History" });
  if (!(await panel.isVisible())) await page.getByRole("button", { name: "History", exact: true }).click();
  await expect(panel).toBeVisible();
  return panel;
}
async function selectSession(page: Page, id: string) {
  const panel = await openHistory(page);
  const back = panel.getByRole("button", { name: "Back to history" });
  if (await back.isVisible()) await back.click();
  await panel.locator(`[data-session-id="${id}"]`).click();
  await expect(page).toHaveURL(new RegExp(`session=${id}`));
}
function problem(route: Route, status: number, detail: string) {
  return route.fulfill({ status, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/fixture", title: "Fixture error", status, detail, instance: new URL(route.request().url()).pathname, code: "FIXTURE_ERROR", requestId: "fixture-request", traceId: "fixture-trace", retryable: false }) });
}
async function refresh(page: Page) {
  const panel = await openHistory(page);
  const showRuns = panel.getByRole("button", { name: "Show query runs", exact: true });
  if (await showRuns.isVisible()) await showRuns.click();
  await panel.getByRole("button", { name: "Refresh orders", exact: true }).click();
}

test("two actual Viewer routes share execution while inputs, refresh, failures, and departure remain independent", async ({ browser, consoleErrors }) => {
  test.setTimeout(90_000);
  const shared = fixture();
  const aContext = await browser.newContext();
  const bContext = await browser.newContext();
  consoleErrors.watch(aContext);
  consoleErrors.watch(bContext);
  consoleErrors.allow({ status: 503, url: /\/dashboard-sessions\/[^/:]+$/, reason: "The fixture fails one Session revalidation in view A." });
  consoleErrors.allow({ status: 403, url: /\/query-runs\/[^/]+\/result$/, reason: "The fixture revokes view A's result permission." });
  const a = await aContext.newPage();
  const b = await bContext.newPage();
  await shared.install(a, "A");
  await shared.install(b, "B");
  expect(shared.requests.filter(request => request.page === "A")).toHaveLength(1);
  expect(shared.requests.filter(request => request.page === "B")).toHaveLength(1);
  await input(a, "Country", "KR");
  await expect(b.getByRole("textbox", { name: "Country", exact: true })).toHaveValue("JP");
  await input(b, "Country", "KR");
  const koreanRun = shared.sets.get("KR")!.orders;
  await input(a, "Country", "US");
  expect(shared.runs.get(koreanRun)!.state).toBe("RUNNING");
  shared.complete("KR", "orders");
  await visible(b, "JP-0");
  shared.complete("KR", "daily");
  shared.complete("US");
  await visible(b, "KR-0");
  await visible(a, "US-0");
  const beforeReconnect = shared.requests.length;
  const streams = shared.streams();
  const failedRevalidation = a.waitForResponse(response => response.url().endsWith(`/dashboard-sessions/${sessionId}`) && response.status() === 503);
  shared.unavailableSessions.add("A");
  await failedRevalidation;
  await visible(a, "US-0");
  shared.unavailableSessions.delete("A");
  await expect.poll(() => shared.streams(), { timeout: 10_000 }).toBeGreaterThan(streams);
  expect(shared.requests).toHaveLength(beforeReconnect);
  await a.screenshot({ path: evidencePath("../screenshots/dashboard-d11/a-us.png"), fullPage: true });
  await b.screenshot({ path: evidencePath("../screenshots/dashboard-d11/b-kr.png"), fullPage: true });
  await input(a, "Country", "KR");
  await visible(a, "KR-0");
  await refresh(a);
  shared.complete("KR");
  await visible(a, "KR-1");
  await visible(b, "KR-0");
  await refresh(a);
  shared.complete("KR", undefined, true);
  await expect(a.getByRole("alert").filter({ hasText: "The updated query failed. Previous results remain visible." })).toHaveCount(2, { timeout: 15_000 });
  await visible(a, "KR-1");
  await visible(b, "KR-0");
  await a.screenshot({ path: evidencePath("../screenshots/dashboard-d11/refresh-failed-own-lastgood.png"), fullPage: true });
  shared.denied.add("A");
  await refresh(a);
  shared.complete("KR");
  await expect(a.getByRole("gridcell", { name: "KR-1", exact: true })).toHaveCount(0, { timeout: 15_000 });
  await expect(a.getByRole("gridcell", { name: "KR-3", exact: true })).toHaveCount(0);
  await visible(b, "KR-0");
  await expect(a.getByText("You do not have access to this result. Check your data permissions.", { exact: true })).toHaveCount(2);
  await expect(a.getByText("The query failed.", { exact: true })).toHaveCount(0);
  await a.screenshot({ path: evidencePath("../screenshots/dashboard-d11/permission-revoked.png"), fullPage: true });
  await refresh(b);
  const running = shared.sets.get("KR")!.orders;
  await aContext.close();
  expect(shared.runs.get(running)!.state).toBe("RUNNING");
  shared.complete("KR");
  await visible(b, "KR-4");
  await b.setViewportSize({ width: 390, height: 844 });
  expect(await b.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await b.screenshot({ path: evidencePath("../screenshots/dashboard-d11/independent-mobile.png"), fullPage: true });
  await b.reload();
  await visible(b, "JP-0");
  await expect(b.getByRole("textbox", { name: "Country", exact: true })).toHaveValue("JP");
  await bContext.close();
});

test("actual Viewer keeps latest complete candidate, discards late resolve, and rolls rejected input back", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 400, url: /:resolve$/, reason: "The fixture rejects the invalid country input." });
  const shared = fixture();
  await shared.install(page, "A");
  shared.setHold("KR");
  await input(page, "Country", "KR");
  await expect.poll(() => shared.hold.has("A")).toBe(true);
  await input(page, "City", "Seoul");
  await expect.poll(() => shared.requests.length).toBe(3);
  expect(shared.requests[2].variables).toEqual({ country: "KR", city: "Seoul" });
  await input(page, "Country", "BAD");
  await expect(page.getByRole("alert").filter({ hasText: "Invalid country" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Country", exact: true })).toHaveValue("JP");
  await expect(page.getByRole("textbox", { name: "City", exact: true })).toHaveValue("Tokyo");
  const lateResponses = ["Tokyo", "Seoul"].map(city => page.waitForResponse(response => response.url().endsWith(":resolve") && response.request().postDataJSON().variables.city === city));
  shared.setHold(null);
  shared.hold.get("A")!.forEach(release => release());
  await Promise.all(lateResponses);
  await expect(page.getByRole("textbox", { name: "Country", exact: true })).toHaveValue("JP");
  shared.complete("KR");
  await visible(page, "JP-0");
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d11/rejected-latest-input.png"), fullPage: true });
});


test("cached Session navigation never carries the previous screen bundle into a pending or failed resolve", async ({ page }) => {
  const shared = fixture();
  await shared.install(page, "A");
  const sessionBId = "019e8000-0000-7000-8000-000000000e02";
  const sessionBDefinition = { ...definition, meta: { title: "D11 · Session B" }, variables: { ...definition.variables, country: { ...definition.variables.country, initial: "FR" }, city: { ...definition.variables.city, initial: "Paris" } } };
  const sessionBVariables = { country: "FR", city: "Paris" };
  const runIds = { orders: "019e8000-0000-7000-8000-000000000f01", daily: "019e8000-0000-7000-8000-000000000f02" };
  const failedRunIds = { orders: "019e8000-0000-7000-8000-000000000f03", daily: "019e8000-0000-7000-8000-000000000f04" };
  const sessionBRun = (queryRunId: string, queryName: string, state: "SUCCEEDED" | "FAILED"): DashboardQueryRun => ({ queryType: "SEMANTIC" as const,
    connectorId: workspaceId, queryRunId, queryName, state, resultAvailable: state === "SUCCEEDED", outputColumnNames: ["dimension.country"],
    failureCode: state === "FAILED" ? "FIXTURE_FAILURE" : null, failureMessage: null,
    createdAt: "2026-09-06T11:00:00Z", updatedAt: "2026-09-06T11:00:01Z", startedBy: null, credentialSurface: null, agentApp: null,
  });
  const sessionBRuns = [
    ...Object.entries(runIds).map(([name, id]) => sessionBRun(id, name, "SUCCEEDED")),
    ...Object.entries(failedRunIds).map(([name, id]) => sessionBRun(id, name, "FAILED")),
  ];
  let resolveCount = 0;
  let holdResolve = false;
  let heldResolve: (() => void) | undefined;
  const unexpectedReads: string[] = [];
  await page.route(`**/v2/workspaces/${workspaceId}/dashboard-sessions/${sessionBId}**`, async route => {
    const path = new URL(route.request().url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (path.endsWith("/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: connected\ndata: ${JSON.stringify({ sessionId: sessionBId, reason: "CONNECTED", observedAt: "2026-09-06T11:00:01Z" })}\n\n` });
    if (path.endsWith(":resolve")) {
      resolveCount++;
      expect(route.request().postDataJSON()).toEqual({ variables: sessionBVariables });
      if (holdResolve) {
        await new Promise<void>(resolve => { heldResolve = resolve; });
        return json({ variables: sessionBVariables, queryRuns: failedRunIds });
      }
      return json({ variables: sessionBVariables, queryRuns: runIds });
    }
    if (path.endsWith("/query-runs")) return json({ items: sessionBRuns, nextCursor: null });
    const match = path.match(/\/query-runs\/([^/]+)(\/result)?$/);
    if (match) {
      const run = sessionBRuns.find(candidate => candidate.queryRunId === match[1]);
      if (!run) {
        unexpectedReads.push(path);
        return route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/not-found", title: "Run not found", status: 404, detail: "Run belongs to another Session", instance: path, code: "NOT_FOUND", requestId: "fixture-request", traceId: "fixture-trace", retryable: false }) });
      }
      return json(match[2] ? { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: run.queryRunId, columns: [{ name: "dimension.country", logicalType: "TEXT", nullable: false }], rows: [["FR-0"]] } : run);
    }
    return json({ workspaceId, dashboardId, dashboardSessionId: sessionBId, definition: sessionBDefinition, parameterBindings: {}, initialVariables: sessionBVariables, runningQueryRuns: [] });
  });
  await page.route(`**/v2/workspaces/${workspaceId}/dashboards/${dashboardId}/sessions**`, route => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify({ items: [sessionId, sessionBId].map(id => ({ dashboardId, dashboardSessionId: id, parameterBindings: {}, createdAt: "2026-09-06T10:00:00Z", updatedAt: "2026-09-06T10:00:00Z", runningQueryRuns: [] })), nextCursor: null }),
  }));
  await expect.poll(() => shared.streams(), { timeout: 10_000 }).toBeGreaterThan(1);
  await page.evaluate(() => { (window as typeof window & { d11NavigationMarker?: object }).d11NavigationMarker = {}; });
  await selectSession(page, sessionBId);
  await visible(page, "FR-0");
  await expect(page.getByRole("textbox", { name: "Country", exact: true })).toHaveValue("FR");
  expect(resolveCount).toBe(1);
  await selectSession(page, sessionId);
  await visible(page, "JP-0");
  await expect(page.getByRole("textbox", { name: "Country", exact: true })).toHaveValue("JP");
  holdResolve = true;
  await selectSession(page, sessionBId);
  await expect.poll(() => heldResolve !== undefined).toBe(true);
  await expect(page.getByRole("textbox", { name: "Country", exact: true })).toHaveValue("FR");
  expect(await page.evaluate(() => Boolean((window as typeof window & { d11NavigationMarker?: object }).d11NavigationMarker))).toBe(true);
  await expect(page.getByRole("gridcell", { name: "JP-0", exact: true })).toHaveCount(0);
  await expect(page.getByTestId("dashboard-chart-widget")).toHaveCount(0);
  expect(unexpectedReads).toEqual([]);
  heldResolve!();
  await expect(page.getByText(/^This (chart|table) couldn't load\.$/)).toHaveCount(2);
  await expect(page.getByText("The query failed.", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("gridcell")).toHaveCount(0);
  await expect(page.getByTestId("dashboard-chart-widget")).toHaveCount(0);
  expect(resolveCount).toBe(2);
  expect(unexpectedReads).toEqual([]);
});


test("returning to a Session re-reads its completed results and removes a result revoked meanwhile", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/query-runs\/[^/]+\/result$/, reason: "The fixture revokes the result while the reader is away." });
  const shared = fixture();
  await shared.install(page, "A");
  const sessionBId = "019e8000-0000-7000-8000-000000000e02";
  const sessionBVariables = { country: "FR", city: "Paris" };
  const sessionBRuns = Object.entries({ orders: "019e8000-0000-7000-8000-000000000f11", daily: "019e8000-0000-7000-8000-000000000f12" }).map(([queryName, queryRunId]) => ({ queryType: "SEMANTIC" as const,
    connectorId: workspaceId, queryRunId, queryName, state: "SUCCEEDED", resultAvailable: true, outputColumnNames: ["dimension.country"],
    failureCode: null, failureMessage: null, createdAt: "2026-09-06T11:00:00Z", updatedAt: "2026-09-06T11:00:01Z",
  }));
  await page.route(`**/v2/workspaces/${workspaceId}/dashboard-sessions/${sessionBId}**`, route => {
    const path = new URL(route.request().url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (path.endsWith("/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: connected\ndata: ${JSON.stringify({ sessionId: sessionBId, reason: "CONNECTED", observedAt: "2026-09-06T11:00:01Z" })}\n\n` });
    if (path.endsWith(":resolve")) return json({ variables: sessionBVariables, queryRuns: Object.fromEntries(sessionBRuns.map(run => [run.queryName, run.queryRunId])) });
    if (path.endsWith("/query-runs")) return json({ items: sessionBRuns, nextCursor: null });
    const match = path.match(/\/query-runs\/([^/]+)(\/result)?$/);
    if (match) return json(match[2] ? { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: match[1], columns: [{ name: "dimension.country", logicalType: "TEXT", nullable: false }], rows: [["FR-0"]] } : sessionBRuns.find(run => run.queryRunId === match[1]));
    return json({ workspaceId, dashboardId, dashboardSessionId: sessionBId, definition, parameterBindings: {}, initialVariables: sessionBVariables, runningQueryRuns: [] });
  });
  await page.route(`**/v2/workspaces/${workspaceId}/dashboards/${dashboardId}/sessions**`, route => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify({ items: [sessionId, sessionBId].map(id => ({ dashboardId, dashboardSessionId: id, parameterBindings: {}, createdAt: "2026-09-06T10:00:00Z", updatedAt: "2026-09-06T10:00:00Z", runningQueryRuns: [] })), nextCursor: null }),
  }));
  await selectSession(page, sessionBId);
  await visible(page, "FR-0");
  shared.denied.add("A");
  let releaseResultReads!: () => void;
  const resultReadsHeld = new Promise<void>(resolve => { releaseResultReads = resolve; });
  const heldResultReads: string[] = [];
  await page.route(`**/dashboard-sessions/${sessionId}/query-runs/*/result`, async route => {
    heldResultReads.push(new URL(route.request().url()).pathname);
    await resultReadsHeld;
    await route.fallback();
  });
  await selectSession(page, sessionId);
  await expect.poll(() => heldResultReads.length).toBe(2);
  await expect(page.getByRole("gridcell", { name: "JP-0", exact: true })).toHaveCount(0);
  await expect(page.getByTestId("dashboard-chart-widget")).toHaveCount(0);
  releaseResultReads();
  await expect(page.getByText("You do not have access to this result. Check your data permissions.", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("gridcell", { name: "JP-0", exact: true })).toHaveCount(0);
  await expect(page.getByTestId("dashboard-chart-widget")).toHaveCount(0);
});

test("a denied Refresh command re-checks Session access immediately instead of keeping the last result", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /:refresh$/, reason: "The fixture denies the Refresh command." });
  consoleErrors.allow({ status: 403, url: /\/dashboard-sessions\/[^/:]+$/, reason: "The fixture denies the Session re-check after the denied Refresh." });
  const shared = fixture();
  await shared.install(page, "A");
  let streamHeld = false;
  await page.route(`**/dashboard-sessions/${sessionId}/events`, () => { streamHeld = true; return new Promise<void>(() => {}); });
  await expect.poll(() => streamHeld, { timeout: 10_000 }).toBe(true);
  let sessionReads = 0;
  await page.route(`**/dashboard-sessions/${sessionId}`, route => { sessionReads++; return problem(route, 403, "Workspace access revoked"); });
  await page.route(`**/dashboard-sessions/${sessionId}:refresh`, route => problem(route, 403, "Workspace access revoked"));
  await refresh(page);
  await expect.poll(() => sessionReads).toBe(1);
  await expect(page.getByRole("gridcell", { name: "JP-0", exact: true })).toHaveCount(0);
  await expect(page.getByTestId("dashboard-chart-widget")).toHaveCount(0);
  await expect(page.getByText("Workspace access revoked", { exact: true })).toBeVisible();
});
