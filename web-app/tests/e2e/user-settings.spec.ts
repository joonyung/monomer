import { mkdirSync } from "node:fs";
import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { evidencePath } from "./evidence";
import { E2E_ACCESS_TOKEN, json, mockGraphql, mockSignedOut } from "./support/session";

const organizationId = "00000000-0000-4000-8000-000000000002";
const organization = { id: organizationId, displayName: "Monomer Team", plan: "FREE", createdAt: "2026-09-01T00:00:00Z" };
const adminOrganization = { id: "00000000-0000-4000-8000-000000000003", displayName: "Northstar Labs", plan: "FREE", createdAt: "2026-09-02T00:00:00Z" };
const initialUser = { id: "fixture-user", email: "jiyun@example.test", displayName: "Jiyun Kim" };
const grant = {
  id: "grant-one", clientId: "codex", clientName: "Codex" as string | null,
  resource: "https://api.monomer.cc/mcp", issuedAt: "2026-09-01T00:00:00Z", expiresAt: "2026-10-01T00:00:00Z", revokedAt: null as string | null,
  lastUsedAt: "2026-09-06T21:00:00Z" as string | null,
};
const grants = [grant, { ...grant, id: "grant-two", resource: "https://api.monomer.cc/v2/workspaces", lastUsedAt: null },
  { ...grant, id: "grant-three", clientName: null, clientId: "custom-client", resource: "https://other.example/resource" }];
function deferred() {
  let resolve = () => {};
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}
async function fixture(page: Page) {
  const state = {
    user: { ...initialUser }, connections: grants.map((entry) => ({ ...entry })), connectionPageSize: 100,
    saveError: false, saveUnauthorized: false, refreshes: 0, listError: false, revokeError: false, meError: false, meReads: 0,
    meGate: null as ReturnType<typeof deferred> | null,
    saveGate: null as ReturnType<typeof deferred> | null,
    listGate: null as ReturnType<typeof deferred> | null,
    revokeGate: null as ReturnType<typeof deferred> | null,
    saves: [] as string[], revocations: [] as string[], reads: 0, signOuts: 0, signOutsEverywhere: 0,
  };
  await page.route("**/auth/refresh", (route) => {
    state.refreshes += 1;
    return json(route, { access_token: E2E_ACCESS_TOKEN, expires_at: "2099-01-01T00:00:00Z" });
  });
  await page.route("**/auth/logout", (route) => {
    state.signOuts += 1;
    return route.fulfill({ status: 204 });
  });
  await page.route("**/auth/logout-all", (route) => {
    state.signOutsEverywhere += 1;
    return route.fulfill({ status: 204 });
  });
  await mockGraphql(page, {
    UpdateMyDisplayName: async (route, variables) => {
      const displayName = String(variables.displayName);
      state.saves.push(displayName);
      const updatedUser = { ...state.user, displayName: displayName.trim() };
      if (state.saveGate) await state.saveGate.promise;
      if (state.saveUnauthorized) return route.fulfill({ status: 401 });
      if (state.saveError) return json(route, { errors: [{ message: "Could not save display name" }] });
      if (state.user.id === updatedUser.id) state.user = updatedUser;
      return json(route, { data: { updateMyDisplayName: updatedUser } });
    },
    Me: async (route) => {
      state.meReads += 1;
      if (state.meReads > 1 && state.meGate) await state.meGate.promise;
      if (state.meReads > 1 && state.meError) return json(route, { errors: [{ message: "Profile unavailable" }] });
      return json(route, { data: { me: { user: state.user,
        organizationMemberships: [{ organization, role: "MEMBER" }, { organization: adminOrganization, role: "ADMIN" }],
        loginIdentities: [{ provider: "Google", linkedAt: "2026-09-01T00:00:00Z" }],
      } } });
    },
    AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: [] } }),
  });
  await page.route("**/oauth/connections**", async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (!/^\/oauth\/connections(?:\/|$)/.test(pathname)) return route.continue();
    // The settings sidebar counts active connections, so reads follow whichever session is current.
    expect([`Bearer ${E2E_ACCESS_TOKEN}`, "Bearer second-browser-token"]).toContain(route.request().headers().authorization);
    if (route.request().method() === "POST") {
      state.revocations.push(pathname);
      if (state.revokeGate) await state.revokeGate.promise;
      if (state.revokeError) return route.fulfill({ status: 503 });
      const id = pathname.split("/")[3];
      state.connections = state.connections.map((entry) => entry.id === id ? { ...entry, revokedAt: "2026-09-07T00:00:00Z" } : entry);
      return route.fulfill({ status: 204 });
    }
    state.reads += 1;
    const offset = Number(new URL(route.request().url()).searchParams.get("cursor") ?? 0);
    const connections = state.connections.slice(offset, offset + state.connectionPageSize).map((entry) => ({ ...entry }));
    const nextCursor = offset + state.connectionPageSize < state.connections.length ? String(offset + state.connectionPageSize) : null;
    if (state.listGate) await state.listGate.promise;
    return route.fulfill({ status: state.listError ? 503 : 200, json: { connections, nextCursor } });
  });
  return state;
}
async function screenshot(page: Page, name: string) {
  await page.screenshot({ path: evidencePath(`screenshots/user-settings/${name}.png`), fullPage: true });
}

test.beforeAll(() => mkdirSync(evidencePath("screenshots/user-settings"), { recursive: true }));
test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-07T00:00:00Z") });
});

test("profile reads Me, validates and cancels edits, saves into menu and survives reload", async ({ page }) => {
  const state = await fixture(page);
  await page.goto("/settings/profile");
  await expect(page.getByRole("heading", { name: "Profile", level: 1 })).toBeVisible();
  const sidebar = page.getByTestId("desktop-sidebar");
  await expect(sidebar.getByTestId("user-settings-identity")).toContainText(initialUser.email);
  const navigation = sidebar.getByRole("navigation", { name: "Your settings" });
  await expect(navigation.getByRole("link", { name: /^Profile/ })).toHaveAttribute("aria-current", "page");
  await expect(navigation.getByRole("link", { name: /^Organizations/ })).toContainText("2");
  await expect(navigation.getByRole("link", { name: /^Agents/ })).toContainText("3");
  await expect(page.getByRole("main").getByText(initialUser.email, { exact: true })).toBeVisible();
  await expect(page.getByText(`Google · ${initialUser.email}`, { exact: true })).toBeVisible();
  await expect(page.getByText(/across all Organizations/)).toHaveCount(0);
  await screenshot(page, "profile-desktop");
  await page.getByRole("button", { name: "Edit display name", exact: true }).click();
  const name = page.getByLabel("Display name", { exact: true });
  await expect(name).toHaveValue(initialUser.displayName);
  await name.fill("   ");
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();
  await name.fill("Discarded name");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  expect(state.saves).toEqual([]);
  await page.getByRole("button", { name: "Edit display name", exact: true }).click();
  await expect(name).toHaveValue(initialUser.displayName);
  await name.fill("  Sarah Kim  ");
  state.saveGate = deferred();
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => state.saves.length).toBe(1);
  await expect(name).toBeDisabled();
  await expect(page.getByRole("button", { name: "Cancel", exact: true })).toBeDisabled();
  await screenshot(page, "profile-saving");
  state.saveGate.resolve();
  await expect(page.getByTestId("sidebar-user-display-name")).toHaveText("Sarah Kim");
  await expect(page.getByText("Display name saved.", { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByTestId("sidebar-user-display-name")).toHaveText("Sarah Kim");
  await page.getByTestId("sidebar-user").click();
  await expect(page.getByRole("menuitem", { name: "Authorized apps" })).toHaveCount(0);
  await expect(page.getByRole("menuitem", { name: "Connect an agent", exact: true })).toHaveAttribute("href", "/settings/agents");
  await page.getByRole("menuitem", { name: "Settings", exact: true }).click();
  await expect(page).toHaveURL(/\/settings\/profile$/);
  expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze()).violations).toEqual([]);
});

test("profile save error preserves draft and permits retry", async ({ page }) => {
  const state = await fixture(page);
  state.saveError = true;
  await page.goto("/settings/profile");
  await page.getByRole("button", { name: "Edit display name", exact: true }).click();
  await page.getByLabel("Display name", { exact: true }).fill("Sarah Kim");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByLabel("Display name", { exact: true })).toHaveValue("Sarah Kim");
  await expect(page.getByTestId("sidebar-user-display-name")).toHaveText(initialUser.displayName);
  await screenshot(page, "profile-error");
  state.saveError = false;
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByTestId("sidebar-user-display-name")).toHaveText("Sarah Kim");
});

test("signing out from the workspace chooser ends the server session", async ({ page }) => {
  const state = await fixture(page);
  await page.goto("/workspaces");
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  expect(state.signOuts).toBe(1);
});

test("organizations list memberships and link Admins to Organization settings", async ({ page }) => {
  await fixture(page);
  await page.goto("/settings/organizations");
  const list = page.getByRole("list", { name: "Your Organizations" });
  await expect(list.getByRole("listitem").filter({ hasText: "Monomer Team" })).toContainText("Member");
  await expect(list.getByRole("link", { name: "Organization settings for Monomer Team" })).toHaveCount(0);
  await expect(list.getByRole("link", { name: "Organization settings for Northstar Labs" })).toHaveAttribute("href", `/organizations/${adminOrganization.id}/settings`);
  await screenshot(page, "organizations-desktop");
});

test("agents list active connections by app and target with last use and identity fallbacks", async ({ page }) => {
  await fixture(page);
  await page.goto("/settings/agents");
  await expect(page.getByText("Agents you connect act as you, with your roles, in every Workspace you’re a member of.", { exact: true })).toBeVisible();
  const table = page.getByRole("table", { name: "Active connections" });
  await expect(table.getByRole("columnheader", { name: "Connects to" })).toBeVisible();
  await expect(table.getByRole("columnheader", { name: "Last used" })).toBeVisible();
  await expect(table.getByRole("button", { name: "Revoke Codex" })).toHaveCount(2);
  await expect(table.getByRole("cell", { name: "MCP", exact: true })).toBeVisible();
  await expect(table.getByRole("cell", { name: "REST API", exact: true })).toBeVisible();
  await expect(table.getByRole("cell", { name: "https://other.example/resource", exact: true })).toBeVisible();
  await expect(table.getByRole("cell", { name: "3 hours ago", exact: true })).toHaveCount(2);
  await expect(table.getByRole("cell", { name: "Not used yet", exact: true })).toBeVisible();
  await expect(table.getByRole("button", { name: "Revoke custom-client" })).toBeVisible();
  await expect(page.getByText("All accessible Organizations")).toHaveCount(0);
  await expect(page.getByRole("button", { name: /Show revoked and expired/ })).toHaveCount(0);
  await screenshot(page, "connections-desktop");
  expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze()).violations).toEqual([]);
});

test("connect an agent shows this environment's MCP address and short steps for each client", async ({ page }) => {
  await fixture(page);
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/settings/agents");
  const card = page.getByTestId("connect-agent");
  await expect(card.getByRole("heading", { name: "Connect an agent", level: 2 })).toBeVisible();
  await expect(card.getByTestId("agent-mcp-url")).toHaveText(/^https?:\/\/[^/]+\/mcp$/);
  await card.getByRole("button", { name: "Copy MCP server URL" }).click();
  await expect(card.getByRole("button", { name: "Copy MCP server URL" })).toHaveText("Copied");
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(await card.getByTestId("agent-mcp-url").textContent());
  const tabs = card.getByRole("tablist", { name: "Agent clients" });
  await expect(tabs.getByRole("tab")).toHaveText(["Claude", "Claude Code", "Codex", "Cursor", "Other MCP client"]);
  await expect(tabs.getByRole("tab", { name: "Claude", exact: true })).toHaveAttribute("aria-selected", "true");
  const panel = card.getByRole("tabpanel");
  await expect(panel.getByRole("listitem")).toHaveCount(3);
  await expect(panel.getByRole("link", { name: /Full guide for Claude/ })).toHaveAttribute("href", "https://monomer.cc/en/docs/agents/claude/");
  await expect(panel.getByRole("link", { name: /REST setup/ })).toHaveAttribute("href", "https://monomer.cc/en/docs/agents/rest/");
  await expect(panel.getByTestId("agent-client-unverified")).toHaveCount(0);
  await tabs.getByRole("tab", { name: "Claude", exact: true }).focus();
  await page.keyboard.press("ArrowRight");
  await expect(tabs.getByRole("tab", { name: "Claude Code" })).toBeFocused();
  await expect(panel.getByTestId("agent-client-unverified")).toHaveCount(0);
  await expect(panel.locator("code")).toContainText("claude mcp add --transport http --scope user monomer");
  const command = await panel.locator("code").textContent();
  await panel.getByRole("button", { name: "Copy command", exact: true }).click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(command);
  expect(await panel.locator("code").evaluate((element) => [getComputedStyle(element).fontVariantLigatures, getComputedStyle(element).fontFeatureSettings])).toEqual(["none", "\"calt\" 0"]);
  await screenshot(page, "connect-agent-claude-code-command");
  await tabs.getByRole("tab", { name: "Codex" }).click();
  await expect(panel.locator("code")).toHaveText(/^codex mcp add monomer --url https?:\/\/[^ ]+\/mcp && codex mcp login monomer --oauth-client-registration cimd$/);
  await tabs.getByRole("tab", { name: "Cursor" }).click();
  await expect(panel.getByTestId("agent-client-unverified")).toHaveText("Not yet verified with Monomer sign-in.");
  await expect(panel.getByRole("link", { name: /Full guide for Cursor/ })).toHaveAttribute("href", "https://monomer.cc/en/docs/agents/cursor/");
  await panel.getByRole("button", { name: "Copy configuration", exact: true }).click();
  const configuration = await page.evaluate(() => navigator.clipboard.readText());
  const server = { url: await card.getByTestId("agent-mcp-url").textContent() };
  expect(JSON.parse(configuration)).toEqual({ mcpServers: { monomer: server } });
  const install = new URL((await panel.getByRole("link", { name: "Add to Cursor", exact: true }).getAttribute("href"))!);
  expect(install.protocol).toBe("cursor:");
  expect(install.searchParams.get("name")).toBe("monomer");
  expect(JSON.parse(Buffer.from(install.searchParams.get("config")!, "base64").toString("utf8"))).toEqual(server);
  await screenshot(page, "connect-agent-desktop");
  expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze()).violations).toEqual([]);
});

test("connections across pages collapse into one app row that expands and revokes together", async ({ page }) => {
  const state = await fixture(page);
  state.connectionPageSize = 1;
  state.connections = [
    { ...grant, id: "claude-one", clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", clientName: "Claude", issuedAt: "2026-09-05T00:00:00Z", expiresAt: "2026-10-05T00:00:00Z", lastUsedAt: "2026-09-06T23:30:00Z" },
    { ...grant, id: "claude-two", clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", clientName: "Claude", issuedAt: "2026-08-09T12:00:00Z", expiresAt: "2026-09-08T12:00:00Z", lastUsedAt: null },
    { ...grant, id: "native-rest", clientId: "monomer-native", clientName: "Monomer Native", resource: "https://api.monomer.cc/v2/workspaces", lastUsedAt: null },
  ];
  await page.goto("/settings/agents");
  const navigation = page.getByTestId("desktop-sidebar").getByRole("navigation", { name: "Your settings" });
  await expect(navigation.getByRole("link", { name: /^Agents/ })).toContainText("2");
  const table = page.getByRole("table", { name: "Active connections" });
  await expect(table.getByTestId("connection-group")).toHaveCount(2);
  expect(state.reads).toBeGreaterThanOrEqual(3);
  const claude = table.getByTestId("connection-group").filter({ hasText: "Claude" });
  await expect(claude.getByText("claude.ai", { exact: true })).toHaveAttribute("title", "https://claude.ai/oauth/mcp-oauth-client-metadata");
  await expect(claude).not.toContainText("https://claude.ai/oauth");
  await expect(claude.getByRole("cell", { name: "Within the last hour", exact: true })).toBeVisible();
  await expect(claude).toContainText("In 1 day");
  await expect(claude).toContainText("Reconnect from the app to keep access");
  await claude.getByRole("button", { name: "2 connections" }).click();
  await expect(table.getByTestId("connection-detail")).toHaveCount(2);
  await expect(table.getByRole("button", { name: "Revoke Claude connection from Aug 9, 2026" })).toBeVisible();
  await screenshot(page, "connections-grouped");
  await table.getByRole("button", { name: "Revoke all 2 Claude connections" }).click();
  const dialog = page.getByRole("alertdialog", { name: "Revoke 2 connections for Claude?" });
  await expect(dialog).toContainText("This revokes all 2 connections");
  await expect(dialog).toContainText("MCP server settings");
  await dialog.getByRole("button", { name: "Confirm revocation", exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.revocations).toEqual(["/oauth/connections/claude-one/revoke", "/oauth/connections/claude-two/revoke"]);
  await expect(page.getByText("2 connections revoked.", { exact: true })).toBeVisible();
  await expect(table.getByTestId("connection-group")).toHaveCount(1);
  await table.getByRole("button", { name: "Revoke Monomer Native" }).click();
  const restDialog = page.getByRole("alertdialog", { name: "Revoke access for Monomer Native?" });
  await expect(restDialog).not.toContainText("MCP");
  await restDialog.getByRole("button", { name: "Cancel", exact: true }).click();
});

test("revocation cancels, reports failure, prevents dismissal pending and refreshes only its grant", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 503, url: /\/oauth\/connections\/grant-one\/revoke$/, reason: "The fixture fails the first revocation." });
  const state = await fixture(page);
  await page.goto("/settings/agents");
  const target = page.getByRole("button", { name: "Revoke Codex" }).first();
  await target.click();
  const dialog = page.getByRole("alertdialog", { name: "Revoke access for Codex?" });
  await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(target).toBeFocused();
  expect(state.revocations).toEqual([]);
  await target.click();
  await expect(dialog.getByText(/already retrieved are not deleted/)).toBeVisible();
  await screenshot(page, "revoke-confirm");
  state.revokeError = true;
  await dialog.getByRole("button", { name: "Confirm revocation", exact: true }).click();
  await expect(dialog.getByRole("alert")).toBeVisible();
  await screenshot(page, "revoke-error");
  state.revokeError = false;
  state.revokeGate = deferred();
  await dialog.getByRole("button", { name: "Confirm revocation", exact: true }).click();
  await expect.poll(() => state.revocations.length).toBe(2);
  await expect(dialog.getByRole("button", { name: "Cancel", exact: true })).toBeDisabled();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeVisible();
  await page.mouse.click(5, 5);
  await expect(dialog).toBeVisible();
  await screenshot(page, "revoke-pending");
  state.revokeGate.resolve();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("heading", { name: "Agents", level: 1 })).toBeFocused();
  expect(state.revocations).toEqual(["/oauth/connections/grant-one/revoke", "/oauth/connections/grant-one/revoke"]);
  await expect.poll(() => state.reads).toBeGreaterThan(1);
  await expect(page.getByRole("button", { name: "Revoke Codex" })).toHaveCount(1);
  await page.getByRole("button", { name: "Show revoked and expired (1)" }).click();
  await expect(page.getByRole("list", { name: "Revoked and expired connections" })).toContainText("Revoked Sep 7, 2026");
});

for (const scenario of ["empty", "loading", "error"] as const) {
  test(`agents ${scenario} state`, async ({ page, consoleErrors }) => {
    if (scenario === "error") consoleErrors.allow({ status: 503, url: /\/oauth\/connections\?limit=100$/, reason: "The fixture fails the connection list." });
    const state = await fixture(page);
    if (scenario === "empty") state.connections = [];
    if (scenario === "error") state.listError = true;
    if (scenario === "loading") state.listGate = deferred();
    await page.goto("/settings/agents");
    if (scenario === "empty") {
      await expect(page.getByText("No agents connected yet", { exact: true })).toBeVisible();
      await expect(page.getByText("Connect Claude, Claude Code, or Codex with the steps above to work with your Monomer data.", { exact: true })).toBeVisible();
      await expect(page.getByTestId("connect-agent")).toBeVisible();
      await expect(page.getByTestId("user-settings-back")).toHaveAttribute("href", "/workspaces");
      await expect(page.getByTestId("user-settings-back")).toHaveText("All workspaces");
    }
    if (scenario === "loading") await expect(page.getByText("Loading your connections…", { exact: true })).toBeVisible();
    if (scenario === "error") await expect(page.getByRole("alert")).toBeVisible();
    await screenshot(page, `connections-${scenario}`);
    if (scenario === "error") {
      state.listError = false;
      await page.getByRole("button", { name: "Try again", exact: true }).click();
      await expect(page.getByRole("button", { name: "Revoke Codex" })).toHaveCount(2);
    }
    state.listGate?.resolve();
  });
}

test("mobile long identity and resource values stay within the viewport", async ({ page }) => {
  const state = await fixture(page);
  state.user = { ...initialUser, displayName: "VeryLongDisplayName".repeat(8), email: `${"verylongemail".repeat(8)}@example.test` };
  state.connections = [{ ...grant, clientName: "VeryLongApplicationName".repeat(8), resource: `https://example.test/${"long-resource".repeat(15)}` }];
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/settings/profile");
  await expect(page.getByRole("button", { name: "Edit display name", exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await screenshot(page, "profile-mobile-overflow");
  await page.getByRole("combobox", { name: "Your settings section" }).selectOption("agents");
  const target = page.getByRole("button", { name: `Revoke ${state.connections[0].clientName}`, exact: true });
  await expect(target).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await screenshot(page, "connections-mobile-overflow");
  await target.click();
  const dialog = page.getByRole("alertdialog");
  expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  await screenshot(page, "revoke-mobile-overflow");
});

async function replaceSession(page: Page, user: typeof initialUser, accessToken = E2E_ACCESS_TOKEN) {
  await page.evaluate(async ({ nextUser, nextToken }) => {
    const modulePath = "/src/lib/auth/store.ts";
    const { useAuthStore } = await import(/* @vite-ignore */ modulePath);
    useAuthStore.getState().setSession({ accessToken: nextToken, accessTokenExpiresAt: "2099-01-01T00:00:00Z", user: nextUser, organizationMemberships: [] });
  }, { nextUser: user, nextToken: accessToken });
}

test("session replacement clears cached grants and discards the previous in-flight response", async ({ page }) => {
  const state = await fixture(page);
  await page.goto("/settings/agents");
  await expect(page.getByRole("button", { name: "Revoke Codex" })).toHaveCount(2);
  const navigation = page.getByRole("navigation", { name: "Your settings" });
  await navigation.getByRole("link", { name: /^Profile/ }).click();
  await expect(page.getByRole("button", { name: "Edit display name", exact: true })).toBeVisible();
  const readsBeforeReturn = state.reads;
  state.listGate = deferred();
  await page.clock.fastForward(30_000);
  await navigation.getByRole("link", { name: /^Agents/ }).click();
  await expect.poll(() => state.reads).toBeGreaterThan(readsBeforeReturn);
  const oldRequest = state.listGate;
  state.listGate = null;
  state.connections = [];
  state.user = { id: "second-user", email: "second@example.test", displayName: "Second User" };
  await replaceSession(page, state.user);
  await expect(page.getByTestId("sidebar-user-display-name")).toHaveText("Second User");
  await expect(page.getByText("No agents connected yet", { exact: true })).toBeVisible();
  oldRequest.resolve();
  await expect(page.getByRole("button", { name: /^Revoke Codex/ })).toHaveCount(0);
  await navigation.getByRole("link", { name: /^Profile/ }).click();
  await expect(page.getByRole("main").getByText("second@example.test", { exact: true })).toBeVisible();
  await navigation.getByRole("link", { name: /^Agents/ }).click();
  await expect(page.getByText("No agents connected yet", { exact: true })).toBeVisible();
});

test("a profile response arriving after a session replacement cannot overwrite the new user", async ({ page }) => {
  const state = await fixture(page);
  state.saveGate = deferred();
  await page.goto("/settings/profile");
  await page.getByRole("button", { name: "Edit display name", exact: true }).click();
  await page.getByLabel("Display name", { exact: true }).fill("Old session result");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => state.saves.length).toBe(1);
  const nextUser = { id: "second-user", email: "second@example.test", displayName: "Second User" };
  state.user = nextUser;
  await replaceSession(page, nextUser);
  const oldResponse = page.waitForResponse((response) => response.request().postData()?.includes("updateMyDisplayName") ?? false);
  state.saveGate.resolve();
  await oldResponse;
  await expect(page.getByTestId("sidebar-user-display-name")).toHaveText("Second User");
  await expect(page.getByRole("main").getByText("second@example.test", { exact: true })).toBeVisible();
  await expect(page.getByText("Old session result", { exact: true })).toHaveCount(0);
});

test("sign out removes private settings and preserves the protected return path", async ({ page, consoleErrors }) => {
  await fixture(page);
  await page.goto("/settings/agents");
  await expect(page.getByRole("button", { name: "Revoke Codex" })).toHaveCount(2);
  await page.getByTestId("sidebar-user").click();
  await page.getByTestId("sidebar-user-logout").click();
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByText("Monomer Team", { exact: true })).toHaveCount(0);
  await mockSignedOut(page, consoleErrors);
  await page.goto("/settings/profile");
  await expect(page).toHaveURL(/\/login\?redirect=/);
  expect(new URL(page.url()).searchParams.get("redirect")).toBe("/settings/profile");
});

test("security signs out of all devices only after confirmation", async ({ page }) => {
  const state = await fixture(page);
  await page.goto("/settings/security");
  const trigger = page.getByRole("button", { name: "Sign out of all devices" });
  await trigger.click();
  const dialog = page.getByRole("alertdialog", { name: "Sign out of all devices?" });
  await expect(dialog).toContainText("You'll be signed out everywhere, including this browser. Connected agents keep working — revoke them in Agents.");
  await screenshot(page, "security-confirm");
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(trigger).toBeFocused();
  expect(state.signOutsEverywhere).toBe(0);
  await trigger.click();
  await dialog.getByRole("button", { name: "Sign out everywhere" }).click();
  await expect(page).toHaveURL(/\/login/);
  expect(state.signOutsEverywhere).toBe(1);
});

for (const scenario of ["loading", "error"] as const) {
  test(`profile ${scenario} has explicit recovery`, async ({ page }) => {
    const state = await fixture(page);
    if (scenario === "loading") state.meGate = deferred();
    else state.meError = true;
    await page.goto("/settings/profile");
    if (scenario === "loading") await expect(page.getByText("Loading your profile…", { exact: true })).toBeVisible();
    else await expect(page.getByRole("alert")).toContainText("Couldn't load your profile.");
    await expect(page.getByRole("button", { name: "Edit display name", exact: true })).toHaveCount(0);
    await screenshot(page, `profile-${scenario === "error" ? "load-error" : scenario}`);
    state.meGate?.resolve();
    if (scenario === "error") {
      state.meError = false;
      await page.getByRole("button", { name: "Try again", exact: true }).click();
    }
    await expect(page.getByRole("button", { name: "Edit display name", exact: true })).toBeVisible();
  });
}

test("revoked and expired grants collapse behind a toggle without revocation actions", async ({ page }) => {
  const state = await fixture(page);
  state.connections = [{ ...grant, expiresAt: "2026-09-06T00:00:00Z" }, { ...grant, id: "revoked-grant", clientName: "Revoked app", revokedAt: "2026-09-05T00:00:00Z" }];
  await page.goto("/settings/agents");
  await expect(page.getByText("No active connections", { exact: true })).toBeVisible();
  const toggle = page.getByRole("button", { name: "Show revoked and expired (2)" });
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await toggle.click();
  const ended = page.getByRole("list", { name: "Revoked and expired connections" });
  await expect(ended.getByRole("listitem").filter({ hasText: /^Codex/ })).toContainText("Expired Sep 6, 2026");
  await expect(ended.getByRole("listitem").filter({ hasText: "Revoked app" })).toContainText("Revoked Sep 5, 2026");
  await expect(page.getByRole("button", { name: /^Revoke / })).toHaveCount(0);
  await screenshot(page, "ended-grants");
  expect(state.revocations).toEqual([]);
});

test("an old session's delayed 401 never refreshes or replays its profile mutation as the next user", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 401, url: /\/v2\/graphql$/, reason: "The previous session's profile mutation is answered with 401 after the session changes." });
  const state = await fixture(page);
  state.saveGate = deferred();
  state.saveUnauthorized = true;
  await page.goto("/settings/profile");
  await page.getByRole("button", { name: "Edit display name", exact: true }).click();
  await page.getByLabel("Display name", { exact: true }).fill("Old user mutation");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => state.saves.length).toBe(1);
  const refreshesBeforeReplacement = state.refreshes;
  const nextUser = { id: "second-user", email: "second@example.test", displayName: "Second User" };
  state.user = nextUser;
  await replaceSession(page, nextUser, "second-browser-token");
  await expect(page.getByRole("main").getByText("second@example.test", { exact: true })).toBeVisible();
  const delayedResponse = page.waitForResponse((response) => response.status() === 401 && (response.request().postData()?.includes("updateMyDisplayName") ?? false));
  state.saveGate.resolve();
  await (await delayedResponse).finished();
  await page.waitForLoadState("networkidle");
  expect(state.saves).toEqual(["Old user mutation"]);
  expect(state.refreshes).toBe(refreshesBeforeReplacement);
  expect(state.user).toEqual(nextUser);
  await expect(page.getByTestId("sidebar-user-display-name")).toHaveText("Second User");
  await expect(page.getByRole("main").getByText("second@example.test", { exact: true })).toBeVisible();
});
