export type ParsedOAuthCallback =
  | {
      kind: "success";
      newUser: boolean;
      authProvider: string | null;
      oauthState: string | null;
    }
  | {
      kind: "error";
      message: string;
      oauthState: string | null;
    };

export const OAUTH_CALLBACK_UNCORRELATED_MESSAGE =
  "Sign-in didn't finish in this browser tab. Start again from the sign-in page.";

// Control redirects with its own error codes (OAuth2LoginSuccessHandler.errorCode). Known codes get
// product copy; anything else, including provider-supplied descriptions, gets the generic message.
const OAUTH_CALLBACK_ERROR_MESSAGES: Record<string, string> = {
  access_denied: "This Google account isn't allowed to sign in to this environment.",
  identity_link_required: "This sign-in method isn't linked to your Monomer account. Sign in the way you did before, or ask your Organization Admin.",
  email_challenge_required: "Verify that you own this email address before linking this Google account.",
  provider_missing_claim: "Google didn't share the account details Monomer needs. Try again.",
};

export const OAUTH_CALLBACK_GENERIC_ERROR_MESSAGE = "Sign-in didn't complete. Try again.";

export function oauthCallbackErrorMessage(code: string): string {
  return OAUTH_CALLBACK_ERROR_MESSAGES[code] ?? OAUTH_CALLBACK_GENERIC_ERROR_MESSAGE;
}

export function parseOAuthCallbackParams(params: Pick<URLSearchParams, "get">): ParsedOAuthCallback {
  const oauthState = params.get("oauth_state");
  if (!oauthState) {
    return {
      kind: "error",
      message: OAUTH_CALLBACK_UNCORRELATED_MESSAGE,
      oauthState: null,
    };
  }
  const oauthError = params.get("error");
  if (oauthError) {
    return {
      kind: "error",
      message: oauthCallbackErrorMessage(oauthError),
      oauthState,
    };
  }

  return {
    kind: "success",
    newUser: params.get("new_user") === "true",
    authProvider: params.get("auth_provider"),
    oauthState,
  };
}
