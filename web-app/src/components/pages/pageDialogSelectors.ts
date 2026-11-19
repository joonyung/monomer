import type {
  PagePlacementResponse,
  PagesTreeResponse,
} from "@/api/pages";
import {
  findPagePath,
  findPlacementLocation,
  indexPages,
} from "@/components/pages/pagesTree";

export interface PageDestinationOption {
  pageId: string | null;
  label: string;
  depth: number;
  disabled: boolean;
}

export function formatPagePath(
  snapshot: PagesTreeResponse,
  pageIds: string[] | null,
): string {
  if (!pageIds || pageIds.length === 0) return "Top level";
  const pagesById = indexPages(snapshot.pages);
  return pageIds
    .map((pageId) => pagesById.get(pageId)?.displayTitle)
    .filter((title): title is string => Boolean(title))
    .join(" / ");
}

export function buildPageDestinationOptions(
  snapshot: PagesTreeResponse,
  movingPageId: string | null,
): PageDestinationOption[] {
  const pagesById = indexPages(snapshot.pages);
  const excludedPageIds = movingPageId
    ? new Set(findSubtreePageIds(snapshot.placements, movingPageId))
    : new Set<string>();
  const options: PageDestinationOption[] = [
    { pageId: null, label: "Top level", depth: 0, disabled: false },
  ];

  const visit = (
    placements: PagePlacementResponse[],
    parentTitles: string[],
    depth: number,
  ) => {
    for (const placement of placements) {
      const page = pagesById.get(placement.pageId);
      if (!page) continue;
      const pathTitles = [...parentTitles, page.displayTitle];
      if (page.kind === "FOLDER") {
        options.push({
          pageId: page.pageId,
          label: pathTitles.join(" / "),
          depth,
          disabled: excludedPageIds.has(page.pageId),
        });
      }
      visit(placement.children, pathTitles, depth + 1);
    }
  };

  visit(snapshot.placements, [], 1);
  return options;
}

export function currentParentPath(
  snapshot: PagesTreeResponse,
  pageId: string,
): string {
  const path = findPagePath(snapshot.placements, pageId);
  return formatPagePath(snapshot, path?.slice(0, -1) ?? null);
}

export function appendIndexForParent(
  snapshot: PagesTreeResponse,
  parentPageId: string | null,
  movingPageId: string | null,
): number {
  const targetChildren = parentPageId === null
    ? snapshot.placements
    : findPlacementLocation(snapshot.placements, parentPageId)?.placement.children ?? [];
  return movingPageId && targetChildren.some((placement) => placement.pageId === movingPageId)
    ? Math.max(0, targetChildren.length - 1)
    : targetChildren.length;
}

function findSubtreePageIds(
  placements: PagePlacementResponse[],
  pageId: string,
): string[] {
  for (const placement of placements) {
    if (placement.pageId === pageId) return flattenPlacement(placement);
    const childIds = findSubtreePageIds(placement.children, pageId);
    if (childIds.length > 0) return childIds;
  }
  return [];
}

function flattenPlacement(placement: PagePlacementResponse): string[] {
  return [
    placement.pageId,
    ...placement.children.flatMap(flattenPlacement),
  ];
}
