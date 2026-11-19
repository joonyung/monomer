import { useId, useState } from "react";
import type { SemanticSourceRelation } from "@/api/semantic-model";
import { Button } from "@/components/ui/Button";
import { tableCatalogHref } from "@/lib/catalog/catalog-links";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";
import { Link } from "@/lib/navigation";
import { sourceRelationLabel } from "@/lib/utils/semantic-model-utils";

// Names a model's source table as "Connector · schema.table" and opens that table in Catalog.
export function SourceTableLink({
  workspaceId,
  source,
  connectors,
  className,
}: {
  workspaceId: string;
  source: SemanticSourceRelation;
  connectors: ReadonlyArray<{ id: string; name: string }>;
  className: string;
}) {
  return (
    <Link
      href={tableCatalogHref(workspaceId, {
        connectorId: source.connectorId,
        catalogName: source.catalog ?? null,
        schemaName: source.schema,
        tableName: source.relation,
      })}
      className={`${SEMANTIC_LINK_CLASS} focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-2 focus-visible:outline-[var(--focus-ring)] ${className}`}
    >
      {sourceRelationLabel(source, connectors)}
    </Link>
  );
}

export function SourceTableDisclosure({
  workspaceId,
  sources,
  connectors,
}: {
  workspaceId: string;
  sources: readonly SemanticSourceRelation[];
  connectors: ReadonlyArray<{ id: string; name: string }>;
}) {
  const [expanded, setExpanded] = useState(false);
  const disclosureId = useId();
  const connectorName = (id: string) => connectors.find((connector) => connector.id === id)?.name ?? id;
  const [first, ...remaining] = [...sources].sort((left, right) => connectorName(left.connectorId).localeCompare(connectorName(right.connectorId), "en"));
  if (!first) return null;

  return (
    <div className="min-w-0 space-y-1">
      <SourceTableLink workspaceId={workspaceId} source={first} connectors={connectors} className="break-words text-foreground" />
      {remaining.length > 0 && (
        <>
          <div>
            <Button variant="ghost" size="sm" className="type-meta text-foreground-secondary" aria-expanded={expanded} aria-controls={disclosureId} onClick={() => setExpanded((current) => !current)}>
              Also on {remaining.length} more {remaining.length === 1 ? "connector" : "connectors"}
            </Button>
          </div>
          <div id={disclosureId} hidden={!expanded} className="space-y-1">
            {remaining.map((source) => (
              <SourceTableLink key={source.connectorId} workspaceId={workspaceId} source={source} connectors={connectors} className="block break-words text-foreground" />
            ))}
          </div>
        </>
      )}
    </div>
  );
}
