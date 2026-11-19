import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { DashboardDefinition, DashboardJsonObject } from "@/api/dashboard";
import { DashboardLayout } from "./DashboardLayout";

vi.mock("./DashboardWidgetRenderer", async (importOriginal) => ({
  ...await importOriginal<typeof import("./DashboardWidgetRenderer")>(),
  DashboardWidgetRenderer: ({ parameters }: { parameters: DashboardJsonObject }) => <output>{String(parameters.currency)}</output>,
}));
const definition: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "Snapshot" }, parameters: {}, variables: {}, queries: {},
  widgets: { card: { type: "TEXT", content: "Snapshot" } },
  layout: { sections: [{ id: "section", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "placement", widgetId: "card" } }] },
};

describe("DashboardLayout ready Parameter snapshot", () => {
  it.each(["pending", "failed", "ready"])("passes displayed parameters when the next run is %s", (state) => {
    const markup = renderToStaticMarkup(<DashboardLayout
      definition={definition} parameters={{ currency: "EUR" }}
      parametersByWidgetId={{ card: { currency: state === "ready" ? "EUR" : "USD" } }}
      variables={{}} variableRevision={1} variableRevisionsByWidgetId={{}} variablesByWidgetId={{}}
      variableDefinitions={{}} resultsByWidgetId={{}} displayedWidgetIds={new Set(["card"])}
      refreshingWidgetIds={new Set(state === "pending" ? ["card"] : [])}
      failureWidgetIds={new Set(state === "failed" ? ["card"] : [])} onVariablesSet={() => undefined}
    />);
    expect(markup).toContain(`<output>${state === "ready" ? "EUR" : "USD"}</output>`);
  });
  it("uses current parameters without a per-widget override", () => {
    const markup = renderToStaticMarkup(<DashboardLayout
      definition={definition} parameters={{ currency: "EUR" }} variables={{}}
      variableRevision={0} variableRevisionsByWidgetId={{}} variablesByWidgetId={{}}
      variableDefinitions={{}} resultsByWidgetId={{}} displayedWidgetIds={new Set(["card"])}
      refreshingWidgetIds={new Set()} failureWidgetIds={new Set()} onVariablesSet={() => undefined}
    />);
    expect(markup).toContain("<output>EUR</output>");
  });
});
