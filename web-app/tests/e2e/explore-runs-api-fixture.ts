import type { components } from "../../src/api/control/generated";
import type { SemanticDimensionFilterType } from "../../src/api/explore";
import type { Locator, Page, Route } from "@playwright/test";
import { expect } from "./support/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { mockSignedInWorkspace } from "./support/session";

// Production Explore route fixture for the runs rail, viewed run result, result download and SQL editor
// specs. It answers the Explore, Semantic and Connector reads the page makes and keeps the recent
// runs server-side, so starts and cancels go through the page's own mutations and cache updates.

export const EXPLORE_WORKSPACE_ID = "019e8000-0000-7000-8000-000000000e01";
export const EXPLORE_PATH = `/workspaces/${EXPLORE_WORKSPACE_ID}/explore`;
export const NORTHSTAR_CONNECTOR_ID = "019e8000-0000-7000-8000-000000000e11";
export const FINANCE_CONNECTOR_ID = "019e8000-0000-7000-8000-000000000e12";
const ORGANIZATION_ID = "019e8000-0000-7000-8000-000000000e02";
const REQUEST_DIGEST = `sha256:${"a".repeat(64)}`;
const SEMANTIC_QUERY_SCHEMA_VERSION = 4;

const CONNECTORS = [
  { id: NORTHSTAR_CONNECTOR_ID, workspaceId: EXPLORE_WORKSPACE_ID, name: "Northstar warehouse", type: "POSTGRESQL", properties: { host: "fixture.internal", port: 5432, database: "warehouse" }, createdAtEpochMs: 0, updatedAtEpochMs: 0 },
  { id: FINANCE_CONNECTOR_ID, workspaceId: EXPLORE_WORKSPACE_ID, name: "Finance warehouse", type: "SNOWFLAKE", properties: { account: "fixture", warehouse: "compute", database: "finance" }, createdAtEpochMs: 0, updatedAtEpochMs: 0 },
];

// Metrics read the orders model on Northstar, except these, which read finance_orders on Finance.
const FINANCE_METRICS = new Set(["retained_accounts", "gross_margin"]);

const METRICS = [
  ["active_customers", "Active customers"],
  ["gross_revenue", "Gross revenue"],
  ["retained_accounts", "Retained accounts"],
  ["product_events", "Product events"],
  ["event_export", "Event export"],
  ["gross_margin", "Gross margin"],
  ["refunded_orders", "Refunded orders"],
  ["churned_accounts", "Churned accounts"],
].map(([name, label]) => ({ sourcePartitionInfo: [],
  metricId: `metric-${name}`,
  workspaceId: EXPLORE_WORKSPACE_ID,
  definition: { schemaVersion: 3, name, label, type: "simple", semanticModelName: FINANCE_METRICS.has(name) ? "finance_orders" : "orders", measure: name },
  createdAt: "2026-08-01T00:00:00Z",
  updatedAt: "2026-08-01T00:00:00Z",
}));

const REGIONS = [
  "North America", "Europe", "Asia Pacific", "Latin America", "Middle East & Africa", "United Kingdom", "Germany",
  "Japan", "Australia", "Canada", "France", "Singapore", "South Korea", "India", "Netherlands", "Sweden", "Spain",
  "Italy", "Brazil", "Mexico",
];
const RESULT_COLUMNS = ["region", "gross_revenue", "orders", "avg_order_value", "customers", "refund_rate", "updated_at"];

function semanticModel(name: string, label: string, connectorId: string) {
  return { sourcePartitionInfo: [],
    semanticModelId: `model-${name}`,
    workspaceId: EXPLORE_WORKSPACE_ID,
    definition: {
      schemaVersion: 3, name, label,
      sourceRelations: [{ connectorId, schema: "analytics", relation: name }],
      entities: [],
      dimensions: [{ isPartition: false, name: "region", label: "Region", type: "categorical", expression: "region" }],
      measures: [],
    },
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
  };
}

// Each model has one source table, so a Metrics question resolves to one connector without a choice.
const SEMANTIC_MODELS = [
  semanticModel("orders", "Orders", NORTHSTAR_CONNECTOR_ID),
  semanticModel("finance_orders", "Finance orders", FINANCE_CONNECTOR_ID),
];

export const REGION_GROUP_BY = { kind: "DIMENSION", semanticModelName: "orders", name: "region", granularity: null };

export const RENDERED_SQL = "SELECT\n  SUM(orders.\"gross_revenue\") AS \"gross_revenue\"\nFROM analytics.\"orders\" AS orders\nLIMIT 1000;";

type RunState = "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export type ExploreRunView = Record<string, unknown> & { runId: string; state: RunState };

function runId(sequence: number): string {
  return `019e8000-0000-7000-8000-${String(sequence).padStart(12, "0")}`;
}

function semanticQuery(metrics: string[], groupBys: unknown[]) {
  return { schemaVersion: SEMANTIC_QUERY_SCHEMA_VERSION, metrics, groupBys, metricTimeRange: null, filters: [], orderBy: [], rowLimit: 1000 };
}

function sampleResult(rowCount: number) {
  return {
    columns: RESULT_COLUMNS.map((name, index) => ({ ordinal: index + 1, name, logicalType: index === 0 ? "STRING" : "DECIMAL", nullable: false })),
    rows: REGIONS.slice(0, rowCount).map((region, index) => [region, `${428_320 - index * 17_000}.40`, `${12_481 - index * 400}`, "34.32", `${8_492 - index * 300}`, "2.4", "2026-08-23 14:42"]),
    returnedRowCount: rowCount,
    stopReason: "ROW_LIMIT",
  };
}

function semanticRun(options: {
  sequence: number;
  metricName: string;
  connectorId: string;
  state: RunState;
  createdAt: string;
  durationSeconds: number;
  resultRows?: number;
  totalRows?: string;
  byteSize?: string;
  failureDetail?: string;
  credentialSurface?: "WEB" | "AGENT";
  groupBys?: unknown[];
}): ExploreRunView {
  const succeeded = options.state === "SUCCEEDED";
  return {
    kind: "SEMANTIC",
    runId: runId(options.sequence),
    workspaceId: EXPLORE_WORKSPACE_ID,
    connectorId: options.connectorId,
    requestDigest: REQUEST_DIGEST,
    state: options.state,
    revision: 1,
    resultAvailable: succeeded,
    failureCode: options.state === "FAILED" ? "EXECUTION_FAILED" : null,
    failureDetail: options.failureDetail ?? null,
    createdAt: options.createdAt,
    updatedAt: new Date(Date.parse(options.createdAt) + options.durationSeconds * 1_000).toISOString(),
    resultMetadata: succeeded
      ? { byteSize: options.byteSize ?? "9000", mediaType: "application/vnd.apache.arrow.file", rowCount: options.totalRows ?? "36", columnCount: RESULT_COLUMNS.length, expiresAt: "2099-01-01T00:00:00Z" }
      : null,
    result: succeeded ? sampleResult(options.resultRows ?? 20) : null,
    sql: RENDERED_SQL,
    query: semanticQuery([options.metricName], options.groupBys ?? []),
    reportingTimeZone: null,
    credentialSurface: options.credentialSurface ?? "WEB",
  };
}

function initialRuns(): ExploreRunView[] {
  return [
    semanticRun({ sequence: 0xf01, metricName: "active_customers", connectorId: NORTHSTAR_CONNECTOR_ID, state: "RUNNING", createdAt: new Date(Date.now() - 7_000).toISOString(), durationSeconds: 0 }),
    semanticRun({ sequence: 0xf02, metricName: "gross_revenue", connectorId: NORTHSTAR_CONNECTOR_ID, state: "SUCCEEDED", createdAt: "2026-08-23T05:38:00Z", durationSeconds: 14, totalRows: "48", byteSize: "12000", groupBys: [REGION_GROUP_BY] }),
    semanticRun({ sequence: 0xf03, metricName: "retained_accounts", connectorId: FINANCE_CONNECTOR_ID, state: "SUCCEEDED", createdAt: "2026-08-23T05:24:00Z", durationSeconds: 31, totalRows: "120", byteSize: "28000", credentialSurface: "AGENT" }),
    semanticRun({ sequence: 0xf04, metricName: "product_events", connectorId: NORTHSTAR_CONNECTOR_ID, state: "FAILED", createdAt: "2026-08-23T05:00:00Z", durationSeconds: 62, failureDetail: "The warehouse stopped the statement after its execution timeout." }),
    semanticRun({ sequence: 0xf05, metricName: "event_export", connectorId: NORTHSTAR_CONNECTOR_ID, state: "CANCELLED", createdAt: "2026-08-22T09:14:00Z", durationSeconds: 9 }),
    semanticRun({ sequence: 0xf06, metricName: "gross_margin", connectorId: FINANCE_CONNECTOR_ID, state: "SUCCEEDED", createdAt: "2026-08-22T07:52:00Z", durationSeconds: 22 }),
    semanticRun({ sequence: 0xf07, metricName: "refunded_orders", connectorId: NORTHSTAR_CONNECTOR_ID, state: "SUCCEEDED", createdAt: "2026-08-22T02:06:00Z", durationSeconds: 11, resultRows: 0, totalRows: "0", byteSize: "64" }),
    semanticRun({ sequence: 0xf08, metricName: "churned_accounts", connectorId: NORTHSTAR_CONNECTOR_ID, state: "SUCCEEDED", createdAt: "2026-08-21T08:40:00Z", durationSeconds: 19 }),
  ];
}

function json(body: unknown, status = 200) {
  return { status, contentType: status >= 400 ? "application/problem+json" : "application/json", body: JSON.stringify(body) };
}

export interface ExploreRunsRejection {
  status: number;
  title: string;
  code: string;
  detail: string;
  partitionFilters?: components["schemas"]["ProblemDetails"]["partitionFilters"];
}

function problem(path: string, rejection: ExploreRunsRejection) {
  return json({ type: "about:blank", title: rejection.title, status: rejection.status, detail: rejection.detail, instance: path, code: rejection.code, requestId: "explore-runs-fixture", traceId: "explore-runs-fixture-trace", retryable: false, ...(rejection.partitionFilters ? { partitionFilters: rejection.partitionFilters } : {}) }, rejection.status);
}

function notFound(path: string) {
  return problem(path, { status: 404, title: "Not found", code: "NOT_FOUND", detail: `No fixture for ${path}.` });
}

export interface ExploreRunsApi {
  readonly startRequests: Array<{ connectorId: string; sql?: string; query?: { metrics: string[] } }>;
  readonly cancelledRunIds: string[];
  releaseRenderedSql(): void;
  releaseReadsAfterCancel(): void;
}

export interface ExploreRunsOptions {
  // Holds the render-sql response until releaseRenderedSql, exposing the loading state.
  holdRenderedSql?: boolean;
  // Holds every run read that arrives after a cancel until releaseReadsAfterCancel, so the page can
  // only learn the cancelled state from the cancel response itself.
  holdReadsAfterCancel?: boolean;
  // Answers every SQL and Semantic run start with this problem instead of creating a run.
  startRejection?: ExploreRunsRejection;
  // Answers every run cancel with this problem and leaves the run unchanged.
  cancelRejection?: ExploreRunsRejection;
  // Runs listed before the fixture's own runs, newest first.
  extraRuns?: ExploreRunView[];
  dimensionFilterTypes?: SemanticDimensionFilterType[];
  partitionSource?: components["schemas"]["SemanticModelResponse"]["sourcePartitionInfo"][number];
}

function releasable(held: boolean): { released: Promise<void>; release: () => void } {
  let release = () => {};
  const released = held ? new Promise<void>((resolve) => { release = resolve; }) : Promise.resolve();
  return { released, release: () => release() };
}

export async function openExploreRuns(page: Page, options: ExploreRunsOptions = {}): Promise<ExploreRunsApi> {
  const runs = [...(options.extraRuns ?? []), ...initialRuns()];
  let nextSequence = 0xf10;
  const renderedSql = releasable(options.holdRenderedSql === true);
  const readsAfterCancel = releasable(options.holdReadsAfterCancel === true);
  const api: ExploreRunsApi = {
    startRequests: [],
    cancelledRunIds: [],
    releaseRenderedSql: renderedSql.release,
    releaseReadsAfterCancel: readsAfterCancel.release,
  };

  await page.route("**/v2/workspaces/**", async (route: Route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith("/result")) {
      await route.fallback();
      return;
    }
    if (path.endsWith("/connectors")) {
      await route.fulfill(json({ connectors: CONNECTORS }));
      return;
    }
    if (path.endsWith("/semantic/metrics")) {
      await route.fulfill(json(METRICS));
      return;
    }
    if (path.endsWith("/semantic/semantic-models")) {
      await route.fulfill(json(SEMANTIC_MODELS.map((model) => options.partitionSource && options.partitionSource.semanticModelName === model.definition.name ? { ...model, definition: { ...model.definition, dimensions: [...model.definition.dimensions, ...options.partitionSource.dimensions.filter((name) => !model.definition.dimensions.some((dimension) => dimension.name === name)).map((name) => ({ isPartition: false, name, label: "Order date", type: "time", expression: name, granularity: "day" }))] }, sourcePartitionInfo: [options.partitionSource] } : model)));
      return;
    }
    if (path.endsWith("/semantic/query/group-bys")) {
      const body = request.postDataJSON() as { connectorId: string };
      const modelName = body.connectorId === FINANCE_CONNECTOR_ID ? "finance_orders" : "orders";
      await route.fulfill(json({
        groupBys: [{ ...REGION_GROUP_BY, semanticModelName: modelName }],
        excludedSemanticModels: [],
        dimensionFilterTypes: options.dimensionFilterTypes ?? [{ dimension: { semanticModelName: modelName, name: "region" }, kind: "TEXT" }],
      }));
      return;
    }
    if (path.endsWith("/semantic/query/render-sql") && request.method() === "POST") {
      await renderedSql.released;
      await route.fulfill(json({ dialect: "POSTGRESQL", sql: RENDERED_SQL }));
      return;
    }
    if ((path.endsWith("/explore/sql-runs") || path.endsWith("/explore/semantic-runs")) && request.method() === "POST") {
      const body = request.postDataJSON() as ExploreRunsApi["startRequests"][number];
      api.startRequests.push(body);
      if (options.startRejection) {
        await route.fulfill(problem(path, options.startRejection));
        return;
      }
      const createdAt = new Date().toISOString();
      const run: ExploreRunView = {
        kind: body.query ? "SEMANTIC" : "SQL",
        runId: runId(nextSequence++),
        workspaceId: EXPLORE_WORKSPACE_ID,
        connectorId: body.connectorId,
        requestDigest: REQUEST_DIGEST,
        state: "RUNNING",
        revision: 1,
        resultAvailable: false,
        failureCode: null,
        failureDetail: null,
        createdAt,
        updatedAt: createdAt,
        resultMetadata: null,
        result: null,
        sql: body.sql ?? RENDERED_SQL,
        credentialSurface: "WEB",
        reportingTimeZone: body.query ? "UTC" : null,
        ...(body.query ? { query: body.query } : {}),
      };
      runs.unshift(run);
      await route.fulfill(json(run, 202));
      return;
    }
    if (path.endsWith("/explore/runs")) {
      if (api.cancelledRunIds.length > 0) await readsAfterCancel.released;
      await route.fulfill(json({ runs: runs.slice(0, 20) }));
      return;
    }
    const cancel = path.match(/\/explore\/runs\/([^/]+):cancel$/);
    if (cancel && request.method() === "POST") {
      const index = runs.findIndex((run) => run.runId === decodeURIComponent(cancel[1]));
      if (index < 0) {
        await route.fulfill(notFound(path));
        return;
      }
      if (options.cancelRejection) {
        await route.fulfill(problem(path, options.cancelRejection));
        return;
      }
      api.cancelledRunIds.push(runs[index].runId);
      runs[index] = { ...runs[index], state: "CANCELLED", revision: 2, failureCode: "CANCELLED_BY_ACTOR", updatedAt: new Date().toISOString() };
      await route.fulfill(json(runs[index]));
      return;
    }
    const detail = path.match(/\/explore\/runs\/([^/:]+)$/);
    if (detail) {
      if (api.cancelledRunIds.length > 0) await readsAfterCancel.released;
      const run = runs.find((candidate) => candidate.runId === decodeURIComponent(detail[1]));
      await route.fulfill(run ? json(run) : notFound(path));
      return;
    }
    await route.fulfill(notFound(path));
  });

  await mockSignedInWorkspace(page, {
    workspaceId: EXPLORE_WORKSPACE_ID,
    workspaceName: "Explore runs",
    organizationId: ORGANIZATION_ID,
    role: "EXPLORER",
    user: { id: "explore-runs-user", email: "explore-runs@example.test", displayName: "Explore runs" },
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, EXPLORE_PATH);
  await page.waitForURL(`**${EXPLORE_PATH}`);
  return api;
}

/**
 * Adds a metric to the Metrics question: from the Metrics list while no run is in view, otherwise
 * through the question's picker, which it then closes.
 */
export async function selectMetric(page: Page, label: string): Promise<void> {
  const list = page.getByRole("region", { name: "Metrics list" });
  const add = page.getByRole("button", { name: /^(Choose a metric|Add metric)$/ });
  await list.or(add).first().waitFor();
  if (await list.isVisible()) {
    await list.getByRole("button", { name: new RegExp(`^${label}`) }).click();
    return;
  }
  await add.click();
  await page.getByRole("dialog", { name: "Metrics" }).getByRole("button", { name: new RegExp(`^${label}`) }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "Metrics" })).toHaveCount(0);
}

/** The viewed run's result, shown under the composer and labelled by its run title. */
/** A run view in the fixture's workspace; tests set the kind, query, sql, and result they need. */
export function exploreRunView(sequence: number, fields: Record<string, unknown> & { state: RunState }): ExploreRunView {
  return {
    kind: "SEMANTIC",
    runId: runId(sequence),
    workspaceId: EXPLORE_WORKSPACE_ID,
    connectorId: NORTHSTAR_CONNECTOR_ID,
    requestDigest: REQUEST_DIGEST,
    revision: 1,
    resultAvailable: fields.state === "SUCCEEDED",
    failureCode: null,
    failureDetail: null,
    createdAt: "2026-08-24T09:00:00Z",
    updatedAt: "2026-08-24T09:00:03Z",
    resultMetadata: null,
    result: null,
    sql: RENDERED_SQL,
    credentialSurface: "WEB",
    reportingTimeZone: null,
    ...fields,
  };
}

export function runPanel(page: Page, title: RegExp | string): Locator {
  return page.getByRole("region", { name: title });
}
