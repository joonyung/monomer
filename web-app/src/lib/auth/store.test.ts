import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { hasPersistedSession, readAuthSnapshot, useAuthStore } from "./store";
import { createMemoryStorage } from "@/test/memory-storage";

const membership = {
  organization: { id: "organization-1", plan: "FREE", createdAt: "2026-09-01T00:00:00Z", displayName: "User One" },
  role: "ADMIN",
};

describe("route-independent auth store", () => {
  const storage = createMemoryStorage();

  beforeEach(() => {
    storage.clear();
    vi.stubGlobal("window", { sessionStorage: storage, localStorage: storage });
    useAuthStore.getState().logout();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("keeps identity and token state without an ambient organization or workspace", () => {
    useAuthStore.getState().setSession({
      accessToken: "token-1",
      accessTokenExpiresAt: "2030-01-01T00:00:00Z",
      user: { id: "user-1", email: "user@example.com", displayName: "User One" },
      organizationMemberships: [membership],
    });
    expect(useAuthStore.getState().organizationMemberships).toEqual([membership]);
    expect(readAuthSnapshot()).toEqual({
      accessToken: "token-1",
      accessTokenExpiresAt: "2030-01-01T00:00:00Z",
    });
    expect(readAuthSnapshot()).not.toHaveProperty("workspaceId");
    expect(readAuthSnapshot()).not.toHaveProperty("organizationId");
  });

  it("remembers the last opened workspace per user in this browser across sign-out", () => {
    const user = { id: "user-1", email: "user@example.com", displayName: "User One" };
    const session = { accessToken: "token-1", accessTokenExpiresAt: "2030-01-01T00:00:00Z", user, organizationMemberships: [membership] };
    useAuthStore.getState().setSession(session);
    useAuthStore.getState().setLandingPreference("workspace-1");
    expect(useAuthStore.getState().lastAccessibleWorkspaceId).toBe("workspace-1");
    useAuthStore.getState().logout();
    expect(useAuthStore.getState().lastAccessibleWorkspaceId).toBeNull();

    useAuthStore.getState().setSession(session);
    expect(useAuthStore.getState().lastAccessibleWorkspaceId).toBe("workspace-1");
    useAuthStore.getState().logout();
    useAuthStore.getState().setSession({ ...session, user: { ...user, id: "user-2" } });
    expect(useAuthStore.getState().lastAccessibleWorkspaceId).toBeNull();
  });

  it("records an expired session and clears the reason on sign-out or a new session", () => {
    const session = {
      accessToken: "token-1",
      accessTokenExpiresAt: "2030-01-01T00:00:00Z",
      user: { id: "user-1", email: "user@example.com", displayName: "User One" },
      organizationMemberships: [membership],
    };
    useAuthStore.getState().setSession(session);
    expect(hasPersistedSession()).toBe(true);
    useAuthStore.getState().expireSession();
    expect(useAuthStore.getState()).toMatchObject({
      status: "unauthenticated",
      sessionEndReason: "expired",
      accessToken: null,
      user: null,
      error: null,
    });
    expect(hasPersistedSession()).toBe(false);

    useAuthStore.getState().logout();
    expect(useAuthStore.getState().sessionEndReason).toBeNull();

    useAuthStore.getState().expireSession();
    useAuthStore.getState().setSession(session);
    expect(useAuthStore.getState().sessionEndReason).toBeNull();

    useAuthStore.getState().expireSession();
    useAuthStore.getState().setUnauthenticated("Sign-in didn't complete. Try again.");
    expect(useAuthStore.getState().sessionEndReason).toBeNull();
  });
});
