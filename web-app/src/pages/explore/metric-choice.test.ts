import { describe, expect, it, vi } from "vitest";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric } from "@/lib/types/metric";
import { emptyExploreDraft } from "./explore-draft";
import { metricSwapMessage, withMetricChosen } from "./SemanticQuestion";

vi.mock("@/lib/navigation", () => ({ Link: () => null }));

const simple = (name: string, model: string): Metric => ({
  id: `id-${name}`, name, label: name, type: "simple",
  typeParams: { semanticModelName: model, measure: name }, createdAt: "", updatedAt: "",
});
const model = (name: string, connectorIds: string[]): SemanticModelResponse => ({
  id: `model-${name}`, name, label: name, entities: [], dimensions: [], measures: [], createdAt: "", updatedAt: "",
  sourceRelations: connectorIds.map((connectorId) => ({ connectorId, schema: "public", relation: name })),
});

// orders runs on pg and sf, events only on sf, finance only on bq, accounts only on pg.
const metrics = [simple("revenue", "orders"), simple("visits", "events"), simple("margin", "finance"), simple("ledger", "accounts"), simple("broken", "missing")];
const models = [model("orders", ["pg", "sf"]), model("events", ["sf"]), model("finance", ["bq"]), model("accounts", ["pg"])];
const connectorIds = ["pg", "sf", "bq"];
const draftWith = (chosen: string[]) => ({ ...emptyExploreDraft().semantic, metrics: chosen, connectorId: "pg" });

describe("choosing a metric across connectors", () => {
  it("adds a metric that runs beside the chosen ones and removes a chosen one", () => {
    expect(withMetricChosen(draftWith(["revenue"]), "visits", metrics, models, connectorIds)).toEqual({ draft: draftWith(["revenue", "visits"]), removed: [] });
    expect(withMetricChosen(draftWith(["revenue", "visits"]), "revenue", metrics, models, connectorIds)).toEqual({ draft: draftWith(["visits"]), removed: [] });
  });

  it("switches to the new metric's connector, keeping only chosen metrics that can run beside it", () => {
    const chosen = withMetricChosen(draftWith(["revenue", "visits"]), "margin", metrics, models, connectorIds);
    expect(chosen).toEqual({ draft: draftWith(["margin"]), removed: ["revenue", "visits"] });
  });

  it("keeps chosen metrics that also run on the new metric's connector", () => {
    const chosen = withMetricChosen(draftWith(["revenue", "ledger"]), "visits", metrics, models, connectorIds);
    expect(chosen).toEqual({ draft: draftWith(["revenue", "visits"]), removed: ["ledger"] });
  });

  it("adds a metric no connector can run instead of dropping working metrics for it", () => {
    expect(withMetricChosen(draftWith(["revenue"]), "broken", metrics, models, connectorIds)).toEqual({ draft: draftWith(["revenue", "broken"]), removed: [] });
  });

  it("names the switch, the removed metrics, and when a connector still needs choosing", () => {
    const swap = (removed: string[], connectorName: string | null, removedCannotRun = false) => ({ removed, connectorName, removedCannotRun });
    expect(metricSwapMessage(swap(["revenue"], "BigQuery"), metrics, true)).toBe("Switched to BigQuery. Removed revenue.");
    expect(metricSwapMessage(swap(["revenue", "visits"], null), metrics, true)).toBe("Removed revenue and visits. Choose a connector to run.");
    expect(metricSwapMessage(swap(["revenue", "visits", "ledger"], "BigQuery"), metrics, true)).toBe("Switched to BigQuery. Removed 3 metrics.");
    expect(metricSwapMessage(swap(["revenue", "visits", "ledger"], "BigQuery"), metrics, false)).toBe("Switched to BigQuery. Removed revenue, visits, and ledger.");
    expect(metricSwapMessage(swap(["broken"], "BigQuery", true), metrics, true)).toBe("Removed broken, which can’t run on any connector.");
  });
});
