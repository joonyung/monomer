import { describe, expect, it } from "vitest";
import { controlOperationDefinitions } from "./generated-operations";
import { ControlProblemValidationError } from "./problem";
import {
  ControlResponseContractError,
  readControlResponse,
} from "./transport";

const workspaceId = "019e501f-0000-7000-8000-000000000011";

function problemBody(): object {
  return {
    code: "ADMISSION_LIMITED",
    detail: "Retry later.",
    instance: `/v2/workspaces/${workspaceId}/agent/invoke`,
    retryable: true,
    status: 429,
    title: "Admission limited",
    requestId: "request-1",
    traceId: "trace-1",
    type: "https://monomer.cc/problems/admission-limited",
  };
}

describe("Control response rails", () => {
  it("permits non-retryable ServiceUnavailable responses without Retry-After on every Agent action", () => {
    const agentOperations = [
      controlOperationDefinitions.agentWorkspaceList,
      controlOperationDefinitions.agentWorkspaceSearch,
      controlOperationDefinitions.agentWorkspaceRead,
      controlOperationDefinitions.agentWorkspaceCreate,
      controlOperationDefinitions.agentWorkspaceEdit,
      controlOperationDefinitions.agentWorkspaceInvoke,
    ];

    for (const operation of agentOperations) {
      expect(operation.responses).toContainEqual({
        contentTypes: ["application/problem+json"],
        requiredHeaders: [],
        status: 503,
      });
    }
  });

  it("keeps Dashboard session acceptance unadorned and exposes the exact semantic problem rail", () => {
    expect(controlOperationDefinitions.openDashboardSession.responses).toContainEqual({
      contentTypes: ["application/json"],
      requiredHeaders: [],
      status: 202,
    });
    expect(controlOperationDefinitions.openDashboardSession.responses).toContainEqual({
      contentTypes: ["application/problem+json"],
      requiredHeaders: [],
      status: 422,
    });
    expect(controlOperationDefinitions.watchDashboardSession.responses).toContainEqual({
      contentTypes: ["text/event-stream"],
      requiredHeaders: [],
      status: 200,
    });
  });

  it("rejects an undeclared status before producing a typed response", async () => {
    await expect(readControlResponse(
      controlOperationDefinitions.findSemanticModels,
      new Response("{}", { headers: { "Content-Type": "application/json" }, status: 418 }),
    )).rejects.toBeInstanceOf(ControlResponseContractError);
  });

  it("keeps the contract violation out of the user-facing message", async () => {
    const failure = await readControlResponse(
      controlOperationDefinitions.listCatalogSchemas,
      new Response("{}", { headers: { "Content-Type": "application/json" }, status: 503 }),
    ).catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ControlResponseContractError);
    expect((failure as ControlResponseContractError).message).toBe("Monomer couldn't load this right now. Try again in a moment.");
    expect((failure as ControlResponseContractError).detail).toBe("Control response status 503 is not declared for listCatalogSchemas.");
  });

  it("rejects wrong, absent, and unexpected response bodies", async () => {
    const wrongContentType = readControlResponse(
      controlOperationDefinitions.findSemanticModels,
      new Response("{}", { headers: { "Content-Type": "text/plain" }, status: 200 }),
    );
    const missingContentType = readControlResponse(
      controlOperationDefinitions.findSemanticModels,
      new Response("{}", { status: 200 }),
    );
    const missingRequiredBody = readControlResponse(
      controlOperationDefinitions.findSemanticModels,
      new Response(null, { headers: { "Content-Type": "application/json" }, status: 200 }),
    );
    for (const response of [wrongContentType, missingContentType, missingRequiredBody]) {
      await expect(response).rejects.toBeInstanceOf(ControlResponseContractError);
    }
  });

  it("accepts a declared 204 even when the browser exposes an empty body stream", async () => {
    const chromiumNoContent = new Response(null, { status: 204 });
    Object.defineProperty(chromiumNoContent, "body", { value: new ReadableStream() });

    await expect(readControlResponse(controlOperationDefinitions.getActiveCatalogIngest, chromiumNoContent))
      .resolves.toMatchObject({ status: 204, contentType: null, data: undefined });
    await expect(readControlResponse(
      controlOperationDefinitions.getActiveCatalogIngest,
      new Response(null, { headers: { "Content-Type": "application/json" }, status: 204 }),
    )).rejects.toBeInstanceOf(ControlResponseContractError);
  });

  it("rejects a required Retry-After header before decoding a Problem", async () => {
    await expect(readControlResponse(
      controlOperationDefinitions.agentWorkspaceInvoke,
      new Response(JSON.stringify(problemBody()), {
        headers: { "Content-Type": "application/problem+json" },
        status: 429,
      }),
    )).rejects.toBeInstanceOf(ControlResponseContractError);
  });

  it("reads an unconfirmed create Problem without requiring a retry header", async () => {
    const problem = {
      ...problemBody(),
      code: "OWNER_UNAVAILABLE",
      detail: "List Dashboards before sending the create request again.",
      instance: `/v2/workspaces/${workspaceId}/agent/create`,
      retryable: false,
      status: 503,
      title: "Service Unavailable",
      type: "https://monomer.cc/problems/owner-unavailable",
    };
    await expect(readControlResponse(
      controlOperationDefinitions.agentWorkspaceCreate,
      new Response(JSON.stringify(problem), {
        headers: { "Content-Type": "application/problem+json" },
        status: 503,
      }),
    )).resolves.toMatchObject({ status: 503, data: problem, metadata: { retryAfter: null } });
  });

  it("rejects a malformed ProblemDetails body after status and content dispatch", async () => {
    await expect(readControlResponse(
      controlOperationDefinitions.agentWorkspaceInvoke,
      new Response(JSON.stringify({ ...problemBody(), unexpected: true }), {
        headers: {
          "Content-Type": "application/problem+json",
          "Retry-After": "2",
        },
        status: 429,
      }),
    )).rejects.toBeInstanceOf(ControlProblemValidationError);
  });
});
