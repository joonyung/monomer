import { mkdirSync } from "node:fs";
import { expect, test, type ConsoleErrors } from "./support/test";
import type { Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { evidencePath } from "./evidence";
import { E2E_ACCESS_TOKEN, json, mockGraphql, mockSessionRefresh, mockSignedOut, type GraphqlOperation } from "./support/session";

const invited = { id: "invitee-user", email: "invitee@gmail.com", displayName: "Invited User" };
const organization = { id: "00000000-0000-4000-8000-000000000002", displayName: "Northstar Labs", plan: "FREE", createdAt: "2026-09-01T00:00:00Z" };
const revenue = { workspaceId: "00000000-0000-4000-8000-000000000010", workspaceDisplayName: "Revenue Analytics", role: "EXPLORER" };
const details = {
  organizationId: organization.id, organizationDisplayName: organization.displayName, deliveryEmail: invited.email, invitedByDisplayName: "Alex Morgan",
  expiresAt: new Date(Date.now() + 5 * 86_400_000 + 3_600_000).toISOString(), workspaceAccess: [revenue],
};
async function fixture(page: Page, consoleErrors: ConsoleErrors, scenario: "ready" | "signed-out" | "mismatch" | "unavailable") {
  let accepted = false;
  const commands: string[] = [];
  if (scenario === "signed-out") await mockSignedOut(page, consoleErrors);
  else await mockSessionRefresh(page);
  await mockGraphql(page, {
    AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: [] } }),
    Me: (route) => json(route, { data: { me: {
      user: scenario === "mismatch" ? { ...invited, email: "other@gmail.com" } : invited,
      organizationMemberships: accepted ? [{ organization, role: "MEMBER" }] : [],
    } } }),
  });
  await page.route("**/auth/invitations/*", async (route) => {
    const action = new URL(route.request().url()).pathname.split("/").pop()!;
    commands.push(action);
    const body = route.request().postDataJSON();
    expect(route.request().method()).toBe("POST");
    if (action === "exchange") {
      expect(body).toEqual({ token: "fixture-invitation-proof" });
      await route.fulfill({ json: scenario === "unavailable" ? { outcome: "UNAVAILABLE" } : { outcome: "AVAILABLE", continuationId: "opaque-tab-continuation" } });
    } else {
      expect(body).toEqual({ continuationId: "opaque-tab-continuation" });
      if (action === "resolve") await route.fulfill({ json: scenario === "mismatch" ? { outcome: "EMAIL_MISMATCH", deliveryEmail: invited.email } : { ...details, outcome: scenario === "signed-out" ? "AUTHENTICATION_REQUIRED" : "AVAILABLE" } });
      else {
        expect(route.request().headers().authorization).toBe(`Bearer ${E2E_ACCESS_TOKEN}`);
        accepted = action === "accept";
        await route.fulfill({ json: accepted
          ? { ...details, outcome: "ACCEPTED", workspaceAccess: [], skippedWorkspaceAccess: [revenue] }
          : { ...details, outcome: "DECLINED", workspaceAccess: null, skippedWorkspaceAccess: null } });
      }
    }
  });
  return commands;
}
test.beforeAll(() => mkdirSync(evidencePath("screenshots"), { recursive: true }));
test("explicit acceptance clears URL proof, refreshes memberships and preserves a zero-workspace Member organization", async ({ page, consoleErrors }) => {
  const commands = await fixture(page, consoleErrors, "ready");
  await page.goto("/invitations/accept#token=fixture-invitation-proof");
  await expect(page.getByRole("button", { name: "Accept invitation" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 1, name: "Join Northstar Labs" })).toBeVisible();
  await expect(page.getByText("Alex Morgan invited you.")).toBeVisible();
  await expect(page.getByRole("definition").filter({ hasText: "Revenue Analytics · Explorer" })).toBeVisible();
  await expect(page.getByRole("definition").filter({ hasText: "in 5 days" })).toBeVisible();
  expect(new URL(page.url()).hash).toBe("");
  expect(commands).toEqual(["exchange", "resolve"]);
  expect(await page.evaluate(() => sessionStorage.getItem("monomer.invitation.continuation"))).toBe("opaque-tab-continuation");
  await expect(page.locator('meta[name="referrer"]').first()).toHaveAttribute("content", "no-referrer");
  expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze()).violations).toEqual([]);
  await page.screenshot({ path: evidencePath("screenshots/invitation-ready.png"), fullPage: true });
  await page.getByRole("button", { name: "Accept invitation" }).click();
  const joined = page.getByRole("heading", { level: 1, name: "You joined Northstar Labs" });
  await expect(joined).toBeVisible();
  await expect(joined).toBeFocused();
  await expect(page.getByText("Alex Morgan or a Workspace Owner can add you to a workspace.").first()).toBeVisible();
  await expect(page.getByText(/Access to 1 workspace in this invitation couldn't be\s+added/)).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem("monomer.invitation.continuation"))).toBeNull();
  await page.getByRole("link", { name: "Open workspaces" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Workspaces" })).toBeVisible();
  const organization = page.getByRole("region", { name: "Northstar Labs" });
  await expect(organization).toContainText("Member");
  await expect(organization.getByText("No workspace access yet. An Admin or a Workspace Owner can add you.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Organization settings" })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/invitation-joined-no-workspace.png"), fullPage: true });
});
test("signed-out invitation uses a correlated OAuth continuation without raw proof", async ({ page, consoleErrors }) => {
  const commands = await fixture(page, consoleErrors, "signed-out");
  await page.route("**/oauth2/authorization/google?*", async (route) => {
    const url = new URL(route.request().url());
    expect(url.searchParams.get("invitation_continuation")).toBe("opaque-tab-continuation");
    expect(url.searchParams.get("oauth_state")).toBeTruthy();
    expect(url.href).not.toContain("fixture-invitation-proof");
    await route.fulfill({ body: "OAuth fixture" });
  });
  await page.goto("/invitations/accept#token=fixture-invitation-proof");
  await expect(page.getByRole("link", { name: "Sign in to accept" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Accept invitation" })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/invitation-signed-out.png"), fullPage: true });
  await page.getByRole("link", { name: "Sign in to accept" }).click();
  await expect(page.getByRole("heading", { name: "Sign in to accept your invitation" })).toBeVisible();
  await page.getByTestId("login-google-button").click();
  await expect(page.getByText("OAuth fixture")).toBeVisible();
  expect(commands).not.toContain("accept");
});
test("mismatched email exposes only Google account switching and unavailable links reveal no organization", async ({ page, consoleErrors }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await fixture(page, consoleErrors, "mismatch");
  await page.goto("/invitations/accept#token=fixture-invitation-proof");
  await expect(page.getByRole("heading", { name: "Use the invited Google account" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Choose another Google account" })).toBeVisible();
  await expect(page.getByText("other@gmail.com")).toBeVisible();
  await expect(page.getByText("Northstar Labs")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Accept invitation" })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/invitation-mismatch-mobile.png"), fullPage: true });
  await page.route("**/oauth2/authorization/google?*", async (route) => {
    const url = new URL(route.request().url());
    expect(url.searchParams.get("prompt")).toBe("select_account");
    expect(url.searchParams.get("invitation_continuation")).toBe("opaque-tab-continuation");
    expect(url.searchParams.has("login_hint")).toBe(false);
    await route.fulfill({ body: "Account chooser fixture" });
  });
  await page.getByRole("button", { name: "Choose another Google account" }).click();
  await expect(page.getByText("Account chooser fixture")).toBeVisible();
  await fixture(page, consoleErrors, "unavailable");
  await page.goto("/invitations/accept#token=fixture-invitation-proof");
  await expect(page.getByRole("heading", { name: "Invitation unavailable" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Go to Monomer" })).toHaveAttribute("href", "/");
  await expect(page.getByText("Northstar Labs")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/invitation-unavailable.png"), fullPage: true });
});
test("decline is explicit and never joins the Organization", async ({ page, consoleErrors }) => {
  const commands = await fixture(page, consoleErrors, "ready");
  await page.goto("/invitations/accept#token=fixture-invitation-proof");
  await page.getByRole("button", { name: "Decline", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Invitation declined" })).toBeVisible();
  expect(commands).not.toContain("accept");
  await page.getByRole("link", { name: "Go to Monomer" }).click();
  await expect(page.getByText("Northstar Labs")).toHaveCount(0);
});
test("a failed choice keeps the invitation actionable and a failed refresh keeps the join", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 503, url: /\/auth\/invitations\/accept$/, reason: "The first accept fails so the choice must stay available to retry." });
  const commands = await fixture(page, consoleErrors, "ready");
  let acceptAttempts = 0;
  await page.route("**/auth/invitations/accept", async (route) => {
    acceptAttempts++;
    if (acceptAttempts === 1) await route.fulfill({ status: 503, json: { status: 503 } });
    else await route.fallback();
  });
  await page.goto("/invitations/accept#token=fixture-invitation-proof");
  const accept = page.getByRole("button", { name: "Accept invitation" });
  await accept.click();
  await expect(page.getByRole("alert")).toHaveText("Your choice couldn't be saved. Try again.");
  await expect(page.getByRole("heading", { level: 1, name: "Join Northstar Labs" })).toBeVisible();
  await expect(accept).toBeEnabled();
  await expect(page.getByRole("button", { name: "Decline", exact: true })).toBeEnabled();
  expect(await page.evaluate(() => sessionStorage.getItem("monomer.invitation.continuation"))).toBe("opaque-tab-continuation");
  await page.route("**/v2/graphql", async (route) => {
    if (/\bquery\s+Me\b/.test(route.request().postDataJSON().query)) await json(route, { errors: [{ message: "Temporarily unavailable" }] });
    else await route.fallback();
  });
  await accept.click();
  await expect(page.getByRole("heading", { level: 1, name: "You joined Northstar Labs" })).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
  expect(acceptAttempts).toBe(2);
  expect(commands).toEqual(["exchange", "resolve", "accept"]);
});

test("OAuth callback resumes only the correlated invitation in this tab", async ({ page, consoleErrors }) => {
  const commands = await fixture(page, consoleErrors, "signed-out");
  await page.goto("/invitations/accept#token=fixture-invitation-proof");
  await expect(page.getByRole("link", { name: "Sign in to accept" })).toBeVisible();
  await mockSessionRefresh(page);
  await page.route("**/auth/invitations/resolve", (route) => route.fulfill({ json: { outcome: "AVAILABLE", ...details } }));
  const appOrigin = new URL(page.url()).origin;
  await page.route("**/oauth2/authorization/google?*", async (route) => {
    const start = new URL(route.request().url());
    const callback = new URL("/auth/callback", appOrigin);
    callback.searchParams.set("oauth_state", start.searchParams.get("oauth_state")!);
    callback.searchParams.set("invitation_continuation", start.searchParams.get("invitation_continuation")!);
    await route.fulfill({ status: 302, headers: { location: callback.href } });
  });
  await page.getByRole("link", { name: "Sign in to accept" }).click();
  await page.getByTestId("login-google-button").click();
  await expect(page).toHaveURL(`${appOrigin}/invitations/accept`);
  await expect(page.getByRole("button", { name: "Accept invitation" })).toBeVisible();
  expect(commands).not.toContain("accept");
});

test("organization creation retains the first Organization and General reads actual metadata", async ({ page }) => {
  const firstOrganization = { ...organization, id: "firstOrganization", displayName: "First Organization", plan: "PRO" };
  let created = false;
  let createdOrganization = { ...organization };
  await mockSessionRefresh(page);
  const operations: Record<string, GraphqlOperation> = {
    CreateOrganization: (route, variables) => {
      expect(variables).toEqual({ displayName: "한글 조직" });
      created = true;
      createdOrganization = { ...createdOrganization, displayName: String(variables.displayName) };
      return json(route, { data: { createOrganization: { organization: { id: createdOrganization.id }, defaultWorkspace: { id: "default" } } } });
    },
    UpdateOrganizationDisplayName: (route, variables) => {
      createdOrganization = { ...createdOrganization, displayName: String(variables.displayName) };
      return json(route, { data: { updateOrganizationDisplayName: createdOrganization } });
    },
    OrganizationMembers: (route) => json(route, { data: { organizationMembers: [{ user: invited, role: "ADMIN", joinedAt: "2026-09-01T12:00:00Z", workspaces: [] }] } }),
    OrganizationMembershipInvitations: (route) => json(route, { data: { organizationMembershipInvitations: [] } }),
    ManagedWorkspaces: (route) => json(route, { data: { managedWorkspaces: [] } }),
    OrganizationUsage: (route) => json(route, { data: { organizationUsage: { limits: { members: 30, workspaces: 5, pendingInvitations: 30 }, usage: { members: 1, workspaces: 1, pendingInvitations: 0 } } } }),
    AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: [] } }),
    Me: (route) => json(route, { data: { me: { user: invited, organizationMemberships: [firstOrganization, ...(created ? [createdOrganization] : [])].map((value) => ({ organization: value, role: "ADMIN" })) } } }),
  };
  await mockGraphql(page, Object.fromEntries(Object.entries(operations).map(([name, operation]) => [name, (route, variables) => {
    expect(route.request().postDataJSON().query).not.toMatch(/\bkind\b/);
    return operation(route, variables);
  }])));
  await page.goto("/workspaces");
  await page.getByRole("button", { name: "New Organization", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Create a new Organization" });
  await expect(dialog).toContainText("An Organization holds your team's workspaces, members, and billing plan.");
  await dialog.getByLabel("Organization name").fill("한글 조직");
  await expect(dialog.getByRole("textbox")).toHaveCount(1);
  await page.screenshot({ path: evidencePath("screenshots/create-organization-name-only.png"), fullPage: true });
  await dialog.getByRole("button", { name: "Create Organization" }).click();
  await expect(page).toHaveURL("/workspaces/default");
  await page.goto("/workspaces");
  await expect(page.getByText("First Organization", { exact: true })).toBeVisible();
  await expect(page.getByText("한글 조직", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Organization settings" }).last()).toHaveAttribute("href", `/organizations/${createdOrganization.id}/settings`);
  await page.goto(`/organizations/${createdOrganization.id}/settings`);
  await expect(page.getByTestId("organization-settings-identity")).toContainText("Free plan");
  await expect(page.getByRole("region", { name: "Usage" })).toContainText("Free plan limits during the public beta");
  await page.getByRole("button", { name: "Rename Organization" }).click();
  await page.getByRole("dialog").getByLabel("Organization name").fill("Northstar Data");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("definition").filter({ hasText: "Northstar Data" })).toBeVisible();
  await page.screenshot({ path: evidencePath("screenshots/organization-general-production.png"), fullPage: true });
  await page.goto("/organizations/firstOrganization/settings");
  await expect(page.getByTestId("organization-settings-identity")).toContainText("Pro plan");
  await expect(page.getByTestId("organization-settings-nav-members")).toBeVisible();
  await page.getByTestId("organization-settings-nav-members").click();
  await expect(page.getByText(invited.email, { exact: true }).last()).toBeVisible();
  await expect(page.getByRole("region", { name: /Pending invitations/ })).toHaveCount(0);
  await page.getByRole("button", { name: "Invite people" }).click();
  await expect(page.getByRole("dialog", { name: "Invite people" })).toBeVisible();

});

test("Accept refreshes a bearer expired in a background tab without requiring Google sign-in", async ({ page, consoleErrors }) => {
  await page.clock.install({ time: new Date("2026-09-05T00:00:00Z") });
  await fixture(page, consoleErrors, "ready");
  let refreshes = 0;
  let acceptedBearer: string | undefined;
  await page.route("**/auth/refresh", async (route) => {
    refreshes += 1;
    await json(route, {
      access_token: refreshes === 1 ? "initial-browser-token" : "refreshed-browser-token",
      expires_at: refreshes === 1 ? "2026-09-05T00:02:00Z" : "2026-09-05T01:00:00Z",
    });
  });
  await page.route("**/auth/invitations/accept", async (route) => {
    acceptedBearer = route.request().headers().authorization;
    await route.fulfill({ status: 200, json: acceptedBearer === "Bearer refreshed-browser-token"
      ? { ...details, outcome: "ACCEPTED", workspaceAccess: [revenue], skippedWorkspaceAccess: [] }
      : { outcome: "AUTHENTICATION_REQUIRED" } });
  });
  await page.goto("/invitations/accept#token=fixture-invitation-proof");
  await expect(page.getByRole("button", { name: "Accept invitation" })).toBeVisible();
  expect(refreshes).toBe(1);
  await page.clock.setSystemTime(new Date("2026-09-05T00:05:00Z"));
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(page.getByRole("heading", { name: "You joined Northstar Labs" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Workspace access" })).toContainText("Revenue Analytics · Explorer");
  await expect(page.getByRole("link", { name: "Open Revenue Analytics" })).toHaveAttribute("href", `/workspaces/${revenue.workspaceId}`);
  expect(refreshes).toBe(2);
  expect(acceptedBearer).toBe("Bearer refreshed-browser-token");
  await expect(page.getByRole("button", { name: "Continue with Google" })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/invitation-refreshed-accepted.png"), fullPage: true });
});


test("same-name Organizations keep distinct UUID routes and names cannot resolve a route", async ({ page }) => {
  const organizations = [
    { ...organization, displayName: "같은 조직", plan: "FREE" },
    { ...organization, id: "00000000-0000-4000-8000-000000000003", displayName: "같은 조직", plan: "PRO" },
  ];
  await mockSessionRefresh(page);
  await mockGraphql(page, {
    AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: [] } }),
    Me: (route) => json(route, { data: { me: { user: invited, organizationMemberships: organizations.map((value) => ({ organization: value, role: "ADMIN" })) } } }),
    OrganizationMembers: (route) => json(route, { data: { organizationMembers: [{ user: invited, role: "ADMIN", joinedAt: "2026-09-01T12:00:00Z", workspaces: [] }] } }),
    ManagedWorkspaces: (route) => json(route, { data: { managedWorkspaces: [] } }),
    OrganizationUsage: (route, variables) => json(route, { data: { organizationUsage: {
      limits: variables.organizationId === organizations[0].id ? { members: 30, workspaces: 5, pendingInvitations: 30 } : null,
      usage: { members: 1, workspaces: 0, pendingInvitations: 0 },
    } } }),
  });
  await page.goto("/workspaces");
  const links = page.getByRole("link", { name: "Organization settings" });
  await expect(links).toHaveCount(2);
  for (const [index, value] of organizations.entries()) {
    await expect(links.nth(index)).toHaveAttribute("href", `/organizations/${value.id}/settings`);
  }
  for (const value of organizations) {
    await page.goto(`/organizations/${value.id}/settings`);
    await expect(page.getByTestId("organization-settings-identity")).toContainText(value.plan === "PRO" ? "Pro plan" : "Free plan");
    await expect(page.getByRole("definition").filter({ hasText: value.id })).toBeVisible();
  }
  await page.goto(`/organizations/${encodeURIComponent("같은 조직")}/settings`);
  await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Rename Organization" })).toHaveCount(0);
});


test("full organization keeps the invitation and allows explicit acceptance after space frees", async ({ page, consoleErrors }) => {
  const commands = await fixture(page, consoleErrors, "ready");
  let attempts = 0;
  await page.route("**/auth/invitations/accept", async (route) => {
    attempts += 1;
    expect(route.request().postDataJSON()).toEqual({ continuationId: "opaque-tab-continuation" });
    await route.fulfill({ json: attempts === 1
      ? { ...details, outcome: "MEMBER_LIMIT_REACHED" }
      : { ...details, outcome: "ACCEPTED" } });
  });
  await page.goto("/invitations/accept#token=fixture-invitation-proof");
  await expect(page.getByText(/An invitation doesn't reserve a place/)).toBeVisible();
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(page.getByRole("heading", { name: "This Organization is full" })).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem("monomer.invitation.continuation"))).toBe("opaque-tab-continuation");
  await expect(page.getByRole("button", { name: "Decline", exact: true })).toBeEnabled();
  expect(attempts).toBe(1);
  await page.screenshot({ path: evidencePath("../screenshots/joo-12-invitation-full-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze()).violations).toEqual([]);
  await page.screenshot({ path: evidencePath("../screenshots/joo-12-invitation-full-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(page.getByRole("heading", { name: "You joined Northstar Labs" })).toBeVisible();
  expect(attempts).toBe(2);
  expect(commands.filter((command) => command === "exchange")).toHaveLength(1);
  expect(await page.evaluate(() => sessionStorage.getItem("monomer.invitation.continuation"))).toBeNull();
});

test("a signed-in member sees they are already in the Organization before accepting", async ({ page, consoleErrors }) => {
  const commands = await fixture(page, consoleErrors, "ready");
  await page.route("**/auth/invitations/resolve", (route) => route.fulfill({ json: { ...details, outcome: "ALREADY_MEMBER" } }));
  await page.goto("/invitations/accept#token=fixture-invitation-proof");
  await expect(page.getByRole("heading", { name: "You're already in Northstar Labs" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Accept invitation" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Open workspaces" })).toHaveAttribute("href", "/workspaces");
  expect(commands).not.toContain("accept");
});
