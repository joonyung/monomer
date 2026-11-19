import { hasPersistedSession, useAuthStore } from "./store";
import { fetchMe } from "./graphql";
import { refreshAccessToken } from "@/lib/api/client";
import { CONTROL_API_URL, isTokenExpiringSoon } from "./config";

export { isTokenExpiringSoon } from "./config";

// Sign-in failures are shown to people; the technical cause stays out of the copy.
export const SESSION_START_FAILED_MESSAGE = "We couldn't start your session. Sign in again.";
export const SESSION_RESTORE_FAILED_MESSAGE = "We couldn't restore your session. Sign in again.";

/**
 * Refresh the access token using the HttpOnly refresh cookie. Delegates to the single
 * app-wide refresh in the API client so this path and the 401-retry path never present
 * the rotating refresh token concurrently (which the auth service treats as theft).
 */
export async function tryRefreshSession(): Promise<boolean> {
  return refreshAccessToken();
}

/**
 * Called on app load: restore the session.
 * Order:
 *  1. If a still-valid access token was restored from per-tab storage, reuse it as-is —
 *     do NOT refresh. A routine reload must not rotate the refresh token.
 *  2. Otherwise (no token, or expired) refresh once via the rotating refresh cookie.
 *  3. Either way, fetch `me` to populate the user + memberships; on failure mark unauthenticated.
 * A failed refresh for a tab that held a session ends it as expired, so sign-in can say why.
 */
export async function hydrateSession(): Promise<boolean> {
  const store = useAuthStore.getState();
  store.setHydrating();
  // Capture the session generation so we can discard our results if the session changes
  // (logout or a fresh login) while our awaited calls are in flight — preventing a stale
  // restore from reviving a session the user already ended.
  const epoch = store.authEpoch;
  const superseded = () => useAuthStore.getState().authEpoch !== epoch;

  const hadSession = hasPersistedSession();
  const hasValidToken = !!store.accessToken && !isTokenExpiringSoon(store.accessTokenExpiresAt);
  if (!hasValidToken) {
    const refreshed = await tryRefreshSession();
    if (superseded()) return false;
    if (!refreshed) {
      if (hadSession) useAuthStore.getState().expireSession();
      else useAuthStore.getState().setUnauthenticated();
      return false;
    }
  }

  try {
    const me = await fetchMe(undefined, false);
    if (superseded()) return false;
    useAuthStore.getState().setMe({ user: me.user, organizationMemberships: me.organizationMemberships });
    return true;
  } catch {
    if (superseded()) return false;
    useAuthStore.getState().setUnauthenticated(SESSION_RESTORE_FAILED_MESSAGE);
    return false;
  }
}

export type OAuthCallbackCompletion =
  | { kind: "success" }
  | { kind: "error"; message: string }
  | { kind: "superseded" };

export async function completeOAuthCallbackSession(): Promise<OAuthCallbackCompletion> {
  useAuthStore.getState().beginOAuthCallback();
  const callbackEpoch = useAuthStore.getState().authEpoch;
  const superseded = () => useAuthStore.getState().authEpoch !== callbackEpoch;

  const refreshed = await tryRefreshSession();
  if (superseded()) return { kind: "superseded" };
  if (!refreshed) {
    useAuthStore.getState().setUnauthenticated(SESSION_START_FAILED_MESSAGE);
    return { kind: "error", message: SESSION_START_FAILED_MESSAGE };
  }

  const { accessToken, accessTokenExpiresAt } = useAuthStore.getState();
  if (!accessToken || !accessTokenExpiresAt) {
    useAuthStore.getState().setUnauthenticated(SESSION_START_FAILED_MESSAGE);
    return { kind: "error", message: SESSION_START_FAILED_MESSAGE };
  }

  try {
    const me = await fetchMe(undefined, true);
    if (superseded()) return { kind: "superseded" };
    useAuthStore.getState().setSession({
      accessToken,
      accessTokenExpiresAt,
      user: me.user,
      organizationMemberships: me.organizationMemberships,
    });
    return { kind: "success" };
  } catch {
    if (superseded()) return { kind: "superseded" };
    useAuthStore.getState().setUnauthenticated(SESSION_START_FAILED_MESSAGE);
    return { kind: "error", message: SESSION_START_FAILED_MESSAGE };
  }
}

// Ends every session of the signed-in user, including this browser's. Connected agents are separate
// grants and keep working until they are revoked in Agents. A 401 means this browser has no
// live session left, so the person is already signed out here.
export async function signOutAllDevices(): Promise<void> {
  const response = await fetch(`${CONTROL_API_URL}/auth/logout-all`, {
    method: "POST",
    credentials: "include",
  });
  if (!response.ok && response.status !== 401) throw new Error("Couldn't sign out of all devices. Try again.");
}
