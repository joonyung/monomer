import { describe, expect, it } from "vitest";
import { resolveLoginContext, signInAgainPath } from "./login-context";

describe("resolveLoginContext", () => {
  it("defaults to workspaces for no redirect, a product path, or an unsafe redirect", () => {
    for (const redirect of [null, "/workspaces/abc/explore?run=1", "//attacker.example/x"]) {
      const context = resolveLoginContext(redirect, null);
      expect(context.kind).toBe("default");
      expect(context.heading).toBe("Sign in to Monomer");
      expect(context.description).toBe("Continue to your workspaces.");
      expect(context.invitationContinuation).toBeNull();
    }
    expect(resolveLoginContext("//attacker.example/x", null).redirect).toBe("/");
    expect(resolveLoginContext("/workspaces/abc/explore?run=1", null).redirect).toBe("/workspaces/abc/explore?run=1");
  });

  it("explains app connection for the agent and operations consent pages", () => {
    for (const redirect of ["/oauth/consent?request=r-1", "/ops/consent?request=r-2"]) {
      const context = resolveLoginContext(redirect, "ignored-continuation");
      expect(context.kind).toBe("app-connection");
      expect(context.heading).toBe("Sign in to connect an app");
      expect(context.description).toBe("After you sign in, you'll review what the app can do before anything is connected.");
      expect(context.redirect).toBe(redirect);
      expect(context.invitationContinuation).toBeNull();
    }
  });

  it("carries the invitation continuation only when this tab started the invitation", () => {
    const invitation = resolveLoginContext("/invitations/accept", "continuation-1");
    expect(invitation).toEqual({
      kind: "invitation",
      heading: "Sign in to accept your invitation",
      description: "Use the Google account your invitation was sent to.",
      redirect: "/invitations/accept",
      invitationContinuation: "continuation-1",
    });
    expect(resolveLoginContext("/invitations/accept", null).kind).toBe("default");
    expect(resolveLoginContext("/invitations/accept-other", "continuation-1").kind).toBe("default");
  });
});

describe("signInAgainPath", () => {
  it("returns to the original target through sign-in", () => {
    expect(signInAgainPath("/workspaces/abc/explore?run=1")).toBe("/login?redirect=%2Fworkspaces%2Fabc%2Fexplore%3Frun%3D1");
    expect(signInAgainPath("/invitations/accept")).toBe("/login?redirect=%2Finvitations%2Faccept");
  });

  it("uses plain sign-in without a target or for the home target", () => {
    expect(signInAgainPath(null)).toBe("/login");
    expect(signInAgainPath("/")).toBe("/login");
    expect(signInAgainPath("//attacker.example/x")).toBe("/login");
  });
});
