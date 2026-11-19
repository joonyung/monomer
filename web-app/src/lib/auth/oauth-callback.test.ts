import { describe, expect, it } from "vitest";
import { parseOAuthCallbackParams } from "./oauth-callback";

describe("parseOAuthCallbackParams", () => {
  it("parses successful auth-service callback params", () => {
    const params = new URLSearchParams(
      "new_user=true&auth_provider=google&oauth_state=state-1",
    );

    expect(parseOAuthCallbackParams(params)).toEqual({
      kind: "success",
      newUser: true,
      authProvider: "google",
      oauthState: "state-1",
    });
  });

  it("maps an unknown error code to the generic message instead of echoing its description", () => {
    const params = new URLSearchParams(
      "error=unverified_email&error_description=Google+account+email+is+not+verified.&oauth_state=state-2",
    );

    expect(parseOAuthCallbackParams(params)).toEqual({
      kind: "error",
      message: "Sign-in didn't complete. Try again.",
      oauthState: "state-2",
    });
  });

  it("maps the error codes Control sends to product copy", () => {
    const message = (code: string) => {
      const parsed = parseOAuthCallbackParams(new URLSearchParams({ error: code, error_description: "raw", oauth_state: "state-3" }));
      return parsed.kind === "error" ? parsed.message : null;
    };
    expect(message("access_denied")).toBe("This Google account isn't allowed to sign in to this environment.");
    expect(message("identity_link_required")).toContain("isn't linked to your Monomer account");
    expect(message("email_challenge_required")).toContain("Verify that you own this email address");
    expect(message("provider_missing_claim")).toContain("Google didn't share the account details");
    expect(message("oauth_failed")).toBe("Sign-in didn't complete. Try again.");
  });

  it("rejects a callback without the exact oauth state", () => {
    expect(parseOAuthCallbackParams(new URLSearchParams(""))).toEqual({
      kind: "error",
      message: "Sign-in didn't finish in this browser tab. Start again from the sign-in page.",
      oauthState: null,
    });
  });
});
