import type {
  PagePlacementPatchOperation,
  PagePlacementResponse,
  PageResponse,
} from "@/api/pages";

export type PagesTreeMoveErrorCode =
  | "PAGE_NOT_FOUND"
  | "TARGET_PARENT_NOT_FOUND"
  | "TARGET_IS_PAGE_OR_DESCENDANT"
  | "INVALID_TARGET_INDEX";

export class PagesTreeMoveError extends Error {
  readonly code: PagesTreeMoveErrorCode;

  constructor(code: PagesTreeMoveErrorCode, message: string) {
    super(message);
    this.name = "PagesTreeMoveError";
    this.code = code;
  }
}

export interface PagePlacementLocation {
  placement: PagePlacementResponse;
  pointer: string;
  parentPageId: string | null;
  index: number;
  ancestors: string[];
}

export interface PagePlacementMove {
  patch: PagePlacementPatchOperation[];
  placements: PagePlacementResponse[];
  changed: boolean;
}

export function indexPages<T extends Pick<PageResponse, "pageId">>(pages: T[]): Map<string, T> {
  return new Map(pages.map((page) => [page.pageId, page]));
}

export function findPagePath(
  placements: PagePlacementResponse[],
  pageId: string,
): string[] | null {
  for (const placement of placements) {
    if (placement.pageId === pageId) return [placement.pageId];
    const childPath = findPagePath(placement.children, pageId);
    if (childPath) return [placement.pageId, ...childPath];
  }
  return null;
}

export function findPageAncestors(
  placements: PagePlacementResponse[],
  pageId: string,
): string[] | null {
  const path = findPagePath(placements, pageId);
  return path ? path.slice(0, -1) : null;
}

export function findPlacementLocation(
  placements: PagePlacementResponse[],
  pageId: string,
): PagePlacementLocation | null {
  return findPlacementLocationWithin(placements, pageId, "", null, []);
}

export function buildPageMovePatch(
  placements: PagePlacementResponse[],
  pageId: string,
  targetParentPageId: string | null,
  targetIndexAfterRemoval: number,
): PagePlacementMove {
  const source = findPlacementLocation(placements, pageId);
  if (!source) {
    throw new PagesTreeMoveError("PAGE_NOT_FOUND", "Page placement was not found.");
  }
  if (targetParentPageId === pageId) {
    throw new PagesTreeMoveError(
      "TARGET_IS_PAGE_OR_DESCENDANT",
      "A Page cannot be moved into itself.",
    );
  }
  if (targetParentPageId) {
    const targetPath = findPagePath(placements, targetParentPageId);
    if (!targetPath) {
      throw new PagesTreeMoveError("TARGET_PARENT_NOT_FOUND", "Target parent was not found.");
    }
    if (targetPath.includes(pageId)) {
      throw new PagesTreeMoveError(
        "TARGET_IS_PAGE_OR_DESCENDANT",
        "A Page cannot be moved into its descendant.",
      );
    }
  }

  const removed = removePlacement(placements, pageId);
  if (!removed.placement) {
    throw new PagesTreeMoveError("PAGE_NOT_FOUND", "Page placement was not found.");
  }
  const target = targetPlacementList(removed.placements, targetParentPageId);
  if (targetIndexAfterRemoval < 0 || targetIndexAfterRemoval > target.placements.length) {
    throw new PagesTreeMoveError(
      "INVALID_TARGET_INDEX",
      "Target index is outside the post-removal sibling list.",
    );
  }
  if (
    source.parentPageId === targetParentPageId &&
    source.index === targetIndexAfterRemoval
  ) {
    return { patch: [], placements, changed: false };
  }

  const nextPlacements = insertPlacement(
    removed.placements,
    targetParentPageId,
    targetIndexAfterRemoval,
    removed.placement,
  );
  const targetPath = target.parentPointer === null
    ? `/${targetIndexAfterRemoval}`
    : `${target.parentPointer}/children/${targetIndexAfterRemoval}`;
  return {
    patch: [
      { op: "test", path: `${source.pointer}/pageId`, value: pageId },
      { op: "remove", path: source.pointer },
      { op: "add", path: targetPath, value: removed.placement },
    ],
    placements: nextPlacements,
    changed: true,
  };
}

export function buildPageReorderPatch(
  placements: PagePlacementResponse[],
  pageId: string,
  targetIndexAfterRemoval: number,
): PagePlacementMove {
  const source = findPlacementLocation(placements, pageId);
  if (!source) {
    throw new PagesTreeMoveError("PAGE_NOT_FOUND", "Page placement was not found.");
  }
  return buildPageMovePatch(
    placements,
    pageId,
    source.parentPageId,
    targetIndexAfterRemoval,
  );
}

function findPlacementLocationWithin(
  placements: PagePlacementResponse[],
  pageId: string,
  parentPointer: string,
  parentPageId: string | null,
  ancestors: string[],
): PagePlacementLocation | null {
  for (let index = 0; index < placements.length; index += 1) {
    const placement = placements[index];
    const pointer = `${parentPointer}/${index}`;
    if (placement.pageId === pageId) {
      return { placement, pointer, parentPageId, index, ancestors };
    }
    const child = findPlacementLocationWithin(
      placement.children,
      pageId,
      `${pointer}/children`,
      placement.pageId,
      [...ancestors, placement.pageId],
    );
    if (child) return child;
  }
  return null;
}

function removePlacement(
  placements: PagePlacementResponse[],
  pageId: string,
): { placements: PagePlacementResponse[]; placement: PagePlacementResponse | null } {
  for (let index = 0; index < placements.length; index += 1) {
    const placement = placements[index];
    if (placement.pageId === pageId) {
      return {
        placements: placements.filter((_, candidateIndex) => candidateIndex !== index),
        placement,
      };
    }
    const removedChild = removePlacement(placement.children, pageId);
    if (removedChild.placement) {
      return {
        placements: placements.map((candidate, candidateIndex) =>
          candidateIndex === index
            ? { ...candidate, children: removedChild.placements }
            : candidate,
        ),
        placement: removedChild.placement,
      };
    }
  }
  return { placements, placement: null };
}

function targetPlacementList(
  placements: PagePlacementResponse[],
  parentPageId: string | null,
): { placements: PagePlacementResponse[]; parentPointer: string | null } {
  if (parentPageId === null) return { placements, parentPointer: null };
  const parent = findPlacementLocation(placements, parentPageId);
  if (!parent) {
    throw new PagesTreeMoveError("TARGET_PARENT_NOT_FOUND", "Target parent was not found.");
  }
  return { placements: parent.placement.children, parentPointer: parent.pointer };
}

function insertPlacement(
  placements: PagePlacementResponse[],
  parentPageId: string | null,
  index: number,
  placement: PagePlacementResponse,
): PagePlacementResponse[] {
  if (parentPageId === null) {
    const next = [...placements];
    next.splice(index, 0, placement);
    return next;
  }
  return placements.map((candidate) => {
    if (candidate.pageId === parentPageId) {
      const children = [...candidate.children];
      children.splice(index, 0, placement);
      return { ...candidate, children };
    }
    return {
      ...candidate,
      children: insertPlacement(candidate.children, parentPageId, index, placement),
    };
  });
}
