import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { ExploreRun } from "@/api/explore";
import type { Connector } from "@/lib/types/connector";
import type { Metric } from "@/lib/types/metric";
import { ExploreWorkbench, modeForKey, type ExploreWorkbenchProps } from "@/pages/explore/ExploreWorkbench";
import { CompactConnectorSelect } from "@/pages/explore/CompactConnectorSelect";
import { SemanticConnectorPart, type SemanticConnectorChoice } from "@/pages/explore/SemanticQuestion";
import { emptyExploreDraft, NO_UNAVAILABLE_SELECTIONS } from "@/pages/explore/explore-draft";
import { filterFixtureGroupBys, filterFixtureModels, filterFixtureTypes } from "@/pages/dev/explore-semantic-filter-fixture/SemanticFilterFixture";

vi.mock("@/lib/navigation", () => ({
  Link: ({ href, children, className }: { href: string; children: ReactNode; className?: string }) => <a href={href} className={className}>{children}</a>,
}));

const connector: Connector = {
  id: "connector-1",
  workspaceId: "workspace-1",
  name: "Northstar Commerce PostgreSQL",
  type: "POSTGRESQL",
  properties: { host: "fixture.internal", port: 5432, database: "warehouse" },
  createdAtEpochMs: 0,
  updatedAtEpochMs: 0,
};

const metric: Metric = {
  id: "metric-1",
  name: "revenue",
  label: "Revenue",
  type: "simple",
  typeParams: { semanticModelName: "orders", measure: "revenue" },
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const succeededRun: ExploreRun = {
  kind: "SEMANTIC",
  reportingTimeZone: "UTC",
  runId: "run-1",
  workspaceId: "workspace-1",
  connectorId: "connector-1",
  requestDigest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  state: "SUCCEEDED",
  resultAvailable: true,
  failureCode: null,
  failureDetail: null,
  createdAt: "2026-10-01T10:21:00Z",
  updatedAt: "2026-10-01T10:21:02Z",
  resultMetadata: { byteSize: "2048", mediaType: "application/vnd.apache.arrow.file", rowCount: "12480", columnCount: 2, expiresAt: "2099-01-01T00:00:00Z" },
  result: {
    columns: [
      { ordinal: 1, name: "dimension.orders.region", logicalType: "TEXT", nullable: true },
      { ordinal: 2, name: "metric.revenue", logicalType: "DECIMAL", nullable: false },
    ],
    rows: [["EU", "412880.00"], ["UK", "138240.50"]],
    returnedRowCount: 1000,
    stopReason: "ROW_LIMIT",
  },
  sql: "SELECT region, SUM(amount) FROM orders GROUP BY 1",
  resultState: "AVAILABLE",
  startedBy: { id: "user-sam", displayName: "Sam Lee" },
  credentialSurface: "WEB", agentApp: null,
  query: {
    schemaVersion: 4, metrics: ["revenue"], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100000,
    groupBys: [{ kind: "DIMENSION", semanticModelName: "orders", name: "region", granularity: null }],
  },
};

const noop = () => undefined;

function props(overrides: Partial<ExploreWorkbenchProps> = {}): ExploreWorkbenchProps {
  return {
    workspaceId: "workspace-1",
    principalId: "user-viewer",
    mode: "SEMANTIC",
    onModeChange: noop,
    connectors: [connector],
    connectorsLoading: false,
    connectorsError: null,
    onRetryConnectors: noop,
    semanticQuestion: {
      workspaceId: "workspace-1",
      reportingTimeZone: "UTC",
      draft: { ...emptyExploreDraft().semantic, metrics: ["revenue"], groupBys: [filterFixtureGroupBys[4]!], filters: [{ kind: "TEXT", dimension: { semanticModelName: "orders", name: "region" }, operator: "IN", values: ["EU", "UK"] }] },
      onDraftChange: noop,
      metrics: [metric],
      metricsLoading: false,
      metricsError: null,
      modelFilter: null,
      onClearModelFilter: noop,
      visibleMetricNames: ["revenue"],
      semanticModels: filterFixtureModels,
      semanticModelsLoading: false,
      semanticModelsError: null,
      onRetrySemanticModels: noop,
      groupBys: filterFixtureGroupBys,
      dimensionFilterTypes: filterFixtureTypes,
      excludedSemanticModels: [],
      groupBysLoading: false,
      groupBysError: null,
      unavailable: NO_UNAVAILABLE_SELECTIONS,
      onChooseMetric: noop,
      metricSwap: null,
      canEditDefinitions: true,
      connector: { all: [connector], switchCount: 0, compatible: [connector], selectedId: connector.id, onSelect: noop, resolving: false, problem: null },
    },
    semanticRunDisabled: false,
    semanticStarting: false,
    onRunSemantic: noop,
    renderedSql: { open: false, onOpenChange: noop, loading: false, failure: null, render: null, connectorName: connector.name, disabled: false, onRender: noop },
    sql: "",
    onSqlChange: noop,
    sqlConnectorId: connector.id,
    onSqlConnectorChange: noop,
    sqlRunDisabled: true,
    sqlStarting: false,
    onRunSql: noop,
    startFailure: null,
    replacedQuestion: null,
    runPanel: {
      workspaceId: "workspace-1",
      principalId: "user-viewer",
      run: null,
      loading: false,
      error: null,
      editedSinceRun: false,
      connectors: [connector],
      metrics: [metric],
      semanticModels: filterFixtureModels,
      onCancel: noop,
      cancelling: false,
      cancelError: null,
      onOpenInSql: noop,
      onRunAgain: noop,
      runAgainDisabled: false,
      onClose: noop,
    },
    runs: [],
    metrics: [metric],
    semanticModels: filterFixtureModels,
    newlyAddedRunId: null,
    selectedRunId: null,
    onInspectRun: noop,
    ...overrides,
  };
}

const render = (overrides: Partial<ExploreWorkbenchProps> = {}) => renderToStaticMarkup(<ExploreWorkbench {...props(overrides)} />);

describe("ExploreWorkbench", () => {
  it("marks a restored comparison mismatch and offers editing or removal without changing the filter", () => {
    const semanticQuestion = props().semanticQuestion;
    const draft = { ...semanticQuestion.draft, filters: [{ kind: "NUMBER" as const, dimension: { semanticModelName: "orders", name: "region" }, operator: "EQUALS" as const, values: ["123"] }] };
    const markup = render({ semanticQuestion: { ...semanticQuestion, draft } });
    expect(markup).toContain("comparison type doesn’t match the column");
    expect(markup).toContain("A filter’s comparison type doesn’t match its column. Edit or remove it to run.");
    expect(markup).toContain("region is 123");
    expect(draft.filters[0].kind).toBe("NUMBER");
    expect(render()).not.toContain("Edit or remove it to run.");
  });

  it("offers connector setup only for a successfully loaded empty inventory", () => {
    expect(render({ connectors: [] })).toContain('href="/workspaces/workspace-1/connectors"');
    expect(render({ connectors: [], connectorsLoading: true })).not.toContain("Go to Connectors");
    expect(render({ connectors: [], connectorsError: "Failed" })).not.toContain("Go to Connectors");
    expect(render()).not.toContain("Go to Connectors");
  });

  it("switches between Metrics and SQL as tabs with one tab stop and a labelled panel", () => {
    const markup = render();
    expect(markup).toContain('role="tablist" aria-label="Query type"');
    expect(markup).toMatch(/id="explore-semantic-tab" type="button" role="tab" aria-selected="true" aria-controls="explore-semantic-panel" tabindex="0"[^>]*>Metrics/);
    expect(markup).toMatch(/id="explore-sql-tab" type="button" role="tab" aria-selected="false" aria-controls="explore-sql-panel" tabindex="-1"/);
    expect(markup).toContain('id="explore-semantic-panel" role="tabpanel" aria-labelledby="explore-semantic-tab"');
    expect(render({ mode: "SQL" })).toContain('id="explore-sql-panel" role="tabpanel" aria-labelledby="explore-sql-tab"');
    expect(modeForKey("ArrowRight", "SEMANTIC")).toBe("SQL");
    expect(modeForKey("ArrowLeft", "SEMANTIC")).toBe("SQL");
    expect(modeForKey("Home", "SQL")).toBe("SEMANTIC");
    expect(modeForKey("End", "SEMANTIC")).toBe("SQL");
    expect(modeForKey("Enter", "SEMANTIC")).toBeNull();
  });

  it("names each part of the Semantic question on its own row with the connector decided by the metrics", () => {
    const markup = render();
    for (const label of ["Metrics", "Group by", "Filters", "Time range", "Sort", "Limit"]) expect(markup).toContain(`>${label}</span>`);
    // With no run in view the lists below are the picker, so the question's chips open no second panel.
    expect(markup).toContain('<span class="truncate">Revenue</span>');
    expect(markup).toContain('<span class="truncate">region</span>');
    expect(markup).not.toContain("Add metric");
    expect(markup).not.toContain("Add group by");
    expect(markup).toContain('id="explore-metric-list-search"');
    expect(markup).toContain("region is one of EU, UK");
    expect(markup).toContain(">Any time</button>");
    expect(markup).toContain("1,000 rows");
    expect(markup).toContain("Northstar Commerce PostgreSQL</span>");
    const two = { ...connector, id: "connector-2", name: "Snowflake Finance" };
    const choice = render({ semanticQuestion: { ...props().semanticQuestion, connector: { all: [connector, two], switchCount: 0, compatible: [connector, two], selectedId: null, onSelect: noop, resolving: false, problem: null } } });
    expect(choice).toContain('aria-label="Select connector"');
    expect(choice).toContain("Choose a connector");
    expect(render({ semanticQuestion: { ...props().semanticQuestion, connector: { all: [connector], switchCount: 0, compatible: [], selectedId: null, onSelect: noop, resolving: false, problem: "Revenue reads the model “orders”, which doesn’t exist." } } }))
      .toContain("Revenue reads the model “orders”, which doesn’t exist.");
  });

  it("keeps Run's shortcut out of the button and in a hover tooltip", () => {
    const markup = render();
    const button = markup.match(/<button[^>]*aria-keyshortcuts="(?:Meta|Control)\+Enter"[^>]*>[\s\S]*?<\/button>/)?.[0] ?? "";
    expect(button.replace(/<[^>]+>/g, "")).toBe("Run");
    expect(markup).toMatch(/<span aria-hidden="true" data-testid="run-shortcut-tooltip"[^>]*>Run<kbd[^>]*>(⌘↵|Ctrl ↵)<\/kbd><\/span>/);
  });

  it("renders a rejected start as guidance with its next actions", () => {
    const markup = render({ startFailure: { title: "This query needs a time spine on Production.", explanation: "Monomer needs a table with one row per day.", actions: [{ label: "Set up time spine", href: "/workspaces/workspace-1/semantics?view=time-spines" }], reference: "TIME_SPINE_UNAVAILABLE · Reference trace-1" } });
    expect(markup).toContain('role="alert"');
    expect(markup).toContain("This query needs a time spine on Production.");
    expect(markup).toContain('href="/workspaces/workspace-1/semantics?view=time-spines"');
    expect(markup).toContain("TIME_SPINE_UNAVAILABLE · Reference trace-1");
  });

  it("shows the viewed run under the composer instead of in a dialog", () => {
    const markup = render({
      runs: [succeededRun],
      selectedRunId: "run-1",
      runPanel: { ...props().runPanel, run: succeededRun, editedSinceRun: true },
    });
    expect(markup).toContain('id="explore-run-title"');
    expect(markup).toContain("Revenue by region");
    expect(markup).toContain("Query edited since this run");
    expect(markup).toContain("Copy link");
    expect(markup).toContain('aria-label="Download result"');
    expect(markup).toContain('role="tablist" aria-label="Result view"');
    expect(markup).toContain(">Chart</button>");
    expect(markup).toContain("First 1,000 of 12,480 rows");
    expect(markup).toContain('aria-sort="none"');
    expect(markup).toContain('aria-current="true"');
    expect(markup).not.toContain("Inspect this run");
    expect(renderToStaticMarkup(<ExploreWorkbench {...props({ runPanel: { ...props().runPanel, run: { ...succeededRun, kind: "SQL", query: undefined } as unknown as ExploreRun } })} />)).not.toContain(">Chart</button>");
  });

  it("names who started the viewed run in its header and in Runs", () => {
    const claude = { clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude", verifiedHost: "claude.ai" };
    const agentRun: ExploreRun = { ...succeededRun, credentialSurface: "AGENT", agentApp: claude };
    const header = (run: ExploreRun, principalId: string | null) => renderToStaticMarkup(<ExploreWorkbench {...props({
      runs: [run],
      selectedRunId: "run-1",
      runPanel: { ...props().runPanel, principalId, run },
    })} />).match(/aria-labelledby="explore-run-title"[\s\S]*?<\/header>/)?.[0] ?? "";
    const teammate = header(agentRun, "user-viewer");
    expect(teammate).toContain("Started by Sam Lee");
    expect(teammate).toContain('aria-label="via Claude (claude.ai)"');
    expect(teammate.indexOf("Started by Sam Lee")).toBeLessThan(teammate.indexOf("via Claude"));
    expect(header(succeededRun, "user-sam")).toContain("Started by you");
    expect(header(succeededRun, "user-sam")).not.toContain("via ");
    const rail = render({ runs: [agentRun], selectedRunId: "run-1", runPanel: { ...props().runPanel, run: agentRun } });
    expect(rail).toContain("Sam Lee");
  });

  it("shows why a run failed and its SQL as the run's two tabs", () => {
    const failed = { ...succeededRun, state: "FAILED", resultAvailable: false, result: null, resultMetadata: null, failureCode: "EXECUTION_FAILED", failureDetail: "column \"region\" does not exist" } as ExploreRun;
    const markup = render({ runPanel: { ...props().runPanel, run: failed } });
    expect(markup).toContain("column &quot;region&quot; does not exist");
    expect(markup).toContain('role="tablist" aria-label="Result view"');
    expect(markup).toContain(">Why it failed<");
    expect(markup).toContain(">SQL</button>");
    expect(markup).not.toContain(">Table</button>");
    expect(markup).not.toContain(">Chart</button>");
    expect(markup).toContain('id="explore-result-failure-panel"');
    expect(markup).toContain("Run again");
  });

  it("keeps an expired run open with its question, SQL, and a Run action", () => {
    const expired = { ...succeededRun, result: null, resultState: "EXPIRED" } as ExploreRun;
    const markup = render({ runPanel: { ...props().runPanel, run: expired } });
    expect(markup).toContain("This result has expired. Run it again to see it.");
    expect(markup).toContain(">Table<");
    expect(markup).toContain(">SQL</button>");
    expect(markup).not.toContain(">Chart</button>");
    expect(markup).not.toContain('aria-label="Download result"');
    const unpreviewable = render({ runPanel: { ...props().runPanel, run: { ...expired, resultState: "PREVIEW_UNAVAILABLE" } as ExploreRun } });
    expect(unpreviewable).toContain("This result can’t be previewed. Download it, or run it again.");
    expect(unpreviewable).toContain('aria-label="Download result"');
  });

  it("shows a safe error when the selected run can no longer be read", () => {
    const requestId = "srv_5ac15c33-f9ae-4e8a-9b01-73c80be5daa7";
    const markup = render({ runPanel: { ...props().runPanel, error: { code: "FORBIDDEN", detail: "Explore result is not authorized.", retryable: false, traceId: requestId, violations: [] } } });
    expect(markup).toContain("Explore result is not authorized.");
    expect(markup).toContain(`FORBIDDEN · Reference ${requestId}`);
  });
});


describe("Metrics connector control", () => {
  const choice = (overrides: Partial<SemanticConnectorChoice>): SemanticConnectorChoice => ({
    all: [connector], switchCount: 0, compatible: [connector], selectedId: connector.id, onSelect: vi.fn(), resolving: false, problem: null, ...overrides,
  });
  const renderConnector = (selection: SemanticConnectorChoice) => renderToStaticMarkup(<SemanticConnectorPart connector={selection} />);

  it("shows one compatible connector as read-only with its original title and no chevron", () => {
    const selection = choice({});
    const markup = renderConnector(selection);
    expect(markup).toContain('title="Connector with the source tables of these metrics"');
    expect(markup).toContain('data-connector-type-mark="POSTGRESQL"');
    expect(markup).toContain(connector.name);
    expect(markup).not.toContain("<button");
    expect(markup).not.toContain("tabindex");
    expect(markup).not.toContain("chevron-down");
    expect(selection.onSelect).not.toHaveBeenCalled();
  });

  it("uses the SQL selector for several compatible connectors without picking a default", () => {
    const compatible = [connector, { ...connector, id: "connector-2", name: "Finance warehouse" }];
    const selection = choice({ compatible, selectedId: null });
    const markup = renderConnector(selection);
    expect(markup).toContain('aria-label="Select connector"');
    expect(markup).toContain('aria-haspopup="listbox"');
    expect(markup).toContain('aria-expanded="false"');
    expect(markup).toContain("Choose a connector");
    expect(markup).not.toContain("data-connector-type-mark");
    expect(markup).not.toContain('role="combobox"');
    expect(selection.onSelect).not.toHaveBeenCalled();
    expect(markup).toBe(renderToStaticMarkup(<CompactConnectorSelect connectors={compatible} value={null} onChange={selection.onSelect} loading={false} error={null} onRetry={noop} />));
    const chosen = renderConnector({ ...selection, selectedId: "connector-2" });
    expect(chosen).toContain("Finance warehouse");
    expect(chosen).not.toContain("Choose a connector");
  });

  it("keeps the reason when no connector qualifies, without a selector", () => {
    const problem = "Revenue reads the model “orders”, which doesn’t exist.";
    const markup = renderConnector(choice({ compatible: [], selectedId: null, problem }));
    expect(markup).toContain('role="status"');
    expect(markup).toContain(problem);
    expect(markup).not.toContain("<button");
    expect(markup).not.toContain("data-connector-type-mark");
    expect(renderConnector(choice({ compatible: [], selectedId: null }))).toContain("No connector has source tables for all of these metrics.");
  });

  it("hides the connector when no metric is chosen", () => {
    const semanticQuestion = props().semanticQuestion;
    const markup = render({ semanticQuestion: { ...semanticQuestion, draft: emptyExploreDraft().semantic } });
    expect(markup).not.toContain('title="Connector with the source tables of these metrics"');
    expect(markup).not.toContain('aria-label="Select connector"');
  });
});
