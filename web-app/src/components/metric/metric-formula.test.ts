import { describe, expect, it } from "vitest";
import { metricStorageDefinition } from "./metric-formula";

describe("formula storage", () => {
  it("stores simple division as a ratio and preserves qualified inputs", () => {
    const numerator = { metricName: "revenue", alias: "current", offsetWindow: "1 month" as const };
    const denominator = { metricName: "orders", alias: "prior", filter: { dimension: { semanticModelName: "orders", name: "channel" }, operator: "eq" as const, values: ["Web"] } };
    expect(metricStorageDefinition("derived", { expression: "current ÷ prior", inputMetrics: [denominator, numerator] })).toEqual({ type: "ratio", typeParams: { numerator, denominator } });
  });
  it("keeps other expressions, ambiguous aliases, and unused inputs derived", () => {
    for (const expression of ["a - b", "(a - b) / b", "a / missing", "a / a"]) {
      expect(metricStorageDefinition("derived", { expression, inputMetrics: [{ metricName: "a" }, { metricName: "b" }] }).type).toBe("derived");
    }
    expect(metricStorageDefinition("derived", { expression: "a / b", inputMetrics: [{ metricName: "a" }, { metricName: "b" }, { metricName: "c" }] }).type).toBe("derived");
  });
});
