import { describe, expect, it } from "vitest";
import type { PagePlacementResponse, PageResponse } from "@/api/pages";
import {
  PagesTreeMoveError,
  buildPageMovePatch,
  buildPageReorderPatch,
  findPageAncestors,
  findPagePath,
  findPlacementLocation,
  indexPages,
} from "@/components/pages/pagesTree";

function placement(pageId: string, children: PagePlacementResponse[] = []): PagePlacementResponse {
  return { pageId, children };
}

function page(pageId: string): PageResponse {
  return {
    pageId,
    kind: "FOLDER",
    documentFormat: null,
    dashboardId: null,
    title: pageId,
    displayTitle: pageId,
    description: null,
    createdAt: "2026-07-10T00:00:00Z",
    updatedAt: "2026-07-10T00:00:00Z",
  };
}

describe("Pages tree selectors", () => {
  const deepTree = [
    placement("root", [
      placement("division", [
        placement("team", [
          placement("quarter", [placement("report")]),
        ]),
      ]),
    ]),
  ];

  it("indexes pages and resolves a deep path, ancestors, and JSON pointer", () => {
    const pagesById = indexPages([page("root"), page("report")]);

    expect(pagesById.get("report")?.displayTitle).toBe("report");
    expect(findPagePath(deepTree, "report")).toEqual([
      "root",
      "division",
      "team",
      "quarter",
      "report",
    ]);
    expect(findPageAncestors(deepTree, "report")).toEqual([
      "root",
      "division",
      "team",
      "quarter",
    ]);
    expect(findPlacementLocation(deepTree, "report")?.pointer).toBe(
      "/0/children/0/children/0/children/0/children/0",
    );
  });

  it("returns null for a Page outside the placement tree", () => {
    expect(findPagePath(deepTree, "missing")).toBeNull();
    expect(findPageAncestors(deepTree, "missing")).toBeNull();
    expect(findPlacementLocation(deepTree, "missing")).toBeNull();
  });
});

describe("buildPageMovePatch", () => {
  it("uses the desired post-removal index for same-parent reorder", () => {
    const placements = [placement("first"), placement("second"), placement("third")];

    const move = buildPageReorderPatch(placements, "first", 2);

    expect(move.patch).toEqual([
      { op: "test", path: "/0/pageId", value: "first" },
      { op: "remove", path: "/0" },
      { op: "add", path: "/2", value: placement("first") },
    ]);
    expect(move.placements.map(({ pageId }) => pageId)).toEqual(["second", "third", "first"]);
  });

  it("does not emit a revision-advancing patch for a no-op reorder", () => {
    const placements = [placement("first"), placement("second"), placement("third")];

    const move = buildPageReorderPatch(placements, "second", 1);

    expect(move.changed).toBe(false);
    expect(move.patch).toEqual([]);
    expect(move.placements).toBe(placements);
  });

  it("moves a complete folder subtree without flattening its children", () => {
    const subtree = placement("folder", [placement("report"), placement("nested", [placement("chart")])]);
    const placements = [subtree, placement("destination")];

    const move = buildPageMovePatch(placements, "folder", "destination", 0);

    expect(move.patch[2]).toEqual({
      op: "add",
      path: "/0/children/0",
      value: subtree,
    });
    expect(move.placements).toEqual([
      placement("destination", [subtree]),
    ]);
  });

  it("recomputes the destination pointer after removing an earlier root", () => {
    const placements = [
      placement("source"),
      placement("later-root", [placement("target")]),
    ];

    const move = buildPageMovePatch(placements, "source", "target", 0);

    expect(move.patch[2]).toEqual({
      op: "add",
      path: "/0/children/0/children/0",
      value: placement("source"),
    });
    expect(move.placements).toEqual([
      placement("later-root", [placement("target", [placement("source")])]),
    ]);
  });

  it("prevents moving a folder into itself or any descendant", () => {
    const placements = [placement("folder", [placement("child", [placement("grandchild")])])];

    for (const target of ["folder", "child", "grandchild"]) {
      expect(() => buildPageMovePatch(placements, "folder", target, 0)).toThrowError(
        expect.objectContaining<Partial<PagesTreeMoveError>>({
          code: "TARGET_IS_PAGE_OR_DESCENDANT",
        }),
      );
    }
  });

  it("validates the destination against the post-removal sibling count", () => {
    const placements = [placement("first"), placement("second")];

    expect(() => buildPageReorderPatch(placements, "first", 2)).toThrowError(
      expect.objectContaining<Partial<PagesTreeMoveError>>({ code: "INVALID_TARGET_INDEX" }),
    );
  });
});
