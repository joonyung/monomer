import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import type { DashboardDefinition, DashboardJsonObject, DashboardQueryRun } from "../../src/api/dashboard";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const workspaceId = "019e8000-0000-7000-8000-000000000b81";
const dashboardId = "019e8000-0000-7000-8000-000000000d81";
const sessionId = "019e8000-0000-7000-8000-000000000e81";
const dashboardUrl = `/workspaces/${workspaceId}/dashboards/${dashboardId}?session=${sessionId}`;
const queryNames = ["current", "previous", "left", "right"] as const;
type QueryName = typeof queryNames[number];
type StateMode = "atomic" | "hydration" | "independent" | "reversal" | "latest-rejected" | "initial-loading" | "first-failure" | "display-only" | "limited-input" | "malformed" | "multi-map" | "old-event" | "parameter";

type StateRun = DashboardQueryRun & {
  country: string;
  generation: number;
};

type StateFrame = {
  variables: Record<string, unknown>;
  queries: Record<string, { rows: Record<string, unknown>[] }>;
};

type StateWorkerResponse = {
  type: string;
  phase?: string;
  message?: string;
  variables?: Record<string, unknown>;
};

declare global {
  interface Window {
    stateChartFrames: StateFrame[];
    stateChartWorkerResponses: StateWorkerResponse[];
  }
}

const columns = [
  { name: "dimension.label", logicalType: "TEXT" as const, nullable: false },
  { name: "metric.value", logicalType: "DECIMAL" as const, nullable: false },
];

const atomicSource = `
export default defineChart({
  setup({ chart }) {
    chart.on("click", (_event, frame) => frame.setVariables({ city: "clicked" }));
  },
  render({ queries, variables }) {
    const current = queries.current.rows[0]["dimension.label"];
    const previous = queries.previous.rows[0]["dimension.label"];
    return {
      animation: false,
      title: { text: variables.country + " / " + (variables.city ?? "null"), subtext: current + " | " + previous + " | threshold=" + variables.displayThreshold },
      xAxis: { type: "category", data: ["current", "previous"] },
      yAxis: { type: "value" },
      series: [{ type: "bar", data: [Number(queries.current.rows[0]["metric.value"]), Number(queries.previous.rows[0]["metric.value"])] }],
    };
  },
});`;

const multiMapSource = atomicSource.replace(
  'frame.setVariables({ city: "clicked" })',
  'frame.setVariables({ country: "US", city: "Seattle" })',
);

const malformedMapSource = `
export default defineChart({
  setup({ chart }) {
    let nextCase = 0;
    const malformedMaps = [
      {},
      { undeclared: "x" },
      { reportPeriod: "2026-09-01" },
      { displayThreshold: 10 },
      { country: null },
    ];
    chart.on("click", (_event, frame) => frame.setVariables(malformedMaps[nextCase++] ?? malformedMaps.at(-1)));
  },
  render({ queries }) {
    return {
      animation: false,
      title: { text: "Malformed map admission" },
      xAxis: { type: "category", data: ["current", "previous"] },
      yAxis: { type: "value" },
      series: [{ type: "bar", data: [Number(queries.current.rows[0]["metric.value"]), Number(queries.previous.rows[0]["metric.value"])] }],
    };
  },
});`;

const stateDefinition: DashboardDefinition = {
  schemaVersion: 1,
  meta: { title: "State scenario dashboard", description: "HTTP-barrier Viewer fixture" },
  parameters: {
    reportPeriod: {
      type: "DATE_RANGE", nullable: true, label: "Report period", description: "Optional inclusive date range",
      control: { min: "2026-01-01", max: "2026-12-31", startPlaceholder: "Start date", endPlaceholder: "End date" },
    },
  },
  variables: {
    country: { type: "TEXT", nullable: false, initial: "KR", label: "Country" },
    city: { type: "TEXT", nullable: true, initial: "Seoul", label: "City" },
    displayThreshold: { type: "NUMBER", nullable: false, initial: "10", label: "Display threshold" },
    limitedCountry: { type: "TEXT", nullable: false, initial: "KR", label: "Limited country" },
  },
  queries: Object.fromEntries(queryNames.map(name => [name, { type: "SEMANTIC" as const,
    connectorId: workspaceId,
    query: { schemaVersion: 4, metrics: ["value"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 },
  }])),
  widgets: {
    country: { type: "INPUT", variableName: "country" },
    city: { type: "INPUT", variableName: "city" },
    displayThreshold: { type: "INPUT", variableName: "displayThreshold" },
    limitedCountry: {
      type: "INPUT", variableName: "limitedCountry",
      control: { options: [{ value: "KR", label: "KR" }, { value: "US", label: "US" }] },
    },
    chart: { type: "CHART", queryNames: ["current", "previous"], module: { inputs: { parameters: ["reportPeriod"], variables: ["country", "city", "displayThreshold"], }, source: atomicSource } },
    left: { type: "TABLE", queryName: "left", columns: [{ field: "dimension.label", header: "Left" }] },
    right: { type: "TABLE", queryName: "right", columns: [{ field: "dimension.label", header: "Right" }] },
  },
  layout: {
    sections: [
      {
        id: "inputs", height: { mode: "CONTENT" },
        root: { type: "STACK", id: "input-stack", direction: "ROW", children: [
          { weight: 1, node: { type: "WIDGET", id: "country-item", widgetId: "country" } },
          { weight: 1, node: { type: "WIDGET", id: "city-item", widgetId: "city" } },
          { weight: 1, node: { type: "WIDGET", id: "threshold-item", widgetId: "displayThreshold" } },
          { weight: 1, node: { type: "WIDGET", id: "limited-item", widgetId: "limitedCountry" } },
        ] },
      },
      {
        id: "chart", height: { mode: "SCALED", scale: 1.8 },
        root: { type: "STACK", id: "data-stack", direction: "ROW", children: [
          { weight: 2, node: { type: "WIDGET", id: "chart-item", widgetId: "chart" } },
          { weight: 1, node: { type: "WIDGET", id: "left-item", widgetId: "left" } },
          { weight: 1, node: { type: "WIDGET", id: "right-item", widgetId: "right" } },
        ] },
      },
    ],
  },
};

const initialVariables: DashboardJsonObject = { country: "KR", city: null, displayThreshold: "10", limitedCountry: "KR" };
const initialParameters: DashboardJsonObject = { reportPeriod: null };

function definitionForMode(mode: StateMode): DashboardDefinition {
  if (mode !== "malformed" && mode !== "multi-map") return stateDefinition;
  const chart = stateDefinition.widgets.chart;
  if (chart.type !== "CHART") throw new Error("State fixture chart definition is not a chart");
  return {
    ...stateDefinition,
    widgets: {
      ...stateDefinition.widgets,
      chart: { ...chart, module: { ...chart.module, source: mode === "malformed" ? malformedMapSource : multiMapSource } },
    },
  };
}

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({
    status,
    contentType: status >= 400 ? "application/problem+json" : "application/json",
    body: JSON.stringify(body),
  });
}

function problem(path: string, detail: string, status = 400): Record<string, unknown> {
  return {
    type: "https://monomer.cc/problems/chart-state-fixture",
    title: "State fixture request rejected",
    status,
    detail,
    instance: path,
    code: "STATE_FIXTURE_REJECTED",
    requestId: "state-fixture-request",
    traceId: "state-fixture-trace",
    retryable: false,
  };
}

function frameForRun(run: StateRun) {
  return { queryType: "SEMANTIC" as const, reportingTimeZone: null,
    queryRunId: run.queryRunId,
    columns,
    rows: [[`${run.country}/${run.queryName}/${run.generation}`, String(run.generation + 1)]],
  };
}

function makeRun(runId: string, queryName: QueryName, country: string, generation: number): StateRun {
  return { queryType: "SEMANTIC" as const,
    connectorId: workspaceId,
    queryName,
    queryRunId: runId,
    state: "SUCCEEDED",
    resultAvailable: true,
    outputColumnNames: columns.map(column => column.name),
    failureCode: null,
    failureMessage: null,
    createdAt: `2026-09-07T00:00:0${Math.min(generation, 9)}Z`,
    updatedAt: `2026-09-07T00:00:0${Math.min(generation, 9)}Z`,
    startedBy: null, credentialSurface: null, agentApp: null,
    country,
    generation,
  };
}

function makeBundle(country: string, generation: number): Record<QueryName, StateRun> {
  return Object.fromEntries(queryNames.map(queryName => {
    const run = makeRun(`${country}-${queryName}-${generation}`, queryName, country, generation);
    return [queryName, run];
  })) as Record<QueryName, StateRun>;
}

async function installWorkerCapture(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const NativeWorker = window.Worker;
    window.stateChartFrames = [];
    window.stateChartWorkerResponses = [];
    window.Worker = class extends NativeWorker {
      constructor(...args: ConstructorParameters<typeof Worker>) {
        super(...args);
        let handler: ((event: MessageEvent) => void) | null = null;
        Object.defineProperty(this, "onmessage", { get: () => handler, set: (next) => { handler = next; } });
        this.addEventListener("message", event => {
          window.stateChartWorkerResponses.push(event.data as StateWorkerResponse);
          handler?.(event);
        });
      }

      override postMessage(message: unknown, transferOrOptions?: Transferable[] | StructuredSerializeOptions) {
        const request = message as { type?: string; frame?: StateFrame };
        if (request?.type === "render" && request.frame) window.stateChartFrames.push(request.frame);
        if (Array.isArray(transferOrOptions)) super.postMessage(message, transferOrOptions);
        else super.postMessage(message, transferOrOptions);
      }
    };
  });
}

function fixtureSession(session: string, bindings = initialParameters, mode: StateMode = "display-only") {
  return {
    workspaceId,
    dashboardId,
    dashboardSessionId: session,
    definition: definitionForMode(mode),
    parameterBindings: bindings,
    initialVariables,
    runningQueryRuns: [],
  };
}

type StateFixture = {
  install: () => Promise<void>;
  release: (key: string) => void;
  waitForBlock: (key: string) => Promise<void>;
  waitForRequest: (predicate: (request: { variables: DashboardJsonObject; queryRuns: Record<QueryName, string> }) => boolean) => Promise<{ variables: DashboardJsonObject; queryRuns: Record<QueryName, string> }>;
  requests: { variables: DashboardJsonObject; queryRuns: Record<QueryName, string> }[];
  runs: Map<string, StateRun>;
  openBindings: DashboardJsonObject[];
  acceptedDirectMaps: DashboardJsonObject[];
};

function createFixture(page: Page, mode: StateMode): StateFixture {
  const runs = new Map<string, StateRun>();
  const requests: { variables: DashboardJsonObject; queryRuns: Record<QueryName, string> }[] = [];
  const openBindings: DashboardJsonObject[] = [];
  const acceptedDirectMaps: DashboardJsonObject[] = [];
  const bundlesByCountry = new Map<string, Record<QueryName, StateRun>>();
  const blockers = new Map<string, { promise: Promise<void>; release: () => void }>();
  const waiters: ((request: { variables: DashboardJsonObject; queryRuns: Record<QueryName, string> }) => void)[] = [];
  let generation = 0;
  let initialResolveHeld = mode === "initial-loading";
  let initialFailureSent = false;
  let activeSession = sessionId;

  const block = (key: string) => {
    if (blockers.has(key)) return;
    let release!: () => void;
    const promise = new Promise<void>(resolve => { release = resolve; });
    blockers.set(key, { promise, release });
  };
  const release = (key: string) => blockers.get(key)?.release();
  const waitForBlock = (key: string) => new Promise<void>((resolve, reject) => {
    const startedAt = Date.now();
    const check = () => {
      if (blockers.has(key)) { resolve(); return; }
      if (Date.now() - startedAt > 10_000) { reject(new Error(`Timed out waiting for fixture blocker '${key}'`)); return; }
      setTimeout(check, 10);
    };
    check();
  });
  const awaitBlock = async (key: string) => {
    const blocker = blockers.get(key);
    if (blocker) await blocker.promise;
  };
  const bundle = (country: string) => {
    const existing = bundlesByCountry.get(country);
    if (existing) return existing;
    const next = makeBundle(country, generation++);
    Object.values(next).forEach(run => runs.set(run.queryRunId, run));
    bundlesByCountry.set(country, next);
    return next;
  };
  const runMap = (next: Record<QueryName, StateRun>) => Object.fromEntries(queryNames.map(name => [name, next[name].queryRunId]));
  const notifyRequest = (request: { variables: DashboardJsonObject; queryRuns: Record<QueryName, string> }) => {
    requests.push(request);
    const pending = waiters.splice(0);
    pending.forEach(waiter => waiter(request));
  };
  const waitForRequest = (predicate: (request: { variables: DashboardJsonObject; queryRuns: Record<QueryName, string> }) => boolean) => {
    const existing = requests.find(predicate);
    if (existing) return Promise.resolve(existing);
    return new Promise<{ variables: DashboardJsonObject; queryRuns: Record<QueryName, string> }>(resolve => {
      const waiter = (request: { variables: DashboardJsonObject; queryRuns: Record<QueryName, string> }) => {
        if (predicate(request)) resolve(request);
        else waiters.push(waiter);
      };
      waiters.push(waiter);
    });
  };

  return {
    requests,
    runs,
    openBindings,
    acceptedDirectMaps,
    release,
    waitForBlock,
    waitForRequest,
    install: async () => {
      await page.setViewportSize({ width: 1440, height: 1000 });
      await installWorkerCapture(page);
      await mockSignedInWorkspace(page, {
        workspaceId,
        workspaceName: "State Workspace",
        organizationId: "state-organization",
        role: "EXPLORER",
        user: { id: "state-user", email: "state@example.test", displayName: "State User" },
      });
      await page.route("**/v2/workspaces/**", async route => {
        const request = route.request();
        const url = new URL(request.url());
        const path = url.pathname;
        const success = (body: unknown) => json(route, body);
        if (path.endsWith("/events")) {
          await route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: connected\ndata: ${JSON.stringify({ sessionId: activeSession, reason: "CONNECTED", observedAt: "2026-09-07T00:00:00Z" })}\n\n` });
          return;
        }
        if (path.endsWith(":resolve")) {
          const body = request.postDataJSON() as { variables: DashboardJsonObject };
          const variables = body.variables;
          if (mode === "limited-input" && variables.limitedCountry === "JP") {
            acceptedDirectMaps.push(variables);
          }
          if (mode === "malformed" && (typeof variables.displayThreshold !== "string" || variables.undeclared !== undefined || variables.parameterWrite !== undefined)) {
            await json(route, problem(path, "The complete variable map is invalid"), 400);
            return;
          }
          const country = typeof variables.country === "string" ? variables.country : "KR";
          const next = bundle(country);
          const requestRecord = { variables, queryRuns: runMap(next) as Record<QueryName, string> };
          notifyRequest(requestRecord);
          if (mode === "initial-loading" && !initialResolveHeld) initialResolveHeld = true;
          if (mode === "initial-loading" && country === "KR" && initialResolveHeld) {
            block("initial-resolve");
            initialResolveHeld = false;
            await awaitBlock("initial-resolve");
          }
          if (mode === "first-failure" && !initialFailureSent && country === "KR") {
            initialFailureSent = true;
            Object.assign(next.left, { state: "FAILED", resultAvailable: false, failureCode: "QUERY_FAILED", failureMessage: "Initial execution failed" });
          }
          if (mode === "reversal" && country === "US") {
            block("resolve-US");
            await awaitBlock("resolve-US");
          }
          if (mode === "latest-rejected" && country === "US") {
            block("resolve-late-US");
            await awaitBlock("resolve-late-US");
          }
          if (mode === "latest-rejected" && country === "JP") {
            await json(route, problem(path, "Latest input was rejected"), 400);
            return;
          }
          await success({ variables, queryRuns: requestRecord.queryRuns });
          return;
        }
        if (path.endsWith(":refresh")) {
          const body = request.postDataJSON() as { variables: DashboardJsonObject; queryNames: QueryName[] };
          const country = typeof body.variables.country === "string" ? body.variables.country : "KR";
          const next = bundle(country);
          if (mode === "first-failure") {
            for (const name of body.queryNames) {
              const replacement = makeRun(`${country}-${name}-${generation}`, name, country, generation++);
              next[name] = replacement;
              runs.set(replacement.queryRunId, replacement);
            }
          }
          const requestRecord = { variables: body.variables, queryRuns: runMap(next) as Record<QueryName, string> };
          notifyRequest(requestRecord);
          await success({ variables: body.variables, queryRuns: requestRecord.queryRuns });
          return;
        }
        if (request.method() === "POST" && path.endsWith("/sessions")) {
          const body = request.postDataJSON() as { parameterBindings: DashboardJsonObject };
          openBindings.push(body.parameterBindings);
          activeSession = "019e8000-0000-7000-8000-000000000e82";
          await json(route, { dashboardId, workspaceId, dashboardSessionId: activeSession, parameterBindings: body.parameterBindings }, 202);
          return;
        }
        if (path.endsWith("/sessions")) {
          await success({ items: [{ dashboardId, dashboardSessionId: activeSession, parameterBindings: initialParameters, createdAt: "2026-09-07T00:00:00Z", updatedAt: "2026-09-07T00:00:00Z", runningQueryRuns: [] }], nextCursor: null });
          return;
        }
        const sessionMatch = path.match(/\/dashboard-sessions\/([^/]+)$/);
        if (sessionMatch) {
          await success(fixtureSession(sessionMatch[1], openBindings.at(-1) ?? initialParameters, mode));
          return;
        }
        const resultMatch = path.match(/\/dashboard-sessions\/[^/]+\/query-runs\/([^/]+)\/result$/);
        if (resultMatch) {
          const run = runs.get(resultMatch[1]);
          if (!run) { await json(route, problem(path, "Unknown result"), 404); return; }
          if (mode === "hydration" && run.country === "US" && run.queryName === "previous") {
            block(`result-${run.queryRunId}`);
            await awaitBlock(`result-${run.queryRunId}`);
          }
          if (mode === "old-event" && run.country === "US" && run.queryName === "current") {
            block(`result-${run.queryRunId}`);
            await awaitBlock(`result-${run.queryRunId}`);
          }
          await success(frameForRun(run));
          return;
        }
        const runMatch = path.match(/\/dashboard-sessions\/[^/]+\/query-runs\/([^/]+)$/);
        if (runMatch) {
          const run = runs.get(runMatch[1]);
          if (!run) { await json(route, problem(path, "Unknown run"), 404); return; }
          if (mode === "atomic" && run.country === "US" && run.queryName === "previous") {
            block(`run-${run.queryRunId}`);
            await awaitBlock(`run-${run.queryRunId}`);
          }
          if (mode === "independent" && run.country === "US" && run.queryName === "left") {
            block(`run-${run.queryRunId}`);
            await awaitBlock(`run-${run.queryRunId}`);
          }
          await success(run);
          return;
        }
        if (path.endsWith("/query-runs")) {
          await success({ items: [...runs.values()], nextCursor: null });
          return;
        }
        if (path.endsWith(`/dashboards/${dashboardId}`)) {
          await success({ workspaceId, dashboardId, definition: definitionForMode(mode), createdAt: "2026-09-07T00:00:00Z", updatedAt: "2026-09-07T00:00:00Z" });
          return;
        }
        await success({ items: [] });
      });
      await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, dashboardUrl);
    },
  };
}

async function applyInput(page: Page, label: string, value: string): Promise<void> {
  const variableName = label.split(" ").map((part, index) => index === 0 ? part.toLowerCase() : part[0].toUpperCase() + part.slice(1).toLowerCase()).join("");
  const editor = page.getByTestId(`dashboard-variable-${variableName}`);
  await editor.getByRole("textbox", { name: label, exact: true }).fill(value);
  await editor.getByRole("button", { name: `Apply ${label}`, exact: true }).click();
}

async function openLimitedCountryChoices(page: Page) {
  await page.getByTestId("dashboard-variable-limitedCountry").getByRole("combobox", { name: "Limited country", exact: true }).click();
  return page.getByRole("listbox", { name: "Limited country", exact: true });
}

// A choice applies as soon as it is picked; there is no separate Apply for choices.
async function selectLimitedCountry(page: Page, value: string): Promise<void> {
  await (await openLimitedCountryChoices(page)).getByRole("option", { name: value, exact: true }).click();
  await expect(page.getByTestId("dashboard-variable-limitedCountry").getByRole("button", { name: /^Apply / })).toHaveCount(0);
}

async function openChangeInputsDialog(page: Page) {
  await page.getByRole("region", { name: "Inputs and results" }).getByRole("button", { name: "Change inputs", exact: true }).click();
  return page.getByRole("dialog", { name: "Change inputs" });
}

function chartCanvas(page: Page) {
  return page.getByTestId("dashboard-chart-widget").locator("canvas");
}

async function chartRenderedAtContainerWidth(page: Page): Promise<void> {
  await expect.poll(() => page.getByTestId("dashboard-chart-widget").evaluate(container => container.querySelector("canvas")!.width === container.clientWidth * window.devicePixelRatio)).toBe(true);
  await expect.poll(() => page.evaluate(() => window.stateChartWorkerResponses.some(response => response.type === "rendered"))).toBe(true);
}

async function expectChartUpdatingAndInert(page: Page): Promise<void> {
  await expect(page.locator('[data-capture-widget-id="chart"]').getByRole("status")).toHaveText("Updating results");
  await expect(chartCanvas(page).locator("xpath=ancestor::*[@inert]")).toHaveCount(1);
}

async function initialReady(page: Page): Promise<void> {
  await expect(page.getByRole("gridcell", { name: /KR\/left\/0/ })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("gridcell", { name: /KR\/right\/0/ })).toBeVisible({ timeout: 15_000 });
  await expect.poll(() => page.evaluate(() => window.stateChartFrames.length)).toBeGreaterThan(0);
}

async function latestFrame(page: Page): Promise<StateFrame> {
  return page.evaluate(() => window.stateChartFrames.at(-1)!);
}

function frameLabels(frame: StateFrame): { current: unknown; previous: unknown; variables: Record<string, unknown> } {
  return {
    current: frame.queries.current?.rows[0]?.["dimension.label"],
    previous: frame.queries.previous?.rows[0]?.["dimension.label"],
    variables: frame.variables,
  };
}

test.describe("B · Viewer state barriers", () => {
  test("B03 adopts current and previous query results as one atomic bundle", async ({ page }) => {
    const fixture = createFixture(page, "atomic");
    await fixture.install();
    await initialReady(page);
    const before = frameLabels(await latestFrame(page));
    await applyInput(page, "Country", "US");
    const request = await fixture.waitForRequest(candidate => candidate.variables.country === "US");
    await fixture.waitForBlock(`run-${request.queryRuns.previous}`);
    await expect.poll(async () => frameLabels(await latestFrame(page))).toEqual(before);
    fixture.release(`run-${request.queryRuns.previous}`);
    await expect.poll(async () => frameLabels(await latestFrame(page))).toMatchObject({ current: "US/current/1", previous: "US/previous/1" });
    const frames = await page.evaluate(() => window.stateChartFrames.map(frame => ({ current: frame.queries.current?.rows[0]?.["dimension.label"], previous: frame.queries.previous?.rows[0]?.["dimension.label"] })));
    expect(frames).not.toContainEqual({ current: "US/current/1", previous: "KR/previous/0" });
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/B03-atomic-query-bundle.png"), fullPage: true });
  });

  test("B04 waits for every result artifact before hydrating a new frame", async ({ page }) => {
    const fixture = createFixture(page, "hydration");
    await fixture.install();
    await initialReady(page);
    const before = frameLabels(await latestFrame(page));
    await applyInput(page, "Country", "US");
    const request = await fixture.waitForRequest(candidate => candidate.variables.country === "US");
    await expect.poll(async () => frameLabels(await latestFrame(page))).toEqual(before);
    await fixture.waitForBlock(`result-${request.queryRuns.previous}`);
    fixture.release(`result-${request.queryRuns.previous}`);
    await expect.poll(async () => frameLabels(await latestFrame(page))).toMatchObject({ current: "US/current/1", previous: "US/previous/1" });
    const frames = await page.evaluate(() => window.stateChartFrames.map(frame => ({ current: frame.queries.current?.rows[0]?.["dimension.label"], previous: frame.queries.previous?.rows[0]?.["dimension.label"] })));
    expect(frames).not.toContainEqual({ current: "US/current/1", previous: "KR/previous/0" });
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/B04-result-hydration.png"), fullPage: true });
  });

  test("B06 keeps an independent widget visible while its sibling waits", async ({ page }) => {
    const fixture = createFixture(page, "independent");
    await fixture.install();
    await initialReady(page);
    await applyInput(page, "Country", "US");
    const request = await fixture.waitForRequest(candidate => candidate.variables.country === "US");
    await fixture.waitForBlock(`run-${request.queryRuns.left}`);
    await expect(page.getByRole("gridcell", { name: /US\/right\/1/ })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole("gridcell", { name: /KR\/left\/0/ })).toBeVisible({ timeout: 15_000 });
    fixture.release(`run-${request.queryRuns.left}`);
    await expect(page.getByRole("gridcell", { name: /US\/left\/1/ })).toBeVisible({ timeout: 15_000 });
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/B06-widget-independent.png"), fullPage: true });
  });

  test("B01 keeps the first Viewer entry in a real loading state until resolve completes", async ({ page }) => {
    const fixture = createFixture(page, "initial-loading");
    await fixture.install();
    await expect.poll(() => fixture.requests.length).toBe(1);
    await expect(page.getByRole("gridcell", { name: /KR\/left\/0/ })).toHaveCount(0);
    await expect(page.getByText("Loading results…", { exact: true })).toHaveCount(3);
    await fixture.waitForBlock("initial-resolve");
    fixture.release("initial-resolve");
    await initialReady(page);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/B01-first-entry-ready.png"), fullPage: true });
  });

  test("B05 first failed execution has no fabricated result and recovers only after Retry", async ({ page }) => {
    await page.clock.install();
    const fixture = createFixture(page, "first-failure");
    await fixture.install();
    const failedWidget = page.locator('[data-capture-widget-id="left"]');
    const firstFailure = failedWidget.getByText("This table couldn't load.", { exact: true });
    await expect(firstFailure).toBeVisible();
    await expect(failedWidget.getByText("Initial execution failed", { exact: true })).toBeVisible();
    await expect(page.getByRole("gridcell", { name: /KR\/left/ })).toHaveCount(0);
    await expect(page.getByRole("gridcell", { name: /KR\/right\/0/ })).toBeVisible();
    const runCount = fixture.runs.size;
    await page.clock.fastForward(2_200);
    expect(fixture.requests).toHaveLength(1);
    expect(fixture.runs.size).toBe(runCount);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/B05-first-failure.png"), fullPage: true });
    await failedWidget.getByRole("button", { name: "Retry", exact: true }).click();
    await expect(page.getByRole("gridcell", { name: /KR\/left\/1/ })).toBeVisible();
    await expect(firstFailure).toHaveCount(0);
    expect(fixture.requests).toHaveLength(2);
    expect(fixture.runs.size).toBe(runCount + 1);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/B05-first-recovery.png"), fullPage: true });
  });

  test("B05 retains a successful frame and shows a recoverable latest failure", async ({ page, consoleErrors }) => {
    consoleErrors.allow({ status: 400, url: /:resolve$/, reason: "The fixture rejects the latest input." });
    const fixture = createFixture(page, "latest-rejected");
    await fixture.install();
    await initialReady(page);
    const before = frameLabels(await latestFrame(page));
    await applyInput(page, "Country", "US");
    await fixture.waitForRequest(candidate => candidate.variables.country === "US");
    await fixture.waitForBlock("resolve-late-US");
    await applyInput(page, "Country", "JP");
    await expect(page.getByRole("alert")).toContainText("Latest input was rejected");
    await expect.poll(async () => frameLabels(await latestFrame(page))).toEqual(before);
    const lateResponse = page.waitForResponse(response => response.url().endsWith(":resolve") && response.request().postDataJSON().variables.country === "US");
    fixture.release("resolve-late-US");
    await (await lateResponse).finished();
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect.poll(async () => frameLabels(await latestFrame(page))).toEqual(before);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/B05-latest-failure-retains-last-good.png"), fullPage: true });
  });

  test("B02 keeps the previous complete result while a new input is pending", async ({ page }) => {
    const fixture = createFixture(page, "atomic");
    await fixture.install();
    await initialReady(page);
    const before = frameLabels(await latestFrame(page));
    await applyInput(page, "Country", "US");
    const request = await fixture.waitForRequest(candidate => candidate.variables.country === "US");
    await fixture.waitForBlock(`run-${request.queryRuns.previous}`);
    await expect.poll(async () => frameLabels(await latestFrame(page))).toEqual(before);
    await expect(page.locator('[data-capture-widget-id="chart"]').getByRole("status")).toHaveText("Updating results");
    fixture.release(`run-${request.queryRuns.previous}`);
    await expect(page.getByRole("gridcell", { name: /US\/left\/1/ })).toBeVisible({ timeout: 15_000 });
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/B02-input-pending.png"), fullPage: true });
  });

  test("B07 opens new results for changed inputs without carrying the previous result bundle", async ({ page }) => {
    const fixture = createFixture(page, "parameter");
    await fixture.install();
    await initialReady(page);
    await applyInput(page, "Country", "US");
    const usRequest = await fixture.waitForRequest(candidate => candidate.variables.country === "US");
    const usGeneration = fixture.runs.get(usRequest.queryRuns.current)?.generation;
    await expect.poll(async () => frameLabels(await latestFrame(page))).toMatchObject({ current: `US/current/${usGeneration}`, previous: `US/previous/${usGeneration}` });
    await expect(page.getByRole("complementary", { name: "History" })).toHaveCount(0);
    const dialog = await openChangeInputsDialog(page);
    await expect(dialog).toBeVisible();
    const submit = dialog.getByRole("button", { name: "Show results", exact: true });
    await expect(dialog.getByRole("checkbox", { name: "No value" })).toBeChecked();
    await expect(submit).toBeDisabled();
    await dialog.getByRole("checkbox", { name: "No value" }).uncheck();
    await dialog.getByRole("textbox", { name: "Report period start" }).fill("2026-09-01");
    await dialog.getByRole("textbox", { name: "Report period end" }).fill("2026-09-07");
    await expect(dialog.getByText("Changed from Any", { exact: true })).toBeVisible();
    await expect(submit).toBeEnabled();
    await submit.click();
    await expect.poll(() => fixture.openBindings.at(-1)).toEqual({ reportPeriod: { start: "2026-09-01", end: "2026-09-07" } });
    await expect(page).toHaveURL(new RegExp("session=019e8000-0000-7000-8000-000000000e82"));
    await expect.poll(async () => frameLabels(await latestFrame(page))).toMatchObject({ current: "KR/current/0", previous: "KR/previous/0" });
    await expect(page.getByRole("gridcell", { name: /KR\/left\/0/ })).toBeVisible();
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/B07-session-boundary.png"), fullPage: true });
  });

  test("Run again opens new results with the same inputs", async ({ page }) => {
    const fixture = createFixture(page, "parameter");
    await fixture.install();
    await initialReady(page);
    await page.getByRole("button", { name: "More actions", exact: true }).click();
    await page.getByRole("menuitem", { name: "Run again", exact: true }).click();
    await expect.poll(() => fixture.openBindings.length).toBe(1);
    await expect(page).toHaveURL(new RegExp("session=019e8000-0000-7000-8000-000000000e82"));
    await expect(page.getByRole("dialog", { name: "Change inputs" })).toHaveCount(0);
  });
});

test.describe("C · Viewer input admission", () => {
  test("C01 discards a late US resolve after the newer JP request completes", async ({ page }) => {
    const fixture = createFixture(page, "reversal");
    await fixture.install();
    await initialReady(page);
    await applyInput(page, "Country", "US");
    await fixture.waitForRequest(candidate => candidate.variables.country === "US");
    await fixture.waitForBlock("resolve-US");
    await applyInput(page, "Country", "JP");
    const jpRequest = await fixture.waitForRequest(candidate => candidate.variables.country === "JP");
    const jpGeneration = fixture.runs.get(jpRequest.queryRuns.current)?.generation;
    await expect.poll(async () => frameLabels(await latestFrame(page))).toMatchObject({ current: `JP/current/${jpGeneration}`, previous: `JP/previous/${jpGeneration}` });
    const lateResponse = page.waitForResponse(response => response.url().endsWith(":resolve") && response.request().postDataJSON().variables.country === "US");
    fixture.release("resolve-US");
    await (await lateResponse).finished();
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect.poll(async () => frameLabels(await latestFrame(page))).toMatchObject({ current: `JP/current/${jpGeneration}`, previous: `JP/previous/${jpGeneration}` });
    await expect(page.getByRole("textbox", { name: "Country", exact: true })).toHaveValue("JP");
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/C01-response-reversal.png"), fullPage: true });
  });

  test("C02 submits country and city together without an intermediate map", async ({ page }) => {
    const fixture = createFixture(page, "multi-map");
    await fixture.install();
    await initialReady(page);
    const before = fixture.requests.length;
    await chartRenderedAtContainerWidth(page);
    const box = (await chartCanvas(page).boundingBox())!;
    await page.mouse.click(box.x + box.width * 0.35, box.y + box.height * 0.75);
    await expect.poll(() => page.evaluate(() => window.stateChartWorkerResponses.filter(response => response.type === "variables_set").length)).toBe(1);
    const workerSubmission = await page.evaluate(() => window.stateChartWorkerResponses.find(response => response.type === "variables_set"));
    expect(workerSubmission).toMatchObject({ type: "variables_set", variables: { country: "US", city: "Seattle" } });
    await expect.poll(() => fixture.requests.length).toBe(before + 1);
    const latest = fixture.requests.at(-1)!;
    expect(latest.variables).toMatchObject({ country: "US", city: "Seattle" });
    expect(fixture.requests.slice(before).some(request => request.variables.country === "US" && request.variables.city === null)).toBe(false);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/C02-multi-variable-map.png"), fullPage: true });
  });

  test("C03 rejects malformed maps in the native Worker without submitting variables", async ({ page }) => {
    const fixture = createFixture(page, "malformed");
    await fixture.install();
    await initialReady(page);
    await page.evaluate(() => { window.stateChartWorkerResponses = []; });
    const chart = page.getByTestId("dashboard-chart-widget").locator("canvas");
    const box = (await chart.boundingBox())!;
    for (let index = 0; index < 5; index += 1) {
      await page.mouse.click(box.x + box.width * 0.35, box.y + box.height * 0.75);
      await expect.poll(() => page.evaluate(() => window.stateChartWorkerResponses.filter(response => response.type === "failed" && response.phase === "event").length)).toBe(index + 1);
    }
    const responses = await page.evaluate(() => window.stateChartWorkerResponses);
    expect(responses.filter(response => response.type === "variables_set")).toEqual([]);
    expect(responses.filter(response => response.type === "failed" && response.phase === "event")).toHaveLength(5);
    await expect(page.getByRole("gridcell", { name: /KR\/left\/0/ })).toBeVisible();
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/C03-malformed-map-rejected.png"), fullPage: true });
  });

  test("C04 rejects a chart event from the old displayed frame", async ({ page }) => {
    const fixture = createFixture(page, "old-event");
    await fixture.install();
    await initialReady(page);
    await chartRenderedAtContainerWidth(page);
    const before = fixture.requests.length;
    await applyInput(page, "Country", "US");
    const request = await fixture.waitForRequest(candidate => candidate.variables.country === "US");
    await fixture.waitForBlock(`result-${request.queryRuns.current}`);
    await expectChartUpdatingAndInert(page);
    const box = (await chartCanvas(page).boundingBox())!;
    await page.mouse.click(box.x + box.width * 0.35, box.y + box.height * 0.75);
    expect(fixture.requests).toHaveLength(before + 1);
    expect(await latestFrame(page)).toMatchObject({ variables: { country: "KR" } });
    await expect(page.getByRole("textbox", { name: "Country", exact: true })).toHaveValue("US");
    fixture.release(`result-${request.queryRuns.current}`);
    await expect.poll(async () => frameLabels(await latestFrame(page))).toMatchObject({ current: "US/current/1", previous: "US/previous/1" });
    await expect(page.locator('[data-capture-widget-id="chart"]').getByRole("status")).toHaveCount(0);
    expect(await page.evaluate(() => window.stateChartWorkerResponses.filter(response => response.type === "variables_set"))).toEqual([]);
    expect(fixture.requests).toHaveLength(before + 1);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/C04-old-chart-event.png"), fullPage: true });
  });

  test("C05 keeps the latest rejection after a late previous success", async ({ page, consoleErrors }) => {
    consoleErrors.allow({ status: 400, url: /:resolve$/, reason: "The fixture rejects the latest input." });
    const fixture = createFixture(page, "latest-rejected");
    await fixture.install();
    await initialReady(page);
    await applyInput(page, "Country", "US");
    await fixture.waitForRequest(candidate => candidate.variables.country === "US");
    await fixture.waitForBlock("resolve-late-US");
    await applyInput(page, "Country", "JP");
    await expect(page.getByRole("alert")).toContainText("Latest input was rejected");
    const lateResponse = page.waitForResponse(response => response.url().endsWith(":resolve") && response.request().postDataJSON().variables.country === "US");
    fixture.release("resolve-late-US");
    await (await lateResponse).finished();
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(page.getByRole("alert")).toContainText("Latest input was rejected");
    await expect(page.getByRole("textbox", { name: "Country", exact: true })).toHaveValue("KR");
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/C05-latest-rejection-persists.png"), fullPage: true });
  });

  test("C06 changes a display-only variable while reusing the same query runs", async ({ page }) => {
    const fixture = createFixture(page, "display-only");
    await fixture.install();
    await initialReady(page);
    const beforeRuns = [...fixture.runs.keys()];
    await applyInput(page, "Display threshold", "20");
    await expect.poll(() => fixture.requests.at(-1)?.variables.displayThreshold).toBe("20");
    await expect.poll(async () => (await latestFrame(page)).variables.displayThreshold).toBe("20");
    expect([...fixture.runs.keys()]).toEqual(beforeRuns);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/C06-display-only-variable.png"), fullPage: true });
  });

  test("C07 keeps UI choices bounded while a direct type-valid value remains admissible", async ({ page }) => {
    const fixture = createFixture(page, "limited-input");
    await fixture.install();
    await initialReady(page);
    const choices = await openLimitedCountryChoices(page);
    await expect(choices.getByRole("option")).toHaveText(["KR", "US"]);
    await page.keyboard.press("Escape");
    await expect(choices).toHaveCount(0);
    const endpoint = `/v2/workspaces/${workspaceId}/dashboard-sessions/${sessionId}:resolve`;
    const response = await page.evaluate(async ({ url }) => {
      const response = await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ variables: { ...window.stateChartFrames.at(-1)?.variables, limitedCountry: "JP" } }) });
      return { status: response.status, body: await response.json() };
    }, { url: endpoint });
    expect(response.status).toBe(200);
    expect(fixture.acceptedDirectMaps.at(-1)?.limitedCountry).toBe("JP");
    await selectLimitedCountry(page, "US");
    await expect.poll(() => fixture.requests.at(-1)?.variables.limitedCountry).toBe("US");
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/C07-input-contract.png"), fullPage: true });
  });

  test("C08 submits valid, nullable, and invalid range endpoint states", async ({ page }) => {
    const fixture = createFixture(page, "parameter");
    await fixture.install();
    await initialReady(page);
    const dialog = await openChangeInputsDialog(page);
    const period = dialog.getByTestId("dashboard-parameter-reportPeriod");
    await period.getByRole("checkbox", { name: "No value" }).uncheck();
    await period.getByRole("textbox", { name: "Report period start" }).fill("2026-09-01");
    await period.getByRole("textbox", { name: "Report period end" }).fill("2026-09-07");
    await expect(dialog.getByRole("button", { name: "Show results" })).toBeEnabled();
    await dialog.getByRole("button", { name: "Show results" }).click();
    await expect.poll(() => fixture.openBindings.at(-1)).toEqual({ reportPeriod: { start: "2026-09-01", end: "2026-09-07" } });
    await expect(page).toHaveURL(new RegExp("session=019e8000-0000-7000-8000-000000000e82"));
    await expect(dialog).toHaveCount(0);
    const nullableDialog = await openChangeInputsDialog(page);
    const nullablePeriod = nullableDialog.getByTestId("dashboard-parameter-reportPeriod");
    await expect(nullablePeriod.getByRole("textbox", { name: "Report period start" })).toHaveValue("2026-09-01");
    await nullablePeriod.getByRole("checkbox", { name: "No value" }).check();
    await nullableDialog.getByRole("button", { name: "Show results" }).click();
    await expect.poll(() => fixture.openBindings.at(-1)).toEqual({ reportPeriod: null });
    await expect(nullableDialog).toHaveCount(0);
    const invalidDialog = await openChangeInputsDialog(page);
    const invalidPeriod = invalidDialog.getByTestId("dashboard-parameter-reportPeriod");
    await invalidPeriod.getByRole("checkbox", { name: "No value" }).uncheck();
    await invalidPeriod.getByRole("textbox", { name: "Report period start" }).fill("2026-09-10");
    await invalidPeriod.getByRole("textbox", { name: "Report period end" }).fill("2026-09-07");
    await expect(invalidDialog.getByRole("button", { name: "Show results" })).toBeDisabled();
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/state/C08-nullable-range.png"), fullPage: true });
  });
});
