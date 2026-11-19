import { afterEach, describe, expect, it, vi } from "vitest";
import { timeSpineAPI } from "@/api/time-spine";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const definition = {
  schemaVersion: 1 as const,
  timeSpines: [{
    relation: { catalog: "analytics", schema: "shared", relation: "dim_date" },
    primaryColumn: { name: "date_day", granularity: "day" as const },
  }],
};

afterEach(() => vi.unstubAllGlobals());

describe("timeSpineAPI", () => {
  it("lists current configurations by Connector", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([{ connectorId: "connector-1", definition }]));
    vi.stubGlobal("fetch", fetchMock);

    await expect(timeSpineAPI.list("workspace / 1")).resolves.toEqual([{ connectorId: "connector-1", definition }]);
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain("/v2/workspaces/workspace%20%2F%201/semantic/time-spines");
    expect(fetchMock.mock.calls[0]?.[1]?.method ?? "GET").toBe("GET");
  });

  it("replaces one Connector configuration with the full LWW definition", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ connectorId: "connector / 2", definition }));
    vi.stubGlobal("fetch", fetchMock);

    await timeSpineAPI.replace("workspace / 1", "connector / 2", definition);
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain("/semantic/time-spines/connector%20%2F%202");
    expect(init.method).toBe("PUT");
    expect(headers.get("Content-Type")).toBe("application/json");
    expect(headers.get("Idempotency-Key")).toBeNull();
    expect(JSON.parse(String(init.body))).toEqual(definition);
  });
});
