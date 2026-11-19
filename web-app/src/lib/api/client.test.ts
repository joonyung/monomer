import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch, ApiError, parseSseFrame, _internal } from "./client";
import { useAuthStore } from "@/lib/auth/store";

function authedSession(accessToken: string = "jwt-1") {
  useAuthStore.getState().setSession({
    accessToken,
    accessTokenExpiresAt: "2030-01-01T00:00:00Z",
    user: { id: "u1", email: "u1@example.com", displayName: "User One" },
    organizationMemberships: [
      {
        organization: { id: "a-personal", plan: "FREE", createdAt: "2026-09-01T00:00:00Z", displayName: "User One" },
        role: "ADMIN",
      },
    ],
  });
}

function jsonResponse(status: number, body: unknown): Response {
  const text = typeof body === "string" ? body : JSON.stringify(body);
  return new Response(text, {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function unauthorizedProblem(detail: string) {
  return {
    type: "https://monomer.cc/problems/unauthorized",
    title: "Unauthorized",
    status: 401,
    detail,
    instance: "/api",
    code: "UNAUTHORIZED",
    requestId: "request-1",
    traceId: "trace-1",
    retryable: false,
  };
}

describe("apiFetch", () => {
  beforeEach(() => {
    useAuthStore.getState().logout();
    _internal.resetInflightRefresh();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("injects Authorization header from store", async () => {
    authedSession("jwt-abc");
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(200, { ok: true }));

    await apiFetch("https://example.com/api");

    expect(fetchMock).toHaveBeenCalledOnce();
    const call = fetchMock.mock.calls[0];
    const init = call[1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(headers.get("Authorization")).toBe("Bearer jwt-abc");
    expect(headers.get("X-Organization-Id")).toBeNull();
    expect(headers.get("X-Workspace-Id")).toBeNull();
  });

  it("skips Authorization injection when skipAuth=true", async () => {
    authedSession();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(200, {}));
    await apiFetch("https://example.com/api", { skipAuth: true });
    const headers = new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers);
    expect(headers.get("Authorization")).toBeNull();
  });

  it("on 401 refreshes once and retries", async () => {
    authedSession("old-token");
    const fetchMock = vi.spyOn(globalThis, "fetch");
    // First call: 401. Second call: refresh succeeds. Third call: retry returns 200.
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, unauthorizedProblem("expired")))
      .mockResolvedValueOnce(
        jsonResponse(200, { access_token: "new-token", expires_at: "2030-01-01T00:00:00Z" }),
      )
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }));

    const res = await apiFetch("https://example.com/api");
    expect(res.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    // After refresh, token in store updated.
    expect(useAuthStore.getState().accessToken).toBe("new-token");
    // Retry call should carry the NEW token.
    const retryHeaders = new Headers((fetchMock.mock.calls[2][1] as RequestInit).headers);
    expect(retryHeaders.get("Authorization")).toBe("Bearer new-token");
  });

  it("on persistent 401 expires the session and throws ApiError", async () => {
    authedSession("old-token");
    const fetchMock = vi.spyOn(globalThis, "fetch");
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, unauthorizedProblem("expired")))
      .mockResolvedValueOnce(jsonResponse(401, "")) // refresh fails
      .mockResolvedValueOnce(jsonResponse(401, "")); // not reached but safe

    await expect(apiFetch("https://example.com/api")).rejects.toBeInstanceOf(ApiError);
    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().sessionEndReason).toBe("expired");
  });

  it("keeps the session when refresh succeeds and the retried action is still 401", async () => {
    authedSession("old-token");
    const fetchMock = vi.spyOn(globalThis, "fetch");
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, unauthorizedProblem("expired")))
      .mockResolvedValueOnce(
        jsonResponse(200, { access_token: "new-token", expires_at: "2030-01-01T00:00:00Z" }),
      )
      .mockResolvedValueOnce(jsonResponse(401, unauthorizedProblem("Connector mutation is not authorized.")));

    await expect(apiFetch("https://example.com/api")).rejects.toMatchObject({
      status: 401,
      message: "Connector mutation is not authorized.",
    });
    expect(useAuthStore.getState().status).toBe("authenticated");
    expect(useAuthStore.getState().accessToken).toBe("new-token");
  });

  it("keeps the session so semantic authorization-required can render distinctly", async () => {
    authedSession("old-token");
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        jsonResponse(401, {
          error: "semantic_execution_authorization_required",
          message: "Semantic execution requires authentication.",
        }),
      )
      .mockResolvedValueOnce(jsonResponse(401, ""));

    await expect(apiFetch("https://example.com/api")).rejects.toMatchObject({
      status: 401,
      message: "Semantic execution requires authentication.",
    });
    expect(useAuthStore.getState().status).toBe("authenticated");
  });

  it("does not surface a message from an error body that is not Problem Details", async () => {
    authedSession();
    const body = { kind: "ACCESS_DENIED", message: "no access to table foo" };
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(403, body));
    await expect(apiFetch("https://example.com/api")).rejects.toMatchObject({
      status: 403,
      message: "Request failed with 403",
      problem: null,
      bodyText: JSON.stringify(body),
    });
  });

  it("parses canonical Problem Details and uses its safe detail", async () => {
    authedSession();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(403, {
        type: "https://monomer.cc/problems/forbidden",
        title: "Forbidden",
        status: 403,
        detail: "Explore is not authorized.",
        instance: "/v2/workspaces/workspace-1/explore/sql-runs",
        code: "FORBIDDEN",
        requestId: "request-1",
        traceId: "trace-1",
        retryable: false,
      }),
    );

    await expect(apiFetch("https://example.com/api")).rejects.toMatchObject({
      message: "Explore is not authorized.",
      status: 403,
      problem: {
        code: "FORBIDDEN",
        traceId: "trace-1",
        retryable: false,
      },
    });
  });

  it("does not trust malformed Problem Details", async () => {
    authedSession();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(503, {
        title: "Driver exploded",
        status: 503,
        detail: "postgresql://user:secret@warehouse.internal",
      }),
    );

    await expect(apiFetch("https://example.com/api")).rejects.toMatchObject({
      message: "Request failed with 503",
      status: 503,
      problem: null,
    });
  });

  it("does not inject Authorization header when no token in store", async () => {
    // unauthenticated state
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(200, {}));
    await apiFetch("https://example.com/api", { skipRefresh: true });
    const headers = new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers);
    expect(headers.get("Authorization")).toBeNull();
    expect(headers.get("X-Workspace-Id")).toBeNull();
  });

  it("coalesces concurrent refreshes into a single /auth/refresh call (single-flight)", async () => {
    // No token in store; two callers race a refresh at once (the reload scenario:
    // session hydration + a 401-triggered retry). They MUST share one network call so the
    // rotating refresh token is never presented twice — a double-present trips the auth
    // service's reuse detection and revokes the whole token family.
    // `setHydrating` mirrors the real context (hydrate runs while "hydrating", not after an
    // explicit logout) so the rotated token is actually applied.
    useAuthStore.getState().setHydrating();
    _internal.resetInflightRefresh();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        jsonResponse(200, { access_token: "rotated-token", expires_at: "2030-01-01T00:00:00Z" }),
      );

    const [a, b] = await Promise.all([
      _internal.refreshAccessToken(),
      _internal.refreshAccessToken(),
    ]);

    expect(a).toBe(true);
    expect(b).toBe(true);
    const refreshCalls = fetchMock.mock.calls.filter((c) =>
      String(c[0]).includes("/auth/refresh"),
    );
    expect(refreshCalls.length).toBe(1);
    expect(useAuthStore.getState().accessToken).toBe("rotated-token");
  });

  it("discards a refresh whose session changed mid-flight (no stale-token overwrite)", async () => {
    _internal.resetInflightRefresh();
    authedSession("login-token-1"); // bumps authEpoch
    let resolveFetch!: (r: Response) => void;
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise<Response>((r) => { resolveFetch = r; }));

    const pending = _internal.refreshAccessToken(); // captures the current epoch
    // A fresh login lands while the refresh is still in flight (bumps epoch, new token).
    authedSession("login-token-2");
    resolveFetch(jsonResponse(200, { access_token: "stale-refresh-token", expires_at: "2030-01-01T00:00:00Z" }));
    const ok = await pending;

    expect(ok).toBe(false);
    // The new login's token must NOT be overwritten by the stale refresh.
    expect(useAuthStore.getState().accessToken).toBe("login-token-2");
  });

  it("coalesces a failing refresh too, then clears in-flight so a later refresh retries", async () => {
    _internal.resetInflightRefresh();
    const fetchMock = vi.spyOn(globalThis, "fetch");
    // Two concurrent callers, refresh fails: still ONE network call, both get false.
    fetchMock.mockResolvedValueOnce(jsonResponse(401, ""));
    const [a, b] = await Promise.all([
      _internal.refreshAccessToken(),
      _internal.refreshAccessToken(),
    ]);
    expect(a).toBe(false);
    expect(b).toBe(false);
    expect(
      fetchMock.mock.calls.filter((c) => String(c[0]).includes("/auth/refresh")).length,
    ).toBe(1);

    // in-flight cleared by finally: a fresh attempt makes a new call and can succeed.
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { access_token: "later-token", expires_at: "2030-01-01T00:00:00Z" }),
    );
    expect(await _internal.refreshAccessToken()).toBe(true);
    expect(
      fetchMock.mock.calls.filter((c) => String(c[0]).includes("/auth/refresh")).length,
    ).toBe(2);
  });

  it("bounds a stalled refresh, resolves shared callers false, and permits a later retry", async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = vi.spyOn(globalThis, "fetch")
        .mockImplementationOnce((_input, init) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener(
              "abort",
              () => reject(new DOMException("Aborted", "AbortError")),
              { once: true },
            );
          }),
        )
        .mockResolvedValueOnce(
          jsonResponse(200, {
            access_token: "retry-token",
            expires_at: "2030-01-01T00:00:00Z",
          }),
        );

      const first = _internal.refreshAccessToken();
      const shared = _internal.refreshAccessToken();
      expect(fetchMock).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(_internal.REFRESH_TIMEOUT_MILLIS);
      await expect(first).resolves.toBe(false);
      await expect(shared).resolves.toBe(false);

      await expect(_internal.refreshAccessToken()).resolves.toBe(true);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not log out a fresh session when a login supersedes a stale request mid-flight", async () => {
    // The session epoch is pinned at request dispatch, so a login any time before the 401 is
    // resolved (here: while the stale request is still in flight, before refresh) must not log
    // the fresh session out. The fresh login is simulated via setState (epoch bump + new token)
    // to avoid setSession's loadWorkspaces side-effect competing for the fetch mock.
    _internal.resetInflightRefresh();
    // Stale session active at request dispatch (set via setState to avoid loadWorkspaces).
    useAuthStore.setState({
      status: "authenticated",
      accessToken: "token-A",
      accessTokenExpiresAt: "2030-01-01T00:00:00Z",
      user: { id: "u1", email: "u1@example.com", displayName: "User One" },
    });
    const fetchMock = vi.spyOn(globalThis, "fetch");
    let resolveStaleRequest!: (r: Response) => void;
    fetchMock
      .mockReturnValueOnce(new Promise<Response>((r) => { resolveStaleRequest = r; })) // stale request, in-flight
      .mockResolvedValue(jsonResponse(401, "")); // refresh attempt fails

    const pending = apiFetch("https://example.com/api").catch((e) => e);
    // a fresh login lands while the stale request is still in flight (before its 401 is handled)
    useAuthStore.setState((s) => ({ status: "authenticated", accessToken: "token-B", authEpoch: s.authEpoch + 1 }));
    resolveStaleRequest(jsonResponse(401, unauthorizedProblem("expired")));
    const err = await pending;

    expect(err).toBeInstanceOf(ApiError); // the stale request still fails
    // ...but the fresh session must survive — no logout.
    expect(useAuthStore.getState().status).toBe("authenticated");
    expect(useAuthStore.getState().accessToken).toBe("token-B");
  });

  it("does not replay an old mutation after a new login precedes its 401", async () => {
    authedSession("old-user-token");
    let resolveRequest!: (response: Response) => void;
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockReturnValueOnce(new Promise<Response>((resolve) => { resolveRequest = resolve; }))
      .mockResolvedValue(jsonResponse(200, { access_token: "new-user-token", expires_at: "2030-01-01T00:00:00Z" }));
    const pending = apiFetch("https://example.com/v2/graphql", { method: "POST", body: "old-user-mutation" }).catch((error) => error);
    authedSession("new-user-token");
    resolveRequest(jsonResponse(401, ""));
    expect(await pending).toBeInstanceOf(ApiError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(useAuthStore.getState().accessToken).toBe("new-user-token");
  });

  it("does not replay a mutation if the session changes after refresh token adoption", async () => {
    authedSession("old-user-token");
    const unsubscribe = useAuthStore.subscribe((state) => {
      if (state.accessToken === "rotated-old-token") authedSession("new-user-token");
    });
    try {
      const fetchMock = vi.spyOn(globalThis, "fetch")
        .mockResolvedValueOnce(jsonResponse(401, ""))
        .mockResolvedValueOnce(jsonResponse(200, { access_token: "rotated-old-token", expires_at: "2030-01-01T00:00:00Z" }))
        .mockResolvedValue(jsonResponse(200, {}));
      await expect(apiFetch("https://example.com/v2/graphql", { method: "POST", body: "old-user-mutation" })).rejects.toBeInstanceOf(ApiError);
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(useAuthStore.getState().accessToken).toBe("new-user-token");
    } finally {
      unsubscribe();
    }
  });

  it("respects skipRefresh=true on 401", async () => {
    authedSession();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(401, ""));
    await expect(
      apiFetch("https://example.com/api", { skipRefresh: true }),
    ).rejects.toBeInstanceOf(ApiError);
    // Should have only called once (no refresh attempt).
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("parseSseFrame", () => {
  it("parses a CRLF frame without retaining a trailing CR in the event type", () => {
    const frame = parseSseFrame("event:snapshot\r\ndata:{\"id\":1}");
    expect(frame).toEqual({ type: "snapshot", data: '{"id":1}' });
  });

  it("parses an LF frame", () => {
    const frame = parseSseFrame("event:progress\ndata:42");
    expect(frame).toEqual({ type: "progress", data: "42" });
  });

  it("joins multiple data lines with newlines (CRLF line endings)", () => {
    const frame = parseSseFrame("event:log\r\ndata:line one\r\ndata:line two");
    expect(frame).toEqual({ type: "log", data: "line one\nline two" });
  });

  it("defaults the type to message and ignores comment-only lines", () => {
    const commentOnly = parseSseFrame(": keep-alive heartbeat");
    expect(commentOnly).toBeNull();
    const noEvent = parseSseFrame(": ping\r\ndata:hello");
    expect(noEvent).toEqual({ type: "message", data: "hello" });
  });

  it("never keeps a trailing CR on the event type or data with bare-CR line endings", () => {
    const frame = parseSseFrame("event:done\rdata:value\r");
    expect(frame).toEqual({ type: "done", data: "value" });
    expect(frame?.type.endsWith("\r")).toBe(false);
    expect(frame?.data.endsWith("\r")).toBe(false);
  });
});
