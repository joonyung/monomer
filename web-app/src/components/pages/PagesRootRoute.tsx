import { AddDashboardButton } from "./AddDashboardButton";
import { NOTHING_SHARED, PageCollectionSurface } from "@/components/pages/PageCollectionSurface";
import { NewFolderButton } from "@/components/pages/PageMutationDialogs";
import { NewDocumentButton } from "@/components/pages/DocumentPageDialogs";
import { PagesErrorState, PagesLoadingState } from "@/components/pages/PagesRouteState";
import { usePagesDirectory } from "@/components/pages/PagesDirectoryProvider";
import { useCanEditPages } from "@/components/pages/usePagesPermissions";

export function PagesRootRoute({ actions }: { actions?: React.ReactNode }) {
  const canEdit = useCanEditPages();
  const { snapshot, pagesById, status, error, errorRetryable, retry } = usePagesDirectory();

  if ((status === "idle" || status === "loading") && !snapshot) {
    return <PagesLoadingState />;
  }
  if (status === "error" && !snapshot) {
    return (
      <PagesErrorState
        title="Pages could not be loaded"
        description={error ?? "Pages could not be loaded."}
        onRetry={errorRetryable ? retry : undefined}
      />
    );
  }
  if (!snapshot) {
    return (
      <PagesErrorState
        title="Select a workspace"
        description="Pages belong to a workspace. Select one to continue."
      />
    );
  }

  return (
    <PageCollectionSurface
      title="All pages"
      description={null}
      placements={snapshot.placements}
      pagesById={pagesById}
      actions={actions ?? (
        <div className="flex flex-wrap items-center gap-2">
          <NewFolderButton parentPageId={null} triggerLabel="New folder" appearance="button" />
          <NewDocumentButton parentPageId={null} compact={false} />
          <AddDashboardButton parentPageId={null} />
        </div>
      )}
      emptyTitle="No pages yet"
      emptyDescription={canEdit
        ? "Add a dashboard, upload a document, or create a folder. Readers see them here in this order."
        : NOTHING_SHARED}
    />
  );
}
