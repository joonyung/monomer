import { mkdirSync } from "node:fs";
import { expect, test, type ConsoleErrors } from "./support/test";
import type { Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { evidencePath } from "./evidence";
import { E2E_ACCESS_TOKEN, json, mockGraphql, mockSessionRefresh, mockSignedOut } from "./support/session";

const organizationId = "00000000-0000-4000-8000-000000000002";
const organization = { id: organizationId, displayName: "Analytics team", plan: "FREE", createdAt: "2026-09-01T00:00:00Z" };
const consent = {
  request: "fixture-request", csrfToken: "fixture-csrf", clientName: "Monomer Native", clientId: "monomer-native",
  clientOrigin: null, localClient: true, resource: "https://api.monomer.cc/v2/workspaces",
  expiresAt: "2026-09-07T00:10:00Z", existingConnectionCount: 0, latestConnectionIssuedAt: null,
};
type Scenario = "ready" | "error" | "signed-out" | "no-organization" | "expired" | "loading" | "connected";
async function fixture(page: Page, consoleErrors: ConsoleErrors, scenario: Scenario = "ready") {
  const submissions: unknown[] = [];
  const releases: unknown[] = [];
  let reads = 0;
  let releaseLoading = () => {};
  const loaded = new Promise<void>((resolve) => { releaseLoading = resolve; });
  if (scenario === "signed-out") await mockSignedOut(page, consoleErrors);
  else await mockSessionRefresh(page);
  await mockGraphql(page, {
    Me: (route) => json(route, { data: { me: {
      user: { id: "fixture-user", email: "fixture@example.test", displayName: "Research User" },
      organizationMemberships: scenario === "no-organization" ? [] : [{ organization, role: "MEMBER" }],
    } } }),
    AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: scenario === "no-organization" ? [] : ["Revenue", "Growth", "Finance", "Product"].map((name, index) => ({
      id: `00000000-0000-4000-8000-00000000010${index}`, organizationId, name, publicPages: false, reportingTimeZone: "UTC", role: "EXPLORER",
    })) } }),
  });
  await page.route("https://client.example/callback?*", (route) => route.fulfill({ contentType: "text/html; charset=utf-8", body: "Returned to app." }));
  await page.route("**/oauth/consent**", async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname === "/oauth/consent/release") {
      expect(route.request().headers().authorization).toBe(`Bearer ${E2E_ACCESS_TOKEN}`);
      releases.push(route.request().postDataJSON());
      return route.fulfill({ status: 204 });
    }
    if (pathname !== "/oauth/consent" || route.request().resourceType() === "document") return route.continue();
    expect(route.request().headers().authorization).toBe(`Bearer ${E2E_ACCESS_TOKEN}`);
    if (route.request().method() === "POST") {
      const body = route.request().postDataJSON(); submissions.push(body);
      expect(body.request).toBe(consent.request);
      expect(body.csrfToken).toBe(consent.csrfToken);
      return route.fulfill({ json: { redirectUri: `https://client.example/callback?${body.approve ? "code=fixture-code" : "error=access_denied"}` } });
    }
    reads += 1;
    if (scenario === "loading") await loaded;
    return route.fulfill({ status: scenario === "error" ? 400 : 200, json: {
      ...consent,
      expiresAt: scenario === "expired" ? "2000-01-01T00:00:00Z" : consent.expiresAt,
      ...(scenario === "connected" ? { existingConnectionCount: 1, latestConnectionIssuedAt: "2026-08-28T12:00:00Z" } : {}),
    } });
  });
  return { submissions, releases, reads: () => reads, releaseLoading };
}
test.beforeAll(() => mkdirSync(evidencePath("screenshots"), { recursive: true }));
test.beforeEach(async ({ page }) => { await page.clock.install({ time: new Date("2026-09-07T00:00:00Z") }); });

test("user consent names the signed-in user and says the app acts as them in their current and future Workspaces", async ({ page, consoleErrors }) => {
  const state = await fixture(page, consoleErrors);
  await page.goto("/oauth/consent?request=fixture-request");
  await expect(page.getByRole("button", { name: "Connect Monomer Native", exact: true })).toBeEnabled();
  await expect(page.getByRole("heading", { level: 1, name: "Connect Monomer Native to Monomer" })).toBeVisible();
  await expect(page.getByRole("combobox")).toHaveCount(0);
  await expect(page.getByText("fixture@example.test", { exact: true })).toBeVisible();
  await expect(page.getByText("Ask questions and read results in the 4 Workspaces you're a member of — and any you join later", { exact: true })).toBeVisible();
  await expect(page.getByTestId("consent-existing-connection")).toHaveCount(0);
  await expect(page.locator("body")).not.toContainText(organizationId);
  await expect(page.getByRole("heading", { name: "Monomer Native will act as you, with your roles:" })).toBeVisible();
  await expect(page.getByText("Create, edit, and delete what your role allows — dashboards, pages, metrics and models, data connections, and members and invitations if you manage them", { exact: true })).toBeVisible();
  await expect(page.getByText(/Stay connected for 30 days unless you revoke it in/)).toBeVisible();
  await expect(page.getByRole("link", { name: "Agents", exact: true })).toHaveAttribute("href", "/settings/agents");
  await expect(page.getByText(consent.resource, { exact: true })).toBeHidden();
  await page.getByText("Technical details", { exact: true }).click();
  await expect(page.getByText(consent.resource, { exact: true })).toBeVisible();
  expect(state.submissions).toHaveLength(0);
  expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze()).violations).toEqual([]);
  await page.screenshot({ path: evidencePath("screenshots/agent-oauth-consent-desktop.png"), fullPage: true });
  await page.getByRole("button", { name: "Connect Monomer Native", exact: true }).click();
  await expect(page.getByText("Returned to app.")).toBeVisible();
  expect(state.submissions).toEqual([{ request: consent.request, csrfToken: consent.csrfToken, approve: true }]);
});

test("mobile consent cancellation never grants and does not require Organization selection", async ({ page, consoleErrors }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await fixture(page, consoleErrors);
  await page.goto("/oauth/consent?request=fixture-request");
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toBeEnabled();
  await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 390);
  await page.screenshot({ path: evidencePath("screenshots/agent-oauth-consent-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(page).toHaveURL(/error=access_denied/);
  expect(state.submissions).toEqual([{ request: consent.request, csrfToken: consent.csrfToken, approve: false }]);
});

test("zero Workspace memberships can authorize without choosing an Organization", async ({ page, consoleErrors }) => {
  const state = await fixture(page, consoleErrors, "no-organization");
  await page.goto("/oauth/consent?request=fixture-request");
  await expect(page.getByRole("combobox")).toHaveCount(0);
  await expect(page.getByText("Ask questions and read results in Workspaces you join", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Connect Monomer Native", exact: true }).click();
  await expect(page).toHaveURL(/code=fixture-code/);
  expect(state.submissions).toEqual([{ request: consent.request, csrfToken: consent.csrfToken, approve: true }]);
});

test("signed-out consent preserves the exact return request and never fetches details", async ({ page, consoleErrors }) => {
  const state = await fixture(page, consoleErrors, "signed-out");
  await page.goto("/oauth/consent?request=fixture-request");
  await expect(page).toHaveURL(/\/login\?redirect=/);
  expect(new URL(page.url()).searchParams.get("redirect")).toBe("/oauth/consent?request=fixture-request");
  expect(state.reads()).toBe(0);
  expect(state.submissions).toHaveLength(0);
  await page.screenshot({ path: evidencePath("screenshots/agent-oauth-login-required.png"), fullPage: true });
});

for (const scenario of ["error", "expired", "loading"] as const) {
  test(`consent ${scenario} state cannot grant`, async ({ page, consoleErrors }) => {
    if (scenario === "error") consoleErrors.allow({ status: 400, url: /\/oauth\/consent\?/, reason: "The fixture rejects the consent request to show the unavailable state." });
    const state = await fixture(page, consoleErrors, scenario);
    await page.goto("/oauth/consent?request=fixture-request");
    if (scenario === "loading") {
      await expect(page.getByRole("status")).toContainText("Checking");
    } else {
      await expect(page.getByRole("alert")).toContainText("This request expired. Start again from the app.");
      await expect(page.getByRole("button", { name: "Connect Monomer Native", exact: true })).toHaveCount(0);
    }
    await page.screenshot({ path: evidencePath(`screenshots/agent-oauth-consent-${scenario}.png`), fullPage: true });
    state.releaseLoading();
    expect(state.submissions).toHaveLength(0);
  });
}

test("an app that is already connected is called out before connecting again", async ({ page, consoleErrors }) => {
  await fixture(page, consoleErrors, "connected");
  await page.goto("/oauth/consent?request=fixture-request");
  await expect(page.getByTestId("consent-existing-connection")).toContainText("Monomer Native is already connected to your account (since Aug 28).");
  await expect(page.getByTestId("consent-existing-connection")).toContainText("Connecting again adds another connection");
});

test("switching account releases the request and opens the Google account chooser back to the same request", async ({ page, consoleErrors }) => {
  const state = await fixture(page, consoleErrors);
  let start: URL | null = null;
  await page.route("**/oauth2/authorization/google?*", async (route) => {
    start = new URL(route.request().url());
    await route.fulfill({ body: "Account chooser fixture" });
  });
  await page.goto("/oauth/consent?request=fixture-request");
  await page.getByRole("button", { name: "Not you? Switch account" }).click();
  await expect(page.getByText("Account chooser fixture")).toBeVisible();
  expect(state.releases).toEqual([{ request: consent.request, csrfToken: consent.csrfToken }]);
  expect(start!.searchParams.get("prompt")).toBe("select_account");
  expect(start!.searchParams.has("login_hint")).toBe(false);
  expect(start!.searchParams.get("oauth_state")).toBeTruthy();
  expect(state.submissions).toHaveLength(0);
});
