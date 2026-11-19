import type { ChartWidgetWorkerFactory } from "@/features/chart-widget";
import type { TableWorkerFactory } from "@/features/table-widget/runtime/table-widget-runtime";
import ChartWidgetWorker from "@/features/chart-widget/runtime/worker/entry.ts?worker&inline";
import TableWidgetWorker from "@/features/table-widget/runtime/worker/entry.ts?worker&inline";

// The host serves this view from its own sandbox origin, so the app's same-origin Worker URLs do
// not exist here. The same Worker code is bundled into the page and started from blob URLs.
export const createInlineChartWidgetWorker: ChartWidgetWorkerFactory = (generation) =>
  new ChartWidgetWorker({ name: `monomer-chart-widget-${generation}` });

export const createInlineTableWidgetWorker: TableWorkerFactory = (generation) =>
  new TableWidgetWorker({ name: `monomer-table-widget-${generation}` });
