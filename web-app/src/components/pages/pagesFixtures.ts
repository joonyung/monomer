import type { PagePlacementResponse, PageResponse, PagesTreeResponse } from "@/api/pages";
import type { PagesDirectoryFixture } from "@/components/pages/PagesDirectoryProvider";

export type PagesFixtureMode = "populated" | "empty-editor" | "empty-member" | "depth" | "dense";

export const PAGES_FIXTURE_WORKSPACE_ID = "00000000-0000-4000-8000-000000000101";
export const PAGES_FIXTURE_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000102";
export const PAGES_FIXTURE_FOLDER_ID = "00000000-0000-0000-0000-000000000301";
export const PAGES_FIXTURE_NESTED_FOLDER_ID = "00000000-0000-0000-0000-000000000302";
export const PAGES_FIXTURE_EMPTY_FOLDER_ID = "00000000-0000-0000-0000-000000000303";
export const PAGES_FIXTURE_MARKDOWN_PAGE_ID = "00000000-0000-0000-0000-000000000404";
export const PAGES_FIXTURE_NOTEBOOK_PAGE_ID = "00000000-0000-0000-0000-000000000405";
export const PAGES_FIXTURE_HTML_PAGE_ID = "00000000-0000-0000-0000-000000000408";
const FIXTURE_TIME = "2026-07-10T09:00:00Z";

function page(input: Pick<PageResponse, "pageId" | "kind" | "displayTitle"> & Partial<PageResponse>): PageResponse {
  return {
    title: input.displayTitle,
    description: null,
    documentFormat: null,
    dashboardId: null,
    createdAt: FIXTURE_TIME,
    updatedAt: FIXTURE_TIME,
    ...input,
  };
}

function tree(pages: PageResponse[], placements: PagePlacementResponse[]): PagesTreeResponse {
  return {
    workspaceId: PAGES_FIXTURE_WORKSPACE_ID,
    revision: 17,
    pages,
    placements,
    updatedAt: FIXTURE_TIME,
    updatedBy: { kind: "USER", id: "pages-demo-editor" },
  };
}

function populatedTree(): PagesTreeResponse {
  const pages = [
    page({
      pageId: PAGES_FIXTURE_FOLDER_ID,
      kind: "FOLDER",
      displayTitle: "Executive",
      description: "The weekly operating picture for leadership and finance.",
    }),
    page({
      pageId: PAGES_FIXTURE_NESTED_FOLDER_ID,
      kind: "FOLDER",
      displayTitle: "Weekly reviews",
      description: "Recurring team review material.",
    }),
    page({
      pageId: PAGES_FIXTURE_MARKDOWN_PAGE_ID,
      kind: "DOCUMENT",
      displayTitle: "Q2 operating brief",
      description: "Narrative context, decisions, and follow-ups for the quarterly review.",
      documentFormat: "MARKDOWN",
      dashboardId: null,
    }),
    page({
      pageId: PAGES_FIXTURE_NOTEBOOK_PAGE_ID,
      kind: "DOCUMENT",
      displayTitle: "Retention cohort analysis",
      description: "A static notebook snapshot with stored outputs.",
      documentFormat: "NOTEBOOK",
      dashboardId: null,
    }),
    page({
      pageId: PAGES_FIXTURE_EMPTY_FOLDER_ID,
      kind: "FOLDER",
      displayTitle: "Operations",
      description: "Live operating documents for the team.",
    }),
    page({
      pageId: "00000000-0000-0000-0000-000000000304",
      kind: "FOLDER",
      displayTitle: "Reference",
      description: "A quiet place for material the team returns to.",
    }),
    page({
      pageId: PAGES_FIXTURE_HTML_PAGE_ID,
      kind: "DOCUMENT",
      displayTitle: "Campaign readout",
      description: "A sanitized static HTML report.",
      documentFormat: "HTML",
      dashboardId: null,
    }),
  ];
  return tree(pages, [
    {
      pageId: PAGES_FIXTURE_FOLDER_ID,
      children: [
        { pageId: PAGES_FIXTURE_MARKDOWN_PAGE_ID, children: [] },
        {
          pageId: PAGES_FIXTURE_NESTED_FOLDER_ID,
          children: [
            { pageId: PAGES_FIXTURE_NOTEBOOK_PAGE_ID, children: [] },
          ],
        },
      ],
    },
    {
      pageId: PAGES_FIXTURE_EMPTY_FOLDER_ID,
      children: [],
    },
    {
      pageId: "00000000-0000-0000-0000-000000000304",
      children: [{ pageId: PAGES_FIXTURE_HTML_PAGE_ID, children: [] }],
    },
  ]);
}

function depthTree(): PagesTreeResponse {
  const pages: PageResponse[] = [];
  let placement: PagePlacementResponse = {
    pageId: "00000000-0000-0000-0000-000000000506",
    children: [],
  };
  for (let depth = 6; depth >= 1; depth -= 1) {
    const pageId = `00000000-0000-0000-0000-${String(500 + depth).padStart(12, "0")}`;
    pages.push(page({
      pageId,
      kind: "FOLDER",
      displayTitle: depth === 6
        ? "A deliberately long folder name that remains understandable when the sidebar is narrow"
        : `Planning level ${depth}`,
    }));
    if (depth < 6) placement = { pageId, children: [placement] };
  }
  return tree(pages, [placement]);
}

function denseTree(): PagesTreeResponse {
  const pages: PageResponse[] = [];
  const placements: PagePlacementResponse[] = [];
  let sequence = 0;
  for (let folderIndex = 0; folderIndex < 20; folderIndex += 1) {
    sequence += 1;
    const folderId = `10000000-0000-0000-0000-${String(sequence).padStart(12, "0")}`;
    pages.push(page({ pageId: folderId, kind: "FOLDER", displayTitle: `Portfolio ${folderIndex + 1}` }));
    const children: PagePlacementResponse[] = [];
    const childCount = 24;
    for (let childIndex = 0; childIndex < childCount; childIndex += 1) {
      sequence += 1;
      const pageId = `10000000-0000-0000-0000-${String(sequence).padStart(12, "0")}`;
      pages.push(page({
        pageId,
        kind: "DOCUMENT",
        displayTitle: `Document ${folderIndex + 1}.${childIndex + 1}`,
      }));
      children.push({ pageId, children: [] });
    }
    placements.push({ pageId: folderId, children });
  }
  return tree(pages, placements);
}

export function createPagesFixture(mode: PagesFixtureMode): PagesDirectoryFixture {
  const snapshot = mode === "empty-editor" || mode === "empty-member"
    ? tree([], [])
    : mode === "depth"
      ? depthTree()
      : mode === "dense"
        ? denseTree()
        : populatedTree();
  return {
    snapshot,
    workspaceAccess: {
      granted: true,
      organizationId: PAGES_FIXTURE_ORGANIZATION_ID,
      role: mode === "empty-member" ? "VIEWER" : "EXPLORER", },
  };
}
