import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import { checkSemanticDefinition, isSemanticCheckWarning, semanticCheckProblemMessage, semanticCheckResultSchema, semanticSaveFailure } from "@/api/semantic-check";

const apiFetch = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/client")>()),
  apiFetch,
}));

function problem(code: string, detail: string, violations: Array<{ field: string; code: string; message: string }>) {
  return { type: "https://monomer.cc/problems/x", title: "Conflict", status: 409, detail, instance: "/v2", code, requestId: "r", traceId: "r", retryable: false, violations };
}

describe("semanticSaveFailure", () => {
  it("maps declared violations to definition fields and keeps the safe detail", () => {
    const failure = semanticSaveFailure(new ApiError("x", 409, "", problem(
      "SEMANTIC_NAME_CONFLICT",
      "Another definition in this workspace already uses this name.",
      [{ field: "name", code: "SEMANTIC_NAME_CONFLICT", message: "This name is already used. Choose another name." }],
    )));
    expect(failure).toEqual({
      message: "Another definition in this workspace already uses this name.",
      fieldErrors: { name: "This name is already used. Choose another name." },
    });
  });

  it("keeps whole-definition violations in the message only", () => {
    const failure = semanticSaveFailure(new ApiError("x", 400, "", problem(
      "SEMANTIC_DEFINITION_INVALID",
      "Semantic model must define exactly one primary entity",
      [{ field: "definition", code: "SEMANTIC_DEFINITION_INVALID", message: "Semantic model must define exactly one primary entity" }],
    )));
    expect(failure.fieldErrors).toEqual({});
    expect(failure.message).toBe("Semantic model must define exactly one primary entity");
  });

  it("shows a safe generic message for an undeclared status", () => {
    expect(semanticSaveFailure(new ApiError("boom", 502, "<html>", null)).message)
      .toBe("The definition couldn't be saved. Try again. (status 502)");
    expect(semanticSaveFailure(new Error("network")).message).toBe("The definition couldn't be saved. Try again.");
  });
});

describe("checkSemanticDefinition", () => {
  beforeEach(() => apiFetch.mockReset());

  const draft = { kind: "METRIC" as const, metricId: "m1", request: { name: "revenue", type: "simple" as const, typeParams: { semanticModelName: "billing", measure: "revenue" } } };

  it.each([null, {}, [], { ok: false }, { partitionFilters: [], ok: true, runsOn: [], problems: [], preview: { sql: "SELECT 1" } }, { partitionFilters: [], ok: false, runsOn: [], problems: [null], preview: null }])("rejects malformed Check responses without returning unsafe panel data: %j", async (payload) => {
    apiFetch.mockResolvedValue(new Response(JSON.stringify(payload)));
    await expect(checkSemanticDefinition("w1", draft)).rejects.toThrow("The check is unavailable. Try again.");
  });

  it("rejects an empty response", async () => {
    apiFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(checkSemanticDefinition("w1", draft)).rejects.toThrow("The check is unavailable. Try again.");
  });

  it("keeps HTTP failures as query errors and lets a later retry succeed", async () => {
    apiFetch.mockRejectedValueOnce(new ApiError("Unavailable", 400, "", null));
    apiFetch.mockResolvedValueOnce(new Response(JSON.stringify({ partitionFilters: [], ok: false, runsOn: [], problems: [], preview: null })));
    await expect(checkSemanticDefinition("w1", draft)).rejects.toMatchObject({ status: 400 });
    await expect(checkSemanticDefinition("w1", draft)).resolves.toEqual({ partitionFilters: [], ok: false, runsOn: [], problems: [], preview: null });
  });

  it("posts one metric draft with its stable id and the wire definition", async () => {
    apiFetch.mockResolvedValue(new Response(JSON.stringify({ partitionFilters: [], ok: true, runsOn: ["c1"], preview: null, problems: [] })));
    const result = await checkSemanticDefinition("w1", {
      kind: "METRIC",
      metricId: "m1",
      request: { name: "revenue", type: "simple", typeParams: { semanticModelName: "billing", measure: "revenue" } },
    });
    expect(result.ok).toBe(true);
    const [url, init] = apiFetch.mock.calls[0];
    expect(String(url)).toMatch(/\/v2\/workspaces\/w1\/semantic\/check$/);
    expect(JSON.parse(init.body)).toEqual({
      kind: "METRIC",
      metricId: "m1",
      definition: { schemaVersion: 3, name: "revenue", type: "simple", semanticModelName: "billing", measure: "revenue" },
    });
  });
});

describe("time kind Check problems", () => {
  const result = (code: string, message: string) => ({ ok: false, runsOn: [], partitionFilters: [], preview: null, problems: [{ code, connectorId: "c1", field: "dimensions[0]", definition: null, message }] });

  it("parses the time kind and spine column codes", () => {
    for (const code of ["TIME_KIND_UNDECLARED", "TIME_KIND_MISMATCH", "TIME_SPINE_COLUMN_TYPE_INVALID"]) {
      expect(semanticCheckResultSchema.safeParse(result(code, "x")).success).toBe(true);
    }
    expect(semanticCheckResultSchema.safeParse(result("TIME_KIND_INVENTED", "x")).success).toBe(false);
  });

  it("treats only an undeclared timeKind as a warning and says why it matters", () => {
    expect(isSemanticCheckWarning({ code: "TIME_KIND_UNDECLARED" })).toBe(true);
    expect(isSemanticCheckWarning({ code: "TIME_KIND_MISMATCH" })).toBe(false);
    expect(semanticCheckProblemMessage({ code: "TIME_KIND_UNDECLARED", message: "Declare timeKind for this time dimension." }))
      .toBe("Declare timeKind for this time dimension so times convert to the Reporting time zone correctly.");
    const mismatch = "timeKind `local` doesn't match this column's Catalog type `timestamptz`.";
    expect(semanticCheckProblemMessage({ code: "TIME_KIND_MISMATCH", message: mismatch })).toBe(mismatch);
  });
});
