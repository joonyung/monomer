import { useEffect, useState } from "react";
import * as RadixDialog from "@radix-ui/react-dialog";
import { useQuery } from "@tanstack/react-query";
import { Eye, Search, Table as TableIcon } from "lucide-react";
import { catalogWorkspaceAPI } from "@/api/catalog";
import { isViewKind } from "@/components/catalog/catalogCopy";
import { DIALOG_OVERLAY_CLASS } from "@/components/ui/Dialog";
import { StateBlock } from "@/components/ui/StateBlock";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { qualifiedTableName } from "@/lib/catalog/catalog-links";
import { queryKeys } from "@/lib/query/keys";
import type { CatalogTableSearchHit } from "@/lib/types/catalog";

const RESULT_LIMIT = 20;
const SEARCH_DEBOUNCE_MS = 200;

interface FindTableDialogProps {
  open: boolean;
  onClose: () => void;
  onSelect: (hit: CatalogTableSearchHit) => void;
}

// True when "/" was typed for the page rather than into a field.
export function isFindTableShortcut(event: KeyboardEvent): boolean {
  if (event.key !== "/" || event.metaKey || event.ctrlKey || event.altKey) return false;
  const target = event.target as HTMLElement | null;
  if (!target) return true;
  const tag = target.tagName;
  return !(tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable);
}

// Searches the loaded tables of every connector by table and column name; choosing one opens it in Catalog.
export function FindTableDialog({ open, onClose, onSelect }: FindTableDialogProps) {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);

  useEffect(() => {
    if (!open) return;
    setQuery("");
    setDebouncedQuery("");
    setActiveIndex(0);
  }, [open]);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedQuery(query.trim()), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [query]);

  const searchable = /[\p{L}\p{N}]/u.test(debouncedQuery);
  const resultsQuery = useQuery({
    queryKey: queryKeys.catalog.tableSearch(workspaceId, principalId ?? "", debouncedQuery),
    queryFn: ({ signal }) => catalogWorkspaceAPI.searchTables(workspaceId, debouncedQuery, RESULT_LIMIT, signal),
    enabled: open && Boolean(principalId) && searchable,
    placeholderData: (previous) => previous,
    retry: false,
  });
  const results = searchable ? resultsQuery.data ?? [] : [];

  useEffect(() => {
    if (activeIndex >= results.length) setActiveIndex(0);
  }, [activeIndex, results.length]);

  const choose = (hit: CatalogTableSearchHit) => {
    onSelect(hit);
    onClose();
  };

  const handleKey = (event: React.KeyboardEvent) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveIndex((index) => (index + 1) % Math.max(results.length, 1));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((index) => (index - 1 + results.length) % Math.max(results.length, 1));
    } else if (event.key === "Enter") {
      event.preventDefault();
      const hit = results[activeIndex];
      if (hit) choose(hit);
    }
  };

  return (
    <RadixDialog.Root open={open} onOpenChange={(next) => { if (!next) onClose(); }}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className={DIALOG_OVERLAY_CLASS} />
        <RadixDialog.Content
          aria-describedby={undefined}
          data-testid="find-table-dialog"
          className="fixed left-1/2 top-[12vh] z-50 w-[calc(100vw-24px)] max-w-xl -translate-x-1/2 rounded-md border border-border bg-background shadow-modal outline-none"
        >
          <RadixDialog.Title className="sr-only">Find table</RadixDialog.Title>
          <div className="flex items-center gap-2 border-b border-border px-3 py-3">
            <Search aria-hidden="true" className="h-4 w-4 text-foreground-secondary" />
            <input
              type="text"
              role="combobox"
              aria-expanded={results.length > 0}
              aria-controls="find-table-results"
              aria-activedescendant={results[activeIndex] ? `find-table-result-${activeIndex}` : undefined}
              aria-label="Find table"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={handleKey}
              placeholder="Find a table or column"
              className="w-full bg-transparent type-body text-foreground placeholder:text-foreground-secondary focus:outline-none"
            />
            <kbd className="rounded-sm border border-border bg-surface px-1.5 py-0.5 type-status text-foreground-secondary">ESC</kbd>
          </div>

          <div className="max-h-[60vh] overflow-y-auto py-1.5">
            {!searchable ? (
              <p className="px-4 py-8 text-center type-control text-foreground-secondary">Search the loaded tables of every connector by table or column name.</p>
            ) : resultsQuery.isError && results.length === 0 ? (
              <StateBlock kind="error" layout="inline" title="Search is unavailable right now." description={resultsQuery.error instanceof Error ? resultsQuery.error.message : undefined} />
            ) : resultsQuery.isPending ? (
              <StateBlock kind="loading" layout="inline" title="Searching tables…" />
            ) : results.length === 0 ? (
              <p className="px-4 py-8 text-center type-control text-foreground-secondary">No loaded tables match “{debouncedQuery}”.</p>
            ) : (
              <ul id="find-table-results" role="listbox" aria-label="Matching tables" className="space-y-px px-1.5">
                {results.map((hit, index) => {
                  const Icon = isViewKind(hit.relationKind) ? Eye : TableIcon;
                  const active = index === activeIndex;
                  return (
                    <li key={`${hit.connectorId}:${qualifiedTableName(hit)}`} id={`find-table-result-${index}`} role="option" aria-selected={active}>
                      <button
                        type="button"
                        tabIndex={-1}
                        onClick={() => choose(hit)}
                        onMouseEnter={() => setActiveIndex(index)}
                        className={`flex w-full items-start gap-3 rounded-sm px-2.5 py-2 text-left ${active ? "bg-surface" : ""}`}
                      >
                        <Icon aria-hidden="true" className="mt-0.5 h-4 w-4 flex-shrink-0 text-foreground-secondary" />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate type-control text-foreground">{hit.tableName}</span>
                          <span className="block truncate type-status text-foreground-secondary">{hit.connectorName} · {[hit.catalogName, hit.schemaName].filter(Boolean).join(".")}</span>
                          {hit.matchedColumns.length > 0 ? <span className="block truncate type-status text-foreground-secondary">Columns: {hit.matchedColumns.join(", ")}</span> : null}
                        </span>
                        {active ? <kbd className="rounded-sm border border-border bg-surface px-1.5 py-0.5 type-status text-foreground-secondary">↵</kbd> : null}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          <div className="flex items-center justify-between border-t border-border px-3 py-2 type-status text-foreground-secondary">
            <span>Navigate with ↑↓, open with ↵</span>
            <span>Loaded tables only</span>
          </div>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
