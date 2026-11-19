import { afterEach, describe, expect, it, vi } from "vitest";
import type { ExploreResult, ExploreRun, SemanticDimensionFilterType, SemanticFilter, SemanticQuerySpec } from "@/api/explore";
import { ApiError } from "@/lib/api/client";
import { toExploreErrorPresentation } from "@/api/explore-errors";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Connector } from "@/lib/types/connector";
import type { Metric } from "@/lib/types/metric";
import { exploreChartPlan, MAX_CHART_SERIES } from "./chart-plan";
import {
  draftFromRun,
  draftMatchesRun,
  replacedQuestionWorthKeeping,
  emptyExploreDraft,
  readStoredDraft,
  tablePreviewSql,
  writeStoredDraft,
} from "./explore-draft";
import { exploreRunLink, runViewTabs } from "./ExploreRunPanel";
import { columnFractionDigits, defaultTableSort, formatNumericCell, nextTableSort, sortPreviewRows, type ExploreTableColumn } from "./ExploreResultTable";
import { failedRunGuidance, resultCaption, sqlRunTitle, startRejectionGuidance } from "./run-presentation";
import { metricModelNames, metricsReadingModel, resolveSemanticConnectors } from "./semantic-connectors";
import { excludedModelLabels, excludedModelsSummary, semanticProblemGuidance } from "./semantic-problems";
import { buildSemanticFilter, canonicalDecimal, defaultFilterKind, filterComparisonMismatch, filterDimensions, filterOperatorWords, filterValueHint, initialFilterComparison, knownDimensionFilterKind, periodStart, reconcileFilterComparison } from "./semantic-filters";
import { filterFixtureGroupBys, filterFixtureModels, filterFixtureTypes } from "@/pages/dev/explore-semantic-filter-fixture/SemanticFilterFixture";

vi.mock("@/lib/navigation", () => ({ Link: () => null }));

const simple = (name: string, model: string): Metric => ({
  id: `id-${name}`, name, label: name.charAt(0).toUpperCase() + name.slice(1), type: "simple",
  typeParams: { semanticModelName: model, measure: name }, createdAt: "", updatedAt: "",
});
const ratio: Metric = {
  id: "id-margin", name: "margin", label: "Margin", type: "ratio",
  typeParams: { numerator: { metricName: "revenue" }, denominator: { metricName: "visits" } }, createdAt: "", updatedAt: "",
};
const model = (name: string, connectorIds: string[]): SemanticModelResponse => ({
  id: `model-${name}`, name, label: name, entities: [], dimensions: [], measures: [], createdAt: "", updatedAt: "",
  sourceRelations: connectorIds.map((connectorId) => ({ connectorId, schema: "public", relation: name })),
});
const metrics = [simple("revenue", "billing"), simple("visits", "web"), ratio];
const models = [model("billing", ["pg", "sf"]), model("web", ["sf", "bq"])];
const connectors: Connector[] = ["pg", "sf", "bq"].map((id) => ({
  id, workspaceId: "w", name: `${id} warehouse`, type: id === "bq" ? "BIGQUERY" : "POSTGRESQL",
  properties: { host: "h", port: 5432, database: "d" } as Connector["properties"], createdAtEpochMs: 0, updatedAtEpochMs: 0,
}));

const semanticQuery = (overrides: Partial<SemanticQuerySpec>): SemanticQuerySpec => ({
  schemaVersion: 4, metrics: ["revenue"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 1000, ...overrides,
});

const semanticRun = (query: SemanticQuerySpec, overrides: Partial<ExploreRun> = {}): ExploreRun => ({
  kind: "SEMANTIC", runId: "run-1", workspaceId: "w", connectorId: "pg",
  requestDigest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", state: "SUCCEEDED",
  failureCode: null, failureDetail: null, createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:02Z",
  resultMetadata: null, result: null, resultState: null, sql: "SELECT SUM(amount) FROM billing.invoices", credentialSurface: "WEB", query,
  ...overrides,
} as ExploreRun);

describe("connector choice from metrics", () => {
  it("collects the models a metric reads through its inputs and refuses unknown references", () => {
    expect([...metricModelNames("margin", metrics) ?? []].sort()).toEqual(["billing", "web"]);
    expect(metricModelNames("missing", metrics)).toBeNull();
  });

  it("offers only connectors where every model of every selected metric has a source table", () => {
    const ids = connectors.map((connector) => connector.id);
    expect(resolveSemanticConnectors([], metrics, models, ids).compatible).toEqual([]);
    expect(resolveSemanticConnectors(["revenue"], metrics, models, ids).compatible).toEqual(["pg", "sf"]);
    expect(resolveSemanticConnectors(["margin"], metrics, models, ids).compatible).toEqual(["sf"]);
    expect(resolveSemanticConnectors(["revenue", "visits"], metrics, models, ids).compatible).toEqual(["sf"]);
    expect(resolveSemanticConnectors(["revenue"], metrics, [], ids).compatible).toEqual([]);
  });

  it("names a missing metric or model instead of only saying no connector qualifies", () => {
    const ids = connectors.map((connector) => connector.id);
    expect(resolveSemanticConnectors(["revenue"], metrics, [model("web", ["sf"])], ids))
      .toEqual({ compatible: [], problem: "Revenue reads the model “billing”, which doesn’t exist. Open the metric to fix it, or remove it from the query." });
    expect(resolveSemanticConnectors(["gone"], metrics, models, ids).problem).toContain("no longer exists");
    expect(resolveSemanticConnectors(["revenue", "visits"], metrics, [model("billing", ["pg"]), model("web", ["bq"])], ids))
      .toEqual({ compatible: [], problem: "No connector has source tables for all of these metrics." });
    expect(resolveSemanticConnectors(["revenue"], metrics, models, ids)).toEqual({ compatible: ["pg", "sf"], problem: null });
  });

  it("names a model whose only connector was deleted", () => {
    expect(resolveSemanticConnectors(["revenue"], metrics, [model("billing", ["deleted"])], ["pg"]))
      .toEqual({ compatible: [], problem: "Revenue reads the model “billing”, whose connector was deleted. Point the model at another connector, or remove the metric from the query." });
    expect(resolveSemanticConnectors(["revenue"], metrics, [model("billing", ["deleted"])], []).problem)
      .toBe("No connector has source tables for all of these metrics.");
  });

  it("filters the metric picker to metrics that read a model", () => {
    expect(metricsReadingModel("web", metrics).map((metric) => metric.name)).toEqual(["visits", "margin"]);
  });
});

describe("draft persistence and run restore", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("stores one draft per workspace and ignores malformed or unavailable storage", () => {
    const stored = new Map<string, string>();
    const localStorage = {
      getItem: (key: string) => stored.get(key) ?? null,
      setItem: (key: string, value: string) => { stored.set(key, value); },
    };
    vi.stubGlobal("window", { localStorage });
    const draft = { ...emptyExploreDraft(), mode: "SQL" as const, sql: { connectorId: "pg", text: "SELECT 1" } };
    writeStoredDraft("w1", draft);
    expect(readStoredDraft("w1")).toEqual(draft);
    expect(readStoredDraft("w2")).toBeNull();
    stored.set("monomer.explore.draft.w3", "{\"mode\":\"PIVOT\"}");
    expect(readStoredDraft("w3")).toBeNull();
    const blocked = { getItem: () => { throw new Error("blocked"); }, setItem: () => { throw new Error("full"); } };
    vi.stubGlobal("window", { localStorage: blocked });
    expect(readStoredDraft("w1")).toBeNull();
    expect(() => writeStoredDraft("w1", draft)).not.toThrow();
    vi.unstubAllGlobals();
    expect(readStoredDraft("w1")).toBeNull();
  });

  it("restores a Semantic run's full query and connector and detects later edits", () => {
    const query = semanticQuery({
      groupBys: [{ kind: "METRIC_TIME", granularity: "MONTH" }],
      filters: [{ kind: "TEXT", dimension: { semanticModelName: "billing", name: "region" }, operator: "IN", values: ["EU", "UK"] }],
      metricTimeRange: { start: { kind: "DATE", value: "2025-01-01" }, end: { kind: "DATE", value: "2025-12-31" } },
      orderBy: [{ target: { kind: "METRIC", metricName: "revenue" }, direction: "DESC" }],
      rowLimit: 250,
    });
    const run = semanticRun(query);
    const restored = draftFromRun({ ...emptyExploreDraft(), mode: "SQL" }, run);
    expect(restored.mode).toBe("SEMANTIC");
    const { schemaVersion: _schemaVersion, ...questionFields } = query;
    expect(restored.semantic).toEqual({ ...questionFields, connectorId: "pg" });
    expect(draftMatchesRun(restored, run)).toBe(true);
    expect(draftMatchesRun({ ...restored, semantic: { ...restored.semantic, connectorId: "sf" } }, run)).toBe(false);
    expect(draftMatchesRun({ ...restored, semantic: { ...restored.semantic, rowLimit: 100 } }, run)).toBe(false);
    // Looking at the SQL tab doesn't edit the Metrics question the run answered.
    expect(draftMatchesRun({ ...restored, mode: "SQL" }, run)).toBe(true);
  });

  it("offers a replaced question back only when it asked something no run asks", () => {
    const run = semanticRun(semanticQuery({ metrics: ["revenue"] }));
    const empty = emptyExploreDraft();
    const asking = { ...empty, semantic: { ...empty.semantic, metrics: ["orders"] } };
    expect(replacedQuestionWorthKeeping(empty, run, null)).toBe(false);
    expect(replacedQuestionWorthKeeping(asking, run, null)).toBe(true);
    expect(replacedQuestionWorthKeeping(asking, run, asking)).toBe(false);
    expect(replacedQuestionWorthKeeping(draftFromRun(asking, run), run, null)).toBe(false);
    // A SQL run leaves the Metrics question alone, so only unrun SQL text is at stake.
    const sqlRun = { ...run, kind: "SQL", sql: "SELECT 1" } as ExploreRun;
    expect(replacedQuestionWorthKeeping(asking, sqlRun, null)).toBe(false);
    expect(replacedQuestionWorthKeeping({ ...asking, sql: { connectorId: "pg", text: "SELECT 2" } }, sqlRun, null)).toBe(true);
  });

  it("restores a SQL run's text and connector and ignores surrounding whitespace", () => {
    const run = { ...semanticRun(semanticQuery({})), kind: "SQL", sql: "SELECT 1" } as ExploreRun;
    const restored = draftFromRun(emptyExploreDraft(), run);
    expect(restored).toMatchObject({ mode: "SQL", sql: { connectorId: "pg", text: "SELECT 1" } });
    expect(draftMatchesRun({ ...restored, sql: { connectorId: "pg", text: "SELECT 1\n" } }, run)).toBe(true);
    expect(draftMatchesRun({ ...restored, sql: { connectorId: "pg", text: "SELECT 2" } }, run)).toBe(false);
    expect(draftMatchesRun({ ...restored, sql: { connectorId: "sf", text: "SELECT 1" } }, run)).toBe(false);
  });

  it("drafts a quoted preview query for a Catalog table in the connector's dialect", () => {
    expect(tablePreviewSql("POSTGRESQL", { catalog: null, schema: "billing", table: "invoices" })).toBe("SELECT * FROM \"billing\".\"invoices\" LIMIT 100");
    expect(tablePreviewSql("SNOWFLAKE", { catalog: "ANALYTICS", schema: "PUBLIC", table: "my \"t\"" })).toBe("SELECT * FROM \"ANALYTICS\".\"PUBLIC\".\"my \"\"t\"\"\" LIMIT 100");
    expect(tablePreviewSql("BIGQUERY", { catalog: "northstar-prod", schema: "events", table: "page`views" })).toBe("SELECT * FROM `northstar-prod`.`events`.`page``views` LIMIT 100");
    expect(tablePreviewSql("DATABRICKS", { catalog: "main", schema: "sales", table: "orders" })).toBe("SELECT * FROM `main`.`sales`.`orders` LIMIT 100");
  });
});

describe("run titles and result captions", () => {
  it("titles SQL runs from the first table after FROM", () => {
    expect(sqlRunTitle("select * from `proj`.`ds`.`events` limit 5")).toBe("events · select * from `proj`.`ds…");
    expect(sqlRunTitle("SELECT 1")).toBe("SELECT 1");
    expect(sqlRunTitle("  ")).toBe("SQL query");
  });

  const result = (returnedRowCount: number, stopReason: string): ExploreResult => ({
    columns: [{ ordinal: 1, name: "metric.revenue", logicalType: "DECIMAL", nullable: false }],
    rows: Array.from({ length: returnedRowCount }, (_, index) => [String(index)]),
    returnedRowCount,
    stopReason,
  });

  it("says how much of the result the preview shows and why it stopped", () => {
    expect(resultCaption(result(24, "END_OF_RESULT"), 24, null)).toMatchObject({ status: "24 rows", detail: null, complete: true });
    const truncated = resultCaption(result(1000, "ROW_LIMIT"), 12480, null);
    expect(truncated).toMatchObject({ status: "First 1,000 of 12,480 rows", complete: false });
    expect(truncated.detail).toContain("first 1,000 rows");
    expect(resultCaption(result(400, "BYTE_LIMIT"), 900, null).detail).toContain("size limit");
  });

  it("flags a Semantic result that stopped at its query row limit as incomplete", () => {
    const caption = resultCaption(result(100, "END_OF_RESULT"), 100, semanticQuery({ rowLimit: 100 }));
    expect(caption).toMatchObject({ status: "100 rows · limit reached", complete: false, rowLimitReached: true });
    expect(caption.detail).toContain("100-row limit");
  });
});

describe("chart plan", () => {
  const month = { kind: "METRIC_TIME" as const, granularity: "MONTH" as const };
  const region = { kind: "DIMENSION" as const, semanticModelName: "billing", name: "region", granularity: null };
  const status = { kind: "DIMENSION" as const, semanticModelName: "billing", name: "status", granularity: null };
  const chartResult = (columns: string[], rows: string[][]): ExploreResult => ({
    columns: columns.map((name, index) => ({ ordinal: index + 1, name, logicalType: "TEXT", nullable: true })),
    rows,
    returnedRowCount: rows.length,
    stopReason: "END_OF_RESULT",
  });
  const plan = (query: SemanticQuerySpec, rows: string[][], complete = true, total = rows.length) => exploreChartPlan({
    query,
    result: chartResult([...query.groupBys.map((groupBy) => groupBy.kind === "METRIC_TIME" ? `metric_time.${groupBy.granularity.toLowerCase()}` : `dimension.${groupBy.semanticModelName}.${groupBy.name}`), ...query.metrics.map((name) => `metric.${name}`)], rows),
    totalRowCount: total,
    complete,
    rowLimitReached: false,
  });

  it("draws a time axis as a line, one category as bars, and a category beside time as series", () => {
    expect(plan(semanticQuery({ groupBys: [month] }), [["2025-10-01", "1"]])).toMatchObject({ kind: "ready", shape: "line", xColumn: "metric_time.month", seriesColumn: null });
    expect(plan(semanticQuery({ groupBys: [region] }), [["EU", "1"]])).toMatchObject({ kind: "ready", shape: "bar", xColumn: "dimension.billing.region" });
    expect(plan(semanticQuery({ groupBys: [month, region], metrics: ["revenue", "visits"] }), [["2025-10-01", "EU", "1", "2"]]))
      .toMatchObject({ kind: "ready", shape: "line", seriesColumn: "dimension.billing.region", metrics: [{ metricName: "revenue", column: "metric.revenue" }, { metricName: "visits", column: "metric.visits" }] });
  });

  it("reads a question without group-bys as one number per metric", () => {
    expect(plan(semanticQuery({ groupBys: [], metrics: ["revenue", "visits"] }), [["12", "3"]]))
      .toEqual({ kind: "values", metrics: [{ metricName: "revenue", column: "metric.revenue" }, { metricName: "visits", column: "metric.visits" }] });
  });

  it("explains shapes it does not draw", () => {
    const message = "Charts need a time axis or one category.";
    expect(plan(semanticQuery({ groupBys: [region, status] }), [["EU", "paid", "1"]])).toEqual({ kind: "unavailable", message });
    expect(plan(semanticQuery({ groupBys: [month, region, status] }), [["2025-10-01", "EU", "paid", "1"]])).toEqual({ kind: "unavailable", message });
  });

  it("refuses incomplete results and too many series instead of drawing partial lines", () => {
    expect(plan(semanticQuery({ groupBys: [month] }), [["2025-10-01", "1"]], false, 12480)).toEqual({
      kind: "unavailable",
      message: "Chart needs the complete result — 12,480 rows is more than 1. Use a coarser granularity, fewer group-bys, or a filter.",
    });
    const rows = Array.from({ length: MAX_CHART_SERIES + 1 }, (_, index) => ["2025-10-01", `r${index}`, "1"]);
    const tooMany = plan(semanticQuery({ groupBys: [month, region] }), rows);
    expect(tooMany.kind).toBe("unavailable");
    expect(tooMany.kind === "unavailable" ? tooMany.message : "").toContain("13");
  });
});

describe("result view", () => {
  it("offers Chart only for Semantic runs and links to the exact run", () => {
    const withResult = { resultAvailable: true, resultState: "AVAILABLE", result: { columns: [], rows: [], returnedRowCount: 0, stopReason: "END_OF_RESULT" } } as Partial<ExploreRun>;
    expect(runViewTabs(semanticRun(semanticQuery({}), withResult))).toEqual(["TABLE", "CHART", "SQL"]);
    expect(runViewTabs({ ...semanticRun(semanticQuery({}), withResult), kind: "SQL" } as ExploreRun)).toEqual(["TABLE", "SQL"]);
    expect(runViewTabs(semanticRun(semanticQuery({}), { resultState: "EXPIRED" }))).toEqual(["TABLE", "SQL"]);
    expect(runViewTabs(semanticRun(semanticQuery({}), { state: "RUNNING" }))).toEqual(["TABLE", "CHART", "SQL"]);
    expect(runViewTabs(semanticRun(semanticQuery({}), { state: "FAILED" }))).toEqual(["FAILURE", "SQL"]);
    expect(runViewTabs(semanticRun(semanticQuery({}), { state: "FAILED", sql: " " }))).toEqual(["FAILURE"]);
    expect(exploreRunLink("https://app.monomer.cc", "w 1", "run-1")).toBe("https://app.monomer.cc/workspaces/w%201/explore?run=run-1");
  });

  it("groups digits of exact decimals for reading without rounding", () => {
    expect(formatNumericCell("428320.40", "en-US", 2)).toBe("428,320.40");
    expect(formatNumericCell("-12345678901234567890.123456789", "en-US", 2)).toBe("-12,345,678,901,234,567,890.123456789");
    expect(formatNumericCell("12481", "en-US", 0)).toBe("12,481");
    expect(formatNumericCell("1234.5", "de-DE", 1)).toBe("1.234,5");
    expect(formatNumericCell("NaN", "en-US", 0)).toBe("NaN");
    expect(formatNumericCell("1.5E+10", "en-US", 0)).toBe("1.5E+10");
  });

  it("reads a column with the fewest exact fraction digits, keeping two once a fraction was returned", () => {
    const bigQueryScale = ["7492900.350000000", "8749509.170000000", null];
    expect(columnFractionDigits(bigQueryScale)).toBe(2);
    expect(bigQueryScale.map((value) => value === null ? null : formatNumericCell(value, "en-US", 2))).toEqual(["7,492,900.35", "8,749,509.17", null]);
    expect(columnFractionDigits(["12.50", "3.00"])).toBe(2);
    expect(formatNumericCell("3.00", "en-US", 2)).toBe("3.00");
    expect(columnFractionDigits(["0.125000000", "2.000000000"])).toBe(3);
    expect(formatNumericCell("2.000000000", "en-US", 3)).toBe("2.000");
    expect(columnFractionDigits(["12481", "7"])).toBe(0);
  });

  it("opens an unsorted question's preview along its first time column", () => {
    const column = (timeGranularity: ExploreTableColumn["timeGranularity"]) => ({ id: "c", label: "c", description: "", numeric: false, timeGranularity });
    expect(defaultTableSort([column(null), column("MONTH")], false)).toEqual({ columnIndex: 1, direction: "asc" });
    expect(defaultTableSort([column(null), column("MONTH")], true)).toBeNull();
    expect(defaultTableSort([column(null)], false)).toBeNull();
  });

  it("sorts the preview in the browser and keeps empty cells last", () => {
    const rows = [["b", "10"], [null, "2"], ["a", "9"]];
    expect(nextTableSort(null, 1)).toEqual({ columnIndex: 1, direction: "asc" });
    expect(nextTableSort({ columnIndex: 1, direction: "asc" }, 1)).toEqual({ columnIndex: 1, direction: "desc" });
    expect(nextTableSort({ columnIndex: 1, direction: "desc" }, 1)).toBeNull();
    expect(sortPreviewRows(rows, { columnIndex: 1, direction: "asc" }, true).map((row) => row[1])).toEqual(["2", "9", "10"]);
    expect(sortPreviewRows(rows, { columnIndex: 0, direction: "desc" }, false).map((row) => row[0])).toEqual(["b", "a", null]);
    expect(sortPreviewRows(rows, null, false)).toBe(rows);
  });
});

describe("failure guidance", () => {
  const context = { workspaceId: "w", connectorId: "pg", connectors, metricNames: ["revenue"], metrics, models };
  const problem = (status: number, code: string, detail: string, retryable = false, violations: Array<{ field: string; code: string; message: string }> = []) => toExploreErrorPresentation(new ApiError(detail, status, "{}", {
    type: "https://monomer.cc/problems/x", title: "x", status, detail, instance: "/x", code, requestId: "r", traceId: "trace-1", retryable,
    ...(violations.length > 0 ? { violations } : {}),
  }))!;

  it("sends a missing time spine to the connector's Time Spines view", () => {
    const guidance = startRejectionGuidance(problem(409, "CONFLICT", "TIME_SPINE_UNAVAILABLE"), "SEMANTIC", context);
    expect(guidance.title).toBe("This query needs a time spine on pg warehouse.");
    expect(guidance.actions).toEqual([
      { label: "Set up time spine", href: "/workspaces/w/semantics?view=time-spines&connectorId=pg" },
      { label: "Open metric", href: "/workspaces/w/metrics/id-revenue" },
    ]);
    expect(guidance.reference).toBe("TIME_SPINE_UNAVAILABLE · Reference trace-1");
  });

  it("sends unnamed definition problems to Semantics and connector problems to the connector", () => {
    const unnamed = startRejectionGuidance(problem(400, "INVALID_REQUEST", "METRIC_DEPENDENCY_NOT_FOUND"), "SEMANTIC", context);
    expect(unnamed.title).toBe("A definition this query uses has a problem.");
    expect(unnamed.actions).toEqual([{ label: "Open Semantics", href: "/workspaces/w/semantics" }]);
    const credential = startRejectionGuidance(problem(503, "CREDENTIAL_UNAVAILABLE", "The connector credential was rejected."), "SQL", context);
    expect(credential.actions).toEqual([{ label: "Open connector", href: "/workspaces/w/connectors?connector=pg" }]);
    const permission = failedRunGuidance(semanticRun(semanticQuery({}), { state: "FAILED", failureCode: "PERMISSION_DENIED", failureDetail: "permission denied for schema billing" }), context);
    expect(permission.title).toContain("isn’t allowed");
    expect(permission.actions[0]?.label).toBe("Open connector");
  });

  it("offers Connector repair only for classified SQL Connector failures", () => {
    for (const code of ["CONNECTION_FAILED", "CREDENTIAL_UNAVAILABLE", "AUTHENTICATION_FAILED", "PERMISSION_DENIED"]) {
      const retryable = code !== "AUTHENTICATION_FAILED" && code !== "PERMISSION_DENIED";
      const guidance = startRejectionGuidance(problem(code === "PERMISSION_DENIED" ? 409 : 503, code, "Safe failure detail.", retryable), "SQL", context);
      expect(guidance.actions).toEqual([{ label: "Open connector", href: "/workspaces/w/connectors?connector=pg" }]);
      expect(guidance.explanation).toBe(retryable ? "You can try this request again." : null);
    }
    for (const code of ["SERVICE_UNAVAILABLE", "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "RAW_SQL_VALIDATION_FAILED", "RESOURCE_EXHAUSTED"]) {
      const guidance = startRejectionGuidance(problem(503, code, "Safe failure detail.", true), "SQL", context);
      expect(guidance).toMatchObject({ title: "Safe failure detail.", actions: [], explanation: "You can try this request again." });
      expect(guidance.reference).toContain(code);
    }
  });

  it("sends connection and sign-in failures to the connector without metric advice", () => {
    const failedRun = (kind: "SEMANTIC" | "SQL", failureCode: string, failureDetail: string): ExploreRun => {
      const semantic = semanticRun(semanticQuery({}), { state: "FAILED", failureCode, failureDetail });
      if (kind === "SEMANTIC") return semantic;
      const { query: _query, ...common } = semantic as Extract<ExploreRun, { kind: "SEMANTIC" }>;
      return { ...common, kind: "SQL", sql: "SELECT 1" };
    };
    const openConnector = [{ label: "Open connector", href: "/workspaces/w/connectors?connector=pg" }];
    for (const kind of ["SEMANTIC", "SQL"] as const) {
      const refused = failedRunGuidance(failedRun(kind, "CONNECTION_FAILED", "Connection to 127.0.0.1:15432 refused."), context);
      expect(refused).toEqual({
        title: "Monomer couldn’t connect to pg warehouse.",
        explanation: "Connection to 127.0.0.1:15432 refused.",
        actions: openConnector,
        reference: "CONNECTION_FAILED · Reference run run-1",
      });
      const rejected = failedRunGuidance(failedRun(kind, "AUTHENTICATION_FAILED", "password authentication failed for user \"reader\""), context);
      expect(rejected).toEqual({
        title: "pg warehouse rejected the warehouse sign-in.",
        explanation: "password authentication failed for user \"reader\"",
        actions: openConnector,
        reference: "AUTHENTICATION_FAILED · Reference run run-1",
      });
      for (const guidance of [refused, rejected]) {
        expect(guidance.actions.map((action) => action.label)).not.toContain("Open metric");
        expect(guidance.explanation).not.toContain("time limit");
      }
    }
  });
});

describe("Metrics question filters", () => {
  it("offers declared-grain dimensions once per model and name", () => {
    expect(filterDimensions(filterFixtureGroupBys, filterFixtureModels).map((dimension) => [dimension.semanticModelName, dimension.name, dimension.granularity ?? null]))
      .toEqual(["orders", "events"].flatMap((model) => [[model, "created_at", model === "orders" ? "DAY" : "HOUR"], ...["region", "amount", "active", "region_upper", "profile"].map((name) => [model, name, null])]));
  });

  it("reads operators as words and defaults time dimensions to dates", () => {
    expect(filterOperatorWords("IN", "TEXT")).toBe("is one of");
    expect(filterOperatorWords("GREATER_THAN", "DATE")).toBe("is after");
    expect(filterOperatorWords("LESS_THAN_OR_EQUAL", "NUMBER")).toBe("is at most");
    expect(filterOperatorWords("IS_NULL", "TEXT")).toBe("is empty");
    expect(defaultFilterKind({ kind: "DIMENSION", semanticModelName: "orders", name: "created_at", granularity: "DAY" }, filterFixtureTypes)).toBe("DATE");
    expect(defaultFilterKind({ kind: "DIMENSION", semanticModelName: "orders", name: "region", granularity: null }, filterFixtureTypes)).toBe("TEXT");
    expect(defaultFilterKind({ kind: "DIMENSION", semanticModelName: "orders", name: "created_at", granularity: "HOUR" }, filterFixtureTypes)).toBe("TIMESTAMP");
  });

  it.each([
    ["region", "TEXT", ["EU"], ["EU"]],
    ["amount", "NUMBER", ["1234.50"], ["1234.5"]],
    ["active", "BOOLEAN", ["true"], [true]],
  ] as const)("uses the discovered %s column type and sends its typed values", (name, kind, enteredValues, expectedValues) => {
    const dimension = { kind: "DIMENSION" as const, semanticModelName: "orders", name };
    const comparison = initialFilterComparison(dimension, filterFixtureTypes, null);
    expect(defaultFilterKind(dimension, filterFixtureTypes)).toBe(kind);
    expect(comparison).toEqual({ kind, operator: "EQUALS", values: [], repairing: false });
    expect(buildSemanticFilter(comparison.kind, { semanticModelName: "orders", name }, comparison.operator, [...enteredValues], null).filter)
      .toEqual({ kind, dimension: { semanticModelName: "orders", name }, operator: "EQUALS", values: expectedValues });
  });

  it("leaves computed and unknown dimensions under manual comparison selection", () => {
    for (const name of ["region_upper", "profile"]) {
      const dimension = { kind: "DIMENSION" as const, semanticModelName: "orders", name };
      const initial: SemanticFilter = { kind: "NUMBER", dimension: { semanticModelName: "orders", name }, operator: "IN", values: ["123"] };
      expect(knownDimensionFilterKind(dimension, filterFixtureTypes)).toBeNull();
      expect(defaultFilterKind(dimension, filterFixtureTypes)).toBe("TEXT");
      expect(initialFilterComparison(dimension, filterFixtureTypes, initial)).toEqual({ kind: "NUMBER", operator: "IN", values: ["123"], repairing: false });
      expect(filterComparisonMismatch(initial, filterFixtureTypes)).toBe(false);
    }
  });

  it("requires fresh values when a stored comparison contradicts the latest column type", () => {
    const dimension = { kind: "DIMENSION" as const, semanticModelName: "orders", name: "amount" };
    const initial: SemanticFilter = { kind: "TEXT", dimension: { semanticModelName: "orders", name: "amount" }, operator: "IN", values: ["123", "456"] };
    expect(filterComparisonMismatch(initial, filterFixtureTypes)).toBe(true);
    const repair = initialFilterComparison(dimension, filterFixtureTypes, initial);
    expect(repair).toEqual({ kind: "NUMBER", operator: "IN", values: [], repairing: true });
    expect(buildSemanticFilter(repair.kind, initial.dimension, repair.operator, repair.values, null).filter).toBeNull();
    const booleanTypes: SemanticDimensionFilterType[] = [{ dimension: initial.dimension, kind: "BOOLEAN" }];
    expect(initialFilterComparison(dimension, booleanTypes, initial)).toEqual({ kind: "BOOLEAN", operator: "EQUALS", values: [], repairing: true });
    expect(initialFilterComparison(dimension, [], initial)).toEqual({ kind: "TEXT", operator: "IN", values: initial.values, repairing: false });
  });

  it("preserves matching filters, including false and value-free operators", () => {
    const dimension = { kind: "DIMENSION" as const, semanticModelName: "orders", name: "active" };
    const initial: SemanticFilter = { kind: "BOOLEAN", dimension: { semanticModelName: "orders", name: "active" }, operator: "NOT_EQUALS", values: [false] };
    expect(filterComparisonMismatch(initial, filterFixtureTypes)).toBe(false);
    expect(initialFilterComparison(dimension, filterFixtureTypes, initial)).toEqual({ kind: "BOOLEAN", operator: "NOT_EQUALS", values: ["false"], repairing: false });
    const mismatchingNull: SemanticFilter = { kind: "TEXT", dimension: initial.dimension, operator: "IS_NULL", values: [] };
    expect(initialFilterComparison(dimension, filterFixtureTypes, mismatchingNull)).toEqual({ kind: "BOOLEAN", operator: "IS_NULL", values: [], repairing: true });
  });

  it("keeps unsaved comparison input when unrelated discovery types change or reorder", () => {
    const target = { semanticModelName: "orders", name: "amount" };
    const edited = { kind: "NUMBER" as const, operator: "GREATER_THAN", values: ["456.78"] };
    const unrelatedTypes: SemanticDimensionFilterType[] = [
      { dimension: { semanticModelName: "orders", name: "profile" }, kind: "TEXT" },
      ...filterFixtureTypes.map((entry) => entry.dimension.name === "region" ? { ...entry, kind: "BOOLEAN" as const } : entry),
    ];
    for (const metadata of [filterFixtureTypes, unrelatedTypes, [...unrelatedTypes].reverse()]) {
      const refreshed = reconcileFilterComparison(edited, knownDimensionFilterKind(target, metadata));
      expect(refreshed).toBe(edited);
      expect(buildSemanticFilter(refreshed.kind, target, refreshed.operator, refreshed.values, null).filter)
        .toEqual({ kind: "NUMBER", dimension: target, operator: "GREATER_THAN", values: ["456.78"] });
    }
    expect(reconcileFilterComparison(edited, null)).toBe(edited);
  });

  it("clears input only when the selected dimension requires a different comparison kind", () => {
    const target = { semanticModelName: "orders", name: "amount" };
    const edited = { kind: "NUMBER" as const, operator: "GREATER_THAN", values: ["456"] };
    const metadata: SemanticDimensionFilterType[] = [{ dimension: target, kind: "BOOLEAN" }];
    const refreshed = reconcileFilterComparison(edited, knownDimensionFilterKind(target, metadata));
    expect(refreshed).toEqual({ kind: "BOOLEAN", operator: "EQUALS", values: [] });
    expect(buildSemanticFilter(refreshed.kind, target, refreshed.operator, refreshed.values, null).filter).toBeNull();
    expect(reconcileFilterComparison({ ...edited, operator: "IS_NULL", values: [] }, "BOOLEAN"))
      .toEqual({ kind: "BOOLEAN", operator: "IS_NULL", values: [] });
    const enteredAgain = { ...refreshed, values: ["false"] };
    expect(reconcileFilterComparison(enteredAgain, "BOOLEAN")).toBe(enteredAgain);
  });

  it("preserves restored timestamp values when only their operator changes", () => {
    const dimension = { semanticModelName: "orders", name: "ordered_at" };
    for (const fraction of ["", ".1", ".12", ".123", ".1234", ".12345", ".123456", ".000000"]) {
      const values = [`2026-10-01T00:00:00${fraction}+09:00`];
      for (const operator of ["EQUALS", "NOT_EQUALS", "GREATER_THAN_OR_EQUAL", "LESS_THAN", "IN", "NOT_IN"]) {
        expect(buildSemanticFilter("TIMESTAMP", dimension, operator, values, "HOUR").filter).toMatchObject({ operator, values });
      }
    }
  });

  it("builds filter values with the operator cardinality and never splits on commas", () => {
    const dimension = { semanticModelName: "orders", name: "region" };
    expect(buildSemanticFilter("TEXT", dimension, "IN", ["EU", "Seoul, KR", "EU", " "], null)).toEqual({ filter: { kind: "TEXT", dimension, operator: "IN", values: ["EU", "Seoul, KR"] }, problem: null });
    expect(buildSemanticFilter("TEXT", dimension, "IS_NULL", ["ignored"], null)).toEqual({ filter: { kind: "TEXT", dimension, operator: "IS_NULL", values: [] }, problem: null });
    expect(buildSemanticFilter("BOOLEAN", dimension, "EQUALS", ["TRUE"], null).filter).toEqual({ kind: "BOOLEAN", dimension, operator: "EQUALS", values: [true] });
    expect(buildSemanticFilter("BOOLEAN", dimension, "EQUALS", ["maybe"], null)).toEqual({ filter: null, problem: "Choose true or false." });
    expect(buildSemanticFilter("TEXT", dimension, "EQUALS", [" "], null)).toEqual({ filter: null, problem: null });
    expect(buildSemanticFilter("TEXT", dimension, "EQUALS", ["x".repeat(257)], null).problem).toBe("Each value can be at most 256 characters.");
  });

  it("checks numbers, dates, and times before Run and snaps coarse dates to their period", () => {
    const dimension = { semanticModelName: "orders", name: "amount" };
    expect(["1234.50", "+007", "-0", "1e3", "1,234", "12 3"].map(canonicalDecimal)).toEqual(["1234.5", "7", "0", null, null, null]);
    expect(buildSemanticFilter("NUMBER", dimension, "IN", ["1.50", "1.5"], null).filter?.values).toEqual(["1.5"]);
    expect(buildSemanticFilter("NUMBER", dimension, "EQUALS", ["1,234"], null).problem).toBe("Enter numbers like 1234.5, without commas or spaces.");
    expect(buildSemanticFilter("DATE", dimension, "EQUALS", ["2026-02-30"], "DAY").problem).toBe("Enter a date as YYYY-MM-DD.");
    expect(buildSemanticFilter("DATE", dimension, "GREATER_THAN_OR_EQUAL", ["2026-08-20"], "MONTH").filter?.values).toEqual(["2026-08-01"]);
    expect([periodStart("2026-08-20", "QUARTER"), periodStart("2026-08-20", "YEAR"), periodStart("2026-08-20", "WEEK")]).toEqual(["2026-07-01", "2026-01-01", "2026-08-20"]);
    expect(buildSemanticFilter("TIMESTAMP", dimension, "EQUALS", ["2026-08-20T09:30:00"], "HOUR").problem).toBe("Enter a time with a time zone, like 2026-08-20T09:30:00Z.");
    expect(buildSemanticFilter("TIMESTAMP", dimension, "EQUALS", ["2026-08-20T09:30:00Z"], "HOUR").filter?.values).toEqual(["2026-08-20T09:30:00Z"]);
  });

  it("says how values compare and what not and empty include", () => {
    const word = (grain: string) => grain.toLowerCase();
    expect(filterValueHint("TEXT", "EQUALS", null, word, "UTC")).toBe("Matches the exact text, including upper and lower case.");
    expect(filterValueHint("TEXT", "NOT_IN", null, word, "UTC")).toBe("Matches the exact text, including upper and lower case. Includes rows with no value.");
    expect(filterValueHint("TEXT", "IS_NULL", null, word, "UTC")).toBe("Blank text counts as empty.");
    expect(filterValueHint("DATE", "EQUALS", "MONTH", word, "Asia/Seoul")).toBe("Compares whole months. A date counts as the month it falls in.");
    expect(filterValueHint("TIMESTAMP", "LESS_THAN", "HOUR", word, "UTC")).toBe("Compares whole hours, in UTC time.");
    expect(filterValueHint("TIMESTAMP", "LESS_THAN", "HOUR", word, "Asia/Seoul")).toBe("Compares whole hours, in Asia/Seoul time.");
    expect(filterValueHint("TIMESTAMP", "EQUALS", null, word, "Asia/Seoul")).toBe("Times are in Asia/Seoul.");
    expect(filterValueHint("TIMESTAMP", "IS_NULL", null, word, "Asia/Seoul")).toBeNull();
    expect(filterValueHint("NUMBER", "GREATER_THAN", null, word, "UTC")).toBeNull();
  });
});

describe("semantic problem guidance", () => {
  const context = { workspaceId: "w", connectorId: "pg", connectorName: "pg warehouse", metrics, models };
  const problem = (detail: string, violations: Array<{ field: string; code: string; message: string }>) => toExploreErrorPresentation(new ApiError(detail, 400, "{}", {
    type: "https://monomer.cc/problems/invalid-request", title: "Bad Request", status: 400, detail, instance: "/x", code: "INVALID_REQUEST", requestId: "r", traceId: "trace-1", retryable: false,
    ...(violations.length > 0 ? { violations } : {}),
  }))!;

  it("names the broken model instead of the selected metric", () => {
    const guidance = semanticProblemGuidance(problem("METRICFLOW_MANIFEST_INVALID", [
      { field: "workspace/semantic-models/web", code: "METRICFLOW_MANIFEST_INVALID", message: "Semantic model `web` has no primary entity." },
    ]), context)!;
    expect(guidance.title).toBe("The semantic model “web” has a problem.");
    expect(guidance.explanation).toBe("Semantic model `web` has no primary entity.");
    expect(guidance.actions).toEqual([{ label: "Open semantic model", href: "/workspaces/w/semantic-models/model-web" }]);
    expect(guidance.reference).toBe("METRICFLOW_MANIFEST_INVALID · Reference trace-1");
  });

  it("links each named definition when several are broken", () => {
    const guidance = semanticProblemGuidance(problem("METRICFLOW_MANIFEST_INVALID", [
      { field: "workspace/metrics/margin", code: "METRICFLOW_MANIFEST_INVALID", message: "Metric `margin` divides by a missing input." },
      { field: "workspace/semantic-models/billing", code: "METRICFLOW_MANIFEST_INVALID", message: "Measure `revenue` has no aggregation." },
    ]), context)!;
    expect(guidance.title).toBe("2 definitions this query uses have problems.");
    expect(guidance.actions).toEqual([
      { label: "Open Margin", href: "/workspaces/w/metrics/id-margin" },
      { label: "Open billing", href: "/workspaces/w/semantic-models/model-billing" },
    ]);
  });

  it("sends an empty Catalog to choosing tables", () => {
    const guidance = semanticProblemGuidance(problem("CATALOG_RELATION_NOT_FOUND", [
      { field: "query", code: "CATALOG_RELATION_NOT_FOUND", message: "This connector has no tables loaded in Catalog yet." },
    ]), context)!;
    expect(guidance.title).toBe("No tables from pg warehouse are loaded in Catalog yet.");
    expect(guidance.actions).toEqual([{ label: "Choose tables", href: "/workspaces/w/catalog?connector=pg&choose=1" }]);
  });

  it("names the model whose table isn't loaded", () => {
    const guidance = semanticProblemGuidance(problem("CATALOG_RELATION_NOT_FOUND", [
      { field: "workspace/semantic-models/billing", code: "CATALOG_RELATION_NOT_FOUND", message: "Semantic model `billing` reads public.billing, which isn't loaded in this connector's Catalog." },
    ]), context)!;
    expect(guidance.title).toBe("A table this query reads can’t be found in Catalog for pg warehouse.");
    expect(guidance.explanation).toContain("public.billing");
    expect(guidance.actions.map((action) => action.label)).toEqual(["Open semantic model", "Choose tables"]);
  });

  it("explains a selection that a broken model took away and leaves other selection errors to the caller", () => {
    const named = semanticProblemGuidance(problem("QUERY_GROUP_BY_NOT_FOUND", [
      { field: "workspace/semantic-models/web", code: "METRICFLOW_MANIFEST_INVALID", message: "Semantic model `web` has no primary entity." },
    ]), context)!;
    expect(named.title).toBe("A group by or filter uses “web”, which has a problem.");
    expect(semanticProblemGuidance(problem("QUERY_GROUP_BY_NOT_FOUND", []), context)).toBeNull();
  });

  it("notes models left out of the options and names them in the unavailable hint", () => {
    const excluded = [{ semanticModelName: "web", message: "Semantic model `web` has no primary entity." }];
    expect(excludedModelsSummary(excluded, context, "group bys")).toEqual({
      summary: "“web” has an error, so its group bys aren’t listed.",
      models: [{ label: "web", reason: "Semantic model `web` has no primary entity.", href: "/workspaces/w/semantic-models/model-web" }],
    });
    expect(excludedModelsSummary([...excluded, { semanticModelName: "billing", message: "Broken." }], context, "dimensions")?.summary)
      .toBe("2 models have errors, so their dimensions can’t be filtered.");
    expect(excludedModelsSummary([], context, "group bys")).toBeNull();
    expect(excludedModelLabels(["web", "billing", "web"], excluded, models)).toEqual(["web"]);
  });
});
