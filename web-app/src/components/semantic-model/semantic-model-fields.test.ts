import { describe, expect, it } from "vitest";
import type { SemanticModelDimension, SemanticModelEntity, SemanticModelMeasure, SemanticModelResponse } from "@/api/semantic-model";
import {
  changeFieldKind,
  changeFieldLabel,
  definitionFromFields,
  emptyField,
  fieldPaths,
  fieldsFromDefinition,
  followDraftPaths,
  fieldsWithSourceColumns,
  applyRecommendation,
  fieldMatchesText,
  hiddenFieldIds,
  keyedModels,
  metricTimeAfterFieldChange,
  type FieldDraft,
} from "@/components/semantic-model/semantic-model-fields";

const entities: SemanticModelEntity[] = [
  { name: "invoice_id", type: "primary", expression: "invoice_id", label: "Invoice" },
  { name: "account_id", type: "foreign", expression: "account_id" },
];
const dimensions: SemanticModelDimension[] = [
  { isPartition: false, name: "issued_at", type: "time", expression: "issued_at", granularity: "hour", description: "When the invoice was issued" },
  { isPartition: false, name: "status", type: "categorical", expression: "status" },
];
const measures: SemanticModelMeasure[] = [
  {
    name: "balance",
    aggregation: "sum",
    expression: "balance_cents / 100.0",
    aggregationTimeDimension: "issued_at",
    nonAdditiveDimension: { name: "issued_at", windowChoice: "max", windowGroupings: ["account_id"] },
    label: "Balance",
  },
];

describe("semantic model fields", () => {
  it("preserves snapshot partition through kind changes and keeps it independent of Catalog facts", () => {
    const field = fieldsFromDefinition([], [{ ...dimensions[0], isPartition: true }], [])[0];
    const restored = changeFieldKind(changeFieldKind(field, "measure"), "time");
    expect(definitionFromFields([restored]).dimensions[0].isPartition).toBe(true);
    expect(definitionFromFields([emptyField()]).dimensions[0].isPartition).toBe(false);
    expect(definitionFromFields([changeFieldKind(field, "measure")]).measures[0]).not.toHaveProperty("isPartition");
  });
  it("round-trips a definition unchanged, including an agent-authored hour granularity", () => {
    const fields = fieldsFromDefinition(entities, dimensions, measures);
    expect(fields.map((field) => field.kind)).toEqual(["primary", "foreign", "time", "categorical", "measure"]);
    expect(fields[2].granularity).toBe("hour");
    expect(definitionFromFields(fields)).toEqual({ entities, dimensions, measures });
  });

  it("keeps a declared timeKind through a round trip and a kind change, and saves it only on Time fields", () => {
    const declared: SemanticModelDimension[] = [{ ...dimensions[0], timeKind: "instant" }, dimensions[1]];
    const fields = fieldsFromDefinition([], declared, []);
    expect(fields[0].timeKind).toBe("instant");
    expect(definitionFromFields(fields).dimensions).toEqual(declared);
    const asCategory = changeFieldKind(fields[0], "categorical");
    expect(definitionFromFields([asCategory]).dimensions[0]).not.toHaveProperty("timeKind");
    expect(definitionFromFields([changeFieldKind(asCategory, "time")]).dimensions[0].timeKind).toBe("instant");
    expect(definitionFromFields([{ ...fields[0], timeKind: undefined }]).dimensions[0]).not.toHaveProperty("timeKind");
  });

  it("keys each field by its definition path in table order", () => {
    const fields = fieldsFromDefinition(entities, dimensions, measures);
    expect(fields.map((field) => fieldPaths(fields).get(field.id))).toEqual([
      "entities[0]", "entities[1]", "dimensions[0]", "dimensions[1]", "measures[0]",
    ]);
  });

  it("moves a field between arrays on a kind change and keeps its name, label, description, and expression", () => {
    const fields = fieldsFromDefinition(entities, dimensions, measures);
    const asMeasure = fields.map((field) => (field.name === "status" ? changeFieldKind(field, "measure") : field));
    const moved = definitionFromFields(asMeasure);
    expect(moved.dimensions.map((dimension) => dimension.name)).toEqual(["issued_at"]);
    expect(moved.measures).toEqual([{ name: "status", aggregation: "sum", expression: "status" }, measures[0]]);

    const asKey = fields.map((field) => (field.name === "issued_at" ? changeFieldKind(field, "natural") : field));
    expect(definitionFromFields(asKey).entities[2]).toEqual({
      name: "issued_at", type: "natural", expression: "issued_at", description: "When the invoice was issued",
    });
    expect(fieldPaths(asKey).get(asKey[2].id)).toBe("entities[2]");

    const backToTime = asKey.map((field) => (field.name === "issued_at" ? changeFieldKind(field, "time") : field));
    expect(definitionFromFields(backToTime)).toEqual({ entities, dimensions, measures });
  });

  it("saves only the current kind's values", () => {
    const [measure] = fieldsFromDefinition([], [], measures);
    expect(definitionFromFields([changeFieldKind(measure, "categorical")]).dimensions).toEqual([
      { isPartition: false, name: "balance", type: "categorical", expression: "balance_cents / 100.0", label: "Balance" },
    ]);
    const time = changeFieldKind(measure, "time");
    expect(definitionFromFields([time]).dimensions).toEqual([
      { isPartition: false, name: "balance", type: "time", expression: "balance_cents / 100.0", granularity: "day", label: "Balance" },
    ]);
  });

  it("names an empty field from its label until the name is edited", () => {
    const field = changeFieldLabel(emptyField(), "Gross Revenue");
    expect(field).toMatchObject({ label: "Gross Revenue", name: "gross_revenue" });
    const loaded = fieldsFromDefinition(entities, [], [])[0];
    expect(changeFieldLabel(loaded, "Invoice number")).toMatchObject({ label: "Invoice number", name: "invoice_id" });
    expect(changeFieldLabel(loaded, "").label).toBeUndefined();
  });


});

describe("followDraftPaths", () => {
  it("keeps server errors on their rows after earlier rows are removed or change kind", () => {
    const pathsAtFailure = new Map([["a", "measures[0]"], ["b", "measures[1]"], ["c", "measures[2]"], ["s", "sourceRelations[0]"]]);
    const errors = {
      "measures[0].expression": "Unknown column a",
      "measures[2].expression": "Unknown column c",
      "sourceRelations[0]": "Relation is unavailable",
      name: "Name is taken",
    };
    const currentPaths = new Map([["b", "dimensions[0]"], ["c", "measures[0]"], ["s", "sourceRelations[0]"]]);

    expect(followDraftPaths(errors, pathsAtFailure, currentPaths)).toEqual({
      "measures[0].expression": "Unknown column c",
      "sourceRelations[0]": "Relation is unavailable",
      name: "Name is taken",
    });
  });
});


describe("Catalog recommendations", () => {
  const customerModel: SemanticModelResponse = {
    id: "customers", name: "customers", label: "Customers", description: "Customer accounts",
    sourceRelations: [{ connectorId: "warehouse", schema: "analytics", relation: "customer_accounts" }],
    entities: [{ name: "customer", type: "primary", expression: "id" }], dimensions: [], measures: [],
    createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z",
  };
  const relation = { requiresPartitionFilter: null,
    schemaName: "analytics", name: "orders", kind: "TABLE" as const,
    columns: [
      { name: "order_id", nativeType: "bigint", ordinalPosition: 1, nullable: false },
      { name: "customer_id", nativeType: "bigint", ordinalPosition: 2, nullable: false },
      { name: "ordered_at", nativeType: "timestamp", ordinalPosition: 3, nullable: false },
      { name: "revenue", nativeType: "numeric", ordinalPosition: 4, nullable: false },
    ],
    primaryKey: { columns: ["order_id"] },
    foreignKeys: [{ columns: ["customer_id"], referencedConnectorId: "warehouse", referencedSchemaName: "analytics", referencedRelationName: "customer_accounts", referencedColumns: ["id"] }],
    partitionKeys: [], clusteringKeys: [],
  };

  it("starts all columns unused and stores nothing until recommendations are accepted", () => {
    const fields = fieldsWithSourceColumns([], relation, [customerModel]);
    expect(fields.every((field) => field.kind === "unused")).toBe(true);
    expect(definitionFromFields(fields)).toEqual({ entities: [], dimensions: [], measures: [] });
    expect(fieldPaths(fields).size).toBe(0);
    const accepted = fields.map(applyRecommendation);
    expect(definitionFromFields(accepted)).toEqual({
      entities: [{ name: "order_id", type: "primary", expression: "order_id" }, { name: "customer", type: "foreign", expression: "customer_id" }],
      dimensions: [{ isPartition: false, name: "ordered_at", type: "time", expression: "ordered_at", granularity: "day" }],
      measures: [{ name: "revenue", aggregation: "sum", expression: "revenue" }],
    });
  });

  it("preserves two uses of one column and calculated/advanced fields while adding unused columns", () => {
    const fields = fieldsFromDefinition([{ name: "order_id", type: "primary", expression: "order_id" }], [], [
      { name: "order_count", aggregation: "count", expression: "order_id" },
      { name: "median_revenue", aggregation: "median", expression: "revenue" },
      { name: "positive_orders", aggregation: "sum_boolean", expression: "revenue > 0", nonAdditiveDimension: { name: "ordered_at", windowChoice: "max" } },
    ]);
    const merged = fieldsWithSourceColumns(fields, relation, [customerModel]);
    expect(merged.filter((field) => field.expression === "order_id")).toHaveLength(2);
    expect(definitionFromFields(merged)).toEqual(definitionFromFields(fields));
    expect(merged.find((field) => field.name === "customer_id")?.kind).toBe("unused");
    expect(merged.some((field) => field.recommendation)).toBe(false);
  });

  it("never guesses one component of a composite catalog key as the row key", () => {
    const fields = fieldsWithSourceColumns([], { ...relation, primaryKey: { columns: ["order_id", "customer_id"] } }, [customerModel]);
    expect(fields.some((field) => field.recommendation?.kind === "primary")).toBe(false);
  });
});


describe("Eligible model join targets", () => {
  it("includes primary and unique keys while retaining natural keys outside the target picker", () => {
    const model: SemanticModelResponse = {
      id: "customers", name: "customers", sourceRelations: [],
      entities: [
        { name: "customer_id", type: "primary", expression: "id" },
        { name: "customer_email", type: "unique", expression: "email" },
        { name: "customer_name", type: "natural", expression: "name" },
      ], dimensions: [], measures: [], createdAt: "", updatedAt: "",
    };
    expect(keyedModels([model]).map(({ key }) => key.name)).toEqual(["customer_id", "customer_email"]);
    expect(keyedModels([model], model.id)).toEqual([]);
    expect(definitionFromFields(fieldsFromDefinition(model.entities, [], [])).entities).toEqual(model.entities);
  });
});

describe("Model editor views", () => {
  const relation = { requiresPartitionFilter: null,
    schemaName: "analytics", name: "orders", kind: "TABLE" as const,
    columns: [
      { name: "order_id", nativeType: "bigint", ordinalPosition: 1, nullable: false },
      { name: "ordered_at", nativeType: "timestamp", ordinalPosition: 2, nullable: false },
      { name: "revenue", nativeType: "numeric", ordinalPosition: 3, nullable: false },
    ],
    primaryKey: { columns: ["order_id"] }, foreignKeys: [], partitionKeys: [], clusteringKeys: [],
  };

  it("matches the filter on name, label, expression, or description", () => {
    const [orderId] = fieldsWithSourceColumns([], relation, []);
    expect(fieldMatchesText(orderId, "ORDER")).toBe(true);
    expect(fieldMatchesText({ ...orderId, description: "Placed order" }, "placed")).toBe(true);
    expect(fieldMatchesText(orderId, "revenue")).toBe(false);
    expect(fieldMatchesText(orderId, "  ")).toBe(true);
  });

  it("shows an In use row again once it gets a use and keeps a filtered row that was edited", () => {
    const fields = fieldsWithSourceColumns([], relation, []);
    const inUseHidden = new Set(fields.map((field) => field.id));
    expect(hiddenFieldIds(fields, null, inUseHidden).size).toBe(3);
    const applied = fields.map(applyRecommendation);
    expect(hiddenFieldIds(applied, null, inUseHidden).size).toBe(0);
    const filterHidden = new Set([fields[2].id]);
    const renamed = fields.map((field) => field.id === fields[0].id ? { ...field, name: "zzz" } : field);
    expect([...hiddenFieldIds(renamed, filterHidden, null)]).toEqual([fields[2].id]);
  });

  it("keeps the Metric time on its field when that Time field is renamed, and never adopts a new field", () => {
    const time: FieldDraft = { ...emptyField(), kind: "time", name: "ordered_at", expression: "ordered_at", nameFollowsLabel: false };
    expect(metricTimeAfterFieldChange("ordered_at", time, { ...time, name: "placed_at" })).toBe("placed_at");
    expect(metricTimeAfterFieldChange("shipped_at", time, { ...time, name: "placed_at" })).toBe("shipped_at");
    expect(metricTimeAfterFieldChange("ordered_at", time, { ...time, kind: "categorical" })).toBe("ordered_at");
    const unnamed: FieldDraft = { ...time, name: "" };
    expect(metricTimeAfterFieldChange("", unnamed, { ...unnamed, name: "created_at" })).toBe("");
  });
});
