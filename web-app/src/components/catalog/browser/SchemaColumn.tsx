import { FolderTree } from "lucide-react";
import { StateBlock } from "@/components/ui/StateBlock";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import type { SchemaSummary } from "@/lib/types/catalog";
import { formatLastRefreshed } from "@/lib/catalog/last-refreshed";
import { lastRefreshedLabel } from "@/components/catalog/catalogCopy";
import {
  ColumnHeader,
  ColumnPagination,
  ColumnSearch,
  ColumnShell,
  NodeLoadingIndicator,
} from "./ColumnChrome";

interface SchemaColumnProps {
  width: string | null;
  schemas: SchemaSummary[];
  totalCount: number;
  page: number;
  pageSize: number;
  loading: boolean;
  query: string;
  onQueryChange: (query: string) => void;
  onPageChange: (page: number) => void;
  selectedSchemaName: string | null;
  markSelection: boolean;
  onSelectSchema: (schema: SchemaSummary) => void;
  loadingSchema: (schema: SchemaSummary) => boolean;
  failedSchema: (schema: SchemaSummary) => string | null;
  // Starts a refresh for schemas that were selected but never loaded; null when the viewer can't refresh.
  onRefresh: (() => void) | null;
}

export function SchemaColumn({
  width,
  schemas,
  totalCount,
  page,
  pageSize,
  loading,
  query,
  onQueryChange,
  onPageChange,
  selectedSchemaName,
  markSelection,
  onSelectSchema,
  loadingSchema,
  failedSchema,
  onRefresh,
}: SchemaColumnProps) {
  const scrollbar = useTransientScrollbar();
  return (
    <ColumnShell width={width}>
      <ColumnHeader label="Schemas" count={loading && schemas.length === 0 ? null : totalCount} updating={loading} />
      <ColumnSearch value={query} onChange={onQueryChange} placeholder="Search schemas" />
      <div onScroll={scrollbar.onScroll} className={`min-h-0 flex-1 overflow-y-auto ${scrollbar.className}`}>
        {loading && schemas.length === 0 ? (
          <StateBlock kind="loading" layout="inline" title="Loading schemas…" />
        ) : schemas.length === 0 ? (
          <StateBlock kind="empty" layout="inline" icon={FolderTree} title={query ? `No schemas match "${query}"` : "No schemas"} />
        ) : (
          <ul className="divide-y divide-border">
            {schemas.map((schema) => {
              const isLoading = loadingSchema(schema);
              const failure = failedSchema(schema);
              const selected = selectedSchemaName === schema.name;
              const notLoaded = !failure && !isLoading && schema.lastRefreshedAt === null;
              return (
                <li key={`${schema.catalogName ?? ""}::${schema.name}`} className={`relative flex items-center ${selected ? "bg-surface" : "hover:bg-surface/60"}`}>
                  <button
                    type="button"
                    aria-current={selected ? "true" : undefined}
                    onClick={() => onSelectSchema(schema)}
                    className="relative flex min-w-0 flex-1 items-center gap-2 px-3 py-2.5 text-left transition-colors"
                  >
                    {selected && markSelection && <span aria-hidden="true" className="absolute left-0 top-0 h-full w-[2px]" style={{ backgroundColor: "var(--accent-marker)" }} />}
                    <FolderTree className="h-4 w-4 flex-shrink-0 text-foreground-secondary" />
                    <span className="min-w-0 flex-1">
                      <span className="type-control block truncate text-foreground">{schema.name}</span>
                      <span className={`type-status mt-0.5 block truncate ${failure ? "text-error" : "text-foreground-secondary"}`}>
                        {failure ?? (isLoading ? "Loading metadata…" : notLoaded ? "Not loaded yet" : lastRefreshedLabel(formatLastRefreshed(schema.lastRefreshedAt)))}
                      </span>
                    </span>
                    <NodeLoadingIndicator loading={isLoading} />
                  </button>
                  {notLoaded && onRefresh ? (
                    <button type="button" onClick={onRefresh} aria-label={`Refresh to load ${schema.name}`} className="type-status mr-2 flex-shrink-0 rounded-[4px] px-1.5 py-1 text-foreground underline underline-offset-4">
                      Refresh
                    </button>
                  ) : null}
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
