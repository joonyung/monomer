import { canonicalRenderOption, canonicalFrame, validateChartFrame, CHART_FRAME_NODE_LIMIT, CHART_OPTION_NODE_LIMIT, CHART_STRING_BYTE_LIMIT, INLINE_RASTER_BYTE_LIMIT } from "./canonical-boundary";
import { describe, expect, it } from "vitest";
import { canonicalOption, canonicalClone, createChartValueBudget } from "./canonical-boundary";

describe("ChartWidget canonical boundary", () => {
  it("permits bounded inline raster data and rejects external resources", () => {
    expect(canonicalOption({
      series: [{
        type: "bar",
        data: [{ value: 1, itemStyle: { color: "#2466c3" } }],
      }],
      graphic: [{
        type: "image",
        style: { image: "image://data:image/png;base64,iVBORw0KGgo=" },
      }],
    })).toBeTruthy();
    expect(() => canonicalOption({ graphic: [{ type: "image", style: { image: "https://example.test/logo.png" } }] }))
      .toThrow(/Only inline/);
    expect(() => canonicalOption({ graphic: [{ type: "image", style: { image: "data:image/svg+xml;base64,PHN2Zz4=" } }] }))
      .toThrow(/Only inline/);
  });

  it("keeps URL and resource-looking strings as data and text", () => {
    const url = "https://example.test/pricing";
    expect(canonicalClone({ query: url, parameter: url, variable: url }, "frame").query).toBe(url);
    expect(canonicalOption({
      title: { text: url }, xAxis: { data: [url], axisLabel: { formatter: url } },
      tooltip: { formatter: url },
      dataset: { source: [{ image: url, link: url }] },
      series: [{ data: [{ value: url }] }],
    })).toBeTruthy();
  });

  it.each([
    [{ series: [{ symbol: "image://https://example.test/a.png" }] }, "option.series.0.symbol"],
    [{ series: [{ symbol: ["circle", "image://relative.png"] }] }, "option.series.0.symbol.1"],
    [{ legend: { data: [{ icon: "image://https://example.test/a.png" }] } }, "option.legend.data.0.icon"],
    [{ series: [{ label: { rich: { logo: { backgroundColor: { image: "https://example.test/a.png" } } } } }] }, "option.series.0.label.rich.logo.backgroundColor.image"],
    [{ series: [{ label: { rich: { value: { backgroundColor: { image: "https://example.test/a.png" } } } } }] }, "option.series.0.label.rich.value.backgroundColor.image"],
    [{ series: [{ data: [{ label: { rich: { value: { backgroundColor: { image: "https://example.test/a.png" } } } } }] }] }, "option.series.0.data.0.label.rich.value.backgroundColor.image"],
    [{ graphic: [{ style: { fill: { image: "blob:untrusted" } } }] }, "option.graphic.0.style.fill.image"],
    [{ tooltip: { renderMode: "html" } }, "option.tooltip.renderMode"],
    [{ tooltip: { renderMode: null } }, "option.tooltip.renderMode"],
    [{ tooltip: { formatter: { tagName: "DIV" } } }, "option.tooltip.formatter"],
    [{ tooltip: { appendTo: "body" } }, "option.tooltip.appendTo"],
    [{ title: { link: "https://example.test" } }, "option.title.link"],
    [{ title: [{ sublink: "/relative" }] }, "option.title.0.sublink"],
    [{ series: [{ type: "sunburst", nodeClick: "link" }] }, "option.series.0.nodeClick"],
    [{ series: [{ type: "treemap", data: [{ link: "/relative" }] }] }, "option.series.0.data.0.link"],
    [{ toolbox: { feature: { dataView: {} } } }, "option.toolbox.feature.dataView"],
    [{ toolbox: { feature: { saveAsImage: {} } } }, "option.toolbox.feature.saveAsImage"],
  ])("rejects resource and DOM effects with their option path", (option, path) => {
    expect(() => canonicalOption(option)).toThrow(path);
  });

  it("rejects accessors, prototypes, cycles and non-finite values", () => {
    const value: Record<string, unknown> = {};
    Object.defineProperty(value, "secret", { enumerable: true, get: () => "ambient" });
    expect(() => canonicalClone(value, "frame")).toThrow(/accessors/);
    expect(() => canonicalClone({ constructor: "blocked" }, "frame")).toThrow(/Reserved/);
    const cycle: Record<string, unknown> = {};
    cycle.self = cycle;
    expect(() => canonicalClone(cycle, "frame")).toThrow(/Cyclic/);
    expect(() => canonicalClone({ value: Number.NaN }, "frame")).toThrow(/finite/);
  });

  it("detaches mutable ECharts output from source-owned records", () => {
    const source = { type: "rect", style: { fill: "blue" } };
    const output = canonicalOption(source);
    output.style.fill = "red";
    expect(source.style.fill).toBe("blue");
    expect(Object.isFrozen(output)).toBe(false);
    expect(Object.isFrozen(output.style)).toBe(false);
  });

  it("hands ECharts ordinary records after rejecting reserved keys", () => {
    const option = canonicalOption({ series: [{ type: "bar", data: [1] }] });
    expect(Object.getPrototypeOf(option)).toBe(Object.prototype);
    expect(Object.getPrototypeOf(option.series[0])).toBe(Object.prototype);
    expect(Object.hasOwn(option, "series")).toBe(true);
  });
});


describe("render option root", () => {
  it.each([undefined, null, [], "text", 1, false, Promise.resolve({}), new Date(0), new Map(), { then() {} }])("rejects a non-option root", (value) => {
    expect(() => canonicalRenderOption(value)).toThrow("render.option");
  });
  it("accepts empty plain options without imposing the rule on callback values", () => {
    expect(canonicalRenderOption({})).toEqual({});
    expect(canonicalRenderOption(Object.create(null))).toEqual({});
    expect(canonicalOption("formatter text")).toBe("formatter text");
  });
});

describe("aggregate chart capacity", () => {
  it("admits depth 32 and rejects depth 33 for frame and option", () => {
    const nested = (levels: number): unknown => Array.from({ length: levels }).reduce<unknown>(value => ({ child: value }), 0);
    for (const accept of [validateChartFrame, canonicalOption]) {
      expect(() => accept(nested(31))).not.toThrow();
      expect(() => accept(nested(32))).not.toThrow();
      expect(() => accept(nested(33))).toThrow(/depth budget/);
    }
  });

  it("validates without freezing or copying, and defensively clones a complete frame", () => {
    const frame = { queries: { a: { rows: Array.from({ length: 5000 }, () => Array(10).fill(123)) } }, parameters: {}, variables: {} };
    expect(validateChartFrame(frame)).toBeUndefined();
    expect(Object.isFrozen(frame.queries.a.rows)).toBe(false);
    const copy = canonicalFrame(frame);
    expect(copy).toEqual(frame);
    expect(copy.queries.a.rows).not.toBe(frame.queries.a.rows);
    expect(Object.isFrozen(copy.queries.a.rows)).toBe(true);
  });

  it("shares bytes across queries, parameters and variables", () => {
    const cells = Array(32).fill("x".repeat(196608));
    expect(() => canonicalFrame({ queries: cells, parameters: cells, variables: {} })).not.toThrow();
    const frame = { queries: cells, parameters: cells, variables: cells };
    expect(() => validateChartFrame(frame)).toThrow(/Chart frame exceeds the byte budget/);
    expect(() => canonicalFrame(frame)).toThrow(/Chart frame exceeds the byte budget/);
    expect(() => validateChartFrame({ variables: "x".repeat(CHART_STRING_BYTE_LIMIT + 1) })).toThrow(/string exceeds/);
  });

  it("admits exactly the conservative frame and option byte budgets", () => {
    const frame = Array(63).fill("x".repeat(CHART_STRING_BYTE_LIMIT));
    frame.push("x".repeat(CHART_STRING_BYTE_LIMIT - 195));
    expect(() => validateChartFrame(frame)).not.toThrow();
    frame[63] += "x";
    expect(() => validateChartFrame(frame)).toThrow(/byte budget/);
    const option = Array(31).fill("x".repeat(CHART_STRING_BYTE_LIMIT));
    option.push("x".repeat(CHART_STRING_BYTE_LIMIT - 99));
    expect(() => canonicalOption(option)).not.toThrow();
    option[31] += "x";
    expect(() => canonicalOption(option)).toThrow(/byte budget/);
  });

  it("counts nodes as values and containers rather than rows", () => {
    const values = Array(CHART_FRAME_NODE_LIMIT - 1).fill(0);
    expect(() => validateChartFrame(values)).not.toThrow();
    values.push(0);
    expect(() => validateChartFrame(values)).toThrow(/node budget/);
    expect(() => canonicalOption(Array(CHART_OPTION_NODE_LIMIT - 1).fill(0))).not.toThrow();
    expect(() => canonicalOption(Array(CHART_OPTION_NODE_LIMIT).fill(0))).toThrow(/node budget/);
  });

  it("counts keys, numeric values and escaped UTF-8 before building the output", () => {
    const keys = Object.fromEntries(Array.from({ length: 70 }, (_, i) => [`${i}${"k".repeat(250000)}`, 1]));
    expect(() => validateChartFrame(keys)).toThrow(/byte budget/);
    expect(() => canonicalOption(Array(100).fill(Number.MAX_VALUE), undefined, ["option"], createChartValueBudget("Chart option", 1024, 1000))).toThrow(/byte budget/);
    expect(() => canonicalOption(Array(33).fill("x".repeat(CHART_STRING_BYTE_LIMIT)))).toThrow(/byte budget/);
    expect(() => canonicalOption(Array(6).fill("\u0000".repeat(CHART_STRING_BYTE_LIMIT)))).toThrow(/byte budget/);
    expect(() => validateChartFrame("가".repeat(Math.floor(CHART_STRING_BYTE_LIMIT / 3)))).not.toThrow();
    expect(() => validateChartFrame("가".repeat(Math.floor(CHART_STRING_BYTE_LIMIT / 3) + 1))).toThrow(/string exceeds/);
  });

  it("accepts a complete 2 MiB inline file while retaining aggregate option limits", () => {
    const image = `image://data:image/png;base64,${Buffer.alloc(INLINE_RASTER_BYTE_LIMIT).toString("base64")}`;
    expect(() => canonicalOption({ graphic: { style: { image } } })).not.toThrow();
    expect(() => canonicalOption({ images: [image, image, image] })).toThrow(/byte budget/);
    const oversized = `data:image/png;base64,${Buffer.alloc(INLINE_RASTER_BYTE_LIMIT + 1).toString("base64")}`;
    expect(() => canonicalOption({ graphic: { style: { image: oversized } } })).toThrow(/byte budget/);
  });
});
