export function createChartWidgetWorker(generation: number): Worker {
  return new Worker(
    new URL("./worker/entry.ts", import.meta.url),
    { type: "module", name: `monomer-chart-widget-${generation}` },
  );
}
