import type { ControlProblem } from "@/api/control/types";
import { createBrowserControlClient } from "@/api/control/client";
import { controlOperationDefinitions } from "@/api/control/generated-operations";
import { openControlSse } from "@/api/control/sse";
import { sessionFetch } from "@/lib/api/client";
import type { components } from "@/api/control/generated";

const client = createBrowserControlClient();

export type DashboardScheduleResponse = components["schemas"]["DashboardScheduleResponse"];
export type DashboardScheduleOccurrence = components["schemas"]["DashboardScheduleOccurrence"];
export type DashboardScheduleOccurrencePageResponse = components["schemas"]["DashboardScheduleOccurrencePageResponse"];
export type DashboardDefinitionVersion = components["schemas"]["DashboardDefinitionVersion"];
export type DashboardHistoryResponse = components["schemas"]["DashboardHistoryResponse"];
export type DashboardDefinition = components["schemas"]["DashboardDocument"];
export type DashboardQuery = DashboardDefinition["queries"][string];
export type DashboardQueryType = DashboardQuery["type"];
export type DashboardListItem = components["schemas"]["DashboardListItem"];
export type DashboardResponse = components["schemas"]["DashboardResponse"];
export type DashboardDraftResponse = components["schemas"]["DashboardDraftResponse"];
export type DashboardDraftReadyResult = components["schemas"]["DashboardDraftReadyResult"];
export type DashboardSessionResponse = components["schemas"]["DashboardSessionResponse"];
export type DashboardSessionSummary = components["schemas"]["DashboardSessionSummary"];
export type DashboardSessionPageResponse = components["schemas"]["DashboardSessionPageResponse"];
export type DashboardQueryRun = components["schemas"]["DashboardQueryRun"];
export type DashboardQueryRunPageResponse = components["schemas"]["DashboardQueryRunPageResponse"];
export type DashboardResult = components["schemas"]["DashboardResult"];
export type DashboardResultColumn = components["schemas"]["DashboardResultColumn"];
export type DashboardSessionEvent = components["schemas"]["DashboardSessionEvent"];
export type DashboardValueControl = components["schemas"]["Dashboardcontrol"];
export type DashboardParameterDefinition = components["schemas"]["DashboardvalueDefinition"];
export type DashboardVariableDefinition = components["schemas"]["DashboardvariableDefinition"];
export type DashboardValueType = components["schemas"]["DashboardvalueType"];
export type DashboardWidget = components["schemas"]["Dashboardwidget"];
export type DashboardLayoutNode = components["schemas"]["DashboardlayoutNode"];
export type DashboardSessionResolutionResponse = components["schemas"]["DashboardSessionResolutionResponse"];
export type DashboardJsonObject = components["schemas"]["DashboardJsonObject"];
export type DashboardJsonValue = components["schemas"]["DashboardJsonValue"];
export type DashboardDraftEvent = components["responses"]["DashboardDraftEventsResponse"]["content"]["text/event-stream"];

export class DashboardApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number, readonly problem: ControlProblem | null = null) {
    super(message);
    this.name = "DashboardApiError";
    this.status = status;
  }
}

function dataOrThrow<T>(
  response: { status: number; contentType: string | null; data: unknown },
  expectedStatus = 200,
): T {
  if (response.status !== expectedStatus || response.contentType !== "application/json") {
    const problem = response.contentType === "application/problem+json" ? response.data as ControlProblem : null;
    throw new DashboardApiError(
      problem?.detail ?? problem?.title ?? `Dashboard request failed (${response.status}).`,
      response.status,
      problem,
    );
  }
  return response.data as T;
}

function requestHeaders(idempotencyKey?: string): { "Idempotency-Key": string } | undefined {
  return idempotencyKey === undefined ? undefined : { "Idempotency-Key": idempotencyKey };
}

export function dashboardIdempotencyKey(action: string): string {
  return `dashboard-${action}-${crypto.randomUUID()}`;
}

export const dashboardAPI = {
  async delete(workspaceId: string, dashboardId: string): Promise<void> {
    const response = await client.request("deleteDashboard", { path: { workspaceId, dashboardId } }, undefined);
    if (response.status !== 204) {
      const problem = response.data as { detail?: string; title?: string } | undefined;
      throw new DashboardApiError(problem?.detail ?? problem?.title ?? `Dashboard request failed (${response.status}).`, response.status);
    }
  },
  async history(workspaceId: string, dashboardId: string, cursor: string | undefined, signal?: AbortSignal): Promise<DashboardHistoryResponse> {
    return dataOrThrow<DashboardHistoryResponse>(await client.request("listDashboardHistory", { path: { workspaceId, dashboardId }, query: { limit: 20, cursor } }, signal));
  },
  async historyVersion(workspaceId: string, dashboardId: string, changeId: string, signal?: AbortSignal): Promise<DashboardDefinitionVersion> {
    return dataOrThrow<DashboardDefinitionVersion>(await client.request("getDashboardHistoryVersion", { path: { workspaceId, dashboardId, changeId } }, signal));
  },
  async restore(workspaceId: string, dashboardId: string, changeId: string, idempotencyKey: string): Promise<DashboardResponse> {
    return dataOrThrow<DashboardResponse>(await client.request("restoreDashboardVersion", { path: { workspaceId, dashboardId, changeId }, headers: requestHeaders(idempotencyKey) }, undefined));
  },
  async getSchedules(workspaceId: string, dashboardId: string, draft: boolean, signal?: AbortSignal): Promise<DashboardScheduleResponse> {
    const response = await client.request(draft ? "getDashboardDraftSchedules" : "getDashboardSchedules", { path: { workspaceId, dashboardId } }, signal);
    return dataOrThrow<DashboardScheduleResponse>(response);
  },
  async listOccurrences(workspaceId: string, dashboardId: string, cursor?: string, signal?: AbortSignal): Promise<DashboardScheduleOccurrencePageResponse> {
    const response = await client.request("listDashboardScheduleOccurrences", { path: { workspaceId, dashboardId }, query: { cursor, limit: 20 } }, signal);
    return dataOrThrow<DashboardScheduleOccurrencePageResponse>(response);
  },
  async getOccurrence(workspaceId: string, dashboardId: string, scheduleKey: string, scheduledAt: string, signal?: AbortSignal): Promise<DashboardScheduleOccurrence> {
    const response = await client.request("getDashboardScheduleOccurrence", { path: { workspaceId, dashboardId, scheduleKey, scheduledAt } }, signal);
    return dataOrThrow<DashboardScheduleOccurrence>(response);
  },
  async list(workspaceId: string, signal?: AbortSignal): Promise<DashboardListItem[]> {
    const response = await client.request("listDashboards", {
      path: { workspaceId },
    }, signal);
    return dataOrThrow<components["schemas"]["DashboardListResponse"]>(response).items;
  },

  async get(
    workspaceId: string,
    dashboardId: string,
    signal?: AbortSignal,
  ): Promise<DashboardResponse> {
    const response = await client.request("getDashboard", {
      path: { workspaceId, dashboardId },
    }, signal);
    return dataOrThrow<DashboardResponse>(response);
  },

  async getDraft(
    workspaceId: string,
    dashboardId: string,
    signal?: AbortSignal,
  ): Promise<DashboardDraftResponse> {
    const response = await client.request("getDashboardDraft", {
      path: { workspaceId, dashboardId },
    }, signal);
    return dataOrThrow<DashboardDraftResponse>(response);
  },

  async getDraftQueryRun(workspaceId: string, dashboardId: string, runId: string, signal?: AbortSignal): Promise<DashboardQueryRun> {
    const response = await client.request("getDashboardDraftQueryRun", { path: { workspaceId, dashboardId, runId } }, signal);
    return dataOrThrow<DashboardQueryRun>(response);
  },

  async startDraft({
    workspaceId,
    dashboardId,
    parameterBindings,
    idempotencyKey,
    signal,
  }: {
    workspaceId: string;
    dashboardId: string;
    parameterBindings: DashboardJsonObject;
    idempotencyKey: string;
    signal?: AbortSignal;
  }): Promise<DashboardDraftResponse> {
    const response = await client.request("startDashboardDraft", {
      path: { workspaceId, dashboardId },
      headers: requestHeaders(idempotencyKey),
      body: { parameterBindings },
    }, signal);
    return dataOrThrow<DashboardDraftResponse>(response, 202);
  },

  async applyDraft({
    workspaceId,
    dashboardId,
    expectedRevision,
    idempotencyKey,
    signal,
  }: {
    workspaceId: string;
    dashboardId: string;
    expectedRevision: number;
    idempotencyKey: string;
    signal?: AbortSignal;
  }): Promise<DashboardResponse> {
    const response = await client.request("applyDashboardDraft", {
      path: { workspaceId, dashboardId },
      headers: requestHeaders(idempotencyKey),
      body: { expectedRevision },
    }, signal);
    return dataOrThrow<DashboardResponse>(response);
  },

  async discardDraft({
    workspaceId,
    dashboardId,
    expectedRevision,
    idempotencyKey,
    signal,
  }: {
    workspaceId: string;
    dashboardId: string;
    expectedRevision: number;
    idempotencyKey: string;
    signal?: AbortSignal;
  }): Promise<DashboardDraftResponse> {
    const response = await client.request("discardDashboardDraft", {
      path: { workspaceId, dashboardId },
      headers: requestHeaders(idempotencyKey),
      body: { expectedRevision },
    }, signal);
    return dataOrThrow<DashboardDraftResponse>(response);
  },

  async setDraftParameter({
    workspaceId,
    dashboardId,
    name,
    expectedRevision,
    value,
    idempotencyKey,
    signal,
  }: {
    workspaceId: string;
    dashboardId: string;
    name: string;
    expectedRevision: number;
    value: DashboardJsonValue;
    idempotencyKey: string;
    signal?: AbortSignal;
  }): Promise<DashboardDraftResponse> {
    const response = await client.request("setDashboardDraftParameter", {
      path: { workspaceId, dashboardId, name },
      headers: requestHeaders(idempotencyKey),
      body: { expectedRevision, value },
    }, signal);
    return dataOrThrow<DashboardDraftResponse>(response);
  },

  async setDraftVariables({ workspaceId, dashboardId, expectedRevision, variables, idempotencyKey, signal }: {
    workspaceId: string;
    dashboardId: string;
    expectedRevision: number;
    variables: DashboardJsonObject;
    idempotencyKey: string;
    signal?: AbortSignal;
  }): Promise<DashboardDraftResponse> {
    const response = await client.request("setDashboardDraftVariables", {
      path: { workspaceId, dashboardId },
      headers: requestHeaders(idempotencyKey),
      body: { expectedRevision, variables },
    }, signal);
    return dataOrThrow<DashboardDraftResponse>(response);
  },

  async refreshDraft({
    workspaceId,
    dashboardId,
    queryNames,
    idempotencyKey,
    signal,
  }: {
    workspaceId: string;
    dashboardId: string;
    queryNames: string[];
    idempotencyKey: string;
    signal?: AbortSignal;
  }): Promise<DashboardDraftResponse> {
    const response = await client.request("refreshDashboardDraft", {
      path: { workspaceId, dashboardId },
      headers: requestHeaders(idempotencyKey),
      body: { queryNames },
    }, signal);
    return dataOrThrow<DashboardDraftResponse>(response);
  },

  async cancelDraftRun({
    workspaceId,
    dashboardId,
    runId,
    idempotencyKey,
    signal,
  }: {
    workspaceId: string;
    dashboardId: string;
    runId: string;
    idempotencyKey: string;
    signal?: AbortSignal;
  }): Promise<DashboardDraftResponse> {
    const response = await client.request("cancelDashboardDraftRun", {
      path: { workspaceId, dashboardId, runId },
      headers: requestHeaders(idempotencyKey),
    }, signal);
    return dataOrThrow<DashboardDraftResponse>(response);
  },

  async openSession({
    workspaceId,
    dashboardId,
    parameterBindings,
    idempotencyKey,
    signal,
  }: {
    workspaceId: string;
    dashboardId: string;
    parameterBindings: DashboardJsonObject;
    idempotencyKey: string;
    signal?: AbortSignal;
  }): Promise<DashboardSessionResponse> {
    const response = await client.request("openDashboardSession", {
      path: { workspaceId, dashboardId },
      headers: requestHeaders(idempotencyKey),
      body: { parameterBindings },
    }, signal);
    return dataOrThrow<DashboardSessionResponse>(response, 202);
  },

  async getSession(
    workspaceId: string,
    sessionId: string,
    signal?: AbortSignal,
  ): Promise<DashboardSessionResponse> {
    const response = await client.request("getDashboardSession", {
      path: { workspaceId, sessionId },
    }, signal);
    return dataOrThrow<DashboardSessionResponse>(response);
  },

  async listSessions(
    workspaceId: string,
    dashboardId: string,
    options: { cursor?: string; pageSize?: number; signal?: AbortSignal } = {},
  ): Promise<DashboardSessionPageResponse> {
    const response = await client.request("listDashboardSessions", {
      path: { workspaceId, dashboardId },
      query: { cursor: options.cursor, pageSize: options.pageSize },
    }, options.signal);
    return dataOrThrow<DashboardSessionPageResponse>(response);
  },

  async listSessionQueryRuns(
    workspaceId: string,
    sessionId: string,
    options: { cursor?: string; pageSize?: number; signal?: AbortSignal } = {},
  ): Promise<DashboardQueryRunPageResponse> {
    const response = await client.request("listDashboardSessionQueryRuns", {
      path: { workspaceId, sessionId },
      query: { cursor: options.cursor, pageSize: options.pageSize },
    }, options.signal);
    return dataOrThrow<DashboardQueryRunPageResponse>(response);
  },

  async resolveSession({ workspaceId, sessionId, variables, idempotencyKey, signal }: {
    workspaceId: string; sessionId: string; variables: DashboardJsonObject; idempotencyKey: string; signal?: AbortSignal;
  }): Promise<DashboardSessionResolutionResponse> {
    const response = await client.request("resolveDashboardSession", {
      path: { workspaceId, sessionId }, headers: requestHeaders(idempotencyKey), body: { variables },
    }, signal);
    return dataOrThrow<DashboardSessionResolutionResponse>(response);
  },

  async refreshSession({ workspaceId, sessionId, variables, queryNames, idempotencyKey, signal }: {
    workspaceId: string; sessionId: string; variables: DashboardJsonObject; queryNames: string[]; idempotencyKey: string; signal?: AbortSignal;
  }): Promise<DashboardSessionResolutionResponse> {
    const response = await client.request("refreshDashboardSession", {
      path: { workspaceId, sessionId }, headers: requestHeaders(idempotencyKey), body: { variables, queryNames },
    }, signal);
    return dataOrThrow<DashboardSessionResolutionResponse>(response);
  },

  async getSessionQueryRun(workspaceId: string, sessionId: string, runId: string, signal?: AbortSignal): Promise<DashboardQueryRun> {
    const response = await client.request("getDashboardSessionQueryRun", { path: { workspaceId, sessionId, runId } }, signal);
    return dataOrThrow<DashboardQueryRun>(response);
  },

  async getResult({
    workspaceId,
    sessionId,
    runId,
    signal,
  }: {
    workspaceId: string;
    sessionId: string;
    runId: string;
    signal?: AbortSignal;
  }): Promise<DashboardResult> {
    const response = await client.request("getDashboardQueryResult", {
      path: { workspaceId, sessionId, runId },
    }, signal);
    return dataOrThrow<DashboardResult>(response);
  },

  async getDraftResult({
    workspaceId,
    dashboardId,
    runId,
    signal,
  }: {
    workspaceId: string;
    dashboardId: string;
    runId: string;
    signal?: AbortSignal;
  }): Promise<DashboardResult> {
    const response = await client.request("getDashboardDraftQueryResult", {
      path: { workspaceId, dashboardId, runId },
    }, signal);
    return dataOrThrow<DashboardResult>(response);
  },

  async watchSession({
    workspaceId,
    sessionId,
    signal,
    onEvent,
  }: {
    workspaceId: string;
    sessionId: string;
    signal: AbortSignal;
    onEvent: (event: DashboardSessionEvent) => void;
  }): Promise<void> {
    await openControlSse({
      fetchImplementation: sessionFetch,
      operation: controlOperationDefinitions.watchDashboardSession,
      request: { path: { workspaceId, sessionId } },
      signal,
      handlers: {
        onEvent: (event) => onEvent(event.data),
      },
    });
  },

  async watchDraft({
    workspaceId,
    dashboardId,
    signal,
    onEvent,
  }: {
    workspaceId: string;
    dashboardId: string;
    signal: AbortSignal;
    onEvent: (event: DashboardDraftEvent) => void;
  }): Promise<void> {
    await openControlSse({
      fetchImplementation: sessionFetch,
      operation: controlOperationDefinitions.watchDashboardDraft,
      request: { path: { workspaceId, dashboardId } },
      signal,
      handlers: {
        onEvent: (event) => onEvent(event.data),
      },
    });
  },
};
