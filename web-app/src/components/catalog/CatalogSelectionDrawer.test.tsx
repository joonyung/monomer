import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { WorkspaceRouteFixtureProvider } from "@/components/auth/TenantRouteContext";
import { OptionList, selectionHasCoverage, selectionSchemaKey } from "@/components/catalog/CatalogSelectionDrawer";
import type { CatalogSelection } from "@/lib/types/catalog";
import { queryKeys } from "@/lib/query/keys";

const WORKSPACE_ID = "83000000-0000-0000-0000-000000000001";
const PRINCIPAL_ID = "83000000-0000-0000-0000-000000000002";
const CONNECTOR_ID = "83000000-0000-0000-0000-000000000003";

function renderOptionList(queryData: unknown, draft: CatalogSelection = { selectedSchemas: [] }, canEdit = true): string {
  const queryClient = new QueryClient();
  queryClient.setQueryData(queryKeys.catalog.selectionOptions(WORKSPACE_ID, PRINCIPAL_ID, CONNECTOR_ID, "SCHEMA", null, "orders", 1, 50, ""), queryData);
  return renderToStaticMarkup(
    <WorkspaceRouteFixtureProvider workspace={{ id: WORKSPACE_ID, organizationId: "83000000-0000-0000-0000-000000000004", publicPages: false, reportingTimeZone: "UTC", name: "Test" }} access={{ granted: true, organizationId: "83000000-0000-0000-0000-000000000004", role: "OWNER" as const, }}>
      <QueryClientProvider client={queryClient}>
        <OptionList canEdit={canEdit} open workspaceId={WORKSPACE_ID} principalId={PRINCIPAL_ID} connectorId={CONNECTOR_ID} target={{ parentKind: "SCHEMA", catalogName: null, schemaName: "orders" }} label="Tables in orders" childKind="RELATION" draft={draft} onChange={() => undefined} />
      </QueryClientProvider>
    </WorkspaceRouteFixtureProvider>,
  );
}

describe("Catalog selection draft contract", () => {
  it("distinguishes no selection from a schema with exact relation coverage", () => {
    const empty: CatalogSelection = { selectedSchemas: [] };
    const onlyRelations: CatalogSelection = {
      selectedSchemas: [{ schemaName: "orders", relations: { kind: "SELECTED_RELATIONS", relationNames: ["orders_12480"] } }],
    };

    expect(selectionHasCoverage(empty)).toBe(false);
    expect(selectionHasCoverage(onlyRelations)).toBe(true);
    expect(selectionSchemaKey("orders")).toBe(JSON.stringify(["orders"]));
  });

  it("keeps an all-relations schema distinct from exact names", () => {
    const all: CatalogSelection = {
      selectedSchemas: [{ schemaName: "public", relations: { kind: "ALL_RELATIONS" } }],
    };
    expect(all.selectedSchemas[0]?.relations.kind).toBe("ALL_RELATIONS");
    expect(all.selectedSchemas[0]?.relations).not.toHaveProperty("relationNames");
  });

  it("renders a cached reload failure with its cached row and retry affordance", () => {
    const markup = renderOptionList({
      parentKind: "SCHEMA",
      childKind: "RELATION",
      catalogName: null,
      schemaName: "orders",
      fetchedAt: "2026-08-21T01:02:03Z",
      options: [{ name: "orders_001", relationKind: "TABLE", available: true }],
      totalCount: 1,
      page: 1,
      pageSize: 50,
      latestJob: { jobId: "83000000-0000-0000-0000-000000000005", parentKind: "SCHEMA", childKind: "RELATION", catalogName: null, schemaName: "orders", state: "COMPLETED_WITH_ERRORS", requestedAt: "2026-08-21T01:02:03Z", terminalAt: "2026-08-21T01:03:03Z", failureCode: "WAREHOUSE_UNAVAILABLE", safeMessage: "Warehouse unavailable" },
    });

    expect(markup).toContain("orders_001");
    expect(markup).toContain("Warehouse unavailable");
    expect(markup).toContain("Retry");
  });

  it("does not turn a first refresh failure into an empty state", () => {
    const markup = renderOptionList({
      parentKind: "SCHEMA",
      childKind: "RELATION",
      catalogName: null,
      schemaName: "orders",
      fetchedAt: null,
      options: [],
      totalCount: 0,
      page: 1,
      pageSize: 50,
      latestJob: { jobId: "83000000-0000-0000-0000-000000000006", parentKind: "SCHEMA", childKind: "RELATION", catalogName: null, schemaName: "orders", state: "COMPLETED_WITH_ERRORS", requestedAt: "2026-08-21T01:02:03Z", terminalAt: "2026-08-21T01:03:03Z", failureCode: "WAREHOUSE_UNAVAILABLE", safeMessage: "Warehouse unavailable" },
    });

    expect(markup).toContain("Warehouse unavailable");
    expect(markup).not.toContain("No tables found.");
  });

  it("keeps exact selected names visible before their list is fetched", () => {
    const markup = renderOptionList({ parentKind: "SCHEMA", childKind: "RELATION", catalogName: null, schemaName: "orders", fetchedAt: null, options: [], totalCount: 0, page: 1, pageSize: 50, latestJob: null }, {
      selectedSchemas: [{ schemaName: "orders", relations: { kind: "SELECTED_RELATIONS", relationNames: ["orders_12480"] } }],
    });
    expect(markup).toContain("orders_12480");
    expect(markup).toContain("Not fetched");
    expect(markup).not.toContain("0 available");
  });

  it("allows a disappeared selected relation to be removed", () => {
    const markup = renderOptionList({
      parentKind: "SCHEMA",
      childKind: "RELATION",
      catalogName: null,
      schemaName: "orders",
      fetchedAt: "2026-08-21T01:02:03Z",
      options: [{ name: "retired_orders", relationKind: "TABLE", available: false }],
      totalCount: 1,
      page: 1,
      pageSize: 50,
      latestJob: null,
    }, {
      selectedSchemas: [{ schemaName: "orders", relations: { kind: "SELECTED_RELATIONS", relationNames: ["retired_orders"] } }],
    });

    const checkbox = markup.match(/<button[^>]*aria-label="retired_orders table"[^>]*>/)?.[0];
    expect(checkbox).toBeDefined();
    expect(checkbox).not.toContain("disabled");
    expect(markup).toContain("Unavailable");
  });

  it("keeps a connection failure's safe message and retry when listed outside the panel", () => {
    const failedList = {
      parentKind: "SCHEMA", childKind: "RELATION", catalogName: null, schemaName: "orders", fetchedAt: null, options: [], totalCount: 0, page: 1, pageSize: 50,
      latestJob: { jobId: "83000000-0000-0000-0000-000000000007", parentKind: "SCHEMA", childKind: "RELATION", catalogName: null, schemaName: "orders", state: "COMPLETED_WITH_ERRORS", requestedAt: "2026-08-21T01:02:03Z", terminalAt: "2026-08-21T01:03:03Z", failureCode: "AUTHENTICATION_FAILED", safeMessage: "Warehouse authentication failed" },
    };
    const markup = renderOptionList(failedList);
    expect(markup).toContain("Warehouse authentication failed");
    expect(markup).toContain("Retry");
    expect(markup).not.toContain("No tables found.");
  });
});

it("allows Explorer option refresh without allowing Catalog selection changes", () => {
  const markup = renderOptionList({ parentKind: "SCHEMA", childKind: "RELATION", catalogName: null, schemaName: "orders", fetchedAt: "2026-09-29T00:00:00Z", options: [{ name: "orders_001", relationKind: "TABLE", available: true }], totalCount: 1, page: 1, pageSize: 50, latestJob: null }, { selectedSchemas: [] }, false);
  expect(markup.match(/<button[^>]*aria-label="orders_001 table"[^>]*>/)?.[0]).toContain("disabled");
  expect(markup.match(/<button[^>]*aria-label="Refresh tables in orders"[^>]*>/)?.[0]).not.toContain('disabled=""');
});
