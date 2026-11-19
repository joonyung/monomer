import { describe, expect, it } from "vitest";
import {
  problemSummary,
  problemsByField,
  technicalNameProblem,
  validateSemanticModelDraft,
  type SemanticModelDraft,
} from "@/components/semantic-model/semantic-model-validation";
import { suggestTechnicalName } from "@/lib/utils/semantic-model-utils";

const validDraft: SemanticModelDraft = {
  name: "orders",
  sourceRelations: [{ connectorId: "connector-1", schema: "analytics", relation: "orders" }],
  entities: [{ name: "order_id", type: "primary", expression: "id" }],
  dimensions: [{ isPartition: false, name: "ordered_at", type: "time", granularity: "day", expression: "ordered_at" }],
  measures: [{ name: "revenue", aggregation: "sum", expression: "amount" }],
  aggregationTimeDimension: "ordered_at",
};

describe("semantic model validation", () => {
  it("distinguishes an empty name from a malformed one", () => {
    expect(technicalNameProblem("", "orders")).toBe("Enter a name.");
    expect(technicalNameProblem("Orders", "orders")).toBe("Use lowercase letters, numbers, and underscores, starting with a letter (e.g. orders).");
    expect(technicalNameProblem("orders_2026", "orders")).toBeNull();
  });

  it("accepts a complete draft", () => {
    expect(validateSemanticModelDraft(validDraft)).toEqual([]);
  });

  it("names the field in each summary item and keys inline messages by field", () => {
    const problems = validateSemanticModelDraft({
      ...validDraft,
      name: "",
      sourceRelations: [],
      entities: [],
      measures: [validDraft.measures[0], { name: "orders_count", aggregation: "count", expression: " " }],
    });
    expect(problems.map(problemSummary)).toEqual([
      "Name: enter a name.",
      "Source: add a source table.",
      "Fields: define exactly one Row key.",
      "Aggregate metric 2: enter a column or SQL expression.",
    ]);
    expect(problemsByField(problems)).toMatchObject({
      name: "Enter a name.",
      sourceRelations: "Add a source table.",
      entities: "Define exactly one Row key.",
      "measures[1].expression": "Enter a column or SQL expression.",
    });
  });

  it("flags duplicate field names, missing granularities, and incomplete source tables", () => {
    const problems = validateSemanticModelDraft({
      ...validDraft,
      sourceRelations: [{ connectorId: "connector-1", schema: "analytics", relation: "" }],
      dimensions: [{ isPartition: false, name: "revenue", type: "time", expression: "ordered_at" }],
      aggregationTimeDimension: "",
    });
    expect(problems.map(problemSummary)).toEqual([
      "Source table 1: choose a table.",
      "Dimension 1: use a name that no other field uses.",
      "Dimension 1: select Day, Week, Month, or another time period.",
      "Aggregate metric 1: use a name that no other field uses.",
    ]);
    expect(problems.map((problem) => problem.field)).toEqual([
      "sourceRelations[0]",
      "dimensions[0].name",
      "dimensions[0].granularity",
      "measures[0].name",
    ]);
  });

  it("keys time axis and non-additive references by their definition paths", () => {
    const problems = validateSemanticModelDraft({
      ...validDraft,
      aggregationTimeDimension: "shipped_at",
      measures: [
        { name: "revenue", aggregation: "sum", expression: "amount", aggregationTimeDimension: "shipped_at" },
        { name: "balance", aggregation: "sum", expression: "balance", nonAdditiveDimension: { name: "shipped_at", windowChoice: "max" } },
        { name: "open_balance", aggregation: "sum", expression: "balance", nonAdditiveDimension: { name: "ordered_at", windowChoice: "min", windowGroupings: ["account_id"] } },
      ],
    });
    expect(problemsByField(problems)).toEqual({
      aggregationTimeDimension: "Select a Time field.",
      "measures[0].aggregationTimeDimension": "Select a Time field for Metric time.",
      "measures[1].nonAdditiveDimension": "Select a Time field for One value per period.",
      "measures[2].nonAdditiveDimension": "Group One value per period by key names.",
    });
    expect(problems.find((problem) => problem.field === "aggregationTimeDimension")?.subject).toBe("Metric time");
  });

  it("suggests a technical name from a label", () => {
    expect(suggestTechnicalName("Gross Revenue (USD)")).toBe("gross_revenue_usd");
    expect(suggestTechnicalName("2026 Orders")).toBe("orders");
    expect(suggestTechnicalName("")).toBe("");
  });
});
