import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { DraftBody, parseShowResult, QueryResultBody, type ShowContext } from "./contract";
import type { DashboardViewController, DashboardViewSnapshot } from "./controller";
import { DashboardView } from "./DashboardView";
import { createViewHostStore } from "./host";
import { bundleFromReadyResult, draftLayoutContent, type ResultEntry } from "./presentation";
import { namedResultFromAgent } from "./result";
import { draftRead, ORDERS_RUN, ORDERS_TOTAL_RUN, ordersPivotWidget, ordersResult, ordersTotalQuery, ordersTotalResult, REVENUE_RUN, revenueResult, showResult, WEB_URL } from "./fixtures";

const accepted = parseShowResult(showResult({ requestId: "req-1", shownAt: 1000 }));
const show = (accepted.kind === "accepted" ? accepted.show : null) as ShowContext;
const draft = DraftBody.parse((draftRead({ revision: "3", notes: "Draft notes.", formattedTable: true, ordersRun: ORDERS_RUN }).structuredContent as { body: unknown }).body);
const bodyOf = (result: { structuredContent?: unknown }) => QueryResultBody.parse((result.structuredContent as { body: unknown }).body);
const results = new Map<string, ResultEntry>([
  [REVENUE_RUN, { kind: "loaded", result: namedResultFromAgent(REVENUE_RUN, bodyOf(revenueResult)) }],
  [ORDERS_RUN, { kind: "loaded", result: namedResultFromAgent(ORDERS_RUN, bodyOf(ordersResult)) }],
]);
const bundles = Object.fromEntries(Object.entries(draft.readyResults).map(([widgetId, ready]) => [widgetId, bundleFromReadyResult(ready)]));
const content = draftLayoutContent({ definition: draft.definition, parameterBindings: {}, variables: {}, revision: 3, readyBundles: bundles, adoptedBundles: bundles, targets: {}, results });

const READY: DashboardViewSnapshot = { stage: "ready", show, webUrl: WEB_URL, message: null, content, live: "live", runningQueries: 0, updatedAt: Date.now() };

function render(snapshot: Partial<DashboardViewSnapshot>, options: { displayMode?: "inline" | "fullscreen"; fullscreen?: boolean; charts?: "run" | "placeholder" } = {}) {
  const controller = { subscribe: () => () => undefined, getSnapshot: () => ({ ...READY, ...snapshot }) } as unknown as DashboardViewController;
  const hostStore = createViewHostStore();
  hostStore.update({ displayMode: options.displayMode ?? "inline", availableDisplayModes: options.fullscreen === false ? ["inline"] : ["inline", "fullscreen"], theme: "dark" });
  return renderToStaticMarkup(<DashboardView
    controller={controller}
    hostStore={hostStore}
    actions={{ openLink: vi.fn(), requestDisplayMode: vi.fn() }}
    workerFactories={{ chart: vi.fn(), table: vi.fn() }}
    runtimePlan={{ charts: options.charts ?? "run", tables: "run" }}
  />);
}

describe("Dashboard view", () => {
  it("shows an inline dashboard with its live status and host actions", () => {
    const markup = render({});
    expect(markup).toContain("Weekly revenue");
    expect(markup).toContain('role="status" aria-live="polite" data-testid="mcp-view-status">Draft · Updated Just now');
    expect(markup).toContain(">Expand</button>");
    expect(markup).toContain(">Open in Monomer</button>");
    expect(markup).not.toContain("Exit full screen");
    expect(markup).toContain("max-height:520px");
    expect(markup).toContain("Draft notes.");
    expect(markup).toContain('data-testid="dashboard-chart-widget"');
    expect(markup).toContain('data-host-theme="dark"');
  });

  it("titles the header from the current Draft definition, not the title at show time", () => {
    const renamed = { ...content, definition: { ...content.definition, meta: { ...content.definition.meta, title: "Renamed in the Draft" } } };
    const markup = render({ content: renamed });
    expect(markup).toContain(">Renamed in the Draft</h2>");
    expect(render({ content: null, stage: "loading" })).toContain(`>${show.title}</h2>`);
  });

  it("offers Expand only where the host allows fullscreen, and Exit in fullscreen", () => {
    expect(render({}, { fullscreen: false })).not.toContain(">Expand</button>");
    const fullscreen = render({}, { displayMode: "fullscreen" });
    expect(fullscreen).toContain(">Exit full screen</button>");
    expect(fullscreen).not.toContain(">Expand</button>");
    expect(fullscreen).not.toContain("max-height:520px");
  });

  it("offers Resume when paused and Retry when updates stopped", () => {
    expect(render({ live: "paused" })).toContain(">Resume</button>");
    expect(render({ live: "paused" })).toContain("Draft · Paused");
    expect(render({ live: "failed" })).toContain(">Retry</button>");
  });

  it("greys out a superseded view and says where the newer one is", () => {
    const markup = render({ live: "superseded" });
    expect(markup).toContain('class="mcp-view-notice type-meta">A newer view of this dashboard is open below.');
    expect(markup).toContain('class="mcp-view-superseded" inert=""');
  });

  it("replaces charts with a placeholder when Workers cannot run here", () => {
    const markup = render({}, { charts: "placeholder" });
    expect(markup).toContain("This chart can&#x27;t run in this chat.");
    expect(markup).not.toContain('data-testid="dashboard-chart-widget"');
  });

  it("renders a pivot TABLE once its totals result is loaded too", () => {
    const body = structuredClone((draftRead({ revision: "3", notes: "Draft notes.", formattedTable: false, ordersRun: ORDERS_RUN }).structuredContent as { body: Record<string, unknown> }).body);
    const definition = body.definition as { queries: Record<string, unknown>; widgets: Record<string, unknown> };
    definition.queries.ordersTotal = ordersTotalQuery;
    definition.widgets.orders_table = ordersPivotWidget;
    (body.readyResults as Record<string, { queryRuns: Record<string, string> }>).orders_table.queryRuns.ordersTotal = ORDERS_TOTAL_RUN;
    const pivotDraft = DraftBody.parse(body);
    const pivotBundles = Object.fromEntries(Object.entries(pivotDraft.readyResults).map(([widgetId, ready]) => [widgetId, bundleFromReadyResult(ready)]));
    const contentWith = (loaded: Map<string, ResultEntry>) => draftLayoutContent({ definition: pivotDraft.definition, parameterBindings: {}, variables: {}, revision: 3, readyBundles: pivotBundles, adoptedBundles: pivotBundles, targets: {}, results: loaded });
    expect(render({ content: contentWith(results) })).not.toContain('aria-label="Orders by region"');
    const withTotal = new Map(results);
    withTotal.set(ORDERS_TOTAL_RUN, { kind: "loaded", result: namedResultFromAgent(ORDERS_TOTAL_RUN, bodyOf(ordersTotalResult)) });
    const markup = render({ content: contentWith(withTotal) });
    expect(markup).toContain('role="grid" aria-label="Orders by region"');
    expect(markup).toContain('aria-label="Region: APAC"');
    expect(markup).toMatch(/data-row-id="row:0" data-column-index="0"[^>]*><div[^>]*><span data-value-kind="value">8<\/span>/);
    expect(markup).toMatch(/data-row-id="row:0" data-column-index="2"[^>]*><div[^>]*><span data-value-kind="value">17<\/span>/);
  });

  it("closes as out of date or failed without the layout", () => {
    const outOfDate = render({ stage: "out-of-date", show: null, content: null, live: "stopped" });
    expect(outOfDate).toContain("This view is out of date.");
    expect(outOfDate).toContain("Reconnect the Monomer connector, or open the dashboard in Monomer.");
    expect(outOfDate).toContain(">Open in Monomer</button>");
    const failed = render({ stage: "error", message: "You don't have access to this dashboard.", content: null, live: "stopped" });
    expect(failed).toContain(">Retry</button>");
    expect(failed).not.toContain("Draft notes.");
  });
});
