import { GOOGLE_OAUTH_START_URL } from "./config";
import { storePostLoginRedirect } from "./post-login-redirect";

export interface GoogleSignInStart {
  // Where the app goes after sign-in. It is sanitized and kept in this tab under an opaque state.
  redirect: string;
  // Asks Google to show its account chooser instead of reusing the signed-in Google account.
  chooseAccount: boolean;
  // Carries an invitation continuation so Control correlates the sign-in with the invitation.
  invitationContinuation: string | null;
}

export function googleSignInUrl({ redirect, chooseAccount, invitationContinuation }: GoogleSignInStart): string {
  const target = new URL(GOOGLE_OAUTH_START_URL);
  target.searchParams.set("oauth_state", storePostLoginRedirect(redirect));
  if (invitationContinuation) target.searchParams.set("invitation_continuation", invitationContinuation);
  if (chooseAccount) target.searchParams.set("prompt", "select_account");
  return target.toString();
}

export function startGoogleSignIn(start: GoogleSignInStart): void {
  window.location.assign(googleSignInUrl(start));
}
