import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { DashboardQueryRun } from "@/api/dashboard";
import { sessionExactTime } from "./session-time";
import { DashboardActivitySidebar, DashboardQueryRunDetail, type DashboardActivitySidebarProps } from "./DashboardActivitySidebar";

const run: DashboardQueryRun = { queryType: "SEMANTIC" as const,
  queryRunId: "run-orders", queryName: "orders", connectorId: "warehouse",
  createdAt: "2026-09-01T10:00:00Z", updatedAt: "2026-09-01T10:00:02Z",
  state: "SUCCEEDED", resultAvailable: true, outputColumnNames: ["order_count"],
  failureCode: null, failureMessage: null, startedBy: null, credentialSurface: null, agentApp: null,
};
const sam = { id: "u-sam", displayName: "Sam Lee" };
const codex = { clientId: "https://chatgpt.com/codex/client.json", name: "Codex", verifiedHost: "chatgpt.com" };
const props: DashboardActivitySidebarProps = {
  principalId: "u-viewer",
  expandedGroups: {}, onExpandedGroupsChange: () => undefined,
  sessions: [{ dashboardId: "dashboard", dashboardSessionId: "selected-session", createdAt: "2026-09-01T09:00:00Z", updatedAt: "2026-09-01T11:00:00Z", lastQueryRunAt: run.createdAt, parameterBindings: { country: "KR" }, runningQueryRuns: [{ queryName: "orders", queryRunId: "running-orders", state: "RUNNING" }] }],
  selectedSessionId: "selected-session",
  selectedSession: { dashboardSessionId: "selected-session", definition: { schemaVersion: 1, meta: { title: "Orders" }, parameters: {}, variables: {}, queries: {}, widgets: {}, layout: { sections: [] } }, parameterBindings: { country: "KR" } },
  queryRuns: [run], targetQueryRuns: { orders: run.queryRunId }, collapsed: false,
  onCollapsedChange: () => undefined, queryRunsLoading: false, queryRunsError: null,
  hasNextQueryRuns: false, loadingNextQueryRuns: false, onLoadMoreQueryRuns: () => undefined,
  onRefreshQuery: () => undefined, refreshingQueryNames: [], hasNextSessions: false,
  loadingNextSessions: false, sessionsError: null, onLoadMoreSessions: () => undefined,
  onSelectSession: () => undefined,
  view: "sessions", onViewChange: () => undefined, mobileOpen: false, onMobileOpenChange: () => undefined,
};

const render = (overrides: Partial<DashboardActivitySidebarProps> = {}) => renderToStaticMarkup(<DashboardActivitySidebar {...props} {...overrides} />);
const visibleText = (markup: string) => markup.replace(/<[^>]+>/g, "");

describe("Dashboard History panel", () => {
  it("names each entry by its inputs, shows its result time and running activity, and offers query runs", () => {
    const markup = render();
    expect(markup).toContain('aria-label="History"');
    expect(markup).toContain('>History</h2>');
    expect(markup).toContain('aria-label="Close history"');
    expect(markup).toContain('data-session-id="selected-session"');
    expect(markup).toContain('aria-current="true"');
    expect(markup).toContain('bg-accent-marker');
    expect(visibleText(markup)).toContain('Country KR');
    expect(markup).toContain(`aria-label="Country KR. Opened ${sessionExactTime(props.sessions[0].createdAt)}. Last run ${sessionExactTime(run.createdAt)}."`);
    expect(visibleText(markup)).toMatch(/Last run /);
    expect(markup).toContain(`Opened · ${sessionExactTime(props.sessions[0].createdAt)}`);
    expect(markup).toContain(`Last query run · ${sessionExactTime(run.createdAt)}`);
    expect(markup).not.toContain(sessionExactTime(props.sessions[0].updatedAt));
    expect(markup).toContain('1 running');
    expect(markup).toMatch(/<span class="[^"]*text-foreground-secondary"><svg[^>]*lucide-loader-circle[^>]*motion-reduce:animate-none[^>]*>[\s\S]*?<\/svg>1 running<\/span>/);
    expect(markup).not.toContain('text-success');
    expect(visibleText(markup)).toContain('Select an entry to see its results.');
    expect(visibleText(markup)).toContain('Show query runs');
    expect(visibleText(markup)).not.toMatch(/session/i);
    expect(markup).not.toContain('aria-expanded');
  });

  it("names entries by the first labelled input followed by the remaining values in definition order", () => {
    const definitions = {
      region: { type: "TEXT" as const, nullable: false, label: "Region" },
      period: { type: "TEXT" as const, nullable: false, label: "Period" },
      currency: { type: "TEXT" as const, nullable: true, label: "Currency", control: { options: [{ value: "USD" }], emptyLabel: "All currencies" } },
    };
    const markup = render({
      selectedSession: { definition: { parameters: definitions } },
      sessions: [{ ...props.sessions[0], parameterBindings: { currency: null, period: "Q3 2026", region: "APAC" } }],
    });
    expect(visibleText(markup)).toContain('Region APAC · Q3 2026 · All currencies');
  });

  it("does not infer a result time from the entry update time and removes finished activity", () => {
    const markup = render({ sessions: [{ ...props.sessions[0], lastQueryRunAt: null, runningQueryRuns: [] }] });
    expect(markup).toContain('No runs yet');
    expect(markup).not.toContain('1 running');
  });

  it("starts historical query groups collapsed while retaining latest state and explicit refresh", () => {
    const markup = render({ view: "queries", queryRuns: [run, { ...run, queryRunId: "older-running", state: "RUNNING", createdAt: "2026-09-01T08:00:00Z" }] });
    expect(markup).toContain('aria-label="Back to history"');
    expect(markup).toContain('>History</h2>');
    expect(markup).toContain(`Opened ${sessionExactTime(props.sessions[0].createdAt)}`);
    expect(markup).toContain('>Query runs</span>');
    const toggle = markup.match(/<button[^>]*aria-label="Query orders"[\s\S]*?<\/button>/)?.[0];
    expect(toggle).toContain('>orders</span>');
    expect(toggle).not.toContain('aria-label="Refresh orders"');
    expect(markup).toContain('aria-label="Query orders" aria-expanded="false"');
    expect(visibleText(markup)).toContain('Latest: Succeeded');
    expect(markup).toMatch(/Latest: <span[^>]*><span[^>]*><svg[^>]*lucide-check[^>]*data-work-status="SUCCEEDED"/);
    expect(markup).toContain('1 running');
    expect(markup).toContain('aria-label="Refresh orders"');
    expect(markup).toContain('inert="" aria-hidden="true" data-expanded="false"');
    expect(markup).toContain('2 loaded runs');
    expect(markup).not.toContain('New session');
  });

  it("restores parent-owned disclosure after remount while isolating other entries", () => {
    const expandedGroups = { "selected-session": ["orders"], "another-session": [] };
    const selected = render({ view: "queries", expandedGroups });
    expect(selected).toContain('aria-label="Query orders" aria-expanded="true"');
    expect(visibleText(selected)).toContain('Succeeded · Current target');
    expect(selected).toContain('aria-label="Open orders run');
    const other = render({ view: "queries", selectedSessionId: "another-session", expandedGroups });
    expect(other).toContain('aria-label="Query orders" aria-expanded="false"');
    const restored = render({ view: "queries", expandedGroups });
    expect(restored).toContain('aria-label="Query orders" aria-expanded="true"');
  });

  it("keeps loaded groups and entries visible alongside explicit failure and retry", () => {
    const queries = render({ view: "queries", queryRunsError: "Run history unavailable" });
    expect(queries).toContain('role="alert"');
    expect(queries).toContain('Run history unavailable');
    expect(queries).toContain('aria-label="Query orders"');
    expect(queries).toContain('Retry runs');
    const entries = render({ sessionsError: "History unavailable" });
    expect(entries).toContain('History unavailable');
    expect(entries).toContain('data-session-id="selected-session"');
    expect(entries).toContain('Retry history');
  });

  it("stays closed on desktop until opened", () => {
    const closed = render({ collapsed: true });
    expect(closed).not.toContain('aria-label="History"');
    expect(closed).not.toContain('data-session-id');
    const open = render({ collapsed: false });
    expect(open).toContain('hidden h-full');
    expect(open).toContain('w-[320px]');
    expect(open).toContain('lg:flex');
  });

  it("shows initial loading, empty state, and pagination independently", () => {
    expect(render({ view: "queries", queryRuns: [], queryRunsLoading: true })).toContain('Loading query runs…');
    expect(render({ view: "queries", queryRuns: [] })).toContain('These results have no queries.');
    expect(render({ sessions: [] })).toContain('No history yet.');
    expect(render({ hasNextSessions: true })).toContain('Load earlier entries');
    expect(render({ view: "queries", hasNextQueryRuns: true })).toContain('Load earlier runs');
  });
});


describe("Public dashboard activity", () => {
  it("retains query inspection without member execution actions", () => {
    const markup = render({ readOnly: true, view: "queries", queryNames: ["orders", "unrun"], selectedSession: { definition: { parameters: {} } } });
    expect(markup).toContain('aria-label="Query orders"');
    expect(markup).toContain('aria-label="Query unrun"');
    expect(markup).toContain('aria-label="Open orders run');
    expect(markup).not.toContain('aria-label="Refresh orders"');
    expect(markup).not.toContain('New session');
  });
  it("does not invent inputs or activity for display-only entry summaries", () => {
    const markup = render({ readOnly: true, view: "sessions", sessions: [{ dashboardSessionId: "public", createdAt: run.createdAt, updatedAt: run.updatedAt }] });
    expect(markup).toContain('data-session-id="public"');
    expect(markup).not.toContain('No inputs');
    expect(markup).not.toContain('No runs yet');
    expect(markup).toContain('Opened ');
    expect(markup).not.toContain('New session');
  });
});

describe("Dashboard run request path", () => {
  it("marks only runs a person started through an agent connection", () => {
    const agentRun = { ...run, queryRunId: "agent-orders", createdAt: "2026-09-01T10:05:00Z", credentialSurface: "AGENT" as const };
    const webRun = { ...run, credentialSurface: "WEB" as const };
    const markup = render({ view: "queries", queryRuns: [webRun, agentRun], expandedGroups: { "selected-session": ["orders"] } });
    const rows = [...markup.matchAll(/<button[^>]*aria-label="Open orders run[\s\S]*?<\/button>/g)].map((match) => match[0]);
    expect(rows).toHaveLength(2);
    const agentRow = rows.find((row) => row.includes(", started via agent")) ?? "";
    const webRow = rows.find((row) => !row.includes(", started via agent")) ?? "";
    expect(visibleText(agentRow)).toContain("via agent");
    expect(agentRow).not.toContain("lucide-square-terminal");
    expect(visibleText(webRow)).not.toContain("via agent");
  });

  it("does not mark display-only runs that carry no request path", () => {
    const publicRun = { queryType: "SEMANTIC" as const, queryRunId: run.queryRunId, queryName: run.queryName, createdAt: run.createdAt, updatedAt: run.updatedAt, state: run.state, resultAvailable: run.resultAvailable, outputColumnNames: run.outputColumnNames, failureCode: run.failureCode };
    const markup = render({ readOnly: true, view: "queries", queryRuns: [publicRun], expandedGroups: { "selected-session": ["orders"] } });
    expect(visibleText(markup)).not.toContain("via agent");
  });
});

describe("Dashboard run person", () => {
  const rowsOf = (markup: string) => [...markup.matchAll(/<button[^>]*aria-label="Open orders run[\s\S]*?<\/button>/g)].map((match) => match[0]);
  const plainText = (markup: string) => visibleText(markup
    .replace(/<span class="sr-only">.*?<\/span>/g, "")
    .replace(/<span aria-hidden="true" class="pointer-events-none[^"]*"[^>]*>.*?<\/span>/g, ""));

  it("names the person who started each run, then the agent app", () => {
    const agentRun = { ...run, queryRunId: "agent-orders", createdAt: "2026-09-01T10:05:00Z", startedBy: sam, credentialSurface: "AGENT" as const, agentApp: codex };
    const webRun = { ...run, startedBy: sam, credentialSurface: "WEB" as const };
    const markup = render({ view: "queries", queryRuns: [webRun, agentRun], expandedGroups: { "selected-session": ["orders"] } });
    const [agentRow, webRow] = rowsOf(markup);
    expect(agentRow).toContain(", started by Sam Lee via Codex (chatgpt.com)\"");
    expect(plainText(agentRow)).toContain("·Sam Leevia Codex");
    expect(agentRow).toContain('data-agent-app-logo="openai"');
    expect(webRow).toContain(", started by Sam Lee\"");
    expect(plainText(webRow)).toContain("·Sam Lee");
    expect(plainText(webRow)).not.toContain("via");
  });

  it("says you for the viewer and names no one for a run without a recorded person", () => {
    const own = { ...run, startedBy: sam, credentialSurface: "WEB" as const };
    expect(rowsOf(render({ principalId: "u-sam", view: "queries", queryRuns: [own], expandedGroups: { "selected-session": ["orders"] } }))[0]).toContain(", started by you\"");
    const scheduled = rowsOf(render({ view: "queries", queryRuns: [run], expandedGroups: { "selected-session": ["orders"] } }))[0];
    expect(scheduled).not.toContain("started");
    expect(plainText(scheduled)).toMatch(/^[^·]*Succeeded/);
  });

  it("shows who started the run in its detail", () => {
    const detail = renderToStaticMarkup(<DashboardQueryRunDetail run={{ ...run, startedBy: sam, credentialSurface: "AGENT", agentApp: codex }} principalId="u-viewer" currentTarget={false} />);
    expect(plainText(detail)).toContain("Started bySam Leevia Codex");
    expect(detail).toContain('aria-label="via Codex (chatgpt.com)"');
    const own = renderToStaticMarkup(<DashboardQueryRunDetail run={{ ...run, startedBy: sam, credentialSurface: "WEB" }} principalId="u-sam" currentTarget={false} />);
    expect(plainText(own)).toContain("Started byYou");
    const unrecorded = renderToStaticMarkup(<DashboardQueryRunDetail run={run} principalId="u-viewer" currentTarget={false} />);
    expect(plainText(unrecorded)).not.toContain("Started by");
  });
});

describe("Dashboard run status", () => {
  it("shows the safe exact-bind limitation in a failed SQL run detail", () => {
    const failureMessage = "Databricks Statement Execution API cannot bind a non-null BYTES value. Use a Semantic query.";
    const failed = { ...run, queryType: "SQL" as const, state: "FAILED" as const, resultAvailable: false, failureCode: "QUERY_BIND_UNSUPPORTED", failureMessage };
    const markup = renderToStaticMarkup(<DashboardQueryRunDetail run={failed} principalId="u-viewer" currentTarget={false} />);
    expect(visibleText(markup)).toContain(failureMessage);
    expect(visibleText(markup)).not.toContain("QUERY_BIND_UNSUPPORTED");
  });

  it("names failed runs with the shared failure status instead of a red label", () => {
    const failed = { ...run, queryRunId: "failed-orders", state: "FAILED" as const, resultAvailable: false, failureCode: "QUERY_FAILED" };
    const markup = render({ view: "queries", queryRuns: [failed], targetQueryRuns: {}, expandedGroups: { "selected-session": ["orders"] } });
    const row = markup.match(/<button[^>]*aria-label="Open orders run[\s\S]*?<\/button>/)?.[0] ?? "";
    expect(row).toContain('data-work-status="FAILED" data-work-status-tone="failure"');
    expect(row).toContain('lucide-circle-alert');
    expect(visibleText(row)).toContain('Failed');
    expect(row).not.toMatch(/<span class="[^"]*text-error[^"]*">/);
  });
});
