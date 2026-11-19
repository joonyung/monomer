import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { DashboardWidget } from "@/api/dashboard";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { DashboardWidgetRenderer } from "./DashboardWidgetRenderer";
import { DashboardWidgetEnvironmentContext, type DashboardWidgetEnvironment } from "./DashboardWidgetEnvironment";

const chart: Extract<DashboardWidget, { type: "CHART" }> = { type: "CHART", title: "Revenue", queryNames: ["revenue"], module: { inputs: { parameters: [], variables: [] }, source: "export default defineChart({ render() { return {}; } });" } };
const revenue: NamedDashboardResult = { queryRunId: "run", queryType: "SEMANTIC", reportingTimeZone: null, columns: [], rows: [] };

function render(widget: DashboardWidget, environment: DashboardWidgetEnvironment | null, overrides: Partial<Parameters<typeof DashboardWidgetRenderer>[0]> = {}) {
  const element = <DashboardWidgetRenderer widget={widget} parameters={{}} variables={{}} variableDefinitions={{}} results={{ revenue }} ready
    queryTypes={{ revenue: "SEMANTIC" }} onVariablesSet={() => undefined} variableRevision={0} readOnly {...overrides} />;
  return renderToStaticMarkup(environment ? <DashboardWidgetEnvironmentContext.Provider value={environment}>{element}</DashboardWidgetEnvironmentContext.Provider> : element);
}

describe("dashboard widget environment", () => {
  it("renders the app's own chart when no environment is provided", () => {
    const markup = render(chart, null);
    expect(markup).toContain('data-testid="dashboard-chart-widget"');
    expect(render(chart, {})).toBe(markup);
  });

  it("replaces the chart runtime with the surface's placeholder", () => {
    const markup = render(chart, { chartPlaceholder: <p>Charts cannot run here.</p> });
    expect(markup).toContain("Charts cannot run here.");
    expect(markup).not.toContain('data-testid="dashboard-chart-widget"');
    expect(markup).toContain("Revenue");
  });

  it("adds the surface's action to failed widgets only", () => {
    const action = <button type="button">Open elsewhere</button>;
    expect(render(chart, { failureAction: action }, { ready: false, errorMessage: "The query failed." })).toContain("Open elsewhere");
    expect(render(chart, { failureAction: action }, { ready: false })).not.toContain("Open elsewhere");
    expect(render(chart, null, { ready: false, errorMessage: "The query failed." })).not.toContain("Open elsewhere");
  });
});
