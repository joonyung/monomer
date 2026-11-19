import { ApiError, apiJson, openEventStream } from "@/lib/api/client";
import { workspaceBffUrl } from "@/api/workspace-bff";
import type { PageContentFormat } from "@/api/page-content";

export type { PageContentFormat } from "@/api/page-content";

export type PageKind = "FOLDER" | "DOCUMENT" | "DASHBOARD";
export type PageActorKind = "USER" | "AGENT" | "SYSTEM";

export interface PageActorResponse {
  kind: PageActorKind;
  id: string;
}

export interface PageResponse {
  pageId: string;
  kind: PageKind;
  documentFormat: PageContentFormat | null;
  dashboardId: string | null;
  title: string | null;
  displayTitle: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PagePlacementResponse {
  pageId: string;
  children: PagePlacementResponse[];
}

export interface PagesTreeResponse {
  workspaceId: string;
  revision: number;
  pages: PageResponse[];
  placements: PagePlacementResponse[];
  updatedAt: string;
  updatedBy: PageActorResponse;
}

export interface PageDetailResponse {
  page: PageResponse;
  path: string[];
  children: string[];
  revision: number;
}

export type PageDirectoryChangeType =
  | "folder-created"
  | "document-page-created"
  | "dashboard-page-created"
  | "navigation-metadata-updated"
  | "page-deleted"
  | "placements-patched";

export interface PageDirectoryChangeResponse {
  type: PageDirectoryChangeType;
  pageId: string | null;
  appliedOperationCount: number | null;
}

export type PageMutationResult = "APPLIED" | "REPLAYED";

export interface PageMutationResponse {
  result: PageMutationResult;
  snapshot: PagesTreeResponse;
  change: PageDirectoryChangeResponse | null;
}

interface CreatePageRequestBase {
  description: string | null;
  parentPageId: string | null;
  index: number | null;
  baseRevision: number;
  requestId: string;
}

export interface CreateFolderPageRequest extends CreatePageRequestBase {
  kind: "FOLDER";
  title: string;
}

export interface CreateDocumentPageRequest extends CreatePageRequestBase {
  kind: "DOCUMENT";
  title: string;
  uploadRef: string;
}

export interface CreateDashboardPageRequest extends CreatePageRequestBase {
  kind: "DASHBOARD";
  title: string;
  dashboardId: string;
}

export type CreatePageRequest = CreateFolderPageRequest | CreateDocumentPageRequest | CreateDashboardPageRequest;

export interface UpdatePageRequest {
  title: string | null;
  description: string | null;
  baseRevision: number;
  requestId: string;
}

export interface DeletePageRequest {
  baseRevision: number;
  requestId: string;
}

export type PagePlacementPatchOperation =
  | { op: "test" | "add" | "replace"; path: string; value: unknown }
  | { op: "remove"; path: string };

export interface PatchPagesTreeRequest {
  baseRevision: number;
  requestId: string;
  patch: PagePlacementPatchOperation[];
}

export interface PagesProblemDetail {
  type?: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  code: string;
  current?: PagesTreeResponse;
  requestId?: string;
  existingRequestHash?: string;
  path?: string | null;
  operationIndex?: number | null;
  maximum?: number;
  maximumBytes?: number;
}

export class PagesApiError extends Error {
  readonly status: number;
  readonly problem: PagesProblemDetail | null;
  readonly apiError: ApiError | null;

  constructor(
    message: string,
    status: number,
    problem: PagesProblemDetail | null,
    apiError: ApiError | null,
  ) {
    super(message);
    this.name = "PagesApiError";
    this.status = status;
    this.problem = problem;
    this.apiError = apiError;
  }
}

export class PagesRequestAbortedError extends Error {
  constructor() {
    super("Pages request was aborted.");
    this.name = "PagesRequestAbortedError";
  }
}

export interface PagesTreeStreamHandlers {
  onSnapshot: (snapshot: PagesTreeResponse) => void;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function parsePagesProblemDetail(bodyText: string): PagesProblemDetail | null {
  if (!bodyText) return null;
  try {
    const parsed: unknown = JSON.parse(bodyText);
    if (!isRecord(parsed)) return null;
    if (
      typeof parsed.title !== "string" ||
      typeof parsed.status !== "number" ||
      typeof parsed.detail !== "string" ||
      typeof parsed.code !== "string"
    ) {
      return null;
    }
    return parsed as unknown as PagesProblemDetail;
  } catch {
    return null;
  }
}

export function toPagesApiError(error: unknown): PagesApiError {
  if (error instanceof PagesApiError) return error;
  if (error instanceof ApiError) {
    const problem = parsePagesProblemDetail(error.bodyText);
    return new PagesApiError(problem?.detail ?? error.message, error.status, problem, error);
  }
  const message = error instanceof Error ? error.message : "Pages request failed.";
  return new PagesApiError(message, 0, null, null);
}

function pagesBaseUrl(workspaceId: string): string {
  return workspaceBffUrl(workspaceId, "/pages");
}

async function pagesJson<T>(
  url: string,
  init: Parameters<typeof apiJson<T>>[1],
): Promise<T> {
  try {
    return await apiJson<T>(url, init);
  } catch (error) {
    if (init?.signal?.aborted) throw new PagesRequestAbortedError();
    throw toPagesApiError(error);
  }
}

function parsePagesTreeStreamMessage(
  type: string,
  data: string,
): { snapshot?: PagesTreeResponse } {
  const parsed: unknown = JSON.parse(data);
  if (type === "snapshot") {
    return { snapshot: parsed as PagesTreeResponse };
  }
  throw new Error(`Unsupported Pages stream event: ${type}`);
}

export const pagesAPI = {
  async getTree(workspaceId: string, signal?: AbortSignal): Promise<PagesTreeResponse> {
    return pagesJson(`${pagesBaseUrl(workspaceId)}/tree`, { signal });
  },

  async getPage(
    workspaceId: string,
    pageId: string,
    signal?: AbortSignal,
  ): Promise<PageDetailResponse> {
    return pagesJson(
      `${pagesBaseUrl(workspaceId)}/${encodeURIComponent(pageId)}`,
      { signal },
    );
  },

  async createPage(
    workspaceId: string,
    request: CreatePageRequest,
    signal?: AbortSignal,
  ): Promise<PageMutationResponse> {
    return pagesJson(pagesBaseUrl(workspaceId), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
      signal,
    });
  },

  async patchTree(
    workspaceId: string,
    request: PatchPagesTreeRequest,
    signal?: AbortSignal,
  ): Promise<PageMutationResponse> {
    return pagesJson(`${pagesBaseUrl(workspaceId)}/tree`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
      signal,
    });
  },

  async updatePage(
    workspaceId: string,
    pageId: string,
    request: UpdatePageRequest,
    signal?: AbortSignal,
  ): Promise<PageMutationResponse> {
    return pagesJson(
      `${pagesBaseUrl(workspaceId)}/${encodeURIComponent(pageId)}`,
      {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
        signal,
      },
    );
  },

  async deletePage(
    workspaceId: string,
    pageId: string,
    request: DeletePageRequest,
    signal?: AbortSignal,
  ): Promise<PageMutationResponse> {
    return pagesJson(
      `${pagesBaseUrl(workspaceId)}/${encodeURIComponent(pageId)}`,
      {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
        signal,
      },
    );
  },

  async watchTree(
    workspaceId: string,
    handlers: PagesTreeStreamHandlers,
    signal: AbortSignal,
  ): Promise<void> {
    await openEventStream(
      `${pagesBaseUrl(workspaceId)}/tree/events`,
      {
        onEvent: (type, data) => {
          const message = parsePagesTreeStreamMessage(type, data);
          if (message.snapshot) handlers.onSnapshot(message.snapshot);
        },
      },
      signal,
    );
  },
};
