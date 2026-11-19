import { checkChartSource } from "../../../../scripts/check-chart-source.mjs";
import type { DashboardDefinition } from "@/api/dashboard";
import "ses";
import { beforeAll, expect, it, vi } from "vitest";
import { evaluateChartSource } from "@/features/chart-widget/runtime/worker/module-source";
import type { ChartEventFrame, ChartHandle, ChartRenderFrame, SafeChartEventParams } from "@/features/chart-widget/contract/abi";
import { explicitAnalysisInputs, explicitAnalysisSource } from "./explicit-analysis-source";

beforeAll(() => lockdown({ errorTaming: "unsafe-debug" }));

function example() {
  const inputs = explicitAnalysisInputs();
  const frame: ChartRenderFrame = {
    queries: inputs.queries, parameters: inputs.parameters,
    variables: Object.fromEntries(Object.entries(inputs.variables).map(([name, input]) => [name, input.value])) as ChartRenderFrame["variables"],
    locale: inputs.locale, viewport: inputs.viewport,
  };
  const module = evaluateChartSource(explicitAnalysisSource, "explicit-analysis.ts", new WeakSet());
  const handlers = new Map<string, (event: SafeChartEventParams, frame: ChartEventFrame) => void>();
  module.setup!({ chart: { on: (name: string, selector: unknown, handler: unknown) => handlers.set(name, (handler ?? selector) as never) } as unknown as ChartHandle });
  return { frame, module, handlers };
}

it("projects explicit accepted values across viewport and result changes", () => {
  const { frame, module } = example();
  const variables = { ...frame.variables, zoomStart: "25", zoomEnd: "75", salesVisible: false };
  for (const viewport of [frame.viewport, { width: 680, height: 360 }]) {
    const option = module.render({ ...frame, variables, viewport, queries: explicitAnalysisInputs().queries });
    expect(option.dataZoom).toEqual([expect.objectContaining({ start: 25, end: 75, realtime: false, throttle: 100 })]);
    expect(option.legend).toMatchObject({ selected: { Sales: false, Cost: true } });
  }
  expect(module.render(frame).dataZoom).toEqual([expect.objectContaining({ start: 0, end: 100 })]);
});

it("submits decimal zoom pairs and all legend booleans in one map, including no selected series", () => {
  const { frame, handlers } = example();
  const setVariables = vi.fn();
  handlers.get("datazoom")!({ batch: [{ start: 25.123456, end: 75 }] }, { ...frame, setVariables });
  expect(setVariables).toHaveBeenLastCalledWith({ zoomStart: "25.1235", zoomEnd: "75" });
  handlers.get("legendselectchanged")!({ selected: { Sales: false, Cost: false } }, { ...frame, setVariables });
  expect(setVariables).toHaveBeenLastCalledWith({ salesVisible: false, costVisible: false });
  expect(setVariables).toHaveBeenCalledTimes(2);
});

it("uses displayed row identity and resets zoom in the same country/city map", () => {
  const { frame, handlers } = example();
  const setVariables = vi.fn();
  handlers.get("click")!({ dataIndex: 13 }, { ...frame, setVariables });
  expect(setVariables).toHaveBeenCalledExactlyOnceWith({ country: "US", city: "City 14", zoomStart: "0", zoomEnd: "100" });
  expect(frame.variables.country).toBeNull();
});


it("passes the installed ABI and ECharts authoring checker with declared input types", () => {
  const inputs = explicitAnalysisInputs();
  expect(checkChartSource({
    definition: {
      parameters: {}, variables: Object.fromEntries(Object.entries(inputs.variables).map(([name, input]) => [name, { type: input.type, nullable: input.nullable ?? false, initial: input.value }])),
      queries: { sales: {} }, widgets: { chart: { type: "CHART", queryNames: ["sales"], module: { inputs: { parameters: [], variables: Object.keys(inputs.variables) }, source: explicitAnalysisSource } } },
    } as unknown as DashboardDefinition,
    widgetId: "chart", queryColumns: { sales: inputs.queries.sales.columns.map(({ name, logicalType, nullable }) => ({ name, logicalType, nullable, display: null })) },
  })).toEqual([]);
});
