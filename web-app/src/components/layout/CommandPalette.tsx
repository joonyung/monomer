import { useEffect, useId, useMemo, useState } from "react";
import * as RadixDialog from "@radix-ui/react-dialog";
import { useQuery } from "@tanstack/react-query";
import { isLoadErrorRetryable } from "@/lib/api/load-error";
import { LoaderCircle, Search } from "lucide-react";
import { useRouter } from "@/lib/navigation";
import { useOptionalWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { useAuthStore } from "@/lib/auth/store";
import { canOpenWorkspaceSettings } from "@/components/settings/workspaceSettingsModel";
import { DIALOG_OVERLAY_CLASS } from "@/components/ui/Dialog";
import { workspaceSearchAPI } from "@/api/workspace-search";
import { pagesAPI } from "@/api/pages";
import { queryKeys } from "@/lib/query/keys";
import {
  RESOURCE_RESULT_LIMIT,
  filterCommands,
  isSearchableQuery,
  matchingPages,
  resourceSearchQuery,
  resourceSections,
  workspaceCommands,
  type PaletteItem,
  type PaletteSection,
} from "./commandPaletteModel";

interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
}

const SEARCH_DEBOUNCE_MS = 200;

// Go to a workspace destination, or find a dashboard, page, metric, semantic model, or table by name.
export function CommandPalette({ open, onClose }: CommandPaletteProps) {
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);
  const router = useRouter();
  const listboxId = useId();
  const workspaceContext = useOptionalWorkspaceRouteContext();
  const principalId = useAuthStore((state) => state.user?.id ?? null);
  const organizationMemberships = useAuthStore((state) => state.organizationMemberships);
  const workspaceId = workspaceContext?.workspaceId ?? null;
  const commands = useMemo(
    () => workspaceContext
      ? workspaceCommands(
          workspaceContext.workspaceId,
          canOpenWorkspaceSettings(
            workspaceContext.access.role,
            organizationMemberships.find((membership) => membership.organization.id === workspaceContext.organizationId)?.role ?? null,
          ),
        )
      : [],
    [workspaceContext, organizationMemberships],
  );

  useEffect(() => {
    if (!open) return;
    setQuery("");
    setDebouncedQuery("");
    setActiveIndex(0);
  }, [open]);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedQuery(resourceSearchQuery(query)), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [query]);

  const searchable = Boolean(workspaceId && principalId) && isSearchableQuery(debouncedQuery);
  const resourcesQuery = useQuery({
    queryKey: queryKeys.search.resources(workspaceId ?? "", principalId ?? "", debouncedQuery),
    queryFn: ({ signal }) => workspaceSearchAPI.search(workspaceId as string, debouncedQuery, RESOURCE_RESULT_LIMIT, signal),
    enabled: open && searchable,
    placeholderData: (previous) => previous,
    retry: false,
  });
  const pagesQuery = useQuery({
    queryKey: queryKeys.pages.directory(workspaceId ?? "", principalId ?? ""),
    queryFn: ({ signal }) => pagesAPI.getTree(workspaceId as string, signal),
    enabled: open && searchable,
    staleTime: 30_000,
    retry: false,
  });

  const sections = useMemo<PaletteSection[]>(() => {
    const goTo = filterCommands(commands, query);
    const resources = searchable && workspaceId
      ? resourceSections(
          workspaceId,
          resourcesQuery.data ?? null,
          matchingPages(pagesQuery.data?.pages ?? [], debouncedQuery, RESOURCE_RESULT_LIMIT),
        )
      : [];
    return [...resources, ...(goTo.length > 0 ? [{ id: "go-to" as const, title: "Go to", items: goTo }] : [])];
  }, [commands, query, searchable, workspaceId, resourcesQuery.data, pagesQuery.data, debouncedQuery]);
  const items = useMemo(() => sections.flatMap((section) => section.items), [sections]);
  const searching = searchable && (resourcesQuery.isFetching || pagesQuery.isFetching);
  const searchFailed = searchable && (resourcesQuery.isError || pagesQuery.isError);
  const searchRetryable = (!resourcesQuery.isError || isLoadErrorRetryable(resourcesQuery.error)) && (!pagesQuery.isError || isLoadErrorRetryable(pagesQuery.error));
  const settling = resourceSearchQuery(query) !== debouncedQuery && isSearchableQuery(query);

  useEffect(() => {
    if (activeIndex >= items.length) setActiveIndex(0);
  }, [items.length, activeIndex]);

  const handleSelect = (item: PaletteItem) => {
    router.push(item.href);
    onClose();
  };

  const handleKey = (event: React.KeyboardEvent) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveIndex((index) => (index + 1) % Math.max(items.length, 1));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((index) => (index - 1 + items.length) % Math.max(items.length, 1));
    } else if (event.key === "Enter") {
      event.preventDefault();
      const item = items[activeIndex];
      if (item) handleSelect(item);
    }
  };

  const optionId = (index: number) => `${listboxId}-option-${index}`;
  let flatIndex = -1;

  // Radix owns the focus trap, Escape, and focus return to whatever was focused before ⌘K.
  return (
    <RadixDialog.Root open={open} onOpenChange={(next) => { if (!next) onClose(); }}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className={DIALOG_OVERLAY_CLASS} />
        <RadixDialog.Content
          aria-describedby={undefined}
          className="fixed left-1/2 top-[12vh] z-50 w-[calc(100vw-24px)] max-w-xl -translate-x-1/2 rounded-md border border-border bg-background shadow-modal outline-none"
          style={{ fontFeatureSettings: '"ss01"' }}
        >
          <RadixDialog.Title className="sr-only">Search</RadixDialog.Title>
          <div className="flex items-center gap-2 border-b border-border px-3 py-3">
            {searching || settling ? (
              <LoaderCircle className="h-4 w-4 animate-spin text-foreground-secondary motion-reduce:animate-none" aria-hidden="true" />
            ) : (
              <Search className="h-4 w-4 text-foreground-secondary" aria-hidden="true" />
            )}
            <input
              type="text"
              role="combobox"
              aria-label="Search or go to"
              aria-expanded={items.length > 0}
              aria-controls={listboxId}
              aria-activedescendant={items.length > 0 ? optionId(activeIndex) : undefined}
              aria-autocomplete="list"
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setActiveIndex(0);
              }}
              onKeyDown={handleKey}
              placeholder="Search dashboards, pages, metrics, tables, or go to…"
              className="w-full bg-transparent type-body text-foreground caret-focus-ring placeholder:text-foreground-secondary focus:outline-none"
            />
          </div>

          <div className="max-h-[60vh] overflow-y-auto py-1.5">
            {searchFailed ? (
              <div role="alert" className="flex items-center justify-between gap-3 px-4 py-2 type-status text-foreground-secondary">
                <span>Search couldn&apos;t load. Go to still works.</span>
                {searchRetryable ? <button
                  type="button"
                  onClick={() => { void resourcesQuery.refetch(); void pagesQuery.refetch(); }}
                  className="type-control text-foreground underline underline-offset-4"
                >
                  Retry
                </button> : null}
              </div>
            ) : null}
            {items.length === 0 && !searchFailed ? (
              <div className="px-4 py-8 text-center type-control text-foreground-secondary" role="status">
                {searching || settling ? "Searching…" : "No matches."}
              </div>
            ) : (
              <div id={listboxId} role="listbox" aria-label="Results" className="space-y-1.5">
                {sections.map((section) => (
                  <div key={section.id} role="group" aria-labelledby={`${listboxId}-${section.id}`} data-testid={`palette-section-${section.id}`}>
                    <div id={`${listboxId}-${section.id}`} className="px-4 pb-1 pt-1.5 type-group text-label">
                      {section.title}
                    </div>
                    <ul className="space-y-px px-1.5" role="presentation">
                      {section.items.map((item) => {
                        flatIndex += 1;
                        const index = flatIndex;
                        const Icon = item.icon;
                        const active = index === activeIndex;
                        return (
                          <li
                            key={item.id}
                            id={optionId(index)}
                            role="option"
                            aria-selected={active}
                            onClick={() => handleSelect(item)}
                            onMouseMove={() => { if (!active) setActiveIndex(index); }}
                            className={`flex w-full cursor-default items-center gap-3 rounded-sm px-2.5 py-2 text-left ${active ? "bg-surface" : ""}`}
                          >
                            <span
                              className={`flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-sm ${active ? "bg-surface-hover text-foreground" : "bg-surface text-foreground-secondary"}`}
                            >
                              <Icon className="h-3.5 w-3.5" aria-hidden="true" />
                            </span>
                            <span className="min-w-0 flex-1">
                              <span className="block truncate type-control text-foreground">{item.label}</span>
                              <span className="block truncate type-status text-foreground-secondary">{item.hint}</span>
                            </span>
                            {active ? (
                              <kbd className="rounded-sm border border-border bg-surface px-1.5 py-0.5 type-status text-foreground-secondary" aria-hidden="true">
                                ↵
                              </kbd>
                            ) : null}
                          </li>
                        );
                      })}
                    </ul>
                  </div>
                ))}
              </div>
            )}
          </div>

        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
