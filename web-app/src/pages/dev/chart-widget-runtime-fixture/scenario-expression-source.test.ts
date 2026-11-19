import "ses";
import { beforeAll, expect, it } from "vitest";
import { canonicalFrame } from "@/features/chart-widget/runtime/worker/canonical-boundary";
import { evaluateChartSource } from "@/features/chart-widget/runtime/worker/module-source";
import type { ChartRenderFrame } from "@/features/chart-widget/contract/abi";
import { expressionScenarios, expressionVariableValues } from "./scenario-expression";

beforeAll(() => lockdown({ errorTaming: "unsafe-debug" }));

function example(key: string) {
  const inputs = expressionScenarios[key].inputs;
  const frame: ChartRenderFrame = {
    queries: inputs.queries, parameters: inputs.parameters,
    variables: expressionVariableValues(inputs) as ChartRenderFrame["variables"],
    locale: inputs.locale, viewport: inputs.viewport,
  };
  const module = evaluateChartSource(inputs.moduleSource, "scenario-expression.ts", new WeakSet());
  return { frame, render: () => module.render(frame), module };
}

it("A01 computes exact fixture amounts, rounded change, dates and all tooltip values", () => {
  const { render, frame } = example("A01-kpi-trend");
  const option = render();
  expect(option.title).toMatchObject({ text: "$74,520.00", subtext: "Previous $65,000.00 · +14.6% · Trend 20,000→24,000→30,520" });
  expect(option.xAxis).toMatchObject({ data: ["2026-09-01", "2026-09-02", "2026-09-03"] });
  expect(option.series).toMatchObject([{ data: [20000, 24000, 30520] }]);
  const formatter = (option.tooltip as { formatter: (params: { value: number }[]) => string }).formatter;
  expect([20000, 24000, 30520].map(value => formatter([{ value }]))).toEqual(["Trend 20000", "Trend 24000", "Trend 30520"]);
  expect(frame.queries.current.rows.map(row => row["metric.amount"])).toEqual(["20000.00", "24000.00", "30520.00"]);
});

it.each([
  ["A02-decimal-exact", "9007199254740993.27", "$9,007,199,254,740,993.27 · DECIMAL string preserved"],
  ["A03-zero-negative-null", "$0.00 | -$1,850.25 | —", undefined],
  ["A04-comparison-zero", "$100.00 · —", "comparison unavailable"],
  ["A04-comparison-null", "$100.00 · —", "comparison unavailable"],
  ["A06-reordered-columns", "Named amount 74520.00", undefined],
  ["A08-ko-kr-usd-seoul", "US$1,234.50", "Asia/Seoul · 2026/09/07"],
  ["A08-en-us-usd-utc", "$1,234.50", "UTC · 09/06/2026"],
  ["A08-de-de-krw-seoul", "1.234,50 ₩", "Asia/Seoul · 07/09/2026"],
])("%s compares actual evaluated output to independent expected literals", (key, text, subtext) => {
  const option = example(key!).render();
  expect(option.title).toMatchObject({ text });
  if (subtext !== undefined) expect(option.title).toMatchObject({ subtext });
});

it("A05 replaces the old total with empty text from zero rows", () => {
  const { module, frame } = example("A05-empty-success");
  const option = module.render({ ...frame, queries: { sales: { ...frame.queries.sales, rows: [] } } });
  expect(option.title).toMatchObject({ text: "No data", subtext: "Run SUCCEEDED · row count 0" });
});

it("A06 missing names fail instead of consuming the wrong positional cell", () => {
  expect(() => example("A06-missing-field").render()).toThrow("Missing canonical field metric.amount");
});


it("F08 isolates mutation attempts from the Host and another Widget input", () => {
  const { frame, module } = example("F08-input-immutable");
  const before = structuredClone(frame);
  const otherWidget = canonicalFrame(frame);
  const isolated = canonicalFrame(frame);
  expect(module.render(isolated).title).toMatchObject({ text: "row:blocked · column:blocked · variables:blocked · 42.00" });
  expect(frame).toEqual(before);
  expect(otherWidget).toEqual(before);
  expect(module.render(canonicalFrame(frame)).title).toMatchObject({ text: "row:blocked · column:blocked · variables:blocked · 42.00" });
});

it("A02 preserves the decimal label and descriptors while coordinates explicitly use Number precision", () => {
  const { frame, render } = example("A02-decimal-exact");
  expect(frame.queries.decimal.columns).toEqual([{ name: "metric.amount", logicalType: "DECIMAL", nullable: false, numberFormat: null }]);
  expect(frame.queries.decimal.rows[0]["metric.amount"]).toBe("9007199254740993.27");
  expect(render().series).toMatchObject([{ data: [9007199254740994] }]);
  expect(render().title).toMatchObject({ text: "9007199254740993.27", subtext: "$9,007,199,254,740,993.27 · DECIMAL string preserved" });
});
