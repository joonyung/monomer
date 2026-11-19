import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  completeOAuthCallbackSession,
  hydrateSession,
  isTokenExpiringSoon,
  SESSION_START_FAILED_MESSAGE,
} from "./session";
import { createMemoryStorage } from "@/test/memory-storage";
import { useAuthStore } from "./store";
import { _internal } from "@/lib/api/client";

describe("isTokenExpiringSoon", () => {
  it("returns true when expiry is null", () => {
    expect(isTokenExpiringSoon(null)).toBe(true);
  });

  it("returns true when expiry is unparseable", () => {
    expect(isTokenExpiringSoon("not-a-date")).toBe(true);
  });

  it("returns true when remaining time is within threshold", () => {
    const now = Date.now();
    const expiresAt = new Date(now + 30_000).toISOString(); // 30s remaining
    expect(isTokenExpiringSoon(expiresAt, now)).toBe(true);
  });

  it("returns false when token has comfortable remaining time", () => {
    const now = Date.now();
    const expiresAt = new Date(now + 10 * 60_000).toISOString(); // 10min remaining
    expect(isTokenExpiringSoon(expiresAt, now)).toBe(false);
  });
});

describe("hydrateSession", () => {
  it("rejects a restored unexpired JWT when its server session and refresh are invalid", async () => {
    vi.restoreAllMocks();
    _internal.resetInflightRefresh();
    useAuthStore.getState().logout();
    useAuthStore.setState({
      accessToken: "restored-revoked-session-token",
      accessTokenExpiresAt: "2030-01-01T00:00:00Z",
    });
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status: 401 }));

    await expect(hydrateSession()).resolves.toBe(false);

    expect(String(fetchMock.mock.calls[0][0])).toContain("/v2/graphql");
    expect(String(fetchMock.mock.calls[1][0])).toContain("/auth/refresh");
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().sessionEndReason).toBe("expired");
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  describe("with per-tab storage", () => {
    const storage = createMemoryStorage();

    beforeEach(() => {
      vi.restoreAllMocks();
      _internal.resetInflightRefresh();
      storage.clear();
      vi.stubGlobal("window", { sessionStorage: storage });
      useAuthStore.getState().logout();
    });
    afterEach(() => vi.unstubAllGlobals());

    it("marks the session expired when this tab held one and refresh is rejected", async () => {
      storage.setItem("monomer.auth.session", JSON.stringify({
        accessToken: "expired-token",
        accessTokenExpiresAt: "2020-01-01T00:00:00Z",
      }));
      vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response(null, { status: 401 }));

      await expect(hydrateSession()).resolves.toBe(false);

      expect(useAuthStore.getState().status).toBe("unauthenticated");
      expect(useAuthStore.getState().sessionEndReason).toBe("expired");
      expect(storage.getItem("monomer.auth.session")).toBeNull();
    });

    it("signs a first-time visitor out without an expiry reason", async () => {
      vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response(null, { status: 401 }));

      await expect(hydrateSession()).resolves.toBe(false);

      expect(useAuthStore.getState().status).toBe("unauthenticated");
      expect(useAuthStore.getState().sessionEndReason).toBeNull();
    });
  });
});

describe("completeOAuthCallbackSession", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    _internal.resetInflightRefresh();
    useAuthStore.getState().logout();
  });

  it("refreshes once then fetches me and commits the session", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(200, {
        access_token: "jwt-x",
        expires_at: "2030-01-01T00:00:00Z",
      }))
      .mockResolvedValueOnce(jsonResponse(200, {
        data: {
          me: {
            user: { id: "u1", email: "u1@example.com", displayName: "U One" },
            memberships: [],
          },
        },
      }));

    const result = await completeOAuthCallbackSession();

    expect(result).toEqual({ kind: "success" });
    const s = useAuthStore.getState();
    expect(s.status).toBe("authenticated");
    expect(s.accessToken).toBe("jwt-x");
    expect(s.user?.id).toBe("u1");
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes("/auth/refresh"))).toHaveLength(1);
  });

  it("invalidates an older hydration so stale me cannot replace the callback session", async () => {
    useAuthStore.setState({
      status: "authenticated",
      accessToken: "old-token",
      accessTokenExpiresAt: "2030-01-01T00:00:00Z",
      authEpoch: useAuthStore.getState().authEpoch + 1,
    });
    let resolveOldMe!: (response: Response) => void;
    let graphQlCalls = 0;
    vi.spyOn(globalThis, "fetch").mockImplementation((input) => {
      const url = String(input);
      if (url.includes("/auth/refresh")) {
        return Promise.resolve(jsonResponse(200, {
          access_token: "callback-token",
          expires_at: "2030-01-01T00:00:00Z",
        }));
      }
      graphQlCalls += 1;
      if (graphQlCalls === 1) {
        return new Promise<Response>((resolve) => { resolveOldMe = resolve; });
      }
      return Promise.resolve(jsonResponse(200, {
        data: {
          me: {
            user: { id: "new-user", email: "new@example.com", displayName: "New" },
            memberships: [],
          },
        },
      }));
    });

    const staleHydration = hydrateSession();
    await vi.waitFor(() => expect(graphQlCalls).toBe(1));
    const callbackResult = await completeOAuthCallbackSession();
    resolveOldMe(jsonResponse(200, {
      data: {
        me: {
          user: { id: "old-user", email: "old@example.com", displayName: "Old" },
          memberships: [],
        },
      },
    }));

    expect(callbackResult).toEqual({ kind: "success" });
    expect(await staleHydration).toBe(false);
    expect(useAuthStore.getState().user?.id).toBe("new-user");
    expect(useAuthStore.getState().accessToken).toBe("callback-token");
  });

  it("returns an error and clears state when the refresh-cookie exchange fails", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(401, {}));

    const result = await completeOAuthCallbackSession();

    expect(result).toEqual({
      kind: "error",
      message: SESSION_START_FAILED_MESSAGE,
    });
    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("times out a stalled callback refresh and exposes an unauthenticated error", async () => {
    vi.useFakeTimers();
    try {
      vi.spyOn(globalThis, "fetch").mockImplementation((_input, init) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener(
            "abort",
            () => reject(new DOMException("Aborted", "AbortError")),
            { once: true },
          );
        }),
      );

      const completion = completeOAuthCallbackSession();
      await vi.advanceTimersByTimeAsync(_internal.REFRESH_TIMEOUT_MILLIS);

      await expect(completion).resolves.toEqual({
        kind: "error",
        message: SESSION_START_FAILED_MESSAGE,
      });
      expect(useAuthStore.getState().status).toBe("unauthenticated");
      expect(useAuthStore.getState().error).toBe(SESSION_START_FAILED_MESSAGE);
    } finally {
      vi.useRealTimers();
    }
  });

  it("returns an error and clears the refreshed token when me fails", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(200, {
        access_token: "jwt-x",
        expires_at: "2030-01-01T00:00:00Z",
      }))
      .mockResolvedValueOnce(jsonResponse(200, {
        errors: [{ message: "Profile unavailable" }],
      }));

    const result = await completeOAuthCallbackSession();

    expect(result).toEqual({ kind: "error", message: SESSION_START_FAILED_MESSAGE });
    expect(useAuthStore.getState().error).toBe("We couldn't start your session. Sign in again.");
    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(new Headers(fetchMock.mock.calls[1][1]?.headers).get("Authorization"))
      .toBe("Bearer jwt-x");
  });
});

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
