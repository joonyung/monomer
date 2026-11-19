import { create } from "zustand";
import type { OrganizationMembership, AuthUser } from "./types";
import { isTokenExpiringSoon } from "./config";
import { clearQueryCache } from "@/lib/query/client";
import { latestVisitedWorkspaceId, readWorkspaceVisits, recordWorkspaceVisit } from "./workspace-visits";

export type AuthStatus = "idle" | "hydrating" | "authenticated" | "unauthenticated";
// Why the last live session ended when the person did not sign out. Sign-in explains it.
export type SessionEndReason = "expired";

const SESSION_STORAGE_KEY = "monomer.auth.session";

interface PersistedSession {
  accessToken: string;
  accessTokenExpiresAt: string;
}

function readPersistedSession(): PersistedSession | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<PersistedSession>;
    return typeof parsed.accessToken === "string" && typeof parsed.accessTokenExpiresAt === "string"
      ? { accessToken: parsed.accessToken, accessTokenExpiresAt: parsed.accessTokenExpiresAt }
      : null;
  } catch {
    return null;
  }
}

function writePersistedSession(session: PersistedSession | null): void {
  if (typeof window === "undefined") return;
  try {
    if (session) window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
    else window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    return;
  }
}

// True when this tab held a session before this page load, even if its access token has expired.
export function hasPersistedSession(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return window.sessionStorage.getItem(SESSION_STORAGE_KEY) !== null;
  } catch {
    return false;
  }
}

function clearPersistedSession(): void {
  writePersistedSession(null);
}

interface AuthState {
  status: AuthStatus;
  user: AuthUser | null;
  organizationMemberships: OrganizationMembership[];
  accessToken: string | null;
  accessTokenExpiresAt: string | null;
  lastAccessibleWorkspaceId: string | null;
  error: string | null;
  sessionEndReason: SessionEndReason | null;
  authEpoch: number;
  beginOAuthCallback: () => void;
  setHydrating: () => void;
  setUnauthenticated: (error?: string | null) => void;
  setSession: (input: {
    accessToken: string;
    accessTokenExpiresAt: string;
    user: AuthUser;
    organizationMemberships: OrganizationMembership[];
  }) => void;
  setAccessToken: (input: { accessToken: string; accessTokenExpiresAt: string }) => void;
  setMe: (input: { user: AuthUser; organizationMemberships: OrganizationMembership[] }) => void;
  // Remembers, in this browser, that the signed-in user opened a workspace.
  setLandingPreference: (workspaceId: string) => void;
  // The live session died without the person signing out, for example a rejected refresh.
  expireSession: () => void;
  logout: () => void;
}

const restoredSession = (() => {
  const persisted = readPersistedSession();
  if (!persisted || isTokenExpiringSoon(persisted.accessTokenExpiresAt)) return null;
  return persisted;
})();

export const useAuthStore = create<AuthState>((set, get) => ({
  status: "idle",
  user: null,
  organizationMemberships: [],
  accessToken: restoredSession?.accessToken ?? null,
  accessTokenExpiresAt: restoredSession?.accessTokenExpiresAt ?? null,
  lastAccessibleWorkspaceId: null,
  error: null,
  sessionEndReason: null,
  authEpoch: 0,

  beginOAuthCallback: () => {
    clearQueryCache();
    clearPersistedSession();
    set({
      status: "hydrating",
      user: null,
      organizationMemberships: [],
      accessToken: null,
      accessTokenExpiresAt: null,
      lastAccessibleWorkspaceId: null,
      error: null,
      sessionEndReason: null,
      authEpoch: get().authEpoch + 1,
    });
  },

  setHydrating: () => set({ status: "hydrating", error: null }),

  setUnauthenticated: (error = null) => {
    clearQueryCache();
    clearPersistedSession();
    set({
      status: "unauthenticated",
      user: null,
      organizationMemberships: [],
      accessToken: null,
      accessTokenExpiresAt: null,
      lastAccessibleWorkspaceId: null,
      error,
      sessionEndReason: null,
      authEpoch: get().authEpoch + 1,
    });
  },

  setSession: ({ accessToken, accessTokenExpiresAt, user, organizationMemberships }) => {
    clearQueryCache();
    writePersistedSession({ accessToken, accessTokenExpiresAt });
    set({
      status: "authenticated",
      accessToken,
      accessTokenExpiresAt,
      user,
      organizationMemberships,
      lastAccessibleWorkspaceId: latestVisitedWorkspaceId(readWorkspaceVisits(user.id)),
      error: null,
      sessionEndReason: null,
      authEpoch: get().authEpoch + 1,
    });
  },

  setAccessToken: ({ accessToken, accessTokenExpiresAt }) => {
    if (get().status === "unauthenticated") return;
    writePersistedSession({ accessToken, accessTokenExpiresAt });
    set({ accessToken, accessTokenExpiresAt });
  },

  setMe: ({ user, organizationMemberships }) => set((state) => ({
    user,
    organizationMemberships,
    status: "authenticated",
    lastAccessibleWorkspaceId: state.user?.id === user.id && state.lastAccessibleWorkspaceId
      ? state.lastAccessibleWorkspaceId
      : latestVisitedWorkspaceId(readWorkspaceVisits(user.id)),
  })),

  setLandingPreference: (workspaceId) => {
    const userId = get().user?.id;
    if (userId) recordWorkspaceVisit(userId, workspaceId, new Date());
    set({ lastAccessibleWorkspaceId: workspaceId });
  },

  expireSession: () => {
    clearQueryCache();
    clearPersistedSession();
    set({
      status: "unauthenticated",
      user: null,
      organizationMemberships: [],
      accessToken: null,
      accessTokenExpiresAt: null,
      lastAccessibleWorkspaceId: null,
      error: null,
      sessionEndReason: "expired",
      authEpoch: get().authEpoch + 1,
    });
  },

  logout: () => {
    clearQueryCache();
    clearPersistedSession();
    set({
      status: "unauthenticated",
      user: null,
      organizationMemberships: [],
      accessToken: null,
      accessTokenExpiresAt: null,
      lastAccessibleWorkspaceId: null,
      error: null,
      sessionEndReason: null,
      authEpoch: get().authEpoch + 1,
    });
  },
}));

export function readAuthSnapshot() {
  const state = useAuthStore.getState();
  return {
    accessToken: state.accessToken,
    accessTokenExpiresAt: state.accessTokenExpiresAt,
  };
}
