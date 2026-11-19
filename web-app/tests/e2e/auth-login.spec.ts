import { mkdirSync } from "node:fs";
import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import { completeOAuthCallback, readOAuthReturn } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { E2E_ACCESS_TOKEN, json, mockGraphql, mockSignedOut, problem } from "./support/session";
import { mockAbsentSampleWorkspace } from "./support/overview";

test.beforeEach(async ({ page, consoleErrors }) => mockAbsentSampleWorkspace(page, "a-personal", consoleErrors));

test.beforeAll(() => {
  mkdirSync(evidencePath("screenshots"), { recursive: true });
});

const WORKSPACE_ID = "84000000-0000-4000-8000-000000000001";
const ORGANIZATION_WORKSPACE_ID = "84000000-0000-4000-8000-000000000002";
const ORGANIZATION_ID_BY_WORKSPACE_ID: Record<string, string> = {
  [WORKSPACE_ID]: "a-personal",
  [ORGANIZATION_WORKSPACE_ID]: "a-organization",
};

const meResponse = {
  data: {
    me: {
      user: {
        id: "u-stub",
        email: "stub@example.com",
        displayName: "Stub User",
      },
      organizationMemberships: [
        {
          organization: {
            id: "a-personal",
            displayName: "Stub User",
          },
          role: "ADMIN",
        },
        {
          organization: {
            id: "a-organization",
            displayName: "Acme Inc",
          },
          role: "MEMBER",
        },
      ],
    },
  },
};

interface AuthEvidence {
  callbackRefreshCount: number;
  callbackRefreshCookie: string | undefined;
  meAuthorization: string | undefined;
}

async function installAuthRoutes(
  page: Page,
  holdCallbackRefresh: boolean,
  accessibleWorkspacesError: string | null = null,
) {
  let callbackStarted = false;
  let releaseCallbackRefresh: () => void = () => undefined;
  const callbackRefreshGate = holdCallbackRefresh
    ? new Promise<void>((resolve) => {
        releaseCallbackRefresh = resolve;
      })
    : Promise.resolve();
  const evidence: AuthEvidence = {
    callbackRefreshCount: 0,
    callbackRefreshCookie: undefined,
    meAuthorization: undefined,
  };

  await mockGraphql(page, {
    Me: (route) => {
      evidence.meAuthorization = route.request().headers()["authorization"];
      return json(route, meResponse);
    },
    AccessibleWorkspaces: (route) => accessibleWorkspacesError
      ? json(route, { errors: [{ message: accessibleWorkspacesError }] })
      : json(route, { data: { accessibleWorkspaces: [
        { id: WORKSPACE_ID, organizationId: "a-personal", name: "Stub Workspace", publicPages: false, reportingTimeZone: "UTC", role: "OWNER" },
        { id: ORGANIZATION_WORKSPACE_ID, organizationId: "a-organization", name: "Acme Workspace", publicPages: false, reportingTimeZone: "UTC", role: "OWNER" },
      ] } }),
    WorkspaceAccess: (route, variables) => json(route, { data: { workspaceAccess: {
      granted: true,
      organizationId: ORGANIZATION_ID_BY_WORKSPACE_ID[String(variables.workspaceId)],
      role: "OWNER",
      } } }),
  });

  await page.route("**/auth/refresh", async (route: Route) => {
    if (!callbackStarted) {
      await problem(route, 401, "AUTHENTICATION_REQUIRED", "No refresh session is present.", "Unauthorized");
      return;
    }
    evidence.callbackRefreshCount += 1;
    // headers() strips cookie headers; only allHeaders() carries them.
    evidence.callbackRefreshCookie = (await route.request().allHeaders())["cookie"];
    await callbackRefreshGate;
    await json(route, { access_token: E2E_ACCESS_TOKEN, expires_at: new Date(Date.now() + 60 * 60_000).toISOString() });
  });

  return {
    evidence,
    startCallback: () => {
      callbackStarted = true;
    },
    releaseCallbackRefresh,
  };
}

test("metadata-only callback completes one refresh and preserves a safe non-root return target", async ({ page, baseURL, consoleErrors }) => {
  consoleErrors.allow({ status: 401, url: /\/auth\/refresh$/, reason: "The visitor starts signed out, so the first refresh is rejected before sign-in." });
  const auth = await installAuthRoutes(page, true);
  await page.route("**/v2/workspaces/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/connectors")) return json(route, { connectors: [] });
    if (path.endsWith("/semantic/metrics") || path.endsWith("/semantic/semantic-models")) return json(route, []);
    if (path.endsWith("/explore/runs")) return json(route, { runs: [] });
    return problem(route, 404, "NOT_FOUND", `No fixture for ${path}.`, "Not found");
  });
  const postLoginTarget = `/workspaces/${WORKSPACE_ID}/explore?execution=run-42`;
  // The refresh cookie the provider sets is SameSite=Lax, and CONTROL_API_URL is 127.0.0.1, so the
  // app has to be served from 127.0.0.1 too. Reached through the "localhost" alias the two origins
  // are different sites, Chromium drops the cookie, and the callback refresh goes out
  // uncredentialed even though the cookie is stored.
  const appOrigin = new URL(baseURL ?? "http://127.0.0.1:3005");
  appOrigin.hostname = "127.0.0.1";

  await page.goto(`${appOrigin.origin}${postLoginTarget}`);
  await expect(page).toHaveURL((url) =>
    url.pathname === "/login" && url.searchParams.get("redirect") === postLoginTarget,
  );
  await expect(page.getByTestId("login-google-button")).toBeVisible();

  const frontendOrigin = new URL(page.url()).origin;
  // LoginButton appends ?oauth_state=<minted>, and Playwright matches the query string, so a bare
  // path pattern never intercepts the start URL and the click escapes to the real origin.
  await page.route("**/oauth2/authorization/google**", async (route: Route) => {
    auth.startCallback();
    // The app minted the opaque state and stored the return target under it; the provider must
    // hand that exact state back or the callback cannot correlate the request.
    const mintedState = new URL(route.request().url()).searchParams.get("oauth_state") ?? "";
    await route.fulfill({
      status: 302,
      headers: {
        Location:
          `${frontendOrigin}/auth/callback?new_user=true&auth_provider=google` +
          `&oauth_state=${encodeURIComponent(mintedState)}`,
        "Set-Cookie": "refresh_token=oauth-test-cookie; Path=/auth; HttpOnly; SameSite=Lax",
      },
      body: "",
    });
  });

  await page.getByTestId("login-google-button").click();
  await expect(page.getByRole("status")).toBeVisible();
  auth.releaseCallbackRefresh();

  await expect(page).toHaveURL((url) =>
    url.pathname === `/workspaces/${WORKSPACE_ID}/explore` &&
    url.searchParams.get("execution") === "run-42",
  );
  expect(auth.evidence.callbackRefreshCount).toBe(1);
  expect(auth.evidence.callbackRefreshCookie).toContain("refresh_token=oauth-test-cookie");
  expect(auth.evidence.meAuthorization).toBe(`Bearer ${E2E_ACCESS_TOKEN}`);
  await expect.poll(() => page.evaluate(() =>
    window.localStorage.getItem("auth:post_login_redirect"),
  )).toBeNull();

  await expect(page.getByTestId("sidebar-user-display-name")).toHaveText("Stub User");
  await page.getByTestId("workspace-switcher").click();
  await expect(page.getByTestId("organization-option-a-personal")).toBeVisible();
  await expect(page.getByTestId("organization-option-a-organization")).toBeVisible();
  await page.getByTestId("organization-option-a-organization").click();
  await expect(page.getByTestId("workspace-switcher")).not.toContainText("Acme Workspace");
  await page.getByTestId(`workspace-option-${ORGANIZATION_WORKSPACE_ID}`).click();
  await expect(page.getByTestId("workspace-switcher")).toContainText("Acme Workspace");
});

test("callback rejects an unsafe stored return target, falls back home, and clears storage", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 401, url: /\/auth\/refresh$/, reason: "Sign-in checks the refresh cookie on load, and the visitor starts signed out." });
  const auth = await installAuthRoutes(page, false);

  await page.goto("/login");
  // The sign-in form appears only after the load-time session check has finished, so the
  // callback refresh counted below is the only one.
  await expect(page.getByTestId("login-google-button")).toBeVisible();
  await page.evaluate(() => {
    window.localStorage.setItem(
      "auth:post_login_redirect",
      "/%252e%252e//attacker.example/steal",
    );
  });
  auth.startCallback();
  const oauthState = await completeOAuthCallback(
    page,
    { new_user: "false", auth_provider: "google" },
    "/%252e%252e//attacker.example/steal",
  );

  await expect(page).toHaveURL((url) => url.pathname === "/" && url.search === "");
  expect(auth.evidence.callbackRefreshCount).toBe(1);
  await expect.poll(() => readOAuthReturn(page, oauthState)).toBeNull();
});

test("callback error is exposed as an alert with product copy and returns to sign-in", async ({ page }) => {
  await completeOAuthCallback(
    page,
    {
      error: "unverified_email",
      error_description: "Google account email is not verified.",
    },
    "/",
  );

  const alert = page.getByRole("alert").filter({ hasText: "Sign-in didn't finish" });
  await expect(alert).toBeVisible();
  await expect(alert).toContainText("Sign-in didn't complete. Try again.");
  await expect(alert).not.toContainText("Google account email is not verified.");
  await expect(page.getByRole("heading", { level: 1, name: "Sign-in didn't finish" })).toBeFocused();
  await page.screenshot({ path: evidencePath("screenshots/auth-callback-error.png"), fullPage: true });
  await alert.getByRole("button", { name: "Sign in again" }).click();
  await expect(page).toHaveURL((url) => url.pathname === "/login" && url.search === "");
  await expect(page.getByTestId("login-error")).toHaveText("Sign-in didn't complete. Try again.");
});

test("callback error keeps the original target when signing in again", async ({ page }) => {
  const target = `/workspaces/${WORKSPACE_ID}/explore?execution=run-42`;
  await completeOAuthCallback(page, { error: "provider_missing_claim" }, target);

  await page.getByRole("button", { name: "Sign in again" }).click();
  await expect(page).toHaveURL((url) => url.pathname === "/login" && url.searchParams.get("redirect") === target);
});

test("a refused Google account can choose another account", async ({ page }) => {
  await completeOAuthCallback(page, { error: "access_denied" }, "/");
  await page.getByRole("button", { name: "Sign in again" }).click();
  await expect(page.getByTestId("login-error")).toHaveText("This Google account isn't allowed to sign in to this environment.");

  const googleStart: { url: URL | null } = { url: null };
  await page.route("**/oauth2/authorization/google**", async (route: Route) => {
    googleStart.url = new URL(route.request().url());
    await route.fulfill({ status: 200, contentType: "text/html", body: "<!doctype html><title>Google</title>" });
  });
  await page.getByRole("button", { name: "Use another Google account" }).click();
  await expect.poll(() => googleStart.url?.searchParams.get("prompt") ?? null).toBe("select_account");
  expect(googleStart.url?.searchParams.get("login_hint")).toBeNull();
  expect(googleStart.url?.searchParams.get("oauth_state")).toBeTruthy();
});

test("sign-in names the app connection and links Terms and Privacy", async ({ page, consoleErrors }) => {
  await mockSignedOut(page, consoleErrors);
  await page.goto(`/login?redirect=${encodeURIComponent("/oauth/consent?request=fixture-request")}`);

  await expect(page.getByRole("heading", { level: 1, name: "Sign in to connect an app" })).toBeVisible();
  await expect(page.getByText("After you sign in, you'll review what the app can do before anything is connected.")).toBeVisible();
  await expect(page.getByText("New to Monomer? Continuing with Google creates your free workspace.")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Use another Google account" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Terms" })).toHaveAttribute("href", "https://monomer.cc/en/docs/help/terms/");
  await expect(page.getByRole("link", { name: "Privacy Policy" })).toHaveAttribute("href", "https://monomer.cc/en/docs/help/privacy/");
  await expect(page.getByRole("link", { name: "monomer.cc" })).toHaveAttribute("href", "https://monomer.cc/");
});

test("default sign-in keeps the free workspace note and puts the form first on mobile", async ({ page, consoleErrors }) => {
  await mockSignedOut(page, consoleErrors);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/login");

  await expect(page.getByRole("heading", { level: 1, name: "Sign in to Monomer" })).toBeVisible();
  await expect(page.getByText("New to Monomer? Continuing with Google creates your free workspace.")).toBeVisible();
  const formTop = (await page.getByTestId("login-google-button").boundingBox())!.y;
  const brandTop = (await page.getByRole("region", { name: "Monomer" }).boundingBox())!.y;
  expect(formTop).toBeLessThan(brandTop);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: evidencePath("screenshots/login-mobile.png"), fullPage: true });
});

test("an expired session explains why sign-in is needed", async ({ page, consoleErrors }) => {
  await mockSignedOut(page, consoleErrors);
  await page.addInitScript(() => {
    window.sessionStorage.setItem("monomer.auth.session", JSON.stringify({
      accessToken: "expired-token",
      accessTokenExpiresAt: "2020-01-01T00:00:00Z",
    }));
  });
  await page.goto("/lineage");

  await expect(page).toHaveURL(/\/login\?redirect=%2Flineage/);
  await expect(page.getByRole("status").filter({ hasText: "Your session expired. Sign in again." })).toBeVisible();
  await expect(page.getByTestId("login-error")).toHaveCount(0);
});

test("a live session skips the sign-in form", async ({ page }) => {
  const auth = await installAuthRoutes(page, false);
  auth.startCallback();
  await page.route("**/v2/workspaces/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/connectors")) return json(route, { connectors: [] });
    if (path.endsWith("/semantic/metrics") || path.endsWith("/semantic/semantic-models")) return json(route, []);
    if (path.endsWith("/explore/runs")) return json(route, { runs: [] });
    return problem(route, 404, "NOT_FOUND", `No fixture for ${path}.`, "Not found");
  });
  const target = `/workspaces/${WORKSPACE_ID}/explore?execution=run-42`;

  await page.goto(`/login?redirect=${encodeURIComponent(target)}`);

  await expect(page).toHaveURL((url) =>
    url.pathname === `/workspaces/${WORKSPACE_ID}/explore` &&
    url.searchParams.get("execution") === "run-42",
  );
  await expect(page.getByTestId("login-google-button")).toHaveCount(0);
  expect(auth.evidence.callbackRefreshCount).toBe(1);
});

test("landing exposes the accessible workspace failure and retry action", async ({ page }) => {
  const failureMessage = "Workspace authority schema is unavailable.";
  const auth = await installAuthRoutes(page, false, failureMessage);
  auth.startCallback();

  await completeOAuthCallback(
    page,
    { new_user: "false", auth_provider: "google" },
    "/",
  );

  const alert = page.getByRole("alert");
  await expect(alert).toContainText("Workspaces couldn't load");
  await expect(alert).toContainText("Check your connection and try again.");
  await expect(alert).not.toContainText(failureMessage);
  await expect(page.getByRole("button", { name: "Retry" })).toBeVisible();
  await expect(page.getByText("Finding your workspaces…")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/landing-workspace-error.png"), fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(alert).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: evidencePath("screenshots/landing-workspace-error-mobile.png"), fullPage: true });
});

test("Lineage product route remains auth protected", async ({ page, consoleErrors }) => {
  await mockSignedOut(page, consoleErrors);

  await page.goto("/lineage");
  await expect(page).toHaveURL(/\/login\?redirect=%2Flineage/);
});
