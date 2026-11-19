import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { WorkspaceAccessContextProvider } from "@/components/auth/WorkspaceAccessProvider";
import { CatalogMain, type CatalogMainData } from "@/components/catalog/browser/CatalogMain";
import type { CatalogIngestTask } from "@/lib/types/catalog";

vi.mock("@/hooks/use-workspace", () => ({
  useWorkspaceId: () => "workspace-1",
  usePrincipalId: () => "principal-1",
}));

function data(overrides: Partial<CatalogMainData> = {}): CatalogMainData {
  return {
    schemas: [{ catalogName: null, name: "finance", lastRefreshedAt: null }, { catalogName: null, name: "marketing", lastRefreshedAt: null }],
    schemasTotalCount: 2,
    schemasPage: 1,
    schemasPageSize: 50,
    loadingSchemas: false,
    schemaQuery: "",
    setSchemaQuery: () => undefined,
    setSchemasPage: () => undefined,
    relations: [{ catalogName: null, schemaName: "finance", name: "monthly_close", kind: "MATERIALIZED_VIEW", columnCount: 1, lastRefreshedAt: null }],
    relationsTotalCount: 1,
    relationsPage: 1,
    relationsPageSize: 50,
    loadingRelations: false,
    relationQuery: "",
    setRelationQuery: () => undefined,
    setRelationsPage: () => undefined,
    selectedSchema: { catalogName: null, schemaName: "finance" },
    selectSchema: () => undefined,
    selectedRelation: null,
    relationDetail: null,
    loadingRelationDetail: false,
    selectRelation: () => undefined,
    error: null,
    refresh: async () => undefined,
    ...overrides,
  };
}

const CONNECTOR = { id: "connector-1", type: "POSTGRESQL" as const };

function render(element: ReactElement): string {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <WorkspaceAccessContextProvider workspaceId="workspace-1" access={{ granted: true, organizationId: "organization-1", role: "EDITOR" }}>
        {element}
      </WorkspaceAccessContextProvider>
    </QueryClientProvider>,
  );
}

function failedTask(overrides: Partial<CatalogIngestTask>): CatalogIngestTask {
  return { taskId: "task", parentTaskId: null, targetKind: "SCHEMA", catalogName: null, schemaName: "marketing", relationName: null, relationKind: null, state: "FAILED", loading: false, failureCode: "PERMISSION_DENIED", safeMessage: "permission denied for schema marketing", ...overrides };
}

describe("CatalogMain", () => {
  it("lays out schemas, tables, and a flexible detail without a fixed browser minimum width", () => {
    const markup = render(<CatalogMain connector={CONNECTOR} data={data()} job={null} refreshErrors={[]} onRefresh={() => undefined} returnTo={null} />);
    expect(markup).toContain("Tables");
    expect(markup).toContain("Search tables");
    expect(markup).toContain("Materialized view");
    expect(markup).toContain("Not refreshed yet");
    expect(markup).toContain("Not loaded yet");
    expect(markup).toContain('aria-label="Refresh to load marketing"');
    expect(markup).toContain("Select a table to see its columns");
    expect(markup).toContain("min-w-[420px]");
    expect(markup).not.toContain("min-w-[1080px]");
    expect(markup).not.toContain("Relations");
  });

  it("keeps the latest finished refresh failures on their rows with a fix hint", () => {
    const markup = render(<CatalogMain
      connector={CONNECTOR}
      data={data({ selectedSchema: { catalogName: null, schemaName: "marketing" }, relations: [] })}
      job={null}
      refreshErrors={[failedTask({}), failedTask({ taskId: "relation", targetKind: "RELATION", schemaName: "finance", relationName: "monthly_close", failureCode: "WAREHOUSE_QUERY_FAILED", safeMessage: "Statement timeout" })]}
      onRefresh={() => undefined}
      returnTo={null}
    />);
    expect(markup).toContain("Last refresh failed: permission denied");
    expect(markup).toContain("PostgreSQL denied access.");
    expect(markup).toContain("Grant read access to the connector&#x27;s account, then try again.");
    expect(markup).toContain("Try again");
    expect(markup).toContain("Last refresh failed for 1 table");
  });
});
