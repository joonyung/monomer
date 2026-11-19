import { afterEach, describe, expect, it, vi } from "vitest";
import { semanticDefinitionAPI } from "@/api/semantic-definition-history";
import { ApiError } from "@/lib/api/client";

const history = { definitionId: "definition-1", name: "revenue", deleted: true, changes: [], nextCursor: "older / changes" };

afterEach(() => vi.restoreAllMocks());

describe("semantic definition lifecycle API", () => {
  it.each(["semantic-models", "metrics"] as const)("deletes %s with an empty 204 and encoded identifiers", async (kind) => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 204 }));
    await expect(semanticDefinitionAPI.delete("workspace / 1", kind, "definition / 1")).resolves.toBeUndefined();
    expect(fetchMock.mock.calls[0][0]).toBe(`http://127.0.0.1:11077/v2/workspaces/workspace%20%2F%201/semantic/${kind}/definition%20%2F%201`);
    expect(fetchMock.mock.calls[0][1]?.method).toBe("DELETE");
  });
  it("keeps repeat deletes as a 404 error for the confirmation flow", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 404 }));
    await expect(semanticDefinitionAPI.delete("workspace-1", "metrics", "missing")).rejects.toMatchObject({ status: 404, name: "ApiError" });
  });
  it("reads deleted history without first reading the current definition and preserves its cursor", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(history)));
    await expect(semanticDefinitionAPI.history("workspace-1", "metrics", "definition-1", "older / changes")).resolves.toEqual(history);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toContain("/metrics/definition-1/history?limit=20&cursor=older+%2F+changes");
  });
  it("restores a recorded change using its definition id and stable mutation key", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ metricId: history.definitionId })));
    await semanticDefinitionAPI.restore("workspace-1", "metrics", history.definitionId, "change / 1", "restore-intent-1");
    const [url, request] = fetchMock.mock.calls[0];
    expect(String(url)).toContain("/metrics/definition-1/history/change%20%2F%201/restore");
    expect(request?.method).toBe("POST");
    expect(new Headers(request?.headers).get("Idempotency-Key")).toBe("restore-intent-1");
    expect(request?.body).toBeUndefined();
  });
  it("preserves a restore name conflict and its repair detail", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ kind: "SEMANTIC_NAME_CONFLICT", message: "The name is already used." }), { status: 409 }));
    await expect(semanticDefinitionAPI.restore("workspace-1", "metrics", "old-id", "old-change", "restore-intent-1")).rejects.toBeInstanceOf(ApiError);
  });
});
