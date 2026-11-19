import { formatRefreshTime } from "@/components/catalog/catalogCopy";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Code2,
  Copy,
  Database as DatabaseIcon,
  GitBranch,
  Play,
  Plus,
  Search,
} from "lucide-react";
import { lineageAPI, type LineageResource } from "@/api/lineage";
import type { RelationDetail } from "@/lib/types/catalog";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { relationKindLabel } from "@/components/catalog/catalogCopy";
import { buttonClassName } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { WorkStatus } from "@/components/ui/WorkStatus";
import { useClipboardCopy } from "@/hooks/useClipboardCopy";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import {
  exploreTableHref,
  lineageRelationHref,
  newSemanticModelFromTableHref,
  qualifiedTableName,
  tableCatalogHref,
  type TableAddress,
} from "@/lib/catalog/catalog-links";
import { Link } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { webPaths } from "@/lib/web-paths";

function tableAddress(connectorId: string, relation: RelationDetail): TableAddress {
  return { connectorId, catalogName: relation.catalogName ?? null, schemaName: relation.schemaName, tableName: relation.name };
}

function formatTimestamp(epochMs: number | null | undefined): string | null {
  if (!epochMs) return null;
  const date = new Date(epochMs);
  if (Number.isNaN(date.valueOf())) return null;
  return formatRefreshTime(date.toISOString());
}

// "Used by" lists the semantic models that read this table directly, from the Lineage resource.
function useSemanticModelsUsingTable(relation: RelationDetail | null): { models: LineageResource[]; loaded: boolean } {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const resourceId = relation?.lineageResourceRef?.id ?? null;
  const query = useQuery({
    queryKey: queryKeys.lineage.focus(workspaceId, principalId ?? "", "RELATION", resourceId ?? ""),
    queryFn: ({ signal }) => lineageAPI.getFocusCone(workspaceId, { kind: "RELATION", id: resourceId as string }, signal),
    enabled: Boolean(principalId && resourceId),
    retry: false,
  });
  if (!query.data || !resourceId) return { models: [], loaded: query.isSuccess };
  const dependents = new Set(query.data.edges
    .filter((edge) => edge.parent.kind === "RELATION" && edge.parent.id === resourceId && edge.dependent.kind === "SEMANTIC_MODEL")
    .map((edge) => edge.dependent.id));
  const models = query.data.descendants
    .filter((resource) => resource.resource.kind === "SEMANTIC_MODEL" && dependents.has(resource.resource.id))
    .sort((left, right) => left.label.localeCompare(right.label));
  return { models, loaded: true };
}

interface RelationDetailPanelProps {
  connectorId: string;
  relation: RelationDetail | null;
  loading?: boolean;
  loadingMetadata?: boolean;
  observedAt?: number | null;
}

export function RelationDetailPanel({
  connectorId,
  relation,
  loading = false,
  loadingMetadata = false,
  observedAt = null,
}: RelationDetailPanelProps) {
  const workspaceId = useWorkspaceId();
  const access = useWorkspaceAccess();
  const canExplore = access.hasRole("EXPLORER");
  const canModel = access.hasRole("EDITOR");
  const [columnSearch, setColumnSearch] = useState("");
  const nameCopy = useClipboardCopy();
  const usedBy = useSemanticModelsUsingTable(relation);

  if (!relation && !loading) {
    return (
      <div className="flex h-full w-full bg-background">
        <StateBlock
          kind="empty"
          layout="page"
          icon={DatabaseIcon}
          title="Select a table to see its columns"
          description="Columns, keys and the view definition appear here."
        />
      </div>
    );
  }

  if (loading && !relation) {
    return (
      <div className="flex h-full w-full bg-background">
        <StateBlock kind="loading" layout="page" title="Loading table…" />
      </div>
    );
  }

  if (!relation) return null;

  const address = tableAddress(connectorId, relation);
  const filteredColumns = relation.columns.filter((col) => {
    const q = columnSearch.toLowerCase();
    return (
      col.name.toLowerCase().includes(q) ||
      col.nativeType.toLowerCase().includes(q)
    );
  });

  const partitionKeys = new Set(relation.partitionKeys);
  const clusteringKeys = new Set(relation.clusteringKeys);
  const primaryKeyColumns = new Set(relation.primaryKey?.columns ?? []);
  const lineageHref = relation.lineageResourceRef ? lineageRelationHref(workspaceId, relation.lineageResourceRef.id) : null;
  const foreignKeysByColumn = new Map<string, { label: string; href: string | null }[]>();
  relation.foreignKeys.forEach((foreignKey) => foreignKey.columns.forEach((column, index) => {
    const targetColumn = foreignKey.referencedColumns[index] ?? foreignKey.referencedColumns.join(", ");
    const target: TableAddress = {
      connectorId: foreignKey.referencedConnectorId,
      catalogName: foreignKey.referencedCatalogName ?? null,
      schemaName: foreignKey.referencedSchemaName,
      tableName: foreignKey.referencedRelationName,
    };
    const sameTable = target.connectorId === connectorId && target.catalogName === address.catalogName
      && target.schemaName === address.schemaName && target.tableName === address.tableName;
    const label = `FK → ${qualifiedTableName(target)}.${targetColumn}`;
    const href = target.connectorId === connectorId && !sameTable ? tableCatalogHref(workspaceId, target) : null;
    foreignKeysByColumn.set(column, [...(foreignKeysByColumn.get(column) ?? []), { label, href }]);
  }));
  const observed = formatTimestamp(observedAt);

  return (
    <div
      className="relation-detail-panel flex h-full w-full flex-col bg-background"
      style={{ containerType: "inline-size" }}
    >
      <header className="flex-shrink-0 border-b border-border px-4 pt-5 pb-5 sm:px-6 sm:pt-6">
        <div className="min-w-0">
          <div className="type-status flex items-center gap-1.5 text-foreground-secondary">
            {relation.catalogName && (
              <>
                <span>{relation.catalogName}</span>
                <span className="text-foreground-secondary/40">/</span>
              </>
            )}
            <span>{relation.schemaName}</span>
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-2.5">
            <h2 className="type-section min-w-0 break-words text-foreground">
              {relation.name}
            </h2>
            {loadingMetadata && (
              <span className="type-status inline-flex items-center gap-1.5 rounded-[4px] border border-border bg-surface px-2 py-0.5 text-foreground-secondary">
                <WorkStatus state="RUNNING" labelDisplay="external" size="sm" />
                Loading metadata…
              </span>
            )}
          </div>
          <dl data-testid="relation-detail-facts" className="type-status mt-3 flex flex-wrap items-baseline gap-x-5 gap-y-1.5 tabular-nums">
            <div className="flex gap-1.5"><dt className="sr-only">Kind</dt><dd className="text-foreground">{relationKindLabel(relation.kind)}</dd></div>
            {relation.primaryKey?.name ? <div className="flex gap-1.5"><dt className="text-foreground-secondary">Primary key</dt><dd className="text-foreground">{relation.primaryKey.name}</dd></div> : null}
            {usedBy.models.length > 0 ? (
              <div className="flex gap-1.5">
                <dt className="text-foreground-secondary">Used by</dt>
                <dd className="text-foreground">
                  {usedBy.models.map((model, index) => (
                    <span key={model.resource.id}>
                      {index > 0 ? ", " : null}
                      <Link href={webPaths.semanticModel(workspaceId, model.resource.id)} className="text-foreground underline underline-offset-4">{model.label}</Link>
                    </span>
                  ))}
                </dd>
              </div>
            ) : null}
          </dl>
        </div>

        <div className="relation-detail-actions mt-4 flex flex-wrap items-center gap-2">
          {canExplore ? (
            <Link href={exploreTableHref(workspaceId, address)} className={buttonClassName("primary", "sm", "")}>
              <Play aria-hidden="true" />
              Query in Explore
            </Link>
          ) : null}
          {canModel ? (
            <Link href={newSemanticModelFromTableHref(workspaceId, address)} className={buttonClassName("secondary", "sm", "")}>
              <Plus aria-hidden="true" />
              Describe this table
            </Link>
          ) : null}
          {lineageHref ? (
            <Link href={lineageHref} className={buttonClassName("secondary", "sm", "")}>
              <GitBranch aria-hidden="true" />
              View lineage
            </Link>
          ) : null}
          <button type="button" onClick={() => void nameCopy.copy(qualifiedTableName(address))} className={buttonClassName("ghost", "sm", "")}>
            <Copy aria-hidden="true" />
            {nameCopy.status === "copied" ? "Copied" : "Copy name"}
          </button>
          <span role="status" className={nameCopy.status === "failed" ? "type-status text-error" : "sr-only"}>
            {nameCopy.status === "copied" ? "Table name copied" : nameCopy.status === "failed" ? "Couldn’t copy the table name" : ""}
          </span>
        </div>

        {(relation.partitionKeys.length > 0 || relation.clusteringKeys.length > 0) ? (
          <div className="mt-4 flex flex-wrap items-center gap-1.5">
            {relation.partitionKeys.length > 0 && <MetaPill label="Partitioned by" value={`${relation.partitionKeys.join(", ")}${relation.requiresPartitionFilter === true ? " · Filter required" : relation.requiresPartitionFilter === false ? " · Filter optional" : ""}`} />}
            {relation.clusteringKeys.length > 0 && <MetaPill label="Clustered by" value={relation.clusteringKeys.join(", ")} />}
            {observed && <span className="type-status text-foreground-secondary">Observed {observed}</span>}
          </div>
        ) : null}
      </header>

      <div className="flex-1 min-h-0 overflow-y-auto custom-scrollbar">
        {relation.viewDefinition && (
          <section className="border-b border-border px-6 py-5">
            <SectionLabel icon={Code2}>
              View definition
            </SectionLabel>
            <pre className="type-mono mt-2 max-h-64 overflow-auto rounded-md border border-border bg-surface px-3 py-2 text-foreground">
              {relation.viewDefinition}
            </pre>
          </section>
        )}

        <section className="px-6 py-5">
          <div className="flex items-center justify-between gap-3">
            <SectionLabel>
              Columns
              <span className="ml-2 tabular-nums text-foreground-secondary">
                {filteredColumns.length}
                {columnSearch && ` of ${relation.columns.length}`}
              </span>
            </SectionLabel>
            <div className="relative w-56">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-secondary" />
              <Input
                type="text"
                value={columnSearch}
                onChange={(e) => setColumnSearch(e.target.value)}
                placeholder="Filter columns"
                aria-label="Filter columns"
                className="pl-8"
              />
            </div>
          </div>

          {filteredColumns.length === 0 ? (
            <StateBlock kind="empty" layout="inline" icon={Search} title={`No columns match "${columnSearch}"`} />
          ) : (
            <div className="mt-3 overflow-x-auto rounded-[6px] border border-border">
              <div className="relation-columns-header grid min-w-[600px] grid-cols-[1.15fr_.8fr_56px_.9fr_1.25fr] gap-3 border-b border-border bg-surface px-3 py-2 type-group text-foreground-secondary"><span>Name</span><span>Type</span><span>Null</span><span>Default</span><span>Key</span></div>
              {filteredColumns.map((col) => {
                const keyParts: { label: string; href: string | null }[] = [
                  ...(primaryKeyColumns.has(col.name) ? [{ label: "PK", href: null }] : []),
                  ...(foreignKeysByColumn.get(col.name) ?? []),
                  ...(partitionKeys.has(col.name) ? [{ label: "Partition", href: null }] : []),
                  ...(clusteringKeys.has(col.name) ? [{ label: "Cluster", href: null }] : []),
                ];
                return (
                  <div
                    key={col.name}
                    className="relation-column-row grid min-w-[600px] grid-cols-[1.15fr_.8fr_56px_.9fr_1.25fr] items-start gap-3 border-b border-border-light px-3 py-2.5 type-meta last:border-b-0"
                  >
                    <span className="relation-column-name min-w-0">
                      <span className="block truncate text-foreground">{col.name}</span>
                    </span>
                    <span className="relation-column-type truncate text-foreground-secondary">{col.nativeType}</span>
                    <span className="relation-column-null text-foreground-secondary">{col.nullable ? "Yes" : "No"}</span>
                    <span className="relation-column-default truncate text-foreground-secondary">{col.defaultValue ?? "—"}</span>
                    <span className="relation-column-key min-w-0 text-foreground-secondary">
                      <span className="min-w-0 break-words">
                        {keyParts.length === 0 ? "—" : keyParts.map((part, index) => (
                          <span key={`${part.label}-${index}`}>
                            {index > 0 ? " · " : null}
                            {part.href ? <Link href={part.href} className="text-foreground underline underline-offset-4">{part.label}</Link> : part.label}
                          </span>
                        ))}
                      </span>
                    </span>
                  </div>
                );
              })}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

function SectionLabel({
  children,
  icon: Icon,
}: {
  children: React.ReactNode;
  icon?: React.ComponentType<{ className?: string }>;
}) {
  return (
    <div className="flex items-center gap-1.5">
      {Icon && <Icon className="h-3.5 w-3.5 text-foreground-secondary" />}
      <span className="type-group text-foreground-secondary">
        {children}
      </span>
    </div>
  );
}

function MetaPill({ label, value }: { label: string; value: string }) {
  return (
    <span className="type-status inline-flex items-center gap-1 rounded-sm border border-border bg-surface px-1.5 py-0.5 text-foreground-secondary">
      <span className="type-group">{label}</span>
      <span className="normal-case text-foreground">{value}</span>
    </span>
  );
}
