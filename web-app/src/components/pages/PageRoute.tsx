import { webPaths } from "@/lib/web-paths";
import { DashboardPageSurface } from "./DashboardPageSurface";
import type { DashboardViewerSearch } from "@/pages/dashboard/viewer/page";
import { DocumentPageSurface } from "@/components/pages/DocumentPageSurface";
import { FolderPageSurface } from "@/components/pages/FolderPageSurface";
import { PagesErrorState, PagesLoadingState } from "@/components/pages/PagesRouteState";
import { usePagesDirectory } from "@/components/pages/PagesDirectoryProvider";
import { useCanEditPages } from "@/components/pages/usePagesPermissions";

export function PageRoute({ pageId, search = {}, onSearchChange = () => {} }: { pageId: string; search?: DashboardViewerSearch; onSearchChange?: (search: DashboardViewerSearch) => void }) {
  const { workspaceId, snapshot, pagesById, status, error, errorRetryable, retry } = usePagesDirectory();
  const canEdit = useCanEditPages();

  if ((status === "idle" || status === "loading") && !snapshot) {
    return <PagesLoadingState />;
  }
  if (status === "error" && !snapshot) {
    return (
      <PagesErrorState
        title="Page could not be loaded"
        description={error ?? "Pages could not be loaded."}
        backHref={workspaceId ? webPaths.pages(workspaceId) : undefined}
        onRetry={errorRetryable ? retry : undefined}
      />
    );
  }
  if (!snapshot) {
    return <PagesErrorState title="Select a workspace" description="Pages belong to a workspace." />;
  }

  const page = pagesById.get(pageId);
  if (!page) {
    return (
      <PagesErrorState
        title="Page not found"
        description="This Page may have been removed or moved outside the current workspace."
        backHref={workspaceId ? webPaths.pages(workspaceId) : undefined}
      />
    );
  }

  if (page.kind === "FOLDER") {
    return <FolderPageSurface page={page} snapshot={snapshot} />;
  }
  if (page.kind === "DASHBOARD") {
    return <DashboardPageSurface key={page.pageId} page={page} snapshot={snapshot} search={search} onSearchChange={onSearchChange} />;
  }
  return <DocumentPageSurface key={page.pageId} page={page} snapshot={snapshot} canEdit={canEdit} />;
}
