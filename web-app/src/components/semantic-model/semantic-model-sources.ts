import type { SemanticSourceRelation } from "@/api/semantic-model";
import type { CatalogTableSearchHit } from "@/lib/types/catalog";

export interface SourceTableDraft extends SemanticSourceRelation {
  id: string;
  // The picked table's connector name, shown until the connector list loads.
  connectorName?: string;
}

/** A Catalog table that opens a new model's editor, e.g. from Catalog's "Create semantic model". */
export interface SemanticModelSourceTable {
  connectorId: string;
  catalog: string | null;
  schema: string;
  relation: string;
}

function newSourceId(): string {
  return `source-${crypto.randomUUID()}`;
}

export function sourceTableFromHit(hit: CatalogTableSearchHit): SourceTableDraft {
  return {
    id: newSourceId(),
    connectorId: hit.connectorId,
    connectorName: hit.connectorName,
    ...(hit.catalogName ? { catalog: hit.catalogName } : {}),
    schema: hit.schemaName,
    relation: hit.tableName,
  };
}

export function sourceTableFromCatalog(table: SemanticModelSourceTable): SourceTableDraft {
  return {
    id: newSourceId(),
    connectorId: table.connectorId,
    ...(table.catalog ? { catalog: table.catalog } : {}),
    schema: table.schema,
    relation: table.relation,
  };
}

export function sourceTablesFromDefinition(relations: readonly SemanticSourceRelation[]): SourceTableDraft[] {
  return relations.map((relation) => ({ ...relation, id: newSourceId() }));
}

/** Replaces one source with the picked table, keeping its place and identity. */
export function replaceSourceTable(sources: readonly SourceTableDraft[], id: string, hit: CatalogTableSearchHit): SourceTableDraft[] {
  return sources.map((source) => (source.id === id ? { ...sourceTableFromHit(hit), id } : source));
}

/** Removes a source unless it is the last one. */
export function removeSourceTable(sources: readonly SourceTableDraft[], id: string): SourceTableDraft[] {
  return sources.length > 1 ? sources.filter((source) => source.id !== id) : [...sources];
}

/** Connectors that already have a source, other than `exceptId`'s own; the picker hides their tables. */
export function connectorsInUse(sources: readonly SourceTableDraft[], exceptId: string | null): string[] {
  return sources.filter((source) => source.id !== exceptId).map((source) => source.connectorId);
}

export function sourceRelationForSave(source: SourceTableDraft): SemanticSourceRelation {
  return {
    connectorId: source.connectorId,
    ...(source.catalog ? { catalog: source.catalog } : {}),
    schema: source.schema,
    relation: source.relation,
  };
}

/** The source's Connector name, current when the Connector is loaded and as picked otherwise. */
export function sourceConnectorName(source: SourceTableDraft, connectors: ReadonlyArray<{ id: string; name: string }>): string {
  return connectors.find((connector) => connector.id === source.connectorId)?.name ?? source.connectorName ?? "Unknown connector";
}
