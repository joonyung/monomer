import { AddDashboardButton } from "./AddDashboardButton";
import type { PageResponse, PagesTreeResponse } from "@/api/pages";
import { NOTHING_SHARED, PageCollectionSurface } from "@/components/pages/PageCollectionSurface";
import { NewFolderButton, PageActionsButton } from "@/components/pages/PageMutationDialogs";
import { NewDocumentButton } from "@/components/pages/DocumentPageDialogs";
import { findPagePath, findPlacementLocation, indexPages } from "@/components/pages/pagesTree";
import { useCanEditPages } from "@/components/pages/usePagesPermissions";

export function FolderPageSurface({
  page,
  snapshot,
  actions,
}: {
  page: PageResponse;
  snapshot: PagesTreeResponse;
  actions?: React.ReactNode;
}) {
  const canEdit = useCanEditPages();
  const pagesById = indexPages(snapshot.pages);
  const location = findPlacementLocation(snapshot.placements, page.pageId);
  const placement = location?.placement;
  const path = findPagePath(snapshot.placements, page.pageId) ?? [page.pageId];
  const breadcrumb = path.map((pageId) => {
    const pathPage = pagesById.get(pageId);
    return {
      pageId,
      displayTitle: pathPage?.displayTitle ?? "Page",
    };
  });

  return (
    <PageCollectionSurface
      title={page.displayTitle}
      description={page.description}
      placements={placement?.children ?? []}
      pagesById={pagesById}
      breadcrumb={breadcrumb}
      actions={actions ?? (
        <div className="flex flex-wrap items-center gap-2">
          <NewFolderButton parentPageId={page.pageId} triggerLabel={`New folder in ${page.displayTitle}`} appearance="button" />
          <NewDocumentButton parentPageId={page.pageId} compact={false} />
          <AddDashboardButton parentPageId={page.pageId} />
          {location && (
            <PageActionsButton
              page={page}
              parentPageId={location.parentPageId}
              siblingIndex={location.index}
              siblingCount={location.parentPageId === null
                ? snapshot.placements.length
                : findPlacementLocation(snapshot.placements, location.parentPageId)?.placement.children.length ?? 1}
            />
          )}
        </div>
      )}
      emptyTitle="This folder is empty"
      emptyDescription={canEdit ? "Add a dashboard or document, or move an existing page here." : NOTHING_SHARED}
    />
  );
}
