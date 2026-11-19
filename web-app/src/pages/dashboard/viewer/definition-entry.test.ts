import { describe, expect, it } from "vitest";
import type { DashboardDefinition } from "@/api/dashboard";
import { compatibleParameterValues, dashboardSnapshotsEqual } from "./definition-entry";

const definition: DashboardDefinition = { schemaVersion: 1, meta: { title: "Revenue" }, parameters: {}, variables: {}, queries: {}, widgets: { intro: { type: "TEXT", content: "Questions" } }, layout: { sections: [] } };

describe("bare entry definition comparison", () => {
  it("includes presentation-only Apply changes and ignores JSON object serialization order", () => {
    expect(dashboardSnapshotsEqual(definition, { ...definition, meta: { title: "Updated" } })).toBe(false);
    expect(dashboardSnapshotsEqual(definition, { ...definition, widgets: { intro: { type: "TEXT", content: "Updated questions" } } })).toBe(false);
    expect(dashboardSnapshotsEqual(definition, { layout: definition.layout, widgets: definition.widgets, queries: {}, variables: {}, parameters: {}, meta: definition.meta, schemaVersion: 1 })).toBe(true);
  });
  it("prefills only declared values valid for the new type and nullability", () => {
    const parameters = { valid: { type: "DATE_RANGE" as const, nullable: false }, changedType: { type: "BOOLEAN" as const, nullable: false }, required: { type: "TEXT" as const, nullable: false }, optional: { type: "TEXT_SET" as const, nullable: true }, newInput: { type: "TEXT" as const, nullable: false } };
    expect(compatibleParameterValues(parameters, { valid: { start: "2026-01-01", end: "2026-01-31" }, changedType: "yes", required: null, optional: null, removed: "old" })).toEqual({ valid: { start: "2026-01-01", end: "2026-01-31" }, optional: null });
    expect(compatibleParameterValues({}, { removed: "old" })).toEqual({});
  });
});
