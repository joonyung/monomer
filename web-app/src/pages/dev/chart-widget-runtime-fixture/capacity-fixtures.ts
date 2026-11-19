import type { ChartQueryResult, ChartRenderFrame } from "@/features/chart-widget/contract/abi";

export function capacityResult(rowCount: number, columnCount: number, cell = "12345"): ChartQueryResult {
  const columns = Array.from({ length: columnCount }, (_, index) => `metric.measure_${index}`);
  return {
    columns: columns.map(name => ({ name, logicalType: "DECIMAL", nullable: false, numberFormat: null })),
    rows: Array.from({ length: rowCount }, () => Object.fromEntries(columns.map(column => [column, cell]))),
  };
}

export function capacityFrames(): Record<string, ChartRenderFrame> {
  const base = { parameters: {}, variables: {}, locale: "en-US", viewport: { width: 720, height: 360 } };
  return {
    "5000x10": { ...base, queries: { sales: capacityResult(5000, 10) } },
    "wide-short": { ...base, queries: { sales: capacityResult(20, 1000) } },
    "multiple-queries": { ...base, queries: { sales: capacityResult(5000, 10), costs: capacityResult(5000, 10) } },
    "large-strings": { ...base, queries: { sales: capacityResult(96, 1, "가".repeat(21845)) } },
    "narrow-100000": { ...base, queries: { sales: capacityResult(100000, 1) } },
    "near-byte-limit": { ...base, queries: { sales: capacityResult(240, 1, "가".repeat(21845)) } },
    "empty": { ...base, queries: { sales: capacityResult(0, 10) } },
  };
}

export const capacitySource = `export default defineChart({
  render({ queries }) {
    const result = queries.sales;
    return {
      animation: false,
      xAxis: { type: "category" }, yAxis: { type: "value" },
      series: [{ type: "bar", data: result.rows.map(row => Number(row[result.columns[0].name]) || 0) }],
    };
  },
});`;

export function rasterCapacitySource(bytes: number): string {
  const remaining = bytes - 72;
  return `export default defineChart({ render() {
    const prefix = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJgggAA";
    const image = "data:image/png;base64," + prefix + "A".repeat(${Math.floor(remaining / 3) * 4}) + ${JSON.stringify(remaining % 3 === 1 ? "AA==" : remaining % 3 === 2 ? "AAA=" : "")};
    return { animation: false, graphic: [{ type: "image", left: 10, top: 10, style: { image, width: 80, height: 80 } }] };
  } });`;
}
