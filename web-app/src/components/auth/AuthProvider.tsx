import { useEffect, useRef } from "react";
import { useRouter, usePathname } from "@/lib/navigation";
import { useAuthStore } from "@/lib/auth/store";
import { hydrateSession, isTokenExpiringSoon, tryRefreshSession } from "@/lib/auth/session";
import { sanitizePostLoginRedirect } from "@/lib/auth/post-login-redirect";

const PUBLIC_PATHS = new Set<string>(["/login", "/auth/callback", "/invitations/accept"]);
const E2E_AUTH_BYPASS_ENABLED = import.meta.env.VITE_E2E_AUTH_BYPASS === "true";

const E2E_USER = {
  id: "00000000-0000-0000-0000-000000000001",
  email: "e2e@local.monomer",
  displayName: "E2E User",
};

const E2E_ORGANIZATION = {
  id: "00000000-0000-0000-0000-000000000002",
  plan: "FREE", createdAt: "2026-09-01T00:00:00Z",
  displayName: "E2E Organization",
};

// Dev-only design fixtures under /dev render without auth and may seed the store directly
// so the design verify loop can screenshot deterministic mock states.
// AuthProvider must NOT hydrate, redirect, or otherwise clobber the seeded state
// on these paths. Never matched in production.
export function isDesignFixturePathForBuild(pathname: string, isDevelopmentBuild: boolean): boolean {
  if (!isDevelopmentBuild) return false;
  return pathname === "/dev" || pathname.startsWith("/dev/");
}

function isDesignFixturePath(pathname: string): boolean {
  return isDesignFixturePathForBuild(pathname, import.meta.env.DEV);
}

export function shouldHydrateAuthProviderForPath(
  pathname: string,
  isDevelopmentBuild: boolean,
): boolean {
  return (
    !pathname.startsWith("/login") &&
    !pathname.startsWith("/auth/") &&
    !isDesignFixturePathForBuild(pathname, isDevelopmentBuild)
  );
}

export function isPagesPath(pathname: string): boolean {
  return /^\/workspaces\/[^/]+\/pages(?:\/[^/]+)?\/?$/.test(pathname);
}

function isPublicPath(pathname: string): boolean {
  if (isPagesPath(pathname)) return true;
  if (PUBLIC_PATHS.has(pathname)) return true;
  if (pathname.startsWith("/login")) return true;
  if (pathname.startsWith("/auth/")) return true;
  if (isDesignFixturePath(pathname)) return true;
  return false;
}

// Expires the session only when the refresh failed for the same live session that asked for it;
// a sign-out or a fresh sign-in during the round trip owns the store instead.
export async function refreshAheadOrExpire(): Promise<void> {
  const epoch = useAuthStore.getState().authEpoch;
  const refreshed = await tryRefreshSession();
  const current = useAuthStore.getState();
  if (!refreshed && current.authEpoch === epoch && current.status === "authenticated") {
    current.expireSession();
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const status = useAuthStore((s) => s.status);
  const accessTokenExpiresAt = useAuthStore((s) => s.accessTokenExpiresAt);
  const hydratedRef = useRef(false);

  useEffect(() => {
    if (hydratedRef.current) return;
    hydratedRef.current = true;
    if (!shouldHydrateAuthProviderForPath(pathname, import.meta.env.DEV)) return;
    if (E2E_AUTH_BYPASS_ENABLED) {
      const authStore = useAuthStore.getState();
      authStore.setSession({
        accessToken: "e2e-local-token",
        accessTokenExpiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
        user: E2E_USER,
        organizationMemberships: [{ organization: E2E_ORGANIZATION, role: "ADMIN" }],
      });
      return;
    }
    void hydrateSession();
    // Runs once at mount (guarded by hydratedRef); pathname is read only to skip
    // hydration on dev design fixtures.
  }, [pathname]);

  // Redirect on unauthenticated, but not for public paths.
  useEffect(() => {
    if (status !== "unauthenticated") return;
    if (isPublicPath(pathname)) return;
    const locationSearch = typeof window === "undefined" ? "" : window.location.search;
    const locationHash = typeof window === "undefined" ? "" : window.location.hash;
    const redirect = encodeURIComponent(sanitizePostLoginRedirect(`${pathname}${locationSearch}${locationHash}`));
    router.replace(`/login?redirect=${redirect}`);
  }, [status, pathname, router]);

  // Refresh-ahead loop: when token is close to expiry, attempt refresh. A rejected refresh ends
  // the live session as expired so sign-in can say why the person is there.
  useEffect(() => {
    if (status !== "authenticated") return;
    let cancelled = false;
    const interval = setInterval(() => {
      if (cancelled) return;
      if (isTokenExpiringSoon(accessTokenExpiresAt)) {
        void refreshAheadOrExpire();
      }
    }, 30_000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [status, accessTokenExpiresAt]);

  return <>{children}</>;
}
