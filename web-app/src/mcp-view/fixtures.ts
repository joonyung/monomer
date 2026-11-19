// Test-only fixtures for the MCP Apps Dashboard view, shared by its unit tests and e2e spec:
// monomer_show results, monomer_read answers, and a minimal host page that speaks the MCP Apps
// postMessage protocol to the built view in a sandboxed iframe. The view's runtime never imports it.

export const WORKSPACE_ID = "01920000-0000-7000-8000-0000000000aa";
export const DASHBOARD_ID = "01920000-0000-7000-8000-0000000000d1";
export const SESSION_ID = "01920000-0000-7000-8000-0000000000e1";
export const DRAFT_PATH = `workspace/dashboards/${DASHBOARD_ID}/draft`;
export const DRAFT_STATUS_PATH = `${DRAFT_PATH}/status`;
export const SESSION_PATH = `workspace/dashboards/${DASHBOARD_ID}/sessions/${SESSION_ID}`;
export const WEB_URL = `https://app.monomer.test/workspaces/${WORKSPACE_ID}/dashboards/${DASHBOARD_ID}/builder`;

export const draftRun = (suffix: string) => `${DRAFT_PATH}/query-runs/01920000-0000-7000-8000-0000000001${suffix}`;
export const sessionRun = (suffix: string) => `${SESSION_PATH}/query-runs/01920000-0000-7000-8000-0000000002${suffix}`;
export const REVENUE_RUN = draftRun("01");
export const ORDERS_RUN = draftRun("02");
export const ORDERS_RUN_NEXT = draftRun("03");

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type ToolResult = { isError: boolean; content: { type: "text"; text: string }[]; structuredContent?: Json; _meta?: Json };

const CHART_SOURCE = `export default defineChart({ render({ queries }) {
  const rows = queries.revenue.rows;
  return { animation: false, xAxis: { type: "category", data: rows.map((row) => row["dimension.region"]) }, yAxis: { type: "value" },
    series: [{ type: "bar", data: rows.map((row) => Number(row["metric.revenue"])) }] };
} });`;
const TABLE_SOURCE = `export default defineTable({ render() {
  return { cell({ field, value }) { return field === "metric.orders" ? { text: value + " orders" } : undefined; } };
} });`;

export function draftDefinition(options: { notes: string; formattedTable: boolean }): Json {
  return {
    schemaVersion: 1,
    meta: { title: "Weekly revenue", description: null },
    parameters: {},
    variables: {},
    queries: {
      revenue: { type: "SQL", connectorId: "01920000-0000-7000-8000-0000000000c1", sql: "select region, revenue from weekly", bindings: {}, columns: ["dimension.region", "metric.revenue"], rowLimit: 1000 },
      orders: { type: "SQL", connectorId: "01920000-0000-7000-8000-0000000000c1", sql: "select region, orders from weekly", bindings: {}, columns: ["dimension.region", "metric.orders"], rowLimit: 1000 },
    },
    widgets: {
      notes: { type: "TEXT", content: options.notes },
      revenue_chart: { type: "CHART", title: "Revenue by region", queryNames: ["revenue"], module: { inputs: { parameters: [], variables: [] }, source: CHART_SOURCE } },
      orders_table: {
        type: "TABLE",
        title: "Orders by region",
        queryName: "orders",
        columns: [{ field: "dimension.region", header: "Region" }, { field: "metric.orders", header: "Orders" }],
        ...(options.formattedTable ? { module: { inputs: { queries: [], parameters: [], variables: [] }, source: TABLE_SOURCE } } : {}),
      },
    },
    layout: {
      sections: [
        { id: "intro", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "notes_node", widgetId: "notes" } },
        {
          id: "main",
          height: { mode: "SCALED", scale: 1 },
          root: {
            type: "STACK", id: "main_row", direction: "ROW",
            children: [
              { weight: 1, node: { type: "WIDGET", id: "chart_node", widgetId: "revenue_chart" } },
              { weight: 1, node: { type: "WIDGET", id: "table_node", widgetId: "orders_table" } },
            ],
          },
        },
      ],
    },
  };
}

function readSuccess(kind: string, path: string, body: Json, revision?: string): ToolResult {
  const structuredContent: Json = { requestId: `read-${path.length}`, kind, path, ...(revision === undefined ? {} : { revision }), body, availableOperations: [] };
  return { isError: false, content: [{ type: "text", text: JSON.stringify(structuredContent) }], structuredContent, _meta: { "cc.monomer/viewContract": 1 } };
}

export function readProblem(status: number, code: string, detail: string): ToolResult {
  return { isError: true, content: [{ type: "text", text: JSON.stringify({ type: "about:blank", title: "Problem", status, code, detail }) }] };
}

const bundle = (revision: string, queryRuns: Record<string, string>) => ({ authoringRevision: revision, parameterBindings: {}, variables: {}, queryRuns });

export function draftRead(options: { revision: string; notes: string; formattedTable: boolean; ordersRun: string }): ToolResult {
  return readSuccess("DASHBOARD_DRAFT", DRAFT_PATH, {
    definition: draftDefinition(options),
    parameterBindings: {},
    variables: {},
    targetQueryRuns: { revenue: REVENUE_RUN, orders: options.ordersRun },
    readyResults: { revenue_chart: bundle(options.revision, { revenue: REVENUE_RUN }), orders_table: bundle(options.revision, { orders: options.ordersRun }) },
    createdAt: "2026-10-10T09:00:00Z",
    updatedAt: "2026-10-10T09:00:00Z",
  }, options.revision);
}

// The orders target may be a newer run than the one in the ready bundle, as while it runs or after it fails.
export function draftStatusRead(options: { revision: string; ordersRun: string; ordersState?: "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED"; readyOrdersRun?: string }): ToolResult {
  return readSuccess("DASHBOARD_DRAFT_STATUS", DRAFT_STATUS_PATH, {
    draftRevision: options.revision,
    targetQueryRuns: { revenue: { path: REVENUE_RUN, state: "SUCCEEDED" }, orders: { path: options.ordersRun, state: options.ordersState ?? "SUCCEEDED" } },
    readyResults: { revenue_chart: bundle(options.revision, { revenue: REVENUE_RUN }), orders_table: bundle(options.revision, { orders: options.readyOrdersRun ?? options.ordersRun }) },
  });
}

export function resultRead(runPath: string, columns: string[], rows: (string | null)[][]): ToolResult {
  return readSuccess("DASHBOARD_QUERY_RESULT", `${runPath}/result`, {
    queryType: "SQL",
    columns: columns.map((name) => ({ name, logicalType: name.startsWith("metric.") ? "NUMBER" : "TEXT", nullable: !name.startsWith("dimension."), display: null })),
    rows,
    totalRowCount: String(rows.length),
    returnedRowCount: rows.length,
    truncated: false,
    reportingTimeZone: null,
  });
}

// A pivot of the orders query whose grand total comes from its own named query.
export const ORDERS_TOTAL_RUN = draftRun("04");
export const ordersTotalQuery: Json = { type: "SEMANTIC", connectorId: "01920000-0000-7000-8000-0000000000c1", query: { schemaVersion: 4, metrics: ["orders"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 1 } };
export const ordersPivotWidget: Json = {
  type: "TABLE",
  title: "Orders by region",
  queryName: "orders",
  pivot: { rows: [], columns: [{ field: "dimension.region", header: "Region" }], values: [{ field: "metric.orders", header: "Orders" }], valuesAxis: "COLUMNS", totals: ["ordersTotal"] },
};

export const revenueResult = resultRead(REVENUE_RUN, ["dimension.region", "metric.revenue"], [["EMEA", "120"], ["APAC", "80"], ["AMER", "150"]]);
export const ordersResult = resultRead(ORDERS_RUN, ["dimension.region", "metric.orders"], [["EMEA", "12"], ["APAC", "8"]]);
export const ordersNextResult = resultRead(ORDERS_RUN_NEXT, ["dimension.region", "metric.orders"], [["EMEA", "31"], ["APAC", "27"], ["LATAM", "19"]]);
export const ordersTotalResult = resultRead(ORDERS_TOTAL_RUN, ["metric.orders"], [["17"]]);

export function draftReads(options: { formattedTable: boolean }): Record<string, ToolResult> {
  return {
    [DRAFT_PATH]: draftRead({ revision: "1", notes: "First draft notes.", formattedTable: options.formattedTable, ordersRun: ORDERS_RUN }),
    [DRAFT_STATUS_PATH]: draftStatusRead({ revision: "1", ordersRun: ORDERS_RUN }),
    [`${REVENUE_RUN}/result`]: revenueResult,
    [`${ORDERS_RUN}/result`]: ordersResult,
  };
}

export const SESSION_REVENUE_RUN = sessionRun("01");
export const SESSION_ORDERS_RUN = sessionRun("02");

export function sessionRead(): ToolResult {
  return readSuccess("DASHBOARD_SESSION", SESSION_PATH, {
    definition: draftDefinition({ notes: "Applied notes.", formattedTable: false }),
    parameterBindings: {},
    initialVariables: {},
    runningQueryRuns: [],
  });
}

export function runRead(runPath: string, queryName: string, state: "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED", failureDetail?: string): ToolResult {
  return readSuccess("DASHBOARD_QUERY_RUN", runPath, {
    queryName,
    queryType: "SQL",
    connectorId: "01920000-0000-7000-8000-0000000000c1",
    state,
    outputColumnNames: [],
    createdAt: "2026-10-10T09:00:00Z",
    updatedAt: "2026-10-10T09:00:00Z",
    ...(state === "FAILED" ? { failureCode: "QUERY_FAILED", ...(failureDetail ? { failureDetail } : {}) } : {}),
  });
}

export function sessionShowResult(options: { requestId: string; shownAt: number }): ToolResult {
  const structuredContent: Json = {
    requestId: options.requestId,
    viewContract: 1,
    workspaceId: WORKSPACE_ID,
    dashboardId: DASHBOARD_ID,
    title: "Weekly revenue",
    target: { kind: "SESSION", path: SESSION_PATH, variables: { region: "EMEA" }, queryRuns: { revenue: SESSION_REVENUE_RUN, orders: SESSION_ORDERS_RUN } },
    webUrl: `https://app.monomer.test/workspaces/${WORKSPACE_ID}/dashboards/${DASHBOARD_ID}?session=${SESSION_ID}`,
    shownAt: options.shownAt,
  };
  return { isError: false, content: [{ type: "text", text: "Open in Monomer" }], structuredContent };
}

export function showResult(options: { requestId: string; shownAt: number; viewContract?: number }): ToolResult {
  const structuredContent: Json = {
    requestId: options.requestId,
    viewContract: options.viewContract ?? 1,
    workspaceId: WORKSPACE_ID,
    dashboardId: DASHBOARD_ID,
    title: "Weekly revenue",
    target: { kind: "DRAFT", path: DRAFT_PATH, revision: "1" },
    webUrl: WEB_URL,
    shownAt: options.shownAt,
  };
  return { isError: false, content: [{ type: "text", text: `Open in Monomer: ${WEB_URL}` }], structuredContent };
}

export const HOST_ORIGIN = "https://host.mcp-view.test";
export const SANDBOX_ORIGIN = "https://sandbox.mcp-view.test";

// Mirrors the host CSP measured on claude.ai: inline and eval script, blob Workers, no network.
export const HOST_SANDBOX_CSP = "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'none'";
export const BLOCKED_WORKER_CSP = "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; worker-src 'none'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'none'";

const HOST_SCRIPT = String.raw`
const config = JSON.parse(document.getElementById("host-config").textContent);
const reads = new Map(Object.entries(config.reads));
const calls = [];
const openedLinks = [];
const frames = [];
const notFound = (path) => ({ isError: true, content: [{ type: "text", text: JSON.stringify({ title: "Not found", status: 404, code: "NOT_FOUND", detail: "No fixture for " + path }) }] });
function mount(show, label) {
  const iframe = document.createElement("iframe");
  iframe.src = config.viewUrl;
  iframe.title = label;
  iframe.setAttribute("sandbox", "allow-scripts allow-same-origin");
  iframe.style.cssText = "display:block;width:760px;height:120px;border:1px solid #ccc;margin:0 0 16px";
  document.body.append(iframe);
  frames.push({ iframe, show, label });
}
window.addEventListener("message", (event) => {
  const frame = frames.find((candidate) => candidate.iframe.contentWindow === event.source);
  const message = event.data;
  if (!frame || !message || message.jsonrpc !== "2.0") return;
  const post = (payload) => frame.iframe.contentWindow.postMessage(Object.assign({ jsonrpc: "2.0" }, payload), "*");
  switch (message.method) {
    case "ui/initialize":
      post({ id: message.id, result: {
        protocolVersion: message.params.protocolVersion,
        hostInfo: { name: "Fake MCP host", version: "1.0.0" },
        hostCapabilities: { openLinks: {}, serverTools: {}, logging: {} },
        hostContext: { theme: "light", displayMode: "inline", availableDisplayModes: ["inline", "fullscreen"], locale: "en-US", platform: "web" },
      } });
      return;
    case "ui/notifications/initialized":
      post({ method: "ui/notifications/tool-input", params: { arguments: {} } });
      post({ method: "ui/notifications/tool-result", params: frame.show });
      return;
    case "tools/call": {
      const path = message.params.arguments && message.params.arguments.path;
      calls.push({ frame: frame.label, name: message.params.name, path });
      const result = message.params.name === "monomer_read" ? reads.get(path) : undefined;
      post({ id: message.id, result: result || notFound(path) });
      return;
    }
    case "ui/open-link":
      openedLinks.push(message.params.url);
      post({ id: message.id, result: {} });
      return;
    case "ui/request-display-mode":
      post({ id: message.id, result: { mode: message.params.mode } });
      post({ method: "ui/notifications/host-context-changed", params: { displayMode: message.params.mode } });
      return;
    case "ui/notifications/size-changed":
      frame.iframe.style.height = message.params.height + "px";
      return;
    default:
      if (message.id !== undefined && message.method) post({ id: message.id, error: { code: -32601, message: "Method not found" } });
  }
});
window.mcpHost = { mount, setRead: (path, result) => reads.set(path, result), calls, openedLinks };
for (const [index, show] of config.shows.entries()) mount(show, "Dashboard view " + (index + 1));
`;

export function hostPage(options: { viewUrl: string; shows: ToolResult[]; reads: Record<string, ToolResult> }): string {
  const config = JSON.stringify({ viewUrl: options.viewUrl, shows: options.shows, reads: options.reads }).replace(/</g, "\\u003c");
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Fake MCP host</title></head>
<body style="margin:16px;font-family:system-ui"><script type="application/json" id="host-config">${config}</script><script>${HOST_SCRIPT}</script></body></html>`;
}
