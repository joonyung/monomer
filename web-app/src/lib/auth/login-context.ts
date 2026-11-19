import { INVITATION_CONTINUATION_KEY } from "./invitations";
import { sanitizePostLoginRedirect } from "./post-login-redirect";

const APP_ORIGIN = "https://app.monomer.invalid";
const APP_CONSENT_PATHS = new Set(["/oauth/consent", "/ops/consent"]);
export const INVITATION_ACCEPT_PATH = "/invitations/accept";

export type LoginContextKind = "default" | "app-connection" | "invitation";

export interface LoginContext {
  kind: LoginContextKind;
  heading: string;
  description: string;
  // Where sign-in returns. Already sanitized.
  redirect: string;
  // Present only for an invitation this tab started; Control correlates the sign-in with it.
  invitationContinuation: string | null;
}

function redirectPathname(redirect: string): string {
  return new URL(redirect, APP_ORIGIN).pathname;
}

// Sign-in says why the person is here, derived from where they are going next.
export function resolveLoginContext(
  rawRedirect: string | null,
  storedInvitationContinuation: string | null,
): LoginContext {
  const redirect = sanitizePostLoginRedirect(rawRedirect);
  const pathname = redirectPathname(redirect);
  if (APP_CONSENT_PATHS.has(pathname)) {
    return {
      kind: "app-connection",
      heading: "Sign in to connect an app",
      description: "After you sign in, you'll review what the app can do before anything is connected.",
      redirect,
      invitationContinuation: null,
    };
  }
  if (pathname === INVITATION_ACCEPT_PATH && storedInvitationContinuation) {
    return {
      kind: "invitation",
      heading: "Sign in to accept your invitation",
      description: "Use the Google account your invitation was sent to.",
      redirect: INVITATION_ACCEPT_PATH,
      invitationContinuation: storedInvitationContinuation,
    };
  }
  return {
    kind: "default",
    heading: "Sign in to Monomer",
    description: "Continue to your workspaces.",
    redirect,
    invitationContinuation: null,
  };
}

export function readStoredInvitationContinuation(): string | null {
  if (typeof window === "undefined") return null;
  try {
    const value = window.sessionStorage.getItem(INVITATION_CONTINUATION_KEY);
    return value && value.length > 0 ? value : null;
  } catch {
    return null;
  }
}

// The sign-in page that returns to the target a failed sign-in was headed for.
export function signInAgainPath(target: string | null): string {
  if (target === null) return "/login";
  const redirect = sanitizePostLoginRedirect(target);
  return redirect === "/" ? "/login" : `/login?redirect=${encodeURIComponent(redirect)}`;
}
