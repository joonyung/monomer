import type { components } from "./control/generated";
import { apiJson, ApiError } from "@/lib/api/client";
import { CONTROL_API_URL } from "@/lib/auth/config";
import { useAuthStore } from "@/lib/auth/store";
import { workspaceBffUrl } from "./workspace-bff";
import type {
  DashboardJsonObject,
  DashboardResult,
} from "./dashboard";
import type { PageContentFormat } from "./page-content";

export type PublicPage = components["schemas"]["PublicPage"];
export type PublicTree = components["schemas"]["PublicPagesTree"];
export type PublicSession = components["schemas"]["PublicDashboardSession"];
export type PublicRun = components["schemas"]["PublicDashboardQueryRun"];
export type PublicEntry = components["schemas"]["PublicDashboardSessionEntry"];
export type PublicSessionSummary =
  components["schemas"]["PublicDashboardSessionSummary"];
export interface PublicPageList<T> {
  items: T[];
  nextCursor: string | null;
}
// An anonymous reader has no session to refresh; a signed-in reader's expired token must be refreshed
// like any other request, or a long-open Pages tab turns into "Page unavailable".
const skipRefresh = () => useAuthStore.getState().status !== "authenticated";
const path = (workspaceId: string, suffix: string) =>
  `${CONTROL_API_URL}/v2/public/workspaces/${encodeURIComponent(workspaceId)}/pages${suffix}`;
const read = <T>(workspaceId: string, suffix: string, signal?: AbortSignal) =>
  apiJson<T>(path(workspaceId, suffix), {
    signal,
    skipAuth: false,
    skipRefresh: skipRefresh(),
    credentials: "include",
    cache: "no-store",
  });
const pagePath = (pageId: string) => `/${encodeURIComponent(pageId)}`;
const sessionPath = (pageId: string, sessionId: string) =>
  `${pagePath(pageId)}/sessions/${encodeURIComponent(sessionId)}`;
export const publicPagesAPI = {
  access: (workspaceId: string, signal?: AbortSignal) =>
    apiJson<{ mode: "MEMBER" | "VIEWER" | "PUBLIC" }>(
      workspaceBffUrl(workspaceId, "/pages/access"),
      { signal, skipRefresh: skipRefresh(), cache: "no-store" },
    ),
  tree: (workspaceId: string, signal?: AbortSignal) =>
    read<PublicTree>(workspaceId, "/tree", signal),
  content: (workspaceId: string, pageId: string, signal?: AbortSignal) =>
    read<{ title: string; format: PageContentFormat; source: string }>(
      workspaceId,
      `${pagePath(pageId)}/content`,
      signal,
    ),
  entry: (
    workspaceId: string,
    pageId: string,
    sessionId?: string,
    signal?: AbortSignal,
  ) =>
    read<PublicEntry>(
      workspaceId,
      `${pagePath(pageId)}/sessions/entry${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ""}`,
      signal,
    ),
  sessions: (
    workspaceId: string,
    pageId: string,
    cursor: string | null,
    signal?: AbortSignal,
  ) =>
    read<PublicPageList<PublicSessionSummary>>(
      workspaceId,
      `${pagePath(pageId)}/sessions?pageSize=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
      signal,
    ),
  runs: (
    workspaceId: string,
    pageId: string,
    sessionId: string,
    cursor: string | null,
    signal?: AbortSignal,
  ) =>
    read<PublicPageList<PublicRun>>(
      workspaceId,
      `${sessionPath(pageId, sessionId)}/query-runs?pageSize=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
      signal,
    ),
  run: (
    workspaceId: string,
    pageId: string,
    sessionId: string,
    runId: string,
    signal?: AbortSignal,
  ) =>
    read<PublicRun>(
      workspaceId,
      `${sessionPath(pageId, sessionId)}/query-runs/${encodeURIComponent(runId)}`,
      signal,
    ),
  result: (
    workspaceId: string,
    pageId: string,
    sessionId: string,
    runId: string,
    signal?: AbortSignal,
  ) =>
    read<DashboardResult>(
      workspaceId,
      `${sessionPath(pageId, sessionId)}/query-runs/${encodeURIComponent(runId)}/result`,
      signal,
    ),
  resolve: (
    workspaceId: string,
    pageId: string,
    sessionId: string,
    variables: DashboardJsonObject,
    key: string,
  ) =>
    apiJson<{
      variables: DashboardJsonObject;
      queryRuns: Record<string, string>;
    }>(path(workspaceId, `${sessionPath(pageId, sessionId)}:resolve`), {
      method: "POST",
      body: JSON.stringify({ variables }),
      headers: { "Content-Type": "application/json", "Idempotency-Key": key },
      skipAuth: false,
      skipRefresh: skipRefresh(),
      credentials: "include",
      cache: "no-store",
    }),
};
export function memberSessionEntry(
  workspaceId: string,
  dashboardId: string,
  sessionId?: string,
  signal?: AbortSignal,
) {
  return apiJson<components["schemas"]["DashboardSessionEntry"]>(
    workspaceBffUrl(
      workspaceId,
      `/dashboards/${encodeURIComponent(dashboardId)}/sessions/entry${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ""}`,
    ),
    { signal },
  );
}
export const publicPagesKey = (workspaceId: string) =>
  ["public-pages", workspaceId] as const;
export const isPublicAccessDenied = (error: unknown) =>
  error instanceof ApiError && [401, 403, 404].includes(error.status);
