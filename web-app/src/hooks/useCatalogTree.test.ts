import { describe, expect, it } from "vitest";
import { catalogListPositionInScope, retainPreviousPageInScope } from "@/hooks/useCatalogTree";
import { queryKeys } from "@/lib/query/keys";

const previousPage = { schemas: [{ schemaName: "analytics" }], totalCount: 51, page: 1, pageSize: 50 };

describe("Catalog tree list scope", () => {
  it("keeps the previous page only while paging or searching inside the same connector catalog", () => {
    const scope = queryKeys.catalog.schemasAll("workspace-1", "principal-1", "connector-1", "warehouse");

    expect(retainPreviousPageInScope(
      previousPage,
      queryKeys.catalog.schemas("workspace-1", "principal-1", "connector-1", "warehouse", 1, 50, ""),
      scope,
    )).toBe(previousPage);
    expect(retainPreviousPageInScope(
      previousPage,
      queryKeys.catalog.schemas("workspace-1", "principal-1", "connector-2", "warehouse", 1, 50, ""),
      scope,
    )).toBeUndefined();
    expect(retainPreviousPageInScope(
      previousPage,
      queryKeys.catalog.schemas("workspace-1", "principal-1", "connector-1", null, 1, 50, ""),
      scope,
    )).toBeUndefined();
    expect(retainPreviousPageInScope(previousPage, undefined, scope)).toBeUndefined();
  });

  it("drops the previous schema's tables when another schema is selected", () => {
    const scope = queryKeys.catalog.relationsAll("workspace-1", "principal-1", "connector-1", null, "sales");

    expect(retainPreviousPageInScope(
      previousPage,
      queryKeys.catalog.relations("workspace-1", "principal-1", "connector-1", null, "sales", 2, 50, "ord"),
      scope,
    )).toBe(previousPage);
    expect(retainPreviousPageInScope(
      previousPage,
      queryKeys.catalog.relations("workspace-1", "principal-1", "connector-1", null, "marketing", 1, 50, ""),
      scope,
    )).toBeUndefined();
  });

  it("starts a new scope at the first page without the previous search", () => {
    const position = { scope: "connector-1", page: 3, query: "orders" };

    expect(catalogListPositionInScope(position, "connector-1")).toBe(position);
    expect(catalogListPositionInScope(position, "connector-2")).toEqual({ scope: "connector-2", page: 1, query: "" });
  });
});
