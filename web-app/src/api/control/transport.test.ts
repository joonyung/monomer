import { afterEach, beforeEach, describe, expect, expectTypeOf, it, vi } from "vitest";
import { useAuthStore } from "@/lib/auth/store";
import { createControlClient } from "./client";
import type { components } from "./generated";
import { controlOperationDefinitions } from "./generated-operations";
import {
  createControlTransport,
  createBrowserControlTransport,
  isControlProblemResponse,
  retryAfterMilliseconds,
} from "./transport";
import type { ControlProblem, ControlProblemResponse, ControlRequest } from "./types";

const findSemanticModels = controlOperationDefinitions.findSemanticModels;
const agentWorkspaceInvoke = controlOperationDefinitions.agentWorkspaceInvoke;

const workspaceId = "019e501f-0000-7000-8000-000000000011";

describe("generated Control client transport", () => {
  beforeEach(() => useAuthStore.getState().logout());
  afterEach(() => {
    useAuthStore.getState().logout();
    vi.restoreAllMocks();
  });

  it("keeps generated Agent request types connected to the contract", () => {
    type AgentRequest = ControlRequest<"agentWorkspaceInvoke">;
    type AgentRequestBody = Exclude<AgentRequest["body"], undefined>;
    type AgentProblemResponse = ControlProblemResponse<"agentWorkspaceInvoke">;

    expectTypeOf<AgentRequestBody>().toEqualTypeOf<components["schemas"]["AgentInvokeRequest"]>();
    expectTypeOf<AgentProblemResponse["status"]>().toEqualTypeOf<400 | 401 | 403 | 404 | 409 | 429 | 500 | 503>();

    const validRequest: AgentRequest = {
      path: { workspaceId },
      body: {
        action: "run",
        input: { connectorId: "019e501f-0000-7000-8000-000000000012", kind: "SQL_EXPLORE_RUN", sql: "select 1" },
        path: "workspace/explore/runs",
      },
    };
    expect(validRequest.body.action).toBe("run");

    const requestWithServerAuthorityFields: AgentRequest = {
      path: { workspaceId },
      body: validRequest.body,
      // @ts-expect-error Workspace authority is only the path parameter.
      expectedWorkspaceId: workspaceId,
    };
    expect(requestWithServerAuthorityFields.path.workspaceId).toBe(workspaceId);
  });

  it("uses the explicit Control origin and session credentials", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue(new Response("{}", {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));
    const transport = createControlTransport({ fetchImplementation });
    const client = createControlClient(transport);

    const response = await client.request("findSemanticModels", {
      path: { workspaceId },
    }, undefined);

    expect(response).toMatchObject({
      status: 200,
      contentType: "application/json",
      data: {},
    });
    expect(String(fetchImplementation.mock.calls[0]?.[0])).toBe(
      `http://127.0.0.1:11077/v2/workspaces/${workspaceId}/semantic/semantic-models`,
    );
    const init = fetchImplementation.mock.calls[0]?.[1];
    expect(init).toMatchObject({ cache: "no-store", credentials: "include", method: "GET" });
    const headers = new Headers(init?.headers);
    expect(headers.get("Authorization")).toBeNull();
  });

  it("injects the current browser session bearer without accepting it from callers", async () => {
    useAuthStore.setState((state) => ({
      status: "authenticated",
      accessToken: "catalog-session-token",
      accessTokenExpiresAt: "2030-01-01T00:00:00Z",
      user: { id: "u1", email: "u1@example.com", displayName: "User One" },
      authEpoch: state.authEpoch + 1,
    }));
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));
    const client = createControlClient(createBrowserControlTransport());

    await client.request("findSemanticModels", {
      path: { workspaceId },
    }, undefined);

    const init = fetchMock.mock.calls[0]?.[1];
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer catalog-session-token");
  });

  it("surfaces application/problem+json and Retry-After without a bearer pass-through", async () => {
    const problem: ControlProblem = {
      type: "https://monomer.cc/problems/admission-limited",
      title: "Admission limited",
      status: 429,
      detail: "Retry later.",
      instance: `/v2/workspaces/${workspaceId}/agent/invoke`,
      code: "ADMISSION_LIMITED",
      requestId: "request-2",
      traceId: "trace-1",
      retryable: true,
    };
    const fetchImplementation = vi.fn().mockResolvedValue(new Response(JSON.stringify(problem), {
      status: 429,
      headers: {
        "Content-Type": "application/problem+json; charset=utf-8",
        "Retry-After": "2",
      },
    }));
    const transport = createControlTransport({ fetchImplementation });

    const response = await transport.request(agentWorkspaceInvoke, {
      path: { workspaceId },
      body: {
        action: "run",
        input: { connectorId: "019e501f-0000-7000-8000-000000000012", kind: "SQL_EXPLORE_RUN", sql: "select 1" },
        path: "workspace/explore/runs",
      },
    }, undefined);

    expect(isControlProblemResponse(response)).toBe(true);
    if (!isControlProblemResponse(response)) throw new Error("Expected a Control Problem response.");
    expect(response.data).toEqual(problem);
    expect(response.metadata.retryAfter).toBe("2");
    expect(retryAfterMilliseconds(response.metadata.retryAfter, 1_000)).toBe(2_000);
    const init = fetchImplementation.mock.calls[0]?.[1];
    expect(new Headers(init?.headers).get("Authorization")).toBeNull();
    expect(JSON.parse(String(init?.body))).toEqual({
      action: "run",
      input: { connectorId: "019e501f-0000-7000-8000-000000000012", kind: "SQL_EXPLORE_RUN", sql: "select 1" },
      path: "workspace/explore/runs",
    });
  });

  it("rejects a bearer header even when an unsafe caller bypasses the generated request type", async () => {
    const transport = createControlTransport({ fetchImplementation: vi.fn() });

    await expect(transport.request(findSemanticModels, {
      path: { workspaceId },
      headers: { Authorization: "Bearer must-not-cross" } as never,
    }, undefined)).rejects.toMatchObject({ name: "ControlTransportError" });
  });
});
