import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { PageResponse, PagesTreeResponse } from "@/api/pages";
import {
  PagesNavigationView,
  _internal,
} from "@/components/pages/PagesNavigation";

function page(
  pageId: string,
  kind: PageResponse["kind"],
  displayTitle: string,
): PageResponse {
  return {
    pageId,
    kind,
    title: displayTitle,
    displayTitle,
    description: null,
    documentFormat: kind === "DOCUMENT" ? "MARKDOWN" : null,
    dashboardId: null,
    createdAt: "2026-07-10T00:00:00Z",
    updatedAt: "2026-07-10T00:00:00Z",
  };
}

const POPULATED: PagesTreeResponse = {
  workspaceId: "workspace-1",
  revision: 4,
  pages: [
    page("finance", "FOLDER", "Finance"),
    page("quarterly", "FOLDER", "Quarterly"),
    page("revenue", "DOCUMENT", "Revenue overview"),
  ],
  placements: [
    {
      pageId: "finance",
      children: [
        {
          pageId: "quarterly",
          children: [{ pageId: "revenue", children: [] }],
        },
      ],
    },
  ],
  updatedAt: "2026-07-10T00:00:00Z",
  updatedBy: { kind: "USER", id: "user-1" },
};

function renderNavigation(
  pathname: string,
  snapshot: PagesTreeResponse | null = POPULATED,
  status: "idle" | "loading" | "ready" | "error" = "ready",
  filterable = false,
): string {
  return renderToStaticMarkup(
    <ul>
      <PagesNavigationView
        workspaceId="workspace-1"
        snapshot={snapshot}
        status={status}
        error={status === "error" ? "network unavailable" : null}
        canEdit={false}
        pathname={pathname}
        onRetry={vi.fn()}
        filterable={filterable}
      />
    </ul>,
  );
}

describe("Pages native nested navigation", () => {
  it("deep-link reveals every ancestor and marks only the active Page", () => {
    const markup = renderNavigation("/workspaces/workspace-1/pages/revenue");

    expect(markup).toContain('aria-label="Collapse Finance"');
    expect(markup).toContain('aria-label="Collapse Quarterly"');
    expect(markup).toMatch(/<a[^>]*aria-current="page"[^>]*href="\/workspaces\/workspace-1\/pages\/revenue"/);
    expect(markup.match(/aria-current="page"/g)).toHaveLength(1);
  });

  it("leaves finding Pages to Search for members and keeps the name filter for readers", () => {
    const memberMarkup = renderNavigation("/workspaces/workspace-1/pages");
    const readerMarkup = renderNavigation("/workspaces/workspace-1/pages", POPULATED, "ready", true);

    expect(memberMarkup).not.toContain('type="search"');
    expect(memberMarkup).not.toContain("New top-level folder");
    expect(readerMarkup).toContain('placeholder="Search pages"');
  });

  it("keeps Folder disclosure and Folder landing as separate native controls", () => {
    const markup = renderNavigation("/workspaces/workspace-1/pages/finance");

    expect(markup).toContain('aria-label="Expand Finance"');
    expect(markup).toMatch(/<a[^>]*aria-current="page"[^>]*href="\/workspaces\/workspace-1\/pages\/finance"/);
    expect(markup).not.toContain('role="tree"');
    expect(markup).not.toMatch(/<ul[^>]*>\s*<div/);
  });

  it("reveals fixture deep links without treating the demo root as a Page", () => {
    const deepMarkup = renderNavigation("/dev/pages-demo/revenue");
    const rootMarkup = renderNavigation("/dev/pages-demo");

    expect(deepMarkup).toContain('aria-label="Collapse Finance"');
    expect(deepMarkup).toMatch(/<a[^>]*aria-current="page"[^>]*href="\/dev\/pages-demo\/revenue"/);
    expect(_internal.activePageId("/dev/pages-demo")).toBeNull();
    expect(_internal.activePageId("/dev/pages-demo/revenue")).toBe("revenue");
    expect(rootMarkup).not.toContain('aria-current="page"');
  });

  it("keeps matching ancestors and distinguishes current document formats", () => {
    const pagesById = new Map(POPULATED.pages.map((candidate) => [candidate.pageId, candidate]));
    const filtered = _internal.filterPlacements(POPULATED.placements, pagesById, "Revenue");
    const markup = renderNavigation("/workspaces/workspace-1/pages");

    expect(filtered).toEqual([
      {
        pageId: "finance",
        children: [{ pageId: "quarterly", children: [{ pageId: "revenue", children: [] }] }],
      },
    ]);
    expect(markup).toContain("lucide-file-text");
  });

  it("positions reorder actions among real siblings, not search-filtered ones", () => {
    const placements = [
      { pageId: "a", children: [] },
      { pageId: "b", children: [] },
      { pageId: "c", children: [{ pageId: "c1", children: [] }, { pageId: "c2", children: [] }] },
    ];

    expect(_internal.siblingPosition(placements, "c")).toEqual({ siblingIndex: 2, siblingCount: 3 });
    expect(_internal.siblingPosition(placements, "c2")).toEqual({ siblingIndex: 1, siblingCount: 2 });
  });

  it("renders local loading, error retry, and read-only empty states", () => {
    const empty: PagesTreeResponse = { ...POPULATED, pages: [], placements: [] };

    expect(renderNavigation("/workspaces/workspace-1/pages", null, "loading")).toContain("Loading pages…");
    const errorMarkup = renderNavigation("/workspaces/workspace-1/pages", null, "error");
    expect(errorMarkup).toContain("Pages unavailable");
    expect(errorMarkup).toContain("Retry");
    expect(renderNavigation("/workspaces/workspace-1/pages", empty, "ready")).toContain("No pages yet.");
  });
});
