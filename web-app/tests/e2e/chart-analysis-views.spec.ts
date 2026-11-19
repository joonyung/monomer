import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import type { DashboardDefinition, DashboardJsonObject, DashboardQueryRun, DashboardVariableDefinition } from "../../src/api/dashboard";
import { explicitAnalysisInputs, explicitAnalysisSource } from "../../src/pages/dev/chart-widget-runtime-fixture/explicit-analysis-source";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const workspaceId = "019e8000-0000-7000-8000-000000000b01";
const dashboardId = "019e8000-0000-7000-8000-000000000d01";
const sessionId = "019e8000-0000-7000-8000-000000000e01";
const url = `/workspaces/${workspaceId}/dashboards/${dashboardId}?session=${sessionId}`;
const inputs = explicitAnalysisInputs();
const initial = Object.fromEntries(Object.entries(inputs.variables).map(([name, input]) => [name, input.value])) as DashboardJsonObject;
const definition: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "C05 · Actual Dashboard analysis" }, parameters: {},
  variables: Object.fromEntries(Object.entries(inputs.variables).map(([name, input]) => [name, { type: input.type, nullable: input.nullable === true, initial: input.value as DashboardVariableDefinition["initial"], label: name }])),
  queries: { sales: { type: "SEMANTIC" as const, connectorId: workspaceId, query: { schemaVersion: 4, metrics: ["sales", "cost"], groupBys: [], metricTimeRange: null, filters: [{ kind: "TEXT", dimension: { semanticModelName: "orders", name: "country" }, operator: "EQUALS", value: { kind: "VARIABLE", name: "country" }, emptyBehavior: "OMIT" }], orderBy: [], rowLimit: 100 } } },
  widgets: {
    country: { type: "INPUT", variableName: "country" },
    chart: { type: "CHART", queryNames: ["sales"], module: { inputs: { parameters: [], variables: Object.keys(initial) }, source: explicitAnalysisSource } },
  },
  layout: { sections: [
    { id: "input", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "country-item", widgetId: "country" } },
    { id: "chart", height: { mode: "SCALED", scale: 1.4 }, root: { type: "WIDGET", id: "chart-item", widgetId: "chart" } },
  ] },
};

type Observation = { variables: DashboardJsonObject; events: number; renders: number };
async function observation(page: Page) {
  return page.evaluate(() => (window as typeof window & { analysis: Observation }).analysis);
}
async function waitForWorkerRender(page: Page, previousRenders: number) {
  // Await the Worker lifecycle under the test budget; cold initialization itself allows 10 s.
  await page.waitForFunction(previous => {
    const state = window as typeof window & { analysis: Observation };
    return state.analysis.renders > previous
      || document.querySelector('[data-testid="dashboard-chart-widget"]')?.getAttribute("data-chart-status") === "failed";
  }, previousRenders);
  expect((await observation(page)).renders).toBeGreaterThan(previousRenders);
}
const canvas = (page: Page) => page.getByTestId("dashboard-chart-widget").locator("canvas");
async function moveAway(page: Page) { await page.mouse.move(5, 5); }
async function legend(page: Page) {
  const box = (await canvas(page).boundingBox())!;
  await page.mouse.click(box.x + box.width / 2 - 42, box.y + 16);
}
async function drag(page: Page, during: () => void) {
  await canvas(page).scrollIntoViewIfNeeded();
  const box = (await canvas(page).boundingBox())!;
  await page.mouse.move(box.x + 52, box.y + box.height - 34);
  await page.mouse.down();
  await page.mouse.move(box.x + 210, box.y + box.height - 34, { steps: 40 });
  during();
  await page.mouse.up();
  await moveAway(page);
}
function fixture() {
  const requests: { page: string; variables: DashboardJsonObject }[] = [];
  const runs = new Map<string, DashboardQueryRun>();
  const runByCountry = new Map<string, string>();
  let reject = false;
  let hold = false;
  let release: (() => void) | undefined;
  const admitted = () => runs.size;
  async function install(page: Page, name: string) {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.addInitScript(() => {
      const NativeWorker = window.Worker;
      const state = window as typeof window & { analysis: Observation };
      state.analysis = { variables: {}, events: 0, renders: 0 };
      window.Worker = class extends NativeWorker {
        constructor(...args: ConstructorParameters<typeof Worker>) {
          super(...args);
          this.addEventListener("message", event => {
            if (event.data.type === "variables_set") state.analysis.events++;
            if (event.data.type === "rendered") state.analysis.renders++;
          });
        }
        override postMessage(message: unknown, transferOrOptions?: Transferable[] | StructuredSerializeOptions) {
          const request = message as { type: string; frame: { variables: DashboardJsonObject } };
          if (request.type === "render") state.analysis.variables = request.frame.variables;
          if (Array.isArray(transferOrOptions)) super.postMessage(message, transferOrOptions);
          else super.postMessage(message, transferOrOptions);
        }
      };
    });
    await mockSignedInWorkspace(page, {
      workspaceId,
      workspaceName: "Analysis Workspace",
      organizationId: "organization-1",
      role: "EXPLORER",
      user: { id: name, email: `${name}@example.test`, displayName: name },
    });
    await page.route("**/v2/workspaces/**", async route => {
      const path = new URL(route.request().url()).pathname;
      const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: status === 200 ? "application/json" : "application/problem+json", body: JSON.stringify(body) });
      if (path.endsWith("/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: connected\ndata: ${JSON.stringify({ sessionId, reason: "CONNECTED", observedAt: "2026-09-07T00:00:00Z" })}\n\n` });
      if (path.endsWith(":resolve")) {
        const { variables } = route.request().postDataJSON() as { variables: DashboardJsonObject };
        requests.push({ page: name, variables });
        expect(Object.keys(variables).sort()).toEqual(Object.keys(initial).sort());
        if (hold) await new Promise<void>(resolve => { release = resolve; });
        if (reject) return json({ type: "https://monomer.cc/problems/fixture", title: "Rejected analysis", status: 400, detail: "Analysis rejected", instance: path, code: "FIXTURE_ERROR", requestId: "fixture", traceId: "fixture", retryable: false }, 400);
        const key = JSON.stringify([variables.country, variables.city]);
        let id = runByCountry.get(key);
        if (!id) {
          id = `019e8000-0000-7000-8000-${String(runs.size + 1).padStart(12, "0")}`;
          runByCountry.set(key, id);
          runs.set(id, { queryType: "SEMANTIC" as const, connectorId: workspaceId, queryName: "sales", queryRunId: id, state: variables.country === null ? "SUCCEEDED" : "RUNNING", resultAvailable: variables.country === null, outputColumnNames: inputs.queries.sales.columns.map(column => column.name), failureCode: null, failureMessage: null, createdAt: "2026-09-07T00:00:00Z", updatedAt: "2026-09-07T00:00:00Z", startedBy: null, credentialSurface: null, agentApp: null });
        }
        return json({ variables, queryRuns: { sales: id } });
      }
      const match = path.match(/\/query-runs\/([^/]+)(\/result)?$/);
      if (match) {
        const run = runs.get(match[1])!;
        return json(match[2] ? { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: run.queryRunId, columns: inputs.queries.sales.columns, rows: inputs.queries.sales.rows.map(row => inputs.queries.sales.columns.map(column => row[column.name])) } : run);
      }
      if (path.endsWith("/query-runs")) return json({ items: [...runs.values()], nextCursor: null });
      const runningQueryRuns = [...runs.values()].filter(run => run.state === "RUNNING");
      if (path.endsWith("/sessions")) return json({ items: [{ dashboardId, dashboardSessionId: sessionId, parameterBindings: {}, createdAt: "2026-09-07T00:00:00Z", updatedAt: "2026-09-07T00:00:00Z", runningQueryRuns }], nextCursor: null });
      if (path.endsWith(`/dashboard-sessions/${sessionId}`)) return json({ workspaceId, dashboardId, dashboardSessionId: sessionId, definition, parameterBindings: {}, initialVariables: initial, runningQueryRuns });
      if (path.endsWith(`/dashboards/${dashboardId}`)) return json({ workspaceId, dashboardId, definition, createdAt: "2026-09-07T00:00:00Z", updatedAt: "2026-09-07T00:00:00Z" });
      return json({ items: [] });
    });
    await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, url);
    await waitForWorkerRender(page, 1);
    await expect(page.getByTestId("dashboard-chart-widget").getByRole("alert")).toHaveCount(0);
    await expect.poll(async () => (await observation(page)).variables).toEqual(initial);
  }
  return { install, requests, admitted, reject: () => { reject = true; }, hold: () => { hold = true; }, release: () => { hold = false; release!(); }, held: () => Boolean(release) };
}

test("E01 G04 actual Viewer retains accepted zoom across resize without resolving another query", async ({ page }) => {
  const shared = fixture();
  await shared.install(page, "Resize");
  const initialCount = shared.requests.length;
  await drag(page, () => expect(shared.requests).toHaveLength(initialCount));
  await expect.poll(() => shared.requests.length).toBe(initialCount + 1);
  await expect.poll(async () => (await observation(page)).variables.zoomStart).not.toBe("0");
  const accepted = (await observation(page)).variables;
  for (const width of [390, 1440]) {
    const before = await observation(page);
    const previousWidth = (await canvas(page).boundingBox())!.width;
    await page.setViewportSize({ width, height: 1000 });
    await expect.poll(async () => (await canvas(page).boundingBox())!.width).not.toBe(previousWidth);
    await waitForWorkerRender(page, before.renders);
    expect(shared.requests).toHaveLength(initialCount + 1);
    expect((await observation(page)).variables).toEqual(accepted);
    await expect(page.getByRole("alert")).toHaveCount(0);
    await page.screenshot({ path: evidencePath(`../screenshots/chart-e2e/analysis-resize-${width}.png`), fullPage: true });
  }
  await legend(page);
  await expect.poll(() => shared.requests.length).toBe(initialCount + 2);
  await expect.poll(async () => (await observation(page)).variables.salesVisible).toBe(false);
  expect((await observation(page)).variables.zoomStart).toEqual(accepted.zoomStart);
  expect((await observation(page)).variables.zoomEnd).toEqual(accepted.zoomEnd);
});
