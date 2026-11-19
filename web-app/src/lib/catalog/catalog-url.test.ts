import { describe, expect, it } from "vitest";
import { catalogSearchHref, linkedListRefreshTarget, linkedRunSearch } from "@/lib/catalog/catalog-url";

describe("catalog URL", () => {
  it("keeps an Overview list refresh link while the page fills in the first catalog", () => {
    const linked = { connector: "c1", refresh: "job-1" };
    const href = catalogSearchHref("/workspaces/w1/catalog", "?connector=c1&refresh=job-1", { connector: "c1", catalog: "analytics", ...linkedRunSearch(linked) });
    expect(href).toBe("/workspaces/w1/catalog?connector=c1&catalog=analytics&refresh=job-1");
  });

  it("replaces only Catalog keys and keeps the hosting route's parameters", () => {
    expect(catalogSearchHref("/dev/catalog-production-demo", "?role=OWNER&schema=old&table=t", { connector: "c1", schema: "billing", choose: 1 }))
      .toBe("/dev/catalog-production-demo?role=OWNER&connector=c1&schema=billing&choose=1");
  });

  it("reads a linked list refresh at the level the link names, not the browsed catalog", () => {
    expect(linkedListRefreshTarget({ refresh: "job-1" })).toEqual({ parentKind: "CONNECTOR", catalogName: null, schemaName: null });
    expect(linkedListRefreshTarget({ refresh: "job-1", refreshCatalog: "SALES" })).toEqual({ parentKind: "CATALOG", catalogName: "SALES", schemaName: null });
    expect(linkedListRefreshTarget({ refresh: "job-1", refreshCatalog: "analytics", refreshSchema: "sales ops" })).toEqual({ parentKind: "SCHEMA", catalogName: "analytics", schemaName: "sales ops" });
  });
});
