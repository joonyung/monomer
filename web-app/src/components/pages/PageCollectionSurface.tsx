import { Link } from "@/lib/navigation";
import { ChevronRight, FileText, Folder, LayoutDashboard } from "lucide-react";
import type { PagePlacementResponse, PageResponse } from "@/api/pages";
import { useWorkspaceId } from "@/hooks/use-workspace";
import { formatRelativeTime } from "@/lib/utils/relative-time";
import { webPaths } from "@/lib/web-paths";
import { WorkspacePageHeader } from "@/components/layout/WorkspacePageHeader";
import { StateBlock } from "@/components/ui/StateBlock";

export const NOTHING_SHARED = "Nothing has been shared here yet. Ask a workspace Explorer to add dashboards or documents.";

interface PageCollectionSurfaceProps {
  title: string;
  description: string | null;
  placements: PagePlacementResponse[];
  pagesById: ReadonlyMap<string, PageResponse>;
  breadcrumb?: Array<{ pageId: string; displayTitle: string }>;
  actions?: React.ReactNode;
  emptyTitle: string;
  emptyDescription: string;
}

function pageKindLabel(page: PageResponse, placement: PagePlacementResponse): string {
  if (page.kind === "FOLDER") return `${placement.children.length} ${placement.children.length === 1 ? "item" : "items"}`;
  return page.kind === "DASHBOARD" ? "Dashboard" : "Document";
}

export function PageCollectionSurface({
  title,
  description,
  placements,
  pagesById,
  breadcrumb = [],
  actions,
  emptyTitle,
  emptyDescription,
}: PageCollectionSurfaceProps) {
  const workspaceId = useWorkspaceId();
  return (
    <div className="flex h-full flex-col bg-background">
      <WorkspacePageHeader
        section="Pages"
        title={title}
        description={description || undefined}
        actions={actions}
      />
      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-[1080px] px-5 py-6 sm:px-8 sm:py-8">
        {breadcrumb.length > 0 && (
          <nav aria-label="Page breadcrumb" className="mb-5 flex flex-wrap items-center gap-1.5 type-meta text-foreground-secondary">
            <Link href={webPaths.pages(workspaceId)} className="rounded-[4px] px-1 py-0.5 hover:text-foreground focus:outline-none">
              Pages
            </Link>
            {breadcrumb.slice(0, -1).map((entry) => (
              <span key={entry.pageId} className="contents">
                <span aria-hidden="true">/</span>
                <Link
                  href={webPaths.page(workspaceId, entry.pageId)}
                  className="max-w-[220px] truncate rounded-[4px] px-1 py-0.5 hover:text-foreground focus:outline-none"
                  title={entry.displayTitle}
                >
                  {entry.displayTitle}
                </Link>
              </span>
            ))}
          </nav>
        )}

        {placements.length === 0 ? (
          <div className="rounded-[6px] border border-dashed border-border bg-surface/40">
            <StateBlock
              kind="empty"
              layout="page"
              icon={Folder}
              title={<span role="heading" aria-level={2}>{emptyTitle}</span>}
              description={emptyDescription}
            />
          </div>
        ) : (
          <ul aria-label={`${title} contents`} className="divide-y divide-border-light border-y border-border">
            {placements.map((placement) => {
              const page = pagesById.get(placement.pageId);
              if (!page) return null;
              const Icon = page.kind === "FOLDER" ? Folder : page.kind === "DASHBOARD" ? LayoutDashboard : FileText;
              const kindLabel = pageKindLabel(page, placement);
              const updated = `Updated ${formatRelativeTime(page.updatedAt)}`;
              return (
                <li key={page.pageId} className="transition-colors hover:bg-surface">
                  <Link
                    href={webPaths.page(workspaceId, page.pageId)}
                    className="grid min-h-[68px] grid-cols-[16px_minmax(0,1fr)_16px] items-center gap-x-4 px-3 py-3 focus-visible:outline focus-visible:outline-[2px] focus-visible:outline-offset-[-3px] focus-visible:outline-[var(--focus-ring)] sm:grid-cols-[16px_minmax(0,1fr)_96px_112px_16px] sm:px-4"
                  >
                    <Icon className="h-4 w-4 text-foreground-secondary" aria-hidden="true" />
                    <span className="min-w-0">
                      <span className="block truncate type-item text-foreground" title={page.displayTitle}>
                        {page.displayTitle}
                      </span>
                      {page.description && (
                        <span className="mt-1 block truncate type-meta text-foreground-secondary" title={page.description}>
                          {page.description}
                        </span>
                      )}
                      <span className="mt-1 block type-meta tabular-nums text-foreground-secondary sm:hidden">
                        {kindLabel} · {updated}
                      </span>
                    </span>
                    <span className="hidden type-meta tabular-nums text-foreground-secondary sm:block">{kindLabel}</span>
                    <time
                      dateTime={page.updatedAt}
                      title={new Date(page.updatedAt).toLocaleString("en-US")}
                      className="hidden type-meta tabular-nums text-foreground-secondary sm:block"
                    >
                      {updated}
                    </time>
                    <ChevronRight className="h-4 w-4 text-foreground-secondary" aria-hidden="true" />
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
    </div>
  );
}
