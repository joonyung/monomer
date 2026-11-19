import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "@/lib/navigation";
import { usePathname } from "@/lib/navigation";
import {
  ChevronRight,
  FileCode2,
  FileText,
  LayoutDashboard,
  Folder,
  NotebookTabs,
  RotateCcw,
  Search,
} from "lucide-react";
import type { PageContentFormat } from "@/api/page-content";
import type {
  PagePlacementResponse,
  PageResponse,
  PagesTreeResponse,
} from "@/api/pages";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { useOptionalPagesDirectory } from "@/components/pages/PagesDirectoryProvider";
import {
  NewFolderButton,
  PageActionsButton,
} from "@/components/pages/PageMutationDialogs";
import {
  findPageAncestors,
  findPlacementLocation,
  indexPages,
} from "@/components/pages/pagesTree";
import { webPaths } from "@/lib/web-paths";
import styles from "./PagesNavigation.module.css";

type NavigationPage = Pick<PageResponse, "pageId" | "kind" | "displayTitle" | "title" | "description" | "documentFormat">;
type NavigationTree = Pick<PagesTreeResponse, "placements"> & { pages: NavigationPage[] };

interface ExpandedPagesState {
  folderIds: string[];
}

interface PagesNavigationViewProps {
  workspaceId: string;
  snapshot: NavigationTree | null;
  status: "idle" | "loading" | "ready" | "error";
  error: string | null;
  errorRetryable?: boolean;
  canEdit: boolean;
  canEditPage?: (pageId: string) => boolean;
  pathname: string;
  onRetry: () => void;
  // Readers outside a Workspace shell have no command palette, so their directory keeps its own
  // name filter. Members find Pages through Search.
  filterable: boolean;
}

function activePageId(pathname: string): string | null {
  if (pathname === "/dev/pages-demo") return null;
  const demoMatch = /^\/dev\/pages-demo\/([^/?#]+)(?:\/|$)/.exec(pathname);
  if (demoMatch) return decodeURIComponent(demoMatch[1]);
  const match = /^\/workspaces\/[^/]+\/pages\/([^/?#]+)(?:\/|$)/.exec(pathname);
  return match ? decodeURIComponent(match[1]) : null;
}

function storedExpansion(workspaceId: string): ExpandedPagesState {
  const fallback: ExpandedPagesState = { folderIds: [] };
  if (typeof window === "undefined") return fallback;
  try {
    const raw = window.sessionStorage.getItem(`monomer.pages.expanded.${workspaceId}`);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw) as Partial<ExpandedPagesState>;
    return {
      folderIds: Array.isArray(parsed.folderIds)
        ? parsed.folderIds.filter((value): value is string => typeof value === "string")
        : [],
    };
  } catch {
    return fallback;
  }
}

function expansionForRoute(
  workspaceId: string,
  snapshot: NavigationTree | null,
  pathname: string,
): ExpandedPagesState {
  const stored = storedExpansion(workspaceId);
  const pageId = activePageId(pathname);
  if (!pageId || !snapshot) return stored;
  const ancestors = findPageAncestors(snapshot.placements, pageId) ?? [];
  return {
    folderIds: Array.from(new Set([...stored.folderIds, ...ancestors])),
  };
}

function pageMatches(page: NavigationPage | undefined, query: string): boolean {
  if (!page) return false;
  const searchable = `${page.displayTitle} ${page.description ?? ""}`.toLocaleLowerCase();
  return searchable.includes(query);
}

function filterPlacements(
  placements: PagePlacementResponse[],
  pagesById: Map<string, NavigationPage>,
  query: string,
): PagePlacementResponse[] {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  if (!normalizedQuery) return placements;
  return placements.flatMap((placement) => {
    const page = pagesById.get(placement.pageId);
    if (pageMatches(page, normalizedQuery)) return [placement];
    const children = filterPlacements(placement.children, pagesById, normalizedQuery);
    return children.length > 0 ? [{ ...placement, children }] : [];
  });
}

function siblingPosition(
  placements: PagePlacementResponse[],
  pageId: string,
): { siblingIndex: number; siblingCount: number } {
  const location = findPlacementLocation(placements, pageId);
  if (!location) return { siblingIndex: 0, siblingCount: 1 };
  const siblings = location.parentPageId === null
    ? placements
    : findPlacementLocation(placements, location.parentPageId)?.placement.children ?? [];
  return { siblingIndex: location.index, siblingCount: siblings.length };
}

function DocumentFormatIcon({ format }: { format: PageContentFormat | null | undefined }) {
  if (format === "NOTEBOOK") return <NotebookTabs className="h-3.5 w-3.5 flex-none" aria-hidden="true" />;
  if (format === "HTML") return <FileCode2 className="h-3.5 w-3.5 flex-none" aria-hidden="true" />;
  return <FileText className="h-3.5 w-3.5 flex-none" aria-hidden="true" />;
}

export function PagesNavigation() {
  const pathname = usePathname();
  const { hasRole } = useWorkspaceAccess();
  const context = useOptionalPagesDirectory();
  const snapshot = context?.snapshot ?? null;
  const canEditPage = useCallback((_pageId: string) => hasRole("EXPLORER"), [hasRole]);
  const canEdit = hasRole("EXPLORER");
  if (!context?.workspaceId) return null;
  const {
    workspaceId,
    status,
    error,
    errorRetryable,
    retry,
  } = context;

  return (
    <PagesNavigationView
      key={workspaceId}
      workspaceId={workspaceId}
      snapshot={snapshot}
      status={status}
      error={error}
      errorRetryable={errorRetryable}
      canEdit={canEdit}
      canEditPage={canEditPage}
      pathname={pathname}
      onRetry={retry}
      filterable={false}
    />
  );
}

// The editor's new-folder control, beside the back control in the Pages context header.
export function PagesNavigationView({
  workspaceId,
  snapshot,
  status,
  error,
  errorRetryable = true,
  canEdit,
  canEditPage,
  pathname,
  onRetry,
  filterable,
}: PagesNavigationViewProps) {
  const scrollbar = useTransientScrollbar();
  const pageId = activePageId(pathname);
  const pagesBasePath = pathname.startsWith("/dev/pages-demo") ? "/dev/pages-demo" : webPaths.pages(workspaceId);
  const [expandedState, setExpandedState] = useState<ExpandedPagesState>(() =>
    expansionForRoute(workspaceId, snapshot, pathname),
  );
  const [query, setQuery] = useState("");
  const pagesById = useMemo(
    () => indexPages(snapshot?.pages ?? []),
    [snapshot],
  );

  useEffect(() => {
    const requiredExpansion = expansionForRoute(workspaceId, snapshot, pathname);
    setExpandedState((current) => ({
      folderIds: Array.from(new Set([...current.folderIds, ...requiredExpansion.folderIds])),
    }));
  }, [pathname, snapshot, workspaceId]);

  useEffect(() => {
    try {
      window.sessionStorage.setItem(
        `monomer.pages.expanded.${workspaceId}`,
        JSON.stringify(expandedState),
      );
    } catch {
      // Browser storage is optional. Disclosure remains usable in memory.
    }
  }, [expandedState, workspaceId]);

  const toggleFolder = (folderId: string) => {
    setExpandedState((current) => ({
      ...current,
      folderIds: current.folderIds.includes(folderId)
        ? current.folderIds.filter((candidate) => candidate !== folderId)
        : [...current.folderIds, folderId],
    }));
  };

  const filteredPlacements = useMemo(
    () => filterPlacements(snapshot?.placements ?? [], pagesById, query),
    [pagesById, query, snapshot?.placements],
  );
  const searching = query.trim().length > 0;

  return (
    <li className="flex min-h-0 flex-1 flex-col" data-testid="pages-navigation">
      {filterable ? (
        <div className="flex flex-none items-center px-1 pb-2">
          <div className="relative min-w-0 flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
            <Input
              type="search"
              aria-label="Search Pages"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search pages"
              className="pl-8"
            />
          </div>
        </div>
      ) : null}
      <ul onScroll={scrollbar.onScroll} className={`min-h-0 flex-1 space-y-px overflow-y-auto ${scrollbar.className}`}>
          {status === "loading" || status === "idle" ? (
            <li className="px-2 py-2 type-status text-foreground-secondary" role="status">
              Loading pages…
            </li>
          ) : status === "error" ? (
            <li className="px-2 py-2">
              <p className="truncate type-status text-error" title={error ?? "Pages unavailable"}>
                Pages unavailable
              </p>
              {errorRetryable ? <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={onRetry}
                className="mt-1"
              >
                <RotateCcw aria-hidden="true" />
                Retry
              </Button> : null}
            </li>
          ) : snapshot?.placements.length === 0 ? (
            <li className="px-2 py-2 type-status leading-4 text-foreground-secondary">
              {canEdit ? "No pages yet. Create a folder or upload a document." : "No pages yet."}
            </li>
          ) : filteredPlacements.length === 0 ? (
            <li className="px-2 py-6 text-center type-status text-foreground-secondary">No matching pages</li>
          ) : (
            filteredPlacements.map((placement) => (
              <PagesNavigationItem
                key={placement.pageId}
                placement={placement}
                page={pagesById.get(placement.pageId)}
                pagesById={pagesById}
                placements={snapshot?.placements ?? []}
                activePageId={pageId}
                expandedFolderIds={expandedState.folderIds}
                onToggleFolder={toggleFolder}
                canEdit={canEdit}
                canEditPage={canEditPage}
                parentPageId={null}
                pagesBasePath={pagesBasePath}
                forceExpanded={searching}
              />
            ))
          )}
          {canEdit && !filterable && snapshot ? (
            <li>
              <NewFolderButton parentPageId={null} triggerLabel="New top-level folder" appearance="row" />
            </li>
          ) : null}
      </ul>
    </li>
  );
}

interface PagesNavigationItemProps {
  placement: PagePlacementResponse;
  page: NavigationPage | undefined;
  pagesById: Map<string, NavigationPage>;
  placements: PagePlacementResponse[];
  activePageId: string | null;
  expandedFolderIds: string[];
  onToggleFolder: (folderId: string) => void;
  canEdit: boolean;
  canEditPage?: (pageId: string) => boolean;
  parentPageId: string | null;
  pagesBasePath: string;
  forceExpanded: boolean;
}

function PagesNavigationItem({
  placement,
  page,
  pagesById,
  placements,
  activePageId,
  expandedFolderIds,
  onToggleFolder,
  canEdit,
  canEditPage,
  parentPageId,
  pagesBasePath,
  forceExpanded,
}: PagesNavigationItemProps) {
  if (!page) return null;

  const isFolder = page.kind === "FOLDER";
  const isExpanded = isFolder && (forceExpanded || expandedFolderIds.includes(page.pageId));
  const isActive = activePageId === page.pageId;
  const childrenId = `pages-children-${page.pageId}`;
  const canEditThisPage = canEditPage?.(page.pageId) ?? canEdit;

  return (
    <li>
      <div
        className={`group relative flex min-w-0 items-center rounded-[4px] transition-colors ${
          isActive
            ? "bg-surface text-foreground"
            : "text-foreground-secondary hover:bg-surface/60 hover:text-foreground"
        }`}
      >
        {isActive && (
          <span
            aria-hidden="true"
            className="absolute -left-[9px] top-1/2 h-4 w-[2px] -translate-y-1/2 rounded-r-[2px] bg-accent-marker"
          />
        )}
        <Link
          href={`${pagesBasePath}/${encodeURIComponent(page.pageId)}`}
          aria-current={isActive ? "page" : undefined}
          title={page.displayTitle}
          className="flex min-w-0 flex-1 items-center gap-2 py-1.5 pl-2 pr-1 focus:outline-none"
        >
          {isFolder ? (
            <Folder className="h-3.5 w-3.5 flex-none" aria-hidden="true" />
          ) : page.kind === "DASHBOARD" ? (
            <LayoutDashboard className="h-3.5 w-3.5 flex-none" aria-hidden="true" />
          ) : (
            <DocumentFormatIcon format={page.documentFormat} />
          )}
          <span className="truncate type-meta">{page.displayTitle}</span>
        </Link>
        {canEditThisPage && (
          <PageActionsButton
            page={page}
            parentPageId={parentPageId}
            {...siblingPosition(placements, page.pageId)}
          />
        )}
        {isFolder && forceExpanded ? (
          <span
            aria-hidden="true"
            className="mr-0.5 flex h-7 w-7 flex-none items-center justify-center text-foreground-secondary"
          >
            <ChevronRight className={`h-3.5 w-3.5 rotate-90 ${styles.chevron}`} />
          </span>
        ) : isFolder ? (
          <button
            type="button"
            aria-expanded={isExpanded}
            aria-controls={childrenId}
            aria-label={`${isExpanded ? "Collapse" : "Expand"} ${page.displayTitle}`}
            onClick={() => onToggleFolder(page.pageId)}
            className="mr-0.5 flex h-7 w-7 flex-none items-center justify-center rounded-[4px] text-foreground-secondary focus:outline-none"
          >
            <ChevronRight
              className={`h-3.5 w-3.5 ${styles.chevron} ${isExpanded ? "rotate-90" : ""}`}
              aria-hidden="true"
            />
          </button>
        ) : null}
      </div>

      {isFolder ? (
        <div
          id={childrenId}
          className={`${styles.disclosure} ${isExpanded ? styles.disclosureOpen : ""}`}
          aria-hidden={!isExpanded}
          inert={!isExpanded}
        >
          <div className="min-h-0 overflow-hidden">
          <ul className="ml-3 border-l border-border-light pl-2">
          {placement.children.length === 0 ? (
            <li className="px-2 py-1.5 type-status text-foreground-secondary">Empty folder</li>
          ) : (
            placement.children.map((child) => (
              <PagesNavigationItem
                key={child.pageId}
                placement={child}
                page={pagesById.get(child.pageId)}
                pagesById={pagesById}
                placements={placements}
                activePageId={activePageId}
                expandedFolderIds={expandedFolderIds}
                onToggleFolder={onToggleFolder}
                canEdit={canEditThisPage}
                canEditPage={canEditPage}
                parentPageId={page.pageId}
                pagesBasePath={pagesBasePath}
                forceExpanded={forceExpanded}
              />
            ))
          )}
          </ul>
          </div>
        </div>
      ) : null}
    </li>
  );
}

export const _internal = {
  activePageId,
  expansionForRoute,
  filterPlacements,
  siblingPosition,
};
