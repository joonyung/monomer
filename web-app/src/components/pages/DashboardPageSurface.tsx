import type { PageResponse, PagesTreeResponse } from "@/api/pages";
import { DashboardViewer, type DashboardViewerSearch } from "@/pages/dashboard/viewer/page";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { useWorkspaceId } from "@/hooks/use-workspace";
import { findPlacementLocation } from "./pagesTree";
import { PageActionsButton } from "./PageMutationDialogs";
import { StateBlock } from "@/components/ui/StateBlock";
import { usePageSource } from "./usePageSource";

export function DashboardPageSurface({ page, snapshot, search, onSearchChange }: {
  page: PageResponse;
  snapshot: PagesTreeResponse;
  search: DashboardViewerSearch;
  onSearchChange: (search: DashboardViewerSearch) => void;
}) {
  const workspaceId = useWorkspaceId();
  const source = usePageSource();
  const location = findPlacementLocation(snapshot.placements, page.pageId);
  const query = new URLSearchParams();
  if (search.session) query.set("session", search.session);
  if (search.run) query.set("run", search.run);
  if (search.scheduleKey) query.set("scheduleKey", search.scheduleKey);
  if (search.scheduledAt) query.set("scheduledAt", search.scheduledAt);
  if (!page.dashboardId) return <StateBlock kind="error" layout="page" title="The dashboard link is unavailable." />;
  return <DashboardViewer pagesEntry key={page.pageId} dashboardId={page.dashboardId} search={search} onSearchChange={onSearchChange} pagePresentation={{
    title: page.displayTitle,
    description: page.description,
    source,
    actions: <>
      <Link href={`${webPaths.dashboard(workspaceId, page.dashboardId)}${query.size ? `?${query}` : ""}`} className="type-control underline underline-offset-4">Open dashboard</Link>
      {location && <PageActionsButton page={page} parentPageId={location.parentPageId} siblingIndex={location.index} siblingCount={location.parentPageId === null ? snapshot.placements.length : findPlacementLocation(snapshot.placements, location.parentPageId)?.placement.children.length ?? 1} />}
    </>,
  }} />;
}
