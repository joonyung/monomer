import { describe, expect, it } from "vitest";
import { lineageHref, retainPreviousLineageFocus } from "@/pages/lineage/page";
import type { LineageFocusCone } from "@/api/lineage";

describe("Lineage URL state", () => {
  it("retains the current graph while the next focus request is in flight", () => {
    const currentGraph = { focus: { resource: { kind: "METRIC", id: "metric-1" } } } as LineageFocusCone;

    expect(retainPreviousLineageFocus(currentGraph)).toBe(currentGraph);
    expect(retainPreviousLineageFocus(undefined)).toBeUndefined();
  });

  it("uses stable kind/id search values without a global access mode", () => {
    expect(lineageHref("019e501f-0000-7000-8000-000000000011", {
      kind: "SEMANTIC_MODEL",
      id: "019e501f-0000-7000-8000-000000000012",
    })).toBe("/workspaces/019e501f-0000-7000-8000-000000000011/lineage?kind=SEMANTIC_MODEL&id=019e501f-0000-7000-8000-000000000012");
  });

  it("does not submit a partial focus or expose FQN as identity", () => {
    expect(lineageHref("workspace-1", { kind: "METRIC" })).toBe("/workspaces/workspace-1/lineage");
    expect(lineageHref("workspace-1", { id: "orders" })).toBe("/workspaces/workspace-1/lineage");
  });

});
