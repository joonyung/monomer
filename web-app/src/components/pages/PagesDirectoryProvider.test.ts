import { describe, expect, it, vi } from "vitest";
import type {
  PagePlacementResponse,
  PagesTreeResponse,
} from "@/api/pages";
import {
  _internal,
  pagesDirectoryResponseMatchesScope,
  reconcilePagesSnapshot,
} from "@/components/pages/PagesDirectoryProvider";
import type { WorkspaceAccess } from "@/lib/auth/types";

const workspaceId = "019e501f-0000-7000-8000-000000000011";

function placement(pageId: string, children: PagePlacementResponse[] = []): PagePlacementResponse {
  return { pageId, children };
}

function snapshot(revision: number): PagesTreeResponse {
  return {
    workspaceId,
    revision,
    pages: [],
    placements: [],
    updatedAt: `2026-07-10T00:00:0${revision}Z`,
    updatedBy: { kind: "USER", id: "user-1" },
  };
}

describe("PagesDirectory reconciliation", () => {
  it("hides the previous snapshot synchronously when workspace identity changes", () => {
    const priorSnapshot = snapshot(4);
    priorSnapshot.pages = [{
      pageId: "old-page",
      kind: "DOCUMENT",
      documentFormat: "MARKDOWN",
      dashboardId: null,
      title: "Old workspace title",
      displayTitle: "Old workspace title",
      description: null,
      createdAt: "2026-07-10T00:00:00Z",
      updatedAt: "2026-07-10T00:00:00Z",
    }];

    const visible = _internal.visibleStateForScope({
      workspaceId,
      principalId: "principal-1",
      snapshot: priorSnapshot,
      status: "ready",
      error: null,
      streamConnected: true,
    }, "workspace-2", "principal-1");

    expect(visible.workspaceId).toBe("workspace-2");
    expect(visible.snapshot).toBeNull();
    expect(visible.status).toBe("loading");
    expect(visible.streamConnected).toBe(false);
  });

  it("hides the previous snapshot synchronously when principal identity changes", () => {
    const priorSnapshot = snapshot(4);
    const visible = _internal.visibleStateForScope({
      workspaceId,
      principalId: "principal-a",
      snapshot: priorSnapshot,
      status: "ready",
      error: null,
      streamConnected: true,
    }, workspaceId, "principal-b");

    expect(visible.principalId).toBe("principal-b");
    expect(visible.snapshot).toBeNull();
    expect(visible.status).toBe("loading");
  });

  it("rejects a late one-shot response from the previous principal", async () => {
    let activePrincipalId = "principal-a";
    let resolveResponse!: (value: PagesTreeResponse) => void;
    const response = new Promise<PagesTreeResponse>((resolve) => {
      resolveResponse = resolve;
    });
    const adopted: PagesTreeResponse[] = [];
    const settlement = response.then((incoming) => {
      if (pagesDirectoryResponseMatchesScope(
        incoming.workspaceId,
        "principal-a",
        workspaceId,
        activePrincipalId,
      )) adopted.push(incoming);
    });

    activePrincipalId = "principal-b";
    resolveResponse(snapshot(5));
    await settlement;

    expect(adopted).toEqual([]);
  });

  it("never lets a replayed REST snapshot move local state backwards", () => {
    expect(reconcilePagesSnapshot(snapshot(5), snapshot(2))).toEqual(snapshot(5));
    expect(reconcilePagesSnapshot(snapshot(5), snapshot(6))).toEqual(snapshot(6));
  });

  it("invalidates queued mutations across workspace switches including switch-away-and-back", () => {
    expect(_internal.mutationWorkspaceMatches("workspace-1", 4, "workspace-1", 4)).toBe(true);
    expect(_internal.mutationWorkspaceMatches("workspace-1", 4, "workspace-2", 5)).toBe(false);
    expect(_internal.mutationWorkspaceMatches("workspace-1", 4, "workspace-1", 6)).toBe(false);
  });

  it("reuses one requestId and exact snapshot for an uncertain-network retry", async () => {
    const originalSnapshot = snapshot(4);
    const mutation = vi.fn().mockResolvedValue({
      result: "APPLIED",
      snapshot: snapshot(5),
      change: null,
    });
    const execute = _internal.createRetryableMutationExecution(
      originalSnapshot,
      "stable-request-id",
      mutation,
    );

    await execute();
    await execute();

    expect(mutation).toHaveBeenCalledTimes(2);
    expect(mutation).toHaveBeenNthCalledWith(1, originalSnapshot, "stable-request-id");
    expect(mutation).toHaveBeenNthCalledWith(2, originalSnapshot, "stable-request-id");
  });

});

describe("PagesDirectory fixture mutations", () => {
  it("inserts into a nested folder and preserves all existing placements", () => {
    const placements = [placement("root", [placement("folder")]), placement("after")];

    expect(_internal.insertFixturePlacement(placements, "folder", null, "new-page")).toEqual([
      placement("root", [placement("folder", [placement("new-page")])]),
      placement("after"),
    ]);
  });

  it("rejects deleting a folder that still has children", () => {
    const placements = [
      placement("before"),
      placement("folder", [placement("first"), placement("second")]),
      placement("after"),
    ];

    expect(() => _internal.deleteFixturePlacement(placements, "folder"))
      .toThrow("Folder must be empty before it can be deleted.");
  });

  it("advances fixture mutations by one revision with a direct change result", () => {
    const response = _internal.fixtureMutationResponse(
      snapshot(2),
      [],
      [],
      {
        type: "placements-patched",
        pageId: null,
        appliedOperationCount: 3,
      },
      "2026-07-10T00:00:03Z",
    );

    expect(response.snapshot.revision).toBe(3);
    expect(response.change).toEqual({
      type: "placements-patched",
      pageId: null,
      appliedOperationCount: 3,
    });
  });
});

describe("Pages placement authorization", () => {
  const access: WorkspaceAccess = {
    granted: true,
    organizationId: "organization-1",
    role: "EXPLORER" as const, };
  const pagesSnapshot: PagesTreeResponse = {
    ...snapshot(2),
    pages: [
      {
        pageId: "folder-a",
        kind: "FOLDER",
        documentFormat: null,
        dashboardId: null,
        title: "Folder A",
        displayTitle: "Folder A",
        description: null,
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
      {
        pageId: "page-a",
        kind: "DOCUMENT",
        documentFormat: "MARKDOWN",
        dashboardId: null,
        title: "Page A",
        displayTitle: "Page A",
        description: null,
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
      {
        pageId: "folder-b",
        kind: "FOLDER",
        documentFormat: null,
        dashboardId: null,
        title: "Folder B",
        displayTitle: "Folder B",
        description: null,
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
    ],
    placements: [
      placement("folder-a", [placement("page-a")]),
      placement("folder-b"),
    ],
  };

  it("uses one workspace-wide Edit Pages capability for every target", () => {
    expect(() => _internal.assertEditablePageTarget(access, pagesSnapshot, "page-a"))
      .not.toThrow();
    expect(() => _internal.assertEditablePageTarget(access, pagesSnapshot, "folder-a"))
      .not.toThrow();
    expect(() => _internal.assertEditablePageTarget(access, pagesSnapshot, "folder-b"))
      .not.toThrow();
    expect(() => _internal.assertEditablePageTarget(access, pagesSnapshot, null))
      .not.toThrow();
    expect(() => _internal.assertEditablePageTarget({ ...access, role: "VIEWER" }, pagesSnapshot, "folder-b"))
      .toThrow("Requires Explorer role.");
  });
});
