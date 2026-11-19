import { afterEach, describe, expect, it, vi } from "vitest";
import { connectorsAPI } from "@/api/connectors";

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

describe("connectorsAPI", () => {
  afterEach(() => vi.restoreAllMocks());

  it("exposes list, get, create, revise, delete, and credential rotation", () => {
    expect(Object.keys(connectorsAPI)).toEqual(["list", "getById", "create", "revise", "delete", "rotateCredentials"]);
  });

  it("lists with the encoded workspace collection path", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse({ connectors: [] }));
    await connectorsAPI.list({ workspaceId: "workspace / 2" });

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.pathname).toBe("/v2/workspaces/workspace%20%2F%202/connectors");
    expect(url.searchParams.has("expectedWorkspaceId")).toBe(false);
    expect((fetchMock.mock.calls[0][1] as RequestInit).method ?? "GET").toBe(
      "GET",
    );
  });

  it("reads with encoded workspace and connector path identifiers", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse({ id: "connector-1" }));
    await connectorsAPI.getById("workspace / 2", "connector / 1");

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.pathname).toBe(
      "/v2/workspaces/workspace%20%2F%202/connectors/connector%20%2F%201",
    );
    expect(url.searchParams.has("expectedWorkspaceId")).toBe(false);
    expect((fetchMock.mock.calls[0][1] as RequestInit).method ?? "GET").toBe(
      "GET",
    );
  });

  it("creates with Idempotency-Key and the workspace collection path", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ connectorId: "connector-1", revision: 1 }), {
        status: 201,
        headers: { "Content-Type": "application/json" },
      }),
    );
    const request = {
      connectorType: "POSTGRESQL" as const,
      displayName: "Lab Postgres",
      properties: { host: "127.0.0.1", port: 5432, database: "warehouse" },
      credential: { kind: "USERNAME_PASSWORD" as const, username: "dw_query", password: "secret" },
    };

    await connectorsAPI.create({
      workspaceId: "workspace / 2",
      request,
      idempotencyKey: "create-1",
    });

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(url.pathname).toBe("/v2/workspaces/workspace%20%2F%202/connectors");
    expect(init.method).toBe("POST");
    expect(headers.get("Idempotency-Key")).toBe("create-1");
    expect(JSON.parse(String(init.body))).toEqual(request);
  });

  it("revises with Idempotency-Key and the encoded connector path", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ connectorId: "connector / 1", revision: 2 }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await connectorsAPI.revise({
      workspaceId: "workspace / 2",
      connectorId: "connector / 1",
      request: {
        displayName: "Lab Postgres",
        properties: { host: "127.0.0.1", port: 5432, database: "warehouse" },
      },
      idempotencyKey: "revise-1",
    });

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(url.pathname).toBe(
      "/v2/workspaces/workspace%20%2F%202/connectors/connector%20%2F%201",
    );
    expect(init.method).toBe("PUT");
    expect(headers.get("Idempotency-Key")).toBe("revise-1");
    expect(JSON.parse(String(init.body)).credentials).toBeUndefined();
  });

  it("rotates through the credential endpoint and accepts empty 204", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 204 }));
    const request = { kind: "USERNAME_PASSWORD" as const, username: "reader", password: "fresh" };
    await expect(connectorsAPI.rotateCredentials({ workspaceId: "w", connectorId: "c", request, idempotencyKey: "rotation-1" })).resolves.toBeUndefined();
    expect(new URL(String(fetchMock.mock.calls[0][0])).pathname).toBe("/v2/workspaces/w/connectors/c/credentials");
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe("PUT");
    expect(JSON.parse(String(init.body))).toEqual(request);
  });

  it("deletes the encoded connector path and accepts empty 204", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 204 }));
    await expect(connectorsAPI.delete({ workspaceId: "workspace / 2", connectorId: "connector / 1" })).resolves.toBeUndefined();
    expect(new URL(String(fetchMock.mock.calls[0][0])).pathname).toBe("/v2/workspaces/workspace%20%2F%202/connectors/connector%20%2F%201");
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe("DELETE");
    expect(init.body).toBeUndefined();
  });
});
