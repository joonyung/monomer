import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CatalogManagerDialog } from "@/components/catalog/CatalogManagerDialog";
import { queryKeys } from "@/lib/query/keys";
import type { Connector } from "@/lib/types/connector";
import type { CatalogListOptionPage } from "@/lib/types/catalog";

const WORKSPACE_ID = "workspace-1";
const PRINCIPAL_ID = "principal-1";
const CONNECTOR_ID = "connector-1";

vi.mock("@/hooks/use-workspace", () => ({
  useWorkspaceId: () => WORKSPACE_ID,
  usePrincipalId: () => PRINCIPAL_ID,
}));

vi.mock("@radix-ui/react-dialog", () => {
  const Primitive = ({ children, ...props }: { children?: ReactNode } & Record<string, unknown>) => <div {...props}>{children}</div>;
  return { Root: Primitive, Portal: Primitive, Overlay: Primitive, Content: Primitive, Title: Primitive, Description: Primitive, Close: Primitive, Trigger: Primitive };
});

function connector(): Connector {
  return {
    id: CONNECTOR_ID,
    workspaceId: WORKSPACE_ID,
    name: "Finance warehouse",
    type: "SNOWFLAKE",
    properties: { account: "fixture", warehouse: "compute", database: "analytics" },
    createdAtEpochMs: 1,
    updatedAtEpochMs: 1,
  };
}

function page(options: CatalogListOptionPage["options"], overrides: Partial<CatalogListOptionPage> = {}): CatalogListOptionPage {
  return {
    parentKind: "CONNECTOR",
    childKind: "CATALOG",
    catalogName: null,
    schemaName: null,
    fetchedAt: "2026-09-03T01:02:03Z",
    options,
    totalCount: options.length,
    page: 1,
    pageSize: 50,
    latestJob: null,
    ...overrides,
  };
}

function renderManager(options: CatalogListOptionPage): string {
  const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity } } });
  queryClient.setQueryData(
    queryKeys.catalog.connectorSelection(WORKSPACE_ID, PRINCIPAL_ID, CONNECTOR_ID),
    { selectedCatalogNames: ["catalog-0750"] },
  );
  queryClient.setQueryData(
    queryKeys.catalog.selectionOptions(WORKSPACE_ID, PRINCIPAL_ID, CONNECTOR_ID, "CONNECTOR", null, null, options.page, options.pageSize, ""),
    options,
  );
  return renderToStaticMarkup(
    <QueryClientProvider client={queryClient}>
      <CatalogManagerDialog open connector={connector()} canManage onClose={() => undefined} onSaved={() => undefined} />
    </QueryClientProvider>,
  );
}

afterEach(() => vi.restoreAllMocks());

describe("CatalogManagerDialog", () => {
  it("renders one server page instead of materializing a large candidate list", () => {
    const markup = renderManager(page(
      Array.from({ length: 50 }, (_, index) => ({ name: `catalog-${String(index).padStart(4, "0")}`, relationKind: null, available: true })),
      { totalCount: 1_000 },
    ));

    expect(markup).toContain("catalog-0000");
    expect(markup).toContain("catalog-0049");
    expect(markup).not.toContain("catalog-0500");
    expect(markup).toContain("Page 1 of 20");
  });

  it("keeps selected names visible before discovery without claiming zero available", () => {
    const markup = renderManager(page([], { fetchedAt: null, totalCount: 0 }));
    expect(markup).toContain("catalog-0750");
    expect(markup).toContain("Not fetched");
    expect(markup).not.toContain("0 available");
  });

  it("labels absent selected names only when the complete saved snapshot confirms absence", () => {
    expect(renderManager(page([]))).toContain("Unavailable");
    expect(renderManager(page([], { fetchedAt: null }))).not.toContain("Unavailable");
    expect(renderManager(page([{ name: "catalog-0000", relationKind: null, available: true }], { totalCount: 1000 }))).not.toContain("Unavailable");
  });

  it("preserves selected names absent from the current result page", () => {
    const markup = renderManager(page([{ name: "catalog-0000", relationKind: null, available: true }], { totalCount: 1000 }));
    expect(markup).toContain("catalog-0750");
    expect(markup).toContain("1 catalog selected");
  });
});
