import { describe, expect, it } from "vitest";
import {
  catalogHref,
  configuredCatalogName,
  connectorEditHref,
  exploreTableHref,
  newSemanticModelFromTableHref,
  safeReturnTo,
  tableAddressFromQualifiedName,
} from "@/lib/catalog/catalog-links";

const WORKSPACE = "workspace-1";

describe("catalog links", () => {
  it("keeps the browse location in the Catalog URL and drops a table without its schema", () => {
    expect(catalogHref(WORKSPACE, { connector: "c1", catalog: "FINANCE", schema: "billing", table: "invoices" }))
      .toBe("/workspaces/workspace-1/catalog?connector=c1&catalog=FINANCE&schema=billing&table=invoices");
    expect(catalogHref(WORKSPACE, { connector: "c1", table: "invoices", choose: true }))
      .toBe("/workspaces/workspace-1/catalog?connector=c1&choose=1");
  });

  it("hands a table to Explore and the semantic model editor with the same address", () => {
    const table = { connectorId: "c1", catalogName: null, schemaName: "billing", tableName: "invoices" };
    expect(exploreTableHref(WORKSPACE, table)).toBe("/workspaces/workspace-1/explore?connector=c1&schema=billing&table=invoices");
    expect(newSemanticModelFromTableHref(WORKSPACE, table)).toBe("/workspaces/workspace-1/semantic-models/new?connector=c1&schema=billing&table=invoices");
  });

  it("splits a lineage table name only when the connector depth makes it unambiguous", () => {
    expect(tableAddressFromQualifiedName("c1", "POSTGRESQL", "billing.invoices")).toEqual({ connectorId: "c1", catalogName: null, schemaName: "billing", tableName: "invoices" });
    expect(tableAddressFromQualifiedName("c1", "SNOWFLAKE", "FINANCE.billing.invoices")).toEqual({ connectorId: "c1", catalogName: "FINANCE", schemaName: "billing", tableName: "invoices" });
    expect(tableAddressFromQualifiedName("c1", "POSTGRESQL", "billing.invoices.v2")).toBeNull();
  });

  it("pre-checks the catalog a connector names in its settings", () => {
    expect(configuredCatalogName({ type: "SNOWFLAKE", properties: { account: "a", warehouse: "w", database: "FINANCE" } })).toBe("FINANCE");
    expect(configuredCatalogName({ type: "BIGQUERY", properties: { projectId: "northstar-prod", location: "US" } })).toBe("northstar-prod");
    expect(configuredCatalogName({ type: "DATABRICKS", properties: { host: "h", warehouseId: "abc" } })).toBeNull();
    expect(configuredCatalogName({ type: "POSTGRESQL", properties: { host: "h", port: 5432, database: "analytics" } })).toBeNull();
  });

  it("returns only to a path in the same workspace after editing a connector", () => {
    const back = "/workspaces/workspace-1/catalog?connector=c1&choose=1";
    expect(safeReturnTo(WORKSPACE, back)).toBe(back);
    expect(safeReturnTo(WORKSPACE, "https://evil.example/workspaces/workspace-1/catalog")).toBeNull();
    expect(safeReturnTo(WORKSPACE, "//evil.example/workspaces/workspace-1/catalog")).toBeNull();
    expect(safeReturnTo(WORKSPACE, "/workspaces/workspace-2/catalog")).toBeNull();
    expect(safeReturnTo(WORKSPACE, undefined)).toBeNull();
    expect(connectorEditHref(WORKSPACE, "c1", "rotate", back)).toBe(`/workspaces/workspace-1/connectors?connector=c1&mode=rotate&returnTo=${encodeURIComponent(back)}`);
  });
});
