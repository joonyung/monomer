import "ses";
import { beforeAll, describe, expect, it, vi } from "vitest";
import type { EChartsOption } from "echarts";
import { canonicalOption } from "./canonical-boundary";
import type { Callable } from "./canonical-boundary";
import { createOptionFunctionHandler } from "./safe-callback-boundary";
import { evaluateChartSource } from "./module-source";

beforeAll(() => lockdown({ errorTaming: "unsafe-debug" }));

describe("ChartWidget option callback boundary", () => {
  it("keeps tooltip/label formatters and custom renderItem inside the SES callback port", () => {
    const foreignFunctions = new WeakSet<Callable>();
    const module = evaluateChartSource(`
      export default defineChart({
        render() {
          return {
            tooltip: {
              formatter(params) { return "tooltip:" + params.data.label; },
            },
            series: [{
              type: "custom",
              label: { formatter(params) { return "label:" + params.name; } },
              renderItem(params, api) {
                return {
                  type: "rect",
                  shape: { x: api.value(0), y: 2, width: api.getWidth(), height: api.value(1) },
                  style: { fill: api.visual("color") },
                };
              },
            }],
          };
        },
      });
    `, "callback-test.ts", foreignFunctions);
    const option = canonicalOption(
      module.render({ queries: {}, parameters: {}, variables: {}, locale: "en-US", viewport: { width: 320, height: 200 } }),
      createOptionFunctionHandler(foreignFunctions),
    ) as EChartsOption & {
      tooltip: { formatter: (params: unknown) => unknown };
      series: Array<{
        label: { formatter: (params: unknown) => unknown };
        renderItem: (params: unknown, api: unknown) => unknown;
      }>;
    };
    expect(typeof option.tooltip.formatter).toBe("function");
    expect(typeof option.series[0].label.formatter).toBe("function");
    expect(typeof option.series[0].renderItem).toBe("function");

    const data: Record<string, unknown> = { label: "North" };
    data.live = { method() { throw new Error("must not cross the boundary"); } };
    const tooltip = option.tooltip.formatter({ data, event: { target: { live: true } } });
    const label = option.series[0].label.formatter({ name: "North", target: { live: true } });
    expect(tooltip).toBe("tooltip:North");
    expect(label).toBe("label:North");

    const api = {
      getWidth: vi.fn(() => 300),
      value: vi.fn((dimension: unknown) => dimension === 0 ? 12 : 8),
      visual: vi.fn(() => "#2466c3"),
      getZr: vi.fn(() => ({ live: true })),
    };
    const rendered = option.series[0].renderItem(
      { context: {}, dataIndex: 0, itemPayload: { safe: true } },
      api,
    ) as { type: string; shape: { x: number; width: number; height: number }; style: { fill: string } };
    expect(rendered).toEqual({
      type: "rect",
      shape: { x: 12, y: 2, width: 300, height: 8 },
      style: { fill: "#2466c3" },
    });
    expect(api.getZr).not.toHaveBeenCalled();
  });

  it("rejects foreign functions while preserving rejection for JSON/event boundaries", () => {
    const foreignFunctions = new WeakSet<Callable>();
    const hostCallback = () => "host";
    foreignFunctions.add(hostCallback as Callable);
    expect(() => canonicalOption(
      { tooltip: { formatter: hostCallback } },
      createOptionFunctionHandler(foreignFunctions),
    )).toThrow(/SES-created/);
    expect(() => canonicalOption({ tooltip: { formatter: () => "source" } })).toThrow(/Functions/);
  });
});


describe("callback effect results", () => {
  it("allows URL formatter text but validates a reused callback at each resource path", () => {
    const handler = createOptionFunctionHandler(new WeakSet<Callable>());
    const url = () => "https://example.test/pricing";
    const formatter = handler(url as Callable, ["option", "tooltip", "formatter"]);
    const symbol = handler(url as Callable, ["option", "series", "0", "symbol"]);
    expect(formatter()).toBe("https://example.test/pricing");
    expect(() => symbol()).toThrow("option.series.0.symbol.return");
  });

  it("checks custom graphics, pattern images and DOM tooltip returns", () => {
    const handler = createOptionFunctionHandler(new WeakSet<Callable>());
    const graphic = handler((() => ({ type: "image", style: { image: "https://example.test/a.png" } })) as Callable,
      ["option", "series", "0", "renderItem"]);
    expect(() => graphic()).toThrow("option.series.0.renderItem.return.style.image");
    const richGraphic = handler((() => ({ type: "text", style: {
      text: "{value|x}", rich: { value: { backgroundColor: { image: "https://example.test/a.png" } } },
    } })) as Callable, ["option", "series", "0", "renderItem"]);
    expect(() => richGraphic()).toThrow("option.series.0.renderItem.return.style.rich.value.backgroundColor.image");
    const pattern = handler((() => ({ image: "https://example.test/a.png" })) as Callable,
      ["option", "series", "0", "itemStyle", "color"]);
    expect(() => pattern()).toThrow("option.series.0.itemStyle.color.return.image");
    const inlineSymbol = handler((() => "image://data:image/png;base64,iVBORw0KGgo=") as Callable,
      ["option", "series", "0", "symbol"]);
    expect(inlineSymbol()).toBe("image://data:image/png;base64,iVBORw0KGgo=");
    const tooltip = handler((() => ({ tagName: "DIV", innerHTML: "unsafe" })) as Callable,
      ["option", "tooltip", "formatter"]);
    expect(() => tooltip()).toThrow("option.tooltip.formatter.return");
    const namedAsync = { async() { return "synchronous text"; } };
    expect(handler(namedAsync.async as Callable, ["option", "label", "formatter"])()).toBe("synchronous text");
    const asyncCallback = () => handler((async () => "text") as Callable, ["option", "label", "formatter"]);
    expect(() => asyncCallback()).toThrow("Async chart callbacks");
  });
});

describe("callback invocation capacity", () => {
  const wrap = (fn: (...args: unknown[]) => unknown, path = ["option", "label", "formatter"]) =>
    createOptionFunctionHandler(new WeakSet<Callable>())(fn as Callable, path) as (...args: unknown[]) => unknown;

  it("shares input bytes and nodes across all arguments and resets per invocation", () => {
    const callback = wrap(() => "ok");
    const text = "x".repeat(200000);
    expect(callback(text, text)).toBe("ok");
    expect(callback(text, text)).toBe("ok");
    expect(() => callback(text, text, text)).toThrow(/callback input exceeds the byte budget/);
    expect(() => callback(Array(11000).fill(0), Array(11000).fill(0))).toThrow(/node budget/);
    expect(() => callback({ ["k".repeat(300000)]: 0 }, { ["q".repeat(300000)]: 0 })).toThrow(/byte budget/);
    expect(() => callback(Array(19000).fill(Number.MAX_VALUE))).not.toThrow();
    expect(() => callback(Object.fromEntries(Array.from({ length: 11000 }, (_, index) => [String(index), 0])))).toThrow(/node budget/);
    expect(() => callback(Object.fromEntries(Array.from({ length: 9000 }, (_, index) => [`${index}${"k".repeat(40)}`, Number.MAX_VALUE])))).toThrow(/byte budget/);
  });


  it("admits callback input depth 24 and rejects depth 25", () => {
    const nested = (levels: number): unknown => Array.from({ length: levels }).reduce<unknown>(value => ({ child: value }), 0);
    const callback = wrap(() => "ok");
    expect(callback(nested(22))).toBe("ok");
    expect(callback(nested(23))).toBe("ok");
    expect(() => callback(nested(24))).toThrow(/safety budget/);
  });

  it("checks exact invocation input and output byte boundaries", () => {
    const limit = 512 * 1024;
    const callback = wrap(() => "ok");
    expect(callback("x".repeat(limit - 6))).toBe("ok");
    expect(() => callback("x".repeat(limit - 5))).toThrow(/byte budget/);
    expect(wrap(() => "x".repeat(limit - 3))()).toHaveLength(limit - 3);
    expect(() => wrap(() => "x".repeat(limit - 2))()).toThrow(/byte budget/);
  });

  it("enforces independent bounded callback output", () => {
    expect(wrap(() => "x".repeat(400000))()).toHaveLength(400000);
    expect(() => wrap(() => Array(3).fill("x".repeat(200000)))()).toThrow(/callback output exceeds the byte budget/);
    expect(() => wrap(() => Array(20000).fill(0))()).toThrow(/node budget/);
    expect(() => wrap(() => Array(19000).fill(-Number.MAX_VALUE))()).not.toThrow();
  });

  it("shares renderItem helper calls with the invocation input budget", () => {
    const callback = wrap((_params, api) => {
      const helper = api as { value: (...args: unknown[]) => unknown };
      helper.value();
      helper.value();
      return helper.value();
    }, ["option", "series", "0", "renderItem"]);
    expect(() => callback({}, { value: () => "x".repeat(200000) })).toThrow(/callback input exceeds the byte budget/);
    const multiarg = wrap((_params, api) => (api as { coord: (...args: unknown[]) => unknown })
      .coord("x".repeat(200000), "y".repeat(200000), "z".repeat(200000)), ["option", "series", "0", "renderItem"]);
    const coord = vi.fn();
    expect(() => multiarg({}, { coord })).toThrow(/byte budget/);
    expect(coord).not.toHaveBeenCalled();
  });
});
