import type { ReactNode } from "react";
import { Eye, Table as TableIcon } from "lucide-react";
import { StateBlock } from "@/components/ui/StateBlock";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import type { RelationSummary } from "@/lib/types/catalog";
import { formatLastRefreshed } from "@/lib/catalog/last-refreshed";
import { isViewKind, lastRefreshedLabel, relationKindLabel } from "@/components/catalog/catalogCopy";
import {
  ColumnHeader,
  ColumnPagination,
  ColumnSearch,
  ColumnShell,
  NodeLoadingIndicator,
} from "./ColumnChrome";

interface RelationColumnProps {
  width: string | null;
  back: ReactNode;
  notice: ReactNode;
  relations: RelationSummary[];
  totalCount: number;
  page: number;
  pageSize: number;
  loading: boolean;
  query: string;
  onQueryChange: (query: string) => void;
  onPageChange: (page: number) => void;
  selectedRelationName: string | null;
  onSelectRelation: (relation: RelationSummary) => void;
  loadingRelation: (relation: RelationSummary) => boolean;
  failedRelation: (relation: RelationSummary) => string | null;
  schemaSelected: boolean;
  // False for a selected schema that no refresh has loaded yet.
  schemaLoaded: boolean;
}

export function RelationColumn({
  width,
  back,
  notice,
  relations,
  totalCount,
  page,
  pageSize,
  loading,
  query,
  onQueryChange,
  onPageChange,
  selectedRelationName,
  onSelectRelation,
  loadingRelation,
  failedRelation,
  schemaSelected,
  schemaLoaded,
}: RelationColumnProps) {
  const scrollbar = useTransientScrollbar();
  if (!schemaSelected) {
    return (
      <ColumnShell width={width}>
        {back}
        <ColumnHeader label="Tables" count={null} updating={false} />
        <div className="flex flex-1 items-center justify-center px-6">
          <StateBlock kind="empty" layout="inline" icon={TableIcon} title="Select a schema to list its tables" />
        </div>
      </ColumnShell>
    );
  }

  return (
    <ColumnShell width={width}>
      {back}
      <ColumnHeader label="Tables" count={loading && relations.length === 0 ? null : totalCount} updating={loading} />
      {notice}
      <ColumnSearch value={query} onChange={onQueryChange} placeholder="Search tables" />
      <div onScroll={scrollbar.onScroll} className={`min-h-0 flex-1 overflow-y-auto ${scrollbar.className}`}>
        {loading && relations.length === 0 ? (
          <StateBlock kind="loading" layout="inline" title="Loading tables…" />
        ) : relations.length === 0 ? (
          <StateBlock kind="empty" layout="inline" icon={TableIcon} title={query ? `No tables match "${query}"` : schemaLoaded ? "No tables in this schema" : "Not loaded yet"} description={!query && !schemaLoaded ? "Refresh to load this schema's tables and columns." : undefined} />
        ) : (
          <ul className="divide-y divide-border">
            {relations.map((relation) => {
              const isLoading = loadingRelation(relation);
              const failure = failedRelation(relation);
              const Icon = isViewKind(relation.kind) ? Eye : TableIcon;
              const selected = selectedRelationName === relation.name;
              return (
                <li key={`${relation.catalogName ?? ""}::${relation.schemaName}::${relation.name}`}>
                  <button
                    type="button"
                    aria-current={selected ? "true" : undefined}
                    onClick={() => onSelectRelation(relation)}
                    className={`relative flex w-full items-center gap-2.5 px-3 py-2.5 text-left transition-colors ${selected ? "bg-surface" : "hover:bg-surface/60"}`}
                  >
                    {selected && <span aria-hidden="true" className="absolute left-0 top-0 h-full w-[2px]" style={{ backgroundColor: "var(--accent-marker)" }} />}
                    <Icon aria-hidden="true" className="h-4 w-4 flex-shrink-0 text-foreground-secondary" />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-baseline justify-between gap-2"><span className="type-control truncate text-foreground">{relation.name}</span><span className="type-status flex-shrink-0 text-foreground-secondary">{relationKindLabel(relation.kind)}</span></div>
                      <div className={`type-status mt-0.5 flex items-center gap-1.5 tabular-nums ${failure ? "text-error" : "text-foreground-secondary"}`}>
                        <span className="flex-shrink-0">{relation.columnCount} {relation.columnCount === 1 ? "column" : "columns"}</span>
                        <span aria-hidden="true" className="text-foreground-secondary/40">·</span>
                        <span className="truncate">{failure ?? (isLoading ? "Loading metadata…" : lastRefreshedLabel(formatLastRefreshed(relation.lastRefreshedAt)))}</span>
                      </div>
                    </div>
                    <NodeLoadingIndicator loading={isLoading} />
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
      <ColumnPagination page={page} pageSize={pageSize} totalCount={totalCount} onPageChange={onPageChange} />
    </ColumnShell>
  );
}
