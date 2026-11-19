import { describe, expect, it } from "vitest";
import type { CatalogTableSearchHit } from "@/lib/types/catalog";
import {
  connectorsInUse,
  removeSourceTable,
  replaceSourceTable,
  sourceRelationForSave,
  sourceTableFromCatalog,
  sourceTableFromHit,
  sourceConnectorName,
} from "@/components/semantic-model/semantic-model-sources";

function hit(connectorId: string, connectorName: string, catalogName: string | null, schemaName: string, tableName: string): CatalogTableSearchHit {
  return { connectorId, connectorName, connectorType: "POSTGRESQL", catalogName, schemaName, tableName, relationKind: "TABLE", matchedColumns: [] };
}

describe("semantic model source tables", () => {
  it("saves a picked table without its catalog when it has none", () => {
    const source = sourceTableFromHit(hit("connector-1", "Production Postgres", null, "billing", "invoices"));
    expect(sourceRelationForSave(source)).toEqual({ connectorId: "connector-1", schema: "billing", relation: "invoices" });
    expect(sourceConnectorName(source, [])).toBe("Production Postgres");
    expect(sourceConnectorName(source, [{ id: "connector-1", name: "Warehouse" }])).toBe("Warehouse");

    const fromCatalog = sourceTableFromCatalog({ connectorId: "connector-2", catalog: "main", schema: "sales", relation: "orders" });
    expect(sourceRelationForSave(fromCatalog)).toEqual({ connectorId: "connector-2", catalog: "main", schema: "sales", relation: "orders" });
  });

  it("adds, changes, and removes source tables but never the last one", () => {
    const first = sourceTableFromHit(hit("connector-1", "Postgres", null, "billing", "invoices"));
    const second = sourceTableFromHit(hit("connector-2", "Trino", "lake", "billing", "invoices"));
    const sources = [first, second];
    expect(connectorsInUse(sources, null)).toEqual(["connector-1", "connector-2"]);
    expect(connectorsInUse(sources, second.id)).toEqual(["connector-1"]);

    const changed = replaceSourceTable(sources, second.id, hit("connector-3", "Snowflake", "analytics", "public", "invoices"));
    expect(changed[1]).toMatchObject({ id: second.id, connectorId: "connector-3", catalog: "analytics", schema: "public" });
    expect(changed[0]).toBe(first);

    const remaining = removeSourceTable(changed, first.id);
    expect(remaining.map((source) => source.id)).toEqual([second.id]);
    expect(removeSourceTable(remaining, second.id)).toEqual(remaining);
  });
});
