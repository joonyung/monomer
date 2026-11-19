import { describe, expect, it } from "vitest";
import type {
  PagePlacementResponse,
  PageResponse,
  PagesTreeResponse,
} from "@/api/pages";
import {
  appendIndexForParent,
  buildPageDestinationOptions,
  currentParentPath,
  formatPagePath,
} from "@/components/pages/pageDialogSelectors";

function page(
  pageId: string,
  kind: PageResponse["kind"],
  displayTitle: string,
): PageResponse {
  return {
    pageId,
    kind,
    documentFormat: kind === "DOCUMENT" ? "MARKDOWN" : null,
    dashboardId: null,
    title: displayTitle,
    displayTitle,
    description: null,
    createdAt: "2026-07-10T00:00:00Z",
    updatedAt: "2026-07-10T00:00:00Z",
  };
}

function placement(
  pageId: string,
  children: PagePlacementResponse[] = [],
): PagePlacementResponse {
  return { pageId, children };
}

function snapshot(): PagesTreeResponse {
  return {
    workspaceId: "workspace-1",
    revision: 7,
    pages: [
      page("finance", "FOLDER", "Finance"),
      page("quarterly", "FOLDER", "Quarterly"),
      page("revenue", "DOCUMENT", "Revenue overview"),
      page("operations", "FOLDER", "Operations"),
    ],
    placements: [
      placement("finance", [
        placement("quarterly", [placement("revenue")]),
      ]),
      placement("operations"),
    ],
    updatedAt: "2026-07-10T00:00:00Z",
    updatedBy: { kind: "USER", id: "user-1" },
  };
}

describe("Pages dialog selectors", () => {
  it("offers the root and ordered Folders, never document leaves", () => {
    const options = buildPageDestinationOptions(snapshot(), null);

    expect(options.map(({ pageId, label }) => ({ pageId, label }))).toEqual([
      { pageId: null, label: "Top level" },
      { pageId: "finance", label: "Finance" },
      { pageId: "quarterly", label: "Finance / Quarterly" },
      { pageId: "operations", label: "Operations" },
    ]);
  });

  it("disables a moving Folder and all descendant destinations", () => {
    const options = buildPageDestinationOptions(snapshot(), "finance");

    expect(options.find((option) => option.pageId === "finance")?.disabled).toBe(true);
    expect(options.find((option) => option.pageId === "quarterly")?.disabled).toBe(true);
    expect(options.find((option) => option.pageId === "operations")?.disabled).toBe(false);
  });

  it("formats current parent paths without including the Page itself", () => {
    const view = snapshot();

    expect(currentParentPath(view, "revenue")).toBe("Finance / Quarterly");
    expect(formatPagePath(view, ["finance", "quarterly", "revenue"])).toBe(
      "Finance / Quarterly / Revenue overview",
    );
  });

  it("uses the post-removal append index for same-parent moves", () => {
    const view = snapshot();

    expect(appendIndexForParent(view, "finance", "quarterly")).toBe(0);
    expect(appendIndexForParent(view, null, "finance")).toBe(1);
    expect(appendIndexForParent(view, "operations", "revenue")).toBe(0);
  });
});
