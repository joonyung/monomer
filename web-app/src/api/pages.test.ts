import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAuthStore } from "@/lib/auth/store";
import {
  PagesApiError,
  PagesRequestAbortedError,
  pagesAPI,
  parsePagesProblemDetail,
  type PagesTreeResponse,
} from "@/api/pages";

const workspaceId = "019e501f-0000-7000-8000-000000000011";

function tree(revision: number): PagesTreeResponse {
  return {
    workspaceId,
    revision,
    pages: [],
    placements: [],
    updatedAt: "2026-07-10T00:00:00Z",
    updatedBy: { kind: "USER", id: "user-1" },
  };
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("pagesAPI", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    useAuthStore.setState({
      status: "authenticated",
      accessToken: "pages-token",
      accessTokenExpiresAt: "2030-01-01T00:00:00Z",
    });
  });

  it("reads the tree through the workspace-scoped authenticated endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(200, tree(4)));

    await expect(pagesAPI.getTree(workspaceId)).resolves.toEqual(tree(4));

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain(`/v2/workspaces/${workspaceId}/pages/tree`);
    const headers = new Headers(init?.headers);
    expect(headers.get("Authorization")).toBe("Bearer pages-token");
    expect(headers.get("X-Workspace-Id")).toBeNull();
  });

  it("keeps request cancellation distinct from a Pages API failure", async () => {
    const abortController = new AbortController();
    abortController.abort();
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new DOMException("Aborted", "AbortError"));

    await expect(pagesAPI.getTree(workspaceId, abortController.signal))
      .rejects.toBeInstanceOf(PagesRequestAbortedError);
  });

  it("creates a Dashboard link without an upload reference", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(201, {
      result: "APPLIED", snapshot: tree(1), change: null,
    }));
    await pagesAPI.createPage(workspaceId, {
      kind: "DASHBOARD", dashboardId: "dashboard-1", title: "Weekly revenue", description: null,
      parentPageId: null, index: null, baseRevision: 0, requestId: "request-dashboard",
    });
    expect(JSON.parse(fetchMock.mock.calls[0][1]?.body as string)).toEqual({
      kind: "DASHBOARD", dashboardId: "dashboard-1", title: "Weekly revenue", description: null,
      parentPageId: null, index: null, baseRevision: 0, requestId: "request-dashboard",
    });
  });

  it("sends every nullable create field explicitly", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(201, {
      result: "APPLIED",
      snapshot: tree(1),
      change: null,
    }));

    await pagesAPI.createPage(workspaceId, {
      kind: "FOLDER",
      title: "Revenue",
      description: null,
      parentPageId: null,
      index: null,
      baseRevision: 0,
      requestId: "request-1",
    });

    const request = vi.mocked(globalThis.fetch).mock.calls[0][1];
    expect(JSON.parse(String(request?.body))).toEqual({
      kind: "FOLDER",
      title: "Revenue",
      description: null,
      parentPageId: null,
      index: null,
      baseRevision: 0,
      requestId: "request-1",
    });
    expect(new Headers(request?.headers).get("Content-Type")).toBe("application/json");
  });

  it("creates a Document Page with one opaque upload reference", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(201, {
      result: "APPLIED",
      snapshot: tree(1),
      change: null,
    }));

    await pagesAPI.createPage(workspaceId, {
      kind: "DOCUMENT",
      title: "Operating brief",
      description: null,
      uploadRef: "upload-opaque",
      parentPageId: null,
      index: null,
      baseRevision: 0,
      requestId: "request-document-1",
    });

    expect(JSON.parse(String(vi.mocked(globalThis.fetch).mock.calls[0][1]?.body))).toEqual({
      kind: "DOCUMENT",
      title: "Operating brief",
      description: null,
      uploadRef: "upload-opaque",
      parentPageId: null,
      index: null,
      baseRevision: 0,
      requestId: "request-document-1",
    });
  });

  it("parses Pages ProblemDetail instead of losing it in the generic API error", async () => {
    const current = tree(8);
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(409, {
      type: "about:blank",
      title: "Conflict",
      status: 409,
      detail: "PagesDirectory revision is stale.",
      instance: `/v2/workspaces/${workspaceId}/pages`,
      code: "REVISION_CONFLICT",
      current,
      requestId: "request-2",
    }));

    const error = await pagesAPI.createPage(workspaceId, {
      kind: "FOLDER",
      title: "Stale",
      description: null,
      parentPageId: null,
      index: null,
      baseRevision: 1,
      requestId: "request-2",
    }).catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(PagesApiError);
    expect((error as PagesApiError).problem?.code).toBe("REVISION_CONFLICT");
    expect((error as PagesApiError).problem?.current).toEqual(current);
  });

  it("dispatches only authorized current snapshots over SSE", async () => {
    const snapshot = tree(2);
    const latest = tree(3);
    const stream = [
      `id:2\nevent:snapshot\ndata:${JSON.stringify(snapshot)}\n\n`,
      `id:3\nevent:snapshot\ndata:${JSON.stringify(latest)}\n\n`,
    ].join("");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(stream, {
      status: 200,
      headers: { "Content-Type": "text/event-stream" },
    }));
    const onSnapshot = vi.fn();

    await pagesAPI.watchTree(workspaceId, { onSnapshot }, new AbortController().signal);

    expect(onSnapshot).toHaveBeenNthCalledWith(1, snapshot);
    expect(onSnapshot).toHaveBeenNthCalledWith(2, latest);
    const headers = new Headers(fetchMock.mock.calls[0][1]?.headers);
    expect(headers.get("X-Workspace-Id")).toBeNull();
  });
});

describe("parsePagesProblemDetail", () => {
  it("rejects generic and malformed JSON errors", () => {
    expect(parsePagesProblemDetail("not-json")).toBeNull();
    expect(parsePagesProblemDetail(JSON.stringify({ kind: "ACL_DENIED", message: "No" }))).toBeNull();
  });
});
