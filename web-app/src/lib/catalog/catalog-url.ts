import type { CatalogListRefreshParentKind } from "@/lib/types/catalog";
import type { CatalogSearch } from "@/lib/route-search";

export const CATALOG_URL_KEYS = ["connector", "catalog", "schema", "table", "choose", "ingest", "refresh", "refreshCatalog", "refreshSchema"] as const;

export type LinkedRunSearch = Pick<CatalogSearch, "ingest" | "refresh" | "refreshCatalog" | "refreshSchema">;

// The run an Overview link points at stays in the URL while the page settles its browse scope.
export function linkedRunSearch(search: CatalogSearch): LinkedRunSearch {
  return { ingest: search.ingest, refresh: search.refresh, refreshCatalog: search.refreshCatalog, refreshSchema: search.refreshSchema };
}

// Replaces the Catalog keys of the current URL and keeps the hosting route's other parameters.
export function catalogSearchHref(pathname: string, currentSearch: string, next: CatalogSearch): string {
  const params = new URLSearchParams(currentSearch);
  CATALOG_URL_KEYS.forEach((key) => params.delete(key));
  CATALOG_URL_KEYS.forEach((key) => {
    const value = next[key];
    if (value) params.set(key, String(value));
  });
  const query = params.toString();
  return `${pathname}${query ? `?${query}` : ""}`;
}

export interface ListRefreshTarget {
  parentKind: CatalogListRefreshParentKind;
  catalogName: string | null;
  schemaName: string | null;
}

// A linked list refresh is read at the level the link names, never at the catalog being browsed.
export function linkedListRefreshTarget(search: LinkedRunSearch): ListRefreshTarget {
  const catalogName = search.refreshCatalog ?? null;
  const schemaName = search.refreshSchema ?? null;
  return { parentKind: schemaName ? "SCHEMA" : catalogName ? "CATALOG" : "CONNECTOR", catalogName, schemaName };
}
