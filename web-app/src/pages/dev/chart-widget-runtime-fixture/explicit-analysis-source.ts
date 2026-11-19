import type { ChartWidgetInputs } from "@/features/chart-widget/runtime/worker-protocol";

export const explicitAnalysisSource = `
export default defineChart({
  setup({ chart }) {
    chart.on("datazoom", (event, frame) => {
      const zoom = Array.isArray(event.batch) ? event.batch[0] : event;
      if (!zoom || typeof zoom !== "object" || Array.isArray(zoom)) return;
      if (typeof zoom.start !== "number" || typeof zoom.end !== "number") return;
      frame.setVariables({
        zoomStart: String(Math.round(zoom.start * 10000) / 10000),
        zoomEnd: String(Math.round(zoom.end * 10000) / 10000),
      });
    });
    chart.on("legendselectchanged", (event, frame) => {
      const selected = event.selected;
      if (!selected || typeof selected !== "object" || !("Sales" in selected) || !("Cost" in selected)) return;
      frame.setVariables({
        salesVisible: selected.Sales === true,
        costVisible: selected.Cost === true,
      });
    });
    chart.on("click", { seriesId: "sales" }, (event, frame) => {
      if (typeof event.dataIndex !== "number") return;
      const row = frame.queries.sales.rows[event.dataIndex];
      if (!row) return;
      frame.setVariables({
        country: row["dimension.country"],
        city: row["dimension.city"],
        zoomStart: "0",
        zoomEnd: "100",
      });
    });
  },
  render({ queries, variables }) {
    const rows = queries.sales.rows;
    return {
      animation: false,
      legend: {
        top: 8,
        selected: { Sales: variables.salesVisible, Cost: variables.costVisible },
      },
      grid: { left: 48, right: 24, top: 48, bottom: 88 },
      xAxis: { type: "category", data: rows.map(row => row["dimension.city"]) },
      yAxis: { type: "value" },
      dataZoom: [{
        type: "slider", bottom: 20, height: 28,
        start: Number(variables.zoomStart), end: Number(variables.zoomEnd),
        realtime: false, throttle: 100,
      }],
      series: [
        { id: "sales", name: "Sales", type: "bar", data: rows.map(row => Number(row["metric.sales"])) },
        { id: "cost", name: "Cost", type: "line", data: rows.map(row => Number(row["metric.cost"])) },
      ],
    };
  },
});
`;

export function explicitAnalysisInputs(): ChartWidgetInputs {
  return {
    moduleSource: explicitAnalysisSource,
    queries: {
      sales: {
        columns: ["dimension.country", "dimension.city", "metric.sales", "metric.cost"].map(name => ({
          name, logicalType: name.startsWith("metric.") ? "DECIMAL" : "TEXT", nullable: false, numberFormat: null,
        })),
        rows: Array.from({ length: 24 }, (_, index) => ({
          "dimension.country": index < 12 ? "KR" : "US",
          "dimension.city": `City ${index + 1}`,
          "metric.sales": String(60 + index * 2),
          "metric.cost": String(25 + index),
        })),
      },
    },
    parameters: {},
    variables: {
      country: { value: null, type: "TEXT", nullable: true },
      city: { value: null, type: "TEXT", nullable: true },
      zoomStart: { value: "0", type: "NUMBER" },
      zoomEnd: { value: "100", type: "NUMBER" },
      salesVisible: { value: true, type: "BOOLEAN" },
      costVisible: { value: true, type: "BOOLEAN" },
    },
    locale: "en-US",
    viewport: { width: 720, height: 360 },
  };
}
