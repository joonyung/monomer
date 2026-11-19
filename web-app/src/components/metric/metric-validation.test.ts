import { describe, expect, it } from "vitest";
import { problemSummary } from "@/components/semantic-model/semantic-model-validation";
import { validateMetricDraft } from "@/components/metric/metric-validation";

describe("metric validation", () => {
  it("asks for a name before its format", () => {
    const empty = validateMetricDraft({ name: "", type: "simple", params: { semanticModelName: "orders", measure: "revenue" }, filters: [] });
    expect(empty.map(problemSummary)).toEqual(["Name: enter a name."]);
    const malformed = validateMetricDraft({ name: "Gross Revenue", type: "simple", params: { semanticModelName: "orders", measure: "revenue" }, filters: [] });
    expect(malformed[0].message).toBe("Use lowercase letters, numbers, and underscores, starting with a letter (e.g. gross_revenue).");
  });

  it("names the missing simple definition field", () => {
    expect(validateMetricDraft({ name: "revenue", type: "simple", params: { semanticModelName: "", measure: "" }, filters: [] }).map((problem) => problem.message))
      .toEqual(["Select a model."]);
    expect(validateMetricDraft({ name: "revenue", type: "simple", params: { semanticModelName: "orders", measure: "" }, filters: [] }))
      .toEqual([{ field: "measure", subject: "Aggregate metric", message: "Select an aggregate." }]);
  });

  it("requires distinct ratio inputs", () => {
    expect(validateMetricDraft({ name: "rate", type: "ratio", params: { numerator: { metricName: "" }, denominator: { metricName: "" } }, filters: [] }).map(problemSummary))
      .toEqual(["Numerator: select a numerator metric.", "Denominator: select a denominator metric."]);
    expect(validateMetricDraft({ name: "rate", type: "ratio", params: { numerator: { metricName: "orders" }, denominator: { metricName: "orders" } }, filters: [] }))
      .toEqual([{ field: "denominator", subject: "Denominator", message: "Numerator and denominator must differ." }]);
  });

  it("keys each derived input and the expression", () => {
    const problems = validateMetricDraft({ name: "net", type: "derived", params: { expression: " ", inputMetrics: [{ metricName: "gross" }, { metricName: "" }] }, filters: [] });
    expect(problems.map((problem) => problem.field)).toEqual(["input-1", "expression"]);
  });

  it("accepts a complete definition", () => {
    expect(validateMetricDraft({ name: "revenue", type: "simple", params: { semanticModelName: "orders", measure: "revenue" }, filters: [] })).toEqual([]);
  });
});
