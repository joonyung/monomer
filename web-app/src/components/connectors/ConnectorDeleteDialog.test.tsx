import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { ConnectorDeleteDialog, semanticModelImpact, semanticModelsUsingConnector } from "@/components/connectors/ConnectorDeleteDialog";
import { queryKeys } from "@/lib/query/keys";
import type { Connector } from "@/lib/types/connector";

vi.mock("@radix-ui/react-dialog", () => {
  const Primitive = ({ children, ...props }: { children?: ReactNode } & Record<string, unknown>) => <div {...props}>{children}</div>;
  return { Root: Primitive, Portal: Primitive, Overlay: Primitive, Content: Primitive, Title: Primitive, Description: Primitive, Close: Primitive, Trigger: Primitive };
});

const WORKSPACE_ID = "workspace-1";
const PRINCIPAL_ID = "principal-1";

const connector: Connector = {
  id: "connector-1",
  workspaceId: WORKSPACE_ID,
  name: "Finance warehouse",
  type: "POSTGRESQL",
  properties: { host: "warehouse.internal", port: 5432, database: "warehouse" },
  createdAtEpochMs: 1,
  updatedAtEpochMs: 1,
};

function model(id: string, label: string, connectorIds: string[]): SemanticModelResponse {
  return {
    id, name: id, label, entities: [], dimensions: [], measures: [], createdAt: "", updatedAt: "",
    sourceRelations: connectorIds.map((connectorId) => ({ connectorId, schema: "public", relation: id })),
  };
}

function render(models: SemanticModelResponse[] | null): string {
  const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity } } });
  if (models) queryClient.setQueryData(queryKeys.semanticModels.all(WORKSPACE_ID, PRINCIPAL_ID), models);
  return renderToStaticMarkup(
    <QueryClientProvider client={queryClient}>
      <ConnectorDeleteDialog open workspaceId={WORKSPACE_ID} principalId={PRINCIPAL_ID} connector={connector} onCancel={() => undefined} onDeleted={() => undefined} />
    </QueryClientProvider>,
  );
}

describe("ConnectorDeleteDialog", () => {
  it("counts only semantic models whose source tables read the connector", () => {
    const models = [model("orders", "Orders", ["connector-1"]), model("visits", "Visits", ["connector-2"]), model("mixed", "Mixed", ["connector-2", "connector-1"])];
    expect(semanticModelsUsingConnector(models, "connector-1").map((candidate) => candidate.id)).toEqual(["orders", "mixed"]);
    expect(semanticModelImpact(1)).toBe("Used by 1 semantic model — it won’t run until it uses another connector.");
    expect(semanticModelImpact(2)).toBe("Used by 2 semantic models — they won’t run until they use another connector.");
  });

  it("previews the impact, links each affected model, and requires the exact name", () => {
    const markup = render([model("orders", "Orders", ["connector-1"]), model("visits", "Visits", ["connector-2"]), model("mixed", "Mixed", ["connector-1"])]);
    expect(markup).toContain("Delete Finance warehouse?");
    expect(markup).toContain("This can’t be undone.");
    expect(markup).toContain('role="alertdialog"');
    expect(markup).toContain("Its tables leave Catalog, and its saved credentials are destroyed.");
    expect(markup).toContain("Used by 2 semantic models — they won’t run until they use another connector.");
    expect(markup).toContain(`href="/workspaces/${WORKSPACE_ID}/semantic-models/orders"`);
    expect(markup).toContain(`href="/workspaces/${WORKSPACE_ID}/semantic-models/mixed"`);
    expect(markup).not.toContain("Visits");
    expect(markup).toContain("Type “Finance warehouse” to confirm");
    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>[\s\S]*?Delete connector<\/button>/);
    expect(markup).toContain("Keep connector");
  });

  it("says when no semantic model uses the connector and while it is still checking", () => {
    expect(render([model("visits", "Visits", ["connector-2"])])).toContain("No semantic models use it.");
    expect(render(null)).toContain("Checking which semantic models use it…");
  });
});
