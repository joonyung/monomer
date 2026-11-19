import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  isDesignFixturePathForBuild,
  isPagesPath,
  refreshAheadOrExpire,
  shouldHydrateAuthProviderForPath,
} from "@/components/auth/AuthProvider";
import { useAuthStore } from "@/lib/auth/store";
import { _internal } from "@/lib/api/client";

describe("isDesignFixturePathForBuild", () => {
  it("allows the Pages fixture outside production without exposing the product route", () => {
    expect(isDesignFixturePathForBuild("/dev/pages-demo?surface=folder", true)).toBe(true);
    expect(isDesignFixturePathForBuild("/dev/pages-demo/page-1", true)).toBe(true);
    expect(isDesignFixturePathForBuild("/dev/pages-demo/page-1", false)).toBe(false);
    expect(isDesignFixturePathForBuild("/dev/pages-demo", false)).toBe(false);
    expect(isDesignFixturePathForBuild("/workspaces/workspace-1/pages", true)).toBe(false);
  });

  it("allows only development fixtures outside production", () => {
    expect(isDesignFixturePathForBuild("/dev/design-system-fixture", true)).toBe(true);
    expect(isDesignFixturePathForBuild("/dev/design-system-fixture", false)).toBe(false);
    expect(isDesignFixturePathForBuild("/dev/design-system-fixture/private", true)).toBe(true);
    expect(shouldHydrateAuthProviderForPath("/dev/design-system-fixture", true)).toBe(false);
    expect(shouldHydrateAuthProviderForPath("/dev/design-system-fixture", false)).toBe(true);
  });

  it("gives the OAuth callback exclusive ownership of session hydration", () => {
    expect(shouldHydrateAuthProviderForPath("/auth/callback", false)).toBe(false);
    expect(shouldHydrateAuthProviderForPath("/auth/callback/", false)).toBe(false);
    expect(shouldHydrateAuthProviderForPath("/auth/callback", true)).toBe(false);
    expect(shouldHydrateAuthProviderForPath("/login", false)).toBe(false);
    expect(shouldHydrateAuthProviderForPath("/auth/error", false)).toBe(false);
    expect(shouldHydrateAuthProviderForPath("/", false)).toBe(true);
  });
});

describe("Pages optional authentication boundary", () => {
  it("opens only Pages routes while other Workspace surfaces remain authenticated", () => {
    expect(isPagesPath("/workspaces/workspace-id/pages")).toBe(true);
    expect(isPagesPath("/workspaces/workspace-id/pages/page-id")).toBe(true);
    expect(isPagesPath("/workspaces/workspace-id/dashboards/dashboard-id")).toBe(false);
    expect(isPagesPath("/workspaces/workspace-id/pages/page-id/edit")).toBe(false);
    expect(isPagesPath("/workspaces/workspace-id/settings")).toBe(false);
  });
});

describe("refresh-ahead", () => {
  const session = {
    accessToken: "token-1",
    accessTokenExpiresAt: "2030-01-01T00:00:00Z",
    user: { id: "user-1", email: "user@example.com", displayName: "User One" },
    organizationMemberships: [],
  };

  beforeEach(() => {
    _internal.resetInflightRefresh();
    useAuthStore.getState().logout();
  });
  afterEach(() => vi.restoreAllMocks());

  it("expires the live session when its refresh is rejected", async () => {
    useAuthStore.getState().setSession(session);
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response(null, { status: 401 }));

    await refreshAheadOrExpire();

    expect(useAuthStore.getState().status).toBe("unauthenticated");
    expect(useAuthStore.getState().sessionEndReason).toBe("expired");
  });

  it("keeps the session when refresh succeeds", async () => {
    useAuthStore.getState().setSession(session);
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response(
      JSON.stringify({ access_token: "token-2", expires_at: "2030-01-01T01:00:00Z" }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    ));

    await refreshAheadOrExpire();

    expect(useAuthStore.getState().status).toBe("authenticated");
    expect(useAuthStore.getState().accessToken).toBe("token-2");
  });

  it("does not mark an explicit sign-out during the refresh as expired", async () => {
    useAuthStore.getState().setSession(session);
    let rejectRefresh: (response: Response) => void = () => undefined;
    vi.spyOn(globalThis, "fetch").mockReturnValueOnce(new Promise<Response>((resolve) => {
      rejectRefresh = resolve;
    }));

    const refresh = refreshAheadOrExpire();
    useAuthStore.getState().logout();
    rejectRefresh(new Response(null, { status: 401 }));
    await refresh;

    expect(useAuthStore.getState().sessionEndReason).toBeNull();
  });
});
