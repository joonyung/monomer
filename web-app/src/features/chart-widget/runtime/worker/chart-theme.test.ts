import fs from "node:fs";
import path from "node:path";
import * as echarts from "echarts";
import { parse as parseZrenderColor } from "zrender/lib/tool/color.js";
import { describe, expect, it } from "vitest";
import { CATEGORICAL_CHART_COLORS } from "@/lib/chart-color-scale";
import { applyHostPresentationPolicy, MONOMER_CHART_THEME as THEME, MONOMER_SEQUENTIAL_RAMP } from "./chart-theme";

const MONOMER_CHART_THEME = THEME as Record<string, Record<string, unknown>>;

const ECHARTS_VENDOR_PALETTE = ["#5070dd", "#b6d634", "#505372", "#ff994d", "#0ca8df", "#ffd10a", "#fb628b", "#785db0", "#3fbe95"];

function colorValues(value: unknown, key = ""): string[] {
  if (typeof value === "string") return /color$/i.test(key) ? [value] : [];
  if (Array.isArray(value)) return value.flatMap((item) => colorValues(item, key));
  if (value !== null && typeof value === "object") {
    return Object.entries(value).flatMap(([childKey, child]) => colorValues(child, childKey));
  }
  return [];
}

function renderSvg(option: echarts.EChartsCoreOption, width = 480): string {
  const chart = echarts.init(null, MONOMER_CHART_THEME, { renderer: "svg", ssr: true, width, height: 240 });
  try {
    chart.setOption({ animation: false, ...option });
    return chart.renderToSVGString().toLowerCase();
  } finally {
    chart.dispose();
  }
}

const trend = {
  xAxis: { type: "category", data: ["Jan", "Feb", "Mar", "Apr"] },
  yAxis: { type: "value" },
};

describe("Monomer chart theme", () => {
  it("uses the DESIGN.md categorical hex projection in order", () => {
    const design = fs.readFileSync(path.resolve(import.meta.dirname, "../../../../../DESIGN.md"), "utf8");
    const designHex = [...design.matchAll(/\| `--chart-(\d)` \| [^|]+ \| `[^`]+` \| `(#[0-9a-f]{6})` \|/g)]
      .sort((left, right) => Number(left[1]) - Number(right[1]))
      .map((match) => match[2]);
    expect(designHex).toHaveLength(8);
    expect(MONOMER_CHART_THEME.color).toEqual(designHex);
    expect(MONOMER_CHART_THEME.color).toEqual([...CATEGORICAL_CHART_COLORS]);
  });

  it("contains only colors that zrender parses", () => {
    const colors = colorValues(MONOMER_CHART_THEME);
    expect(colors.length).toBeGreaterThan(20);
    for (const color of colors) {
      if (color === "transparent") continue;
      expect(parseZrenderColor(color), color).toBeDefined();
    }
  });

  it("is deeply immutable and never enables area fill for every line", () => {
    expect(Object.isFrozen(MONOMER_CHART_THEME)).toBe(true);
    expect(Object.isFrozen(MONOMER_CHART_THEME.tooltip)).toBe(true);
    expect(MONOMER_CHART_THEME.line).not.toHaveProperty("areaStyle");
  });

  it("keeps line symbols as the hover target for item-trigger tooltips", () => {
    expect(MONOMER_CHART_THEME.line).not.toHaveProperty("showSymbol");
  });

  it("does not blur other series on hover", () => {
    for (const series of ["line", "bar"]) {
      expect(MONOMER_CHART_THEME[series]).not.toHaveProperty("emphasis");
      expect(MONOMER_CHART_THEME[series]).not.toHaveProperty("blur");
    }
  });

  it("gives a source without colors the first chart token and no vendor defaults", () => {
    const svg = renderSvg({ ...trend, series: [{ id: "revenue", type: "line", data: [3, 5, 4, 6] }] });
    expect(svg).toContain(CATEGORICAL_CHART_COLORS[0]);
    for (const vendorColor of ECHARTS_VENDOR_PALETTE) expect(svg).not.toContain(vendorColor);
  });

  it("assigns categorical series in palette order", () => {
    const svg = renderSvg({
      ...trend,
      series: ["direct", "partner", "marketplace"].map((id) => ({ id, type: "bar", stack: "total", data: [1, 2, 3, 4] })),
    });
    for (const color of CATEGORICAL_CHART_COLORS.slice(0, 3)) expect(svg).toContain(color);
    for (const vendorColor of ECHARTS_VENDOR_PALETTE) expect(svg).not.toContain(vendorColor);
  });

  it("lets an explicit source option override the baseline", () => {
    const svg = renderSvg({
      ...trend,
      series: [
        { id: "current", type: "line", data: [3, 5, 4, 6], lineStyle: { color: "#a2274c" }, itemStyle: { color: "#a2274c" } },
        { id: "prior", type: "line", data: [2, 4, 4, 5], lineStyle: { color: "#6c7074", type: "dashed" }, itemStyle: { color: "#6c7074" } },
      ],
    });
    expect(svg).toContain("#a2274c");
    expect(svg).toContain("#6c7074");
    expect(svg).not.toContain(CATEGORICAL_CHART_COLORS[0]);
  });

  it("draws neutral axes and split lines", () => {
    const svg = renderSvg({ ...trend, series: [{ type: "bar", data: [3, 5, 4, 6] }] });
    expect(svg).toContain("#dfe3e7");
    expect(svg).toContain("#f0f2f4");
  });
});

describe("Host presentation policy", () => {
  it("returns an equivalent option when motion is allowed and no default applies", () => {
    const option = { animation: true, series: [{ type: "line", data: [1] }] };
    expect(applyHostPresentationPolicy(option, false)).toEqual(option);
  });

  it("rounds only the data end of an unstacked bar and keeps stacked segments square", () => {
    const presented = applyHostPresentationPolicy({
      xAxis: { type: "category", data: ["a", "b", "c"] },
      yAxis: { type: "value" },
      series: [
        { id: "single", type: "bar", data: [3, -2, { value: -1, itemStyle: { color: "#e06d4f" } }] },
        { id: "web", type: "bar", stack: "total", data: [1, 2, 3] },
        { id: "app", type: "bar", stack: "total", data: [1, 2, 3] },
      ],
    }, false) as Record<string, any>;
    expect(presented.series[0].itemStyle).toEqual({ borderRadius: [3, 3, 0, 0] });
    expect(presented.series[0].data).toEqual([
      3,
      { value: -2, itemStyle: { borderRadius: [0, 0, 3, 3] } },
      { value: -1, itemStyle: { color: "#e06d4f", borderRadius: [0, 0, 3, 3] } },
    ]);
    for (const stacked of presented.series.slice(1)) expect(stacked).not.toHaveProperty("itemStyle");
  });

  it("rounds the right end of a horizontal bar and respects a source radius", () => {
    const presented = applyHostPresentationPolicy({
      xAxis: { type: "value" },
      yAxis: [{ type: "category", data: ["a", "b"] }],
      series: [
        { type: "bar", data: [4, -1] },
        { type: "bar", data: [2, 3], itemStyle: { borderRadius: 8 } },
        { type: "bar", datasetIndex: 0 },
      ],
    }, false) as Record<string, any>;
    expect(presented.series[0].itemStyle).toEqual({ borderRadius: [0, 3, 3, 0] });
    expect(presented.series[0].data[1]).toEqual({ value: -1, itemStyle: { borderRadius: [3, 0, 0, 3] } });
    expect(presented.series[1].itemStyle).toEqual({ borderRadius: 8 });
    expect(presented.series[2]).not.toHaveProperty("itemStyle");
  });

  it("disables animation on a plain option", () => {
    expect(applyHostPresentationPolicy({ animation: true, series: [] }, true)).toMatchObject({ animation: false, series: [] });
  });

  it("softens an enabled area without source color or opacity", () => {
    const option = { series: [{ type: "line", areaStyle: {} }, { type: "line", areaStyle: { opacity: 0.5 } }, { type: "line" }, { type: "radar", areaStyle: {} }] };
    expect(applyHostPresentationPolicy(option, false).series).toEqual([
      { type: "line", areaStyle: { opacity: 0.14 } },
      { type: "line", areaStyle: { opacity: 0.5 } },
      { type: "line" },
      { type: "radar", areaStyle: { opacity: 0.14 } },
    ]);
    const timeline = applyHostPresentationPolicy({ baseOption: { series: [{ type: "line", areaStyle: {} }] } }, false) as Record<string, any>;
    expect(timeline.baseOption.series).toEqual([{ type: "line", areaStyle: { opacity: 0.14 } }]);
  });
});

describe("Theme defaults preserve source meaning", () => {
  function tickLabels(svg: string): string[] {
    return [...svg.matchAll(/<text[^>]*>([^<]*)<\/text>/g)].map((match) => match[1]);
  }

  it("keeps exact value-axis tick labels for fine and small ranges", () => {
    for (const [data, expected] of [
      [[1_203_000, 1_248_000], "1,210,000"],
      [[0.021, 0.039], "0.024"],
      [[0.0011, 0.0031], "0.0015"],
    ] as const) {
      const labels = tickLabels(renderSvg({ xAxis: { type: "category", data: ["a", "b"] }, yAxis: { type: "value", scale: true }, series: [{ type: "line", data: [...data] }] }));
      expect(labels).toContain(expected);
      const numeric = labels.filter((label) => /\d/.test(label));
      expect(new Set(numeric).size).toBe(numeric.length);
    }
  });

  it("uses the sequential ramp only when a visualMap declares no colors", () => {
    const scatter = { xAxis: {}, yAxis: {}, series: [{ type: "scatter", data: [[1, 2, 0], [2, 3, 10]] }] };
    const themed = renderSvg({ ...scatter, visualMap: { show: false, dimension: 2, min: 0, max: 10 } });
    expect(themed).toContain("rgb(48,85,182)");
    const explicit = renderSvg({ ...scatter, visualMap: { show: false, dimension: 2, min: 0, max: 10, color: ["#ff0000", "#00ff00"] } });
    expect(explicit).toContain("rgb(255,0,0)");
    expect(explicit).not.toContain("rgb(48,85,182)");
    const sizeOnly = renderSvg({ ...scatter, visualMap: { show: false, dimension: 2, min: 0, max: 10, inRange: { symbolSize: [5, 30] } } });
    expect(sizeOnly).toContain(CATEGORICAL_CHART_COLORS[0]);
    expect(MONOMER_CHART_THEME.gradientColor).toEqual([...MONOMER_SEQUENTIAL_RAMP]);
  });

  it("keeps outer labels dark and inside labels light", () => {
    const data = [{ name: "Visited", value: 10 }, { name: "Paid", value: 4 }];
    const outer = renderSvg({ series: [{ type: "funnel", data }] });
    expect(outer).not.toMatch(/<text[^>]*fill="#fdfdfe"[^>]*>visited<\/text>/);
    expect(outer).toMatch(/<text[^>]*>visited<\/text>/);
    const inside = renderSvg({ series: [{ type: "funnel", label: { position: "inside" }, data }] });
    expect(inside).toMatch(/<text[^>]*fill="#eee"[^>]*>visited<\/text>/);
    expect(MONOMER_CHART_THEME.textStyle).not.toHaveProperty("color");
  });

  it("does not stroke bars or heatmap cells with the canvas color", () => {
    expect(MONOMER_CHART_THEME.bar).not.toHaveProperty("itemStyle");
    expect(MONOMER_CHART_THEME.heatmap.itemStyle).not.toHaveProperty("borderWidth");
    const days = Array.from({ length: 365 }, (_, index) => String(index));
    const svg = renderSvg({ xAxis: { type: "category", data: days }, yAxis: { type: "value" }, series: [{ type: "bar", data: days.map(() => 5) }] }, 600);
    expect(svg).not.toContain('stroke="#fdfdfe"');
  });

  it("keeps the treemap breadcrumb so a zoomed node can return to the root", () => {
    expect(MONOMER_CHART_THEME.treemap.breadcrumb).not.toHaveProperty("show");
  });
});

describe("Reduced motion reaches every animation owner", () => {
  function animationEnabled(option: Readonly<Record<string, unknown>>): boolean[] {
    const chart = echarts.init(null, MONOMER_CHART_THEME, { renderer: "svg", ssr: true, width: 320, height: 200 });
    try {
      chart.setOption(option);
      const model = (chart as unknown as { getModel(): { eachSeries(callback: (series: { isAnimationEnabled(): boolean }) => void): void } }).getModel();
      const flags: boolean[] = [];
      model.eachSeries((series) => flags.push(series.isAnimationEnabled()));
      return flags;
    } finally {
      chart.dispose();
    }
  }

  it("turns off series that opt into animation", () => {
    const option = { xAxis: { type: "category", data: ["a"] }, yAxis: {}, series: [{ type: "bar", animation: true, data: [1] }] };
    expect(animationEnabled(applyHostPresentationPolicy(option, false))).toEqual([true]);
    expect(animationEnabled(applyHostPresentationPolicy(option, true))).toEqual([false]);
  });

  it("turns off timeline, media, tooltip, and axis pointer animation", () => {
    const presented = applyHostPresentationPolicy({
      baseOption: { series: [{ type: "line", animation: true }] },
      options: [{ series: [{ type: "line", animation: true }] }],
      media: [{ query: { maxWidth: 500 }, option: { series: [{ type: "line", animation: true }] } }],
    }, true) as Record<string, any>;
    expect(presented.baseOption).toMatchObject({ animation: false, series: [{ animation: false }] });
    expect(presented.options[0]).toMatchObject({ animation: false, series: [{ animation: false }] });
    expect(presented.media[0].option).toMatchObject({ animation: false, series: [{ animation: false }] });
    const pointers = applyHostPresentationPolicy({
      tooltip: { trigger: "axis" },
      xAxis: [{ type: "category", axisPointer: { type: "shadow" } }, { type: "value" }],
    }, true) as Record<string, any>;
    expect(pointers.axisPointer).toEqual({ animation: false });
    expect(pointers.tooltip).toEqual({ trigger: "axis", axisPointer: { animation: false } });
    expect(pointers.xAxis).toEqual([{ type: "category", axisPointer: { type: "shadow", animation: false } }, { type: "value" }]);
  });
});
