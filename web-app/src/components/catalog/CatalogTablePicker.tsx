import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { Eye, Search, Table as TableIcon } from "lucide-react";
import { catalogWorkspaceAPI } from "@/api/catalog";
import { isViewKind } from "@/components/catalog/catalogCopy";
import { buttonClassName, type ButtonVariant } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { qualifiedTableName } from "@/lib/catalog/catalog-links";
import { queryKeys } from "@/lib/query/keys";
import type { CatalogTableSearchHit } from "@/lib/types/catalog";

const RESULT_LIMIT = 50;
const SEARCH_DEBOUNCE_MS = 200;
const PANEL_WIDTH = 420;
const VIEWPORT_MARGIN = 16;

/** How far the panel's left edge moves from the trigger's so the whole panel stays within the viewport margins. */
export function panelOffsetWithinViewport(triggerLeft: number, viewportWidth: number): number {
  const panelWidth = Math.min(PANEL_WIDTH, viewportWidth - VIEWPORT_MARGIN * 2);
  const left = Math.max(VIEWPORT_MARGIN, Math.min(triggerLeft, viewportWidth - VIEWPORT_MARGIN - panelWidth));
  return left - triggerLeft;
}

export interface CatalogTablePickerProps {
  // Shown on the trigger button, e.g. "Choose table" or "Change".
  triggerLabel: string;
  // Accessible name of the trigger when its visible label is not enough, e.g. "Change source table".
  triggerAriaLabel?: string;
  triggerVariant?: ButtonVariant;
  // Limits results to one connector's loaded tables.
  connectorId?: string | null;
  // Hides tables of these connectors, e.g. ones a model already uses.
  excludeConnectorIds?: readonly string[];
  disabled?: boolean;
  onSelect: (hit: CatalogTableSearchHit) => void;
}

// A search popover over the loaded tables of every connector, using the same table search as Catalog's Find table.
export function CatalogTablePicker({ triggerLabel, triggerAriaLabel, triggerVariant = "secondary", connectorId = null, excludeConnectorIds = [], disabled = false, onSelect }: CatalogTablePickerProps) {
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);
  // The panel's left edge relative to the trigger, kept inside the viewport so it never widens the page.
  const [panelOffset, setPanelOffset] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listId = useId();

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedQuery(query.trim()), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    if (!open) return;
    inputRef.current?.focus({ preventScroll: true });
    const onPointerDown = (event: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const searchable = /[\p{L}\p{N}]/u.test(debouncedQuery);
  const resultsQuery = useQuery({
    queryKey: queryKeys.catalog.tableSearch(workspaceId, principalId ?? "", debouncedQuery),
    queryFn: ({ signal }) => catalogWorkspaceAPI.searchTables(workspaceId, debouncedQuery, RESULT_LIMIT, signal),
    enabled: open && Boolean(principalId) && searchable,
    placeholderData: (previous) => previous,
    retry: false,
  });
  const excluded = new Set(excludeConnectorIds);
  const results = (searchable ? resultsQuery.data ?? [] : [])
    .filter((hit) => (connectorId === null || hit.connectorId === connectorId) && !excluded.has(hit.connectorId));

  useEffect(() => {
    if (activeIndex >= results.length) setActiveIndex(0);
  }, [activeIndex, results.length]);

  const close = () => {
    setOpen(false);
    triggerRef.current?.focus();
  };
  const choose = (hit: CatalogTableSearchHit) => {
    onSelect(hit);
    setQuery("");
    close();
  };
  const handleKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      close();
    } else if (event.key === "ArrowDown") {
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
    <div ref={rootRef} className="relative inline-block">
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={triggerAriaLabel}
        disabled={disabled}
        onClick={() => {
          const bounds = triggerRef.current?.getBoundingClientRect();
          if (bounds) setPanelOffset(panelOffsetWithinViewport(bounds.left, window.innerWidth));
          setOpen((current) => !current);
        }}
        className={buttonClassName(triggerVariant, "sm", "")}
      >
        <Search aria-hidden="true" />
        {triggerLabel}
      </button>
      {open ? (
        <div role="dialog" aria-label="Find a table" style={{ left: panelOffset }} className="absolute top-full z-50 mt-1 w-[min(420px,calc(100vw-32px))] rounded-[6px] border border-border bg-background shadow-overlay">
          <div className="flex items-center gap-2 border-b border-border px-3 py-2">
            <Search aria-hidden="true" className="h-4 w-4 text-foreground-secondary" />
            <input
              ref={inputRef}
              type="text"
              role="combobox"
              aria-expanded={results.length > 0}
              aria-controls={listId}
              aria-activedescendant={results[activeIndex] ? `${listId}-${activeIndex}` : undefined}
              aria-label="Find a table"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={handleKey}
              placeholder="Find a table or column"
              className="w-full bg-transparent type-control text-foreground placeholder:text-foreground-secondary focus:outline-none"
            />
          </div>
          <div className="max-h-72 overflow-y-auto py-1">
            {!searchable ? (
              <p className="px-3 py-4 type-meta text-foreground-secondary">Search loaded tables by table or column name.</p>
            ) : resultsQuery.isError && results.length === 0 ? (
              <StateBlock kind="error" layout="inline" title="Search is unavailable right now." />
            ) : resultsQuery.isPending ? (
              <StateBlock kind="loading" layout="inline" title="Searching tables…" />
            ) : results.length === 0 ? (
              <p className="px-3 py-4 type-meta text-foreground-secondary">No loaded tables match “{debouncedQuery}”.</p>
            ) : (
              <ul id={listId} role="listbox" aria-label="Matching tables" className="space-y-px px-1">
                {results.map((hit, index) => {
                  const Icon = isViewKind(hit.relationKind) ? Eye : TableIcon;
                  const active = index === activeIndex;
                  return (
                    <li key={`${hit.connectorId}:${qualifiedTableName(hit)}`} id={`${listId}-${index}`} role="option" aria-selected={active}>
                      <button
                        type="button"
                        tabIndex={-1}
                        onClick={() => choose(hit)}
                        onMouseEnter={() => setActiveIndex(index)}
                        className={`flex w-full items-start gap-2 rounded-sm px-2 py-1.5 text-left ${active ? "bg-surface" : ""}`}
                      >
                        <Icon aria-hidden="true" className="mt-0.5 h-4 w-4 flex-shrink-0 text-foreground-secondary" />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate type-control text-foreground">{hit.tableName}</span>
                          <span className="block truncate type-status text-foreground-secondary">{hit.connectorName} · {[hit.catalogName, hit.schemaName].filter(Boolean).join(".")}</span>
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}
