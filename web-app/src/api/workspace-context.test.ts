import { beforeEach, describe, expect, it, vi } from "vitest";
import { fetchWorkspaceContext } from "@/api/workspace-context";
import { useAuthStore } from "@/lib/auth/store";
import { _internal } from "@/lib/api/client";

const workspaceId = "019e501f-0000-7000-8000-000000000011";

describe("Workspace context BFF", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    _internal.resetInflightRefresh();
    useAuthStore.setState({
      status: "authenticated",
      accessToken: "workspace-context-token",
      accessTokenExpiresAt: "2030-01-01T00:00:00Z",
    });
  });

  it("returns only the verified route context", async () => {
    const access = {
      granted: true,
      organizationId: "organization-1",
      role: "EXPLORER" as const, };
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      data: { workspaceAccess: access },
    }), { status: 200, headers: { "Content-Type": "application/json" } }));

    await expect(fetchWorkspaceContext(workspaceId)).resolves.toEqual(access);
    expect(String(fetchMock.mock.calls[0][0])).toContain("/v2/graphql");
    const body = JSON.parse(String(fetchMock.mock.calls[0][1]?.body));
    expect(body.query).toContain("workspaceAccess(workspaceId: $workspaceId)");
    expect(body.variables).toEqual({ workspaceId });
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get("Authorization")).toBe("Bearer workspace-context-token");
  });

  it.each([
    { code: "WORKSPACE_UNAVAILABLE" },
    { code: "IGNORED_DETAIL", reason: "must not be parsed" },
  ])("maps every 403 body to the same unavailable result", async (body) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      errors: [{
        message: "missing permission",
        extensions: { ...body, classification: "FORBIDDEN" },
      }],
      data: null,
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));

    await expect(fetchWorkspaceContext(workspaceId)).resolves.toBeNull();
  });

  it("keeps transport and server failures retryable", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("unavailable", { status: 503 }));

    await expect(fetchWorkspaceContext(workspaceId)).rejects.toMatchObject({ status: 503 });
  });

  it("ends an invalid browser session after one failed refresh instead of returning unavailable", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));

    await expect(fetchWorkspaceContext(workspaceId)).rejects.toMatchObject({ status: 401 });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(String(fetchMock.mock.calls[1][0])).toContain("/auth/refresh");
    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it("retries workspace admission once with the refreshed session", async () => {
    const access = {
      granted: true,
      organizationId: "organization-1",
      role: "OWNER" as const, };
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(Response.json({ access_token: "refreshed-session-token", expires_at: "2030-01-01T00:00:00Z" }))
      .mockResolvedValueOnce(Response.json({ data: { workspaceAccess: access } }));

    await expect(fetchWorkspaceContext(workspaceId)).resolves.toEqual(access);

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(new Headers(fetchMock.mock.calls[2][1]?.headers).get("Authorization"))
      .toBe("Bearer refreshed-session-token");
    expect(useAuthStore.getState().status).toBe("authenticated");
  });

  it("preserves a live session denied workspace membership without refreshing", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json({
      data: {
        workspaceAccess: {
          granted: false,
          organizationId: null,
          role: "VIEWER" as const, },
      },
    }));

    await expect(fetchWorkspaceContext(workspaceId)).resolves.toBeNull();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(useAuthStore.getState().status).toBe("authenticated");
    expect(useAuthStore.getState().accessToken).toBe("workspace-context-token");
  });
});
