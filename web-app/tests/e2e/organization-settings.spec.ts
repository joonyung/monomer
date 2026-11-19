import { mkdirSync } from "node:fs";
import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";
import { json, mockGraphql, mockSessionRefresh } from "./support/session";
import type { OrganizationMember, OrganizationMembership, AuthUser } from "../../src/lib/auth/types";
import type { OrganizationInvitation } from "../../src/lib/auth/invitations";

// Visible scrollbars are part of these layouts and their evidence.
test.use({ launchOptions: { ignoreDefaultArgs: ["--hide-scrollbars"] } });

test.beforeAll(() => mkdirSync(evidencePath("screenshots"), { recursive: true }));

async function expectNoHorizontalOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}

test("General shows profile, usage, and admins inside the settings sidebar", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/dev/organization-settings-demo?surface=general");
  await expect(page.getByRole("heading", { name: "General", level: 1 })).toBeVisible();
  await expect(page.getByTestId("settings-tab-general")).toHaveCount(0);

  const sidebar = page.getByTestId("desktop-sidebar");
  await expect(sidebar.getByRole("link", { name: "Back to Revenue Analytics" })).toHaveAttribute("href", "/workspaces/ws-organization-revenue");
  await expect(sidebar.getByTestId("organization-settings-identity")).toContainText("Acme Analytics");
  await expect(sidebar.getByTestId("organization-settings-identity")).toContainText("Free plan");
  const navigation = sidebar.getByRole("navigation", { name: "Organization settings" });
  await expect(navigation.getByRole("link", { name: /^General/ })).toHaveAttribute("aria-current", "page");
  await expect(navigation.getByRole("link", { name: /^Members/ })).toContainText("4");
  await expect(navigation.getByRole("link", { name: /^Workspaces/ })).toContainText("4");
  await expect(sidebar.getByTestId("workspace-switcher")).toHaveCount(0);

  await expect(page.getByRole("definition").filter({ hasText: "organization-acme" })).toBeVisible();
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.getByRole("button", { name: "Copy Organization ID" }).click();
  await expect(page.getByRole("button", { name: "Copy Organization ID" })).toHaveText("Copied");
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe("organization-acme");

  const usage = page.getByRole("region", { name: "Usage" });
  await expect(usage).toContainText("Free plan limits during the public beta");
  await expect(usage.getByRole("listitem").filter({ hasText: "Workspaces" })).toContainText("1 workspace left");
  await expect(usage.getByRole("listitem").filter({ hasText: "Members" })).not.toContainText("left");

  const admins = page.getByRole("region", { name: "Admins" });
  await expect(admins).toContainText("Jordan Lee · You");
  await expect(admins).toContainText("Mara Chen");
  await expect(admins).not.toContainText("Devon Park");
  await expect(admins.getByRole("link", { name: "Manage in Members" })).toHaveAttribute("href", "/organizations/organization-acme/settings/members");
  await page.screenshot({ path: evidencePath("screenshots/organization-settings-general.png"), fullPage: true });

  const renameTrigger = page.getByRole("button", { name: "Rename Organization" });
  await renameTrigger.click();
  const dialog = page.getByRole("dialog", { name: "Rename Organization" });
  await expect(dialog.getByLabel("Organization name")).toHaveValue("Acme Analytics");
  await expect(dialog.getByLabel("Organization name")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(renameTrigger).toBeFocused();
  await renameTrigger.click();
  await dialog.getByLabel("Organization name").fill("Acme Data");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("definition").filter({ hasText: "Acme Data" })).toBeVisible();
  await expect(sidebar.getByTestId("organization-settings-identity")).toContainText("Acme Data");

  await sidebar.getByRole("button", { name: "Collapse sidebar" }).click();
  const rail = sidebar.getByRole("navigation", { name: "Organization settings" });
  await expect(rail.getByRole("link", { name: "General" })).toHaveAttribute("aria-current", "page");
  await expect(rail.getByRole("link", { name: "Back to Revenue Analytics" })).toBeVisible();
  await sidebar.getByRole("button", { name: "Open sidebar" }).click();

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("combobox", { name: "Organization settings section" })).toHaveValue("general");
  await expect(page.getByTestId("mobile-workspace-name")).toHaveText("Acme Data");
  await expect(page.getByRole("button", { name: "Rename Organization" })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: evidencePath("screenshots/organization-settings-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "Open organization settings" }).click();
  const drawer = page.getByRole("dialog", { name: "Organization settings" });
  await expect(drawer.getByRole("link", { name: /^Members/ })).toBeVisible();
  await page.keyboard.press("Escape");

  await page.goto("/dev/organization-settings-demo?surface=general&state=unlimited");
  await expect(page.getByRole("region", { name: "Usage" }).getByRole("listitem").first()).toContainText("4");
  await expect(page.getByText("limits during the public beta")).toHaveCount(0);
  await expect(page.getByText("workspace left")).toHaveCount(0);
});

test("workspace rows reflow and search resets pagination", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/dev/organization-settings-demo?state=overflow");
  const rows = page.getByRole("button", { name: /^Manage International/ });
  await expect(rows).toHaveCount(10);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: evidencePath("screenshots/organization-workspaces-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "Next workspace page" }).click();
  await expect(rows).toHaveCount(3);
  await page.getByRole("searchbox", { name: "Search workspaces" }).fill("Operations 1");
  await expect(rows).toHaveCount(5);
  await page.getByRole("searchbox", { name: "Search workspaces" }).fill("not found");
  await expect(page.getByText("No matching workspaces")).toBeVisible();
  await page.getByRole("button", { name: "Clear search" }).click();
  await expect(rows).toHaveCount(10);
  await page.setViewportSize({ width: 820, height: 900 });
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: evidencePath("screenshots/organization-workspaces-tablet.png"), fullPage: true });
});

test("create dialogs show initial access and preserve mobile actions", async ({ page }) => {
  await page.goto("/dev/organization-settings-demo");
  await page.getByRole("button", { name: "New Workspace", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "New Workspace" });
  await expect(dialog.getByLabel("Workspace name")).toBeFocused();
  await expect(dialog.getByText("Included", { exact: true })).toBeVisible();
  await expect(dialog.getByText("Owner · full workspace access", { exact: true })).toBeVisible();
  await page.screenshot({ path: evidencePath("screenshots/organization-new-workspace.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(dialog.getByRole("button", { name: "Create Workspace" })).toBeVisible();
  expect(await dialog.evaluate((element) => element.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: evidencePath("screenshots/organization-new-workspace-mobile.png"), fullPage: true });
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await page.goto("/dev/organization-members-demo");
  await page.getByRole("button", { name: "Invite people" }).click();
  const invite = page.getByRole("dialog", { name: "Invite people" });
  await expect(invite.getByRole("region", { name: "Workspace access" })).toContainText("Optional. Granted when they accept.");
  await expect(invite.getByRole("button", { name: "Add workspace" })).toBeVisible();
  await expect(invite).not.toContainText("30");
  await page.screenshot({ path: evidencePath("screenshots/organization-invite-mobile.png"), fullPage: true });
});

test("empty loading and failed workspace inventories stay distinguishable", async ({ page }) => {
  await page.goto("/dev/organization-settings-demo?state=empty");
  await expect(page.getByText("No workspaces yet")).toBeVisible();
  await expect(page.getByRole("button", { name: "New Workspace", exact: true })).toBeEnabled();
  await page.screenshot({ path: evidencePath("screenshots/organization-workspaces-empty.png"), fullPage: true });
  await page.goto("/dev/organization-settings-demo?state=loading");
  await expect(page.getByRole("status")).toContainText("Loading workspaces");
  await expect(page.getByText("No workspaces yet")).toHaveCount(0);
  await page.goto("/dev/organization-settings-demo?state=error");
  await expect(page.getByRole("alert")).toContainText("Couldn't load workspaces", { timeout: 15000 });
  await expect(page.getByRole("button", { name: "Retry loading workspaces" })).toBeEnabled();
  await page.screenshot({ path: evidencePath("screenshots/organization-workspaces-error.png"), fullPage: true });
});

test.describe("Organization administration", () => {
  test("Members keeps pending invitations above the table and recovers delivery failure", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto("/dev/organization-members-demo");
    await expect(page.getByRole("heading", { name: "Members", level: 1 })).toBeVisible();
    await expect(page.getByTestId("organization-settings-nav-members")).toHaveAttribute("aria-current", "page");

    const pending = page.getByRole("region", { name: /Pending invitations/ });
    await expect(pending).toContainText("Pending invitations · 2");
    await expect(pending.getByRole("listitem").filter({ hasText: "maria@acme.test" })).toContainText("Expires in 5 days");
    const failed = pending.getByRole("listitem").filter({ hasText: "devon@contractor.test" });
    await expect(failed).toContainText("Email not delivered · Invited by Mara Chen");
    await expect(failed.getByRole("button", { name: "Retry sending invitation to devon@contractor.test" })).toHaveText("Retry send");

    const table = page.getByRole("table", { name: "Organization members" });
    for (const header of ["Member", "Role", "Workspace access", "Joined"]) {
      await expect(table.getByRole("columnheader", { name: header, exact: true })).toBeVisible();
    }
    await expect(table.getByRole("row").filter({ hasText: "Jordan Lee" })).toContainText("Finance, Revenue Analytics");
    await expect(table.getByRole("row").filter({ hasText: "Priya Nair" })).toContainText("No workspace yet");

    const manage = page.getByRole("button", { name: /^Manage / });
    const filters = page.getByRole("group", { name: "Filter members" });
    await filters.getByRole("button", { name: /^Admins/ }).click();
    await expect(manage).toHaveCount(2);
    await filters.getByRole("button", { name: /^All/ }).click();
    await expect(manage).toHaveCount(4);
    await page.screenshot({ path: evidencePath("screenshots/admin-members-surface.png"), fullPage: true });

    await page.getByRole("button", { name: "Invite people" }).click();
    const invite = page.getByRole("dialog", { name: "Invite people" });
    await expect(invite.getByRole("group", { name: "Organization role" })).toHaveCount(0);
    await invite.getByRole("textbox", { name: "Email", exact: true }).fill("fail@acme.test");
    await invite.getByRole("button", { name: "Send invitation" }).click();
    await expect(invite).toBeHidden();
    await expect(page.getByRole("status")).toContainText("Invitation saved · email not delivered");
    await expect(pending).toContainText("Pending invitations · 3");
    await expect(page.getByRole("button", { name: "Manage fail", exact: false })).toHaveCount(0);
    await page.getByRole("button", { name: "Retry sending invitation to fail@acme.test" }).click();
    await expect(page.getByRole("status")).toContainText("60 seconds");
    await page.screenshot({ path: evidencePath("screenshots/organization-invitation-delivery-failed.png"), fullPage: true });
    await page.getByRole("button", { name: "Revoke invitation to fail@acme.test" }).click();
    const revoke = page.getByRole("alertdialog", { name: "Revoke invitation?" });
    await revoke.getByRole("button", { name: "Revoke invitation" }).click();
    await expect(page.getByRole("status")).toContainText("Invitation revoked.");
    await expect(pending.getByText("fail@acme.test")).toHaveCount(0);
    // The revoked row held the opener, so focus falls back to the page heading instead of the body.
    await expect(page.getByRole("heading", { name: "Members", level: 1 })).toBeFocused();
  });

  test("the member sheet changes roles and workspace access and confirms removal in place and permits a new invitation", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto("/dev/organization-members-demo");
    const maraTrigger = page.getByRole("button", { name: "Manage Mara Chen" });
    await maraTrigger.click();
    await expect(page).toHaveURL(/member=user-mara/);
    const mara = page.getByRole("dialog", { name: "Mara Chen" });
    await expect(mara).toContainText("Mara is the only owner of Finance and Growth.");
    await expect(mara.getByRole("link", { name: "Choose another owner for Growth first" })).toHaveAttribute(
      "href",
      "/organizations/organization-acme/settings/workspaces?workspace=ws-organization-growth",
    );
    await expect(mara.getByRole("button", { name: "Remove from Organization" })).toBeDisabled();
    await page.screenshot({ path: evidencePath("screenshots/organization-member-impact-production.png"), fullPage: true });
    await page.keyboard.press("Escape");
    await expect(mara).toBeHidden();
    await expect(page).not.toHaveURL(/member=/);
    await expect(maraTrigger).toBeFocused();

    await page.getByRole("button", { name: "Manage Devon Park" }).click();
    const devon = page.getByRole("dialog", { name: "Devon Park" });
    await expect(devon).toContainText("devon@acme.test · Joined");
    const organizationRole = devon.getByRole("group", { name: "Organization role" });
    await organizationRole.getByRole("button", { name: "Admin", exact: true }).click();
    await expect(organizationRole.getByRole("button", { name: "Admin", exact: true })).toHaveAttribute("aria-pressed", "true");
    await organizationRole.getByRole("button", { name: "Member", exact: true }).click();
    await expect(organizationRole.getByRole("button", { name: "Member", exact: true })).toHaveAttribute("aria-pressed", "true");

    const access = devon.getByRole("region", { name: "Workspace access" });
    await expect(access.getByRole("listitem").filter({ hasText: "Growth" })).toContainText("Editor");
    await access.getByRole("button", { name: "Change role in Revenue Analytics" }).click();
    await access.getByRole("group", { name: "Workspace role" }).getByRole("button", { name: "Editor", exact: true }).click();
    await access.getByRole("button", { name: "Save role" }).click();
    await expect(access.getByRole("listitem").filter({ hasText: "Revenue Analytics" })).toContainText("Editor");
    await access.getByRole("button", { name: "Add to Workspace" }).click();
    const add = access.getByRole("form", { name: "Add to Workspace" });
    await add.getByLabel("Workspace", { exact: true }).selectOption({ label: "Finance" });
    await expect(add.getByRole("group", { name: "Workspace role" }).getByRole("button", { name: "Explorer", exact: true })).toHaveAttribute("aria-pressed", "true");
    await add.getByRole("button", { name: "Add to Workspace" }).click();
    await expect(access.getByRole("listitem").filter({ hasText: "Finance" })).toContainText("Explorer");
    await page.screenshot({ path: evidencePath("screenshots/organization-member-sheet.png"), fullPage: true });

    const removeTrigger = devon.getByRole("button", { name: "Remove from Organization" });
    await removeTrigger.click();
    const removal = page.getByRole("alertdialog", { name: "Remove Devon Park?" });
    await expect(removal).toContainText("from Acme Analytics");
    await expect(removal).toContainText("Loses access to Finance, Growth, and Revenue Analytics");
    await expect(removal).toContainText("Apps Devon connected can no longer reach Acme Analytics.");
    await expect(removal).toContainText("To bring Devon back, send a new invitation.");
    await expect(removal.getByRole("button", { name: "Keep member" })).toBeFocused();
    await page.screenshot({ path: evidencePath("screenshots/organization-member-remove-confirmation.png"), fullPage: true });
    await removal.getByRole("button", { name: "Keep member" }).click();
    await expect(page.getByRole("dialog", { name: "Devon Park" }).getByRole("button", { name: "Remove from Organization" })).toBeFocused();

    await page.getByRole("dialog", { name: "Devon Park" }).getByRole("button", { name: "Remove from Organization" }).click();
    await removal.getByRole("button", { name: "Remove member" }).click();
    await expect(removal).toBeHidden();
    await expect(page.getByRole("button", { name: "Manage Devon Park" })).toHaveCount(0);
    await expect(page).not.toHaveURL(/member=/);
    await expect(page.getByRole("group", { name: "Filter members" }).getByRole("button", { name: /^All/ })).toContainText("3");

    await page.getByRole("button", { name: "Invite people" }).click();
    const invitation = page.getByRole("dialog", { name: "Invite people" });
    await invitation.getByRole("textbox", { name: "Email", exact: true }).fill("devon@acme.test");
    await invitation.getByRole("button", { name: "Add workspace" }).click();
    const grant = invitation.getByRole("form", { name: "Add workspace access" });
    await grant.getByRole("combobox", { name: "Workspace" }).selectOption({ label: "Revenue Analytics" });
    await grant.getByRole("button", { name: "Editor" }).click();
    await expect(invitation.getByRole("button", { name: "Send invitation" })).toBeDisabled();
    // Enter in the email field must not send the invitation while workspace access is half chosen.
    await invitation.getByRole("textbox", { name: "Email", exact: true }).press("Enter");
    await expect(grant).toBeVisible();
    await expect(invitation).toBeVisible();
    await expect(page.getByRole("region", { name: /Pending invitations/ }).getByText("devon@acme.test")).toHaveCount(0);
    await grant.getByRole("button", { name: "Add", exact: true }).click();
    await expect(invitation.getByRole("list", { name: "Workspace access to grant" })).toContainText("Revenue Analytics · Editor");
    await invitation.getByRole("button", { name: "Send invitation" }).click();
    await expect(invitation).toBeHidden();
    await expect(page.getByRole("status")).toContainText("Invitation sent.");
    await expect(page.getByRole("region", { name: /Pending invitations/ })).toContainText("devon@acme.test");
    await expect(page.getByRole("region", { name: /Pending invitations/ })).toContainText("Workspace access: Revenue Analytics · Editor");
  });

  test("the member sheet removes a member from one workspace after an in-place confirmation", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto("/dev/organization-members-demo?member=user-mara");
    const mara = page.getByRole("dialog", { name: "Mara Chen" });
    const maraAccess = mara.getByRole("region", { name: "Workspace access" });
    await expect(maraAccess.getByRole("button", { name: "Remove from Finance" })).toBeDisabled();
    await expect(maraAccess.getByRole("button", { name: "Remove from Finance" })).toHaveAttribute(
      "title",
      "Mara is the only owner of Finance. Make someone else an owner first.",
    );

    await page.goto("/dev/organization-members-demo?member=user-devon");
    const devon = page.getByRole("dialog", { name: "Devon Park" });
    const access = devon.getByRole("region", { name: "Workspace access" });
    const growthTrigger = access.getByRole("button", { name: "Remove from Growth" });
    await growthTrigger.click();
    const confirmation = page.getByRole("alertdialog", { name: "Remove Devon Park from Growth?" });
    await expect(confirmation).toContainText("Loses access to Growth and its resources");
    await expect(confirmation).toContainText("Stays a member of Acme Analytics");
    await expect(confirmation.getByRole("button", { name: "Keep" })).toBeFocused();
    await confirmation.getByRole("button", { name: "Keep" }).click();
    await expect(page.getByRole("dialog", { name: "Devon Park" }).getByRole("button", { name: "Remove from Growth" })).toBeFocused();

    await page.getByRole("dialog", { name: "Devon Park" }).getByRole("button", { name: "Remove from Growth" }).click();
    await confirmation.getByRole("button", { name: "Remove from Growth" }).click();
    await expect(confirmation).toBeHidden();
    const remaining = page.getByRole("dialog", { name: "Devon Park" }).getByRole("region", { name: "Workspace access" });
    await expect(remaining.getByRole("listitem").filter({ hasText: "Growth" })).toHaveCount(0);
    await expect(remaining.getByRole("listitem").filter({ hasText: "Revenue Analytics" })).toContainText("Explorer");
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog", { name: "Devon Park" })).toBeHidden();
    await expect(page.getByRole("table", { name: "Organization members" }).getByRole("row").filter({ hasText: "Devon Park" })).not.toContainText("Growth");
  });

  test("Organization Admins cannot re-invite a member or change their own membership", async ({ page }) => {
    await page.goto("/dev/organization-members-demo");
    const inviteTrigger = page.getByRole("button", { name: "Invite people" });
    await inviteTrigger.click();
    const invite = page.getByRole("dialog", { name: "Invite people" });
    const email = invite.getByRole("textbox", { name: "Email", exact: true });
    await expect(email).toBeFocused();
    await email.fill("mara@acme.test");
    await invite.getByRole("button", { name: "Send invitation" }).click();
    await expect(invite.getByRole("alert")).toHaveText("This person is already an Organization member.");
    await page.keyboard.press("Escape");
    await expect(invite).toBeHidden();
    // A rejected invitation stays in the dialog that reported it; Members keeps no stale status for it.
    await expect(page.getByText("This person is already an Organization member.")).toHaveCount(0);
    // The email field autofocuses, yet closing returns focus to the button that opened the dialog.
    await expect(inviteTrigger).toBeFocused();

    await page.goto("/dev/organization-members-demo?member=user-jordan");
    const self = page.getByRole("dialog", { name: "Jordan Lee" });
    await expect(self).toContainText("You can't change your own role. Ask another Admin.");
    await expect(self).toContainText("You can't remove yourself. Ask another Admin.");
    const organizationRole = self.getByRole("group", { name: "Organization role" });
    await expect(organizationRole.getByRole("button", { name: "Member", exact: true })).toBeDisabled();
    await expect(organizationRole.getByRole("button", { name: "Admin", exact: true })).toBeDisabled();
    await expect(self.getByRole("button", { name: "Remove from Organization" })).toBeDisabled();
  });

  test("Organization settings surfaces have no WCAG A/AA violations", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 960 });
    for (const [path, name] of [
      ["/dev/organization-settings-demo?surface=general", "General"],
      ["/dev/organization-members-demo", "Members"],
      ["/dev/organization-members-demo?member=user-devon", "Devon Park"],
      ["/dev/organization-settings-demo?surface=workspaces", "Workspaces"],
      ["/dev/organization-settings-demo?workspace=ws-organization-growth", "Growth"],
    ] as const) {
      await page.goto(path);
      await expect(page.getByRole("heading", { name, exact: true }).first()).toBeVisible();
      const accessibility = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
      expect(accessibility.violations, path).toEqual([]);
    }
  });

  test("the workspace sheet manages owners and shows public access and naming", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto("/dev/organization-settings-demo");
    await expect(page.getByTestId("workspace-usage")).toHaveText("4 of 5");
    const table = page.getByRole("table", { name: "Workspaces" });
    await expect(table.getByRole("row").filter({ hasText: "Ops" })).toContainText("No owner");
    await expect(table.getByRole("row").filter({ hasText: "Revenue Analytics" })).toContainText("Jordan Lee");
    await expect(table.getByRole("columnheader", { name: "Owners" })).toBeVisible();

    const growthTrigger = page.getByRole("button", { name: "Manage Growth" });
    await growthTrigger.click();
    await expect(page).toHaveURL(/workspace=ws-organization-growth/);
    const growth = page.getByRole("dialog", { name: "Growth" });
    await expect(growth).toContainText("2 members · Created Sep 8, 2026");
    await expect(growth.getByRole("link", { name: "Open Workspace" })).toHaveCount(0);
    await expect(growth).toContainText("Pages are public · Anyone with a link can view");
    await expect(growth).toContainText("Owners change this in workspace settings.");
    await expect(growth.getByRole("link", { name: "Open settings" })).toHaveCount(0);
    await expect(growth.getByRole("button", { name: "Rename Workspace" })).toBeVisible();
    await expect(growth.getByRole("button", { name: "Remove Mara Chen as owner" })).toBeDisabled();

    await growth.getByRole("button", { name: "Add owner" }).click();
    const addOwner = growth.getByRole("form", { name: "Add owner" });
    await expect(addOwner.getByLabel("Member").getByRole("option", { name: /Mara Chen/ })).toHaveCount(0);
    await addOwner.getByLabel("Member").selectOption("user-devon");
    await addOwner.getByRole("button", { name: "Add owner" }).click();
    await expect(growth.getByRole("link", { name: "Devon Park" })).toBeVisible();
    await growth.getByRole("button", { name: "Remove Mara Chen as owner" }).click();
    const replacement = growth.getByRole("group", { name: "Role for Mara Chen after removing owner" });
    await expect(replacement.getByRole("button", { name: "Owner", exact: true })).toHaveCount(0);
    await expect(replacement).toContainText("Run queries as this workspace’s connection account and create Dashboards and Pages.");
    await replacement.getByRole("button", { name: "Viewer" }).click();
    await growth.getByRole("button", { name: "Remove as owner", exact: true }).click();
    await expect(growth.getByRole("link", { name: "Mara Chen" })).toHaveCount(0);
    await expect(growth.getByRole("button", { name: "Remove Devon Park as owner" })).toBeDisabled();
    await page.screenshot({ path: evidencePath("screenshots/organization-workspace-owner-editor.png"), fullPage: true });
    await growth.getByRole("button", { name: "Close Workspace" }).click();
    await expect(growth).toBeHidden();
    await expect(growthTrigger).toBeFocused();
    await expect(page).not.toHaveURL(/workspace=/);

    await page.getByRole("button", { name: "Manage Revenue Analytics" }).click();
    const revenue = page.getByRole("dialog", { name: "Revenue Analytics" });
    await expect(revenue.getByRole("link", { name: "Open Workspace" })).toHaveAttribute("href", "/workspaces/ws-organization-revenue");
    await expect(revenue.getByRole("link", { name: "Open settings" })).toHaveAttribute("href", "/workspaces/ws-organization-revenue/settings/public-access");
    await revenue.getByRole("button", { name: "Rename Workspace" }).click();
    const rename = page.getByRole("dialog", { name: "Rename Workspace" });
    await rename.getByLabel("Workspace name").fill("Revenue");
    await rename.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("dialog", { name: "Revenue", exact: true })).toBeVisible();
    await page.keyboard.press("Escape");

    await page.getByRole("button", { name: "New Workspace", exact: true }).click();
    const create = page.getByRole("dialog", { name: "New Workspace" });
    await expect(create.getByLabel("Initial Owner")).toHaveValue("user-jordan");
    await create.getByLabel("Workspace name").fill("Customer Analytics");
    await create.getByLabel("Initial Owner").selectOption("user-mara");
    await create.getByRole("button", { name: "Create Workspace" }).click();
    await expect(table.getByRole("row").filter({ hasText: "Customer Analytics" })).toContainText("Mara Chen");
    await expect(page.getByTestId("workspace-usage")).toHaveText("5 of 5");
    await page.screenshot({ path: evidencePath("screenshots/admin-workspaces-surface.png"), fullPage: true });
  });

  test("Organization member search reports no matching members", async ({ page }) => {
    const admin: AuthUser = { id: "019e8000-0000-7000-8000-0000000000b1", email: "admin@acme.test", displayName: "Avery Admin" };
    const mara: AuthUser = { id: "019e8000-0000-7000-8000-0000000000b2", email: "mara@acme.test", displayName: "Mara Chen" };
    const membership: OrganizationMembership = {
      organization: { id: "019e8000-0000-7000-8000-0000000000a1", displayName: "Acme Analytics", plan: "FREE", createdAt: "2026-09-01T00:00:00Z" },
      role: "ADMIN",
    };
    await mockSessionRefresh(page);
    await mockGraphql(page, {
      Me: (route) => json(route, { data: { me: { user: admin, organizationMemberships: [membership] } } }),
      AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: [] } }),
      ManagedWorkspaces: (route) => json(route, { data: { managedWorkspaces: [] } }),
      OrganizationUsage: (route) => json(route, { data: { organizationUsage: { limits: null, usage: { members: 2, workspaces: 0, pendingInvitations: 0 } } } }),
      OrganizationMembers: (route) => json(route, { data: { organizationMembers: [
        { user: admin, role: "ADMIN", joinedAt: "2026-09-01T00:00:00.000Z", workspaces: [] },
        { user: mara, role: "MEMBER", joinedAt: "2026-09-01T00:00:00.000Z", workspaces: [] },
      ] satisfies OrganizationMember[] } }),
      OrganizationMembershipInvitations: (route) => json(route, { data: { organizationMembershipInvitations: [] satisfies OrganizationInvitation[] } }),
    });
    await page.goto(`/organizations/${membership.organization.id}/settings/members`);
    await expect(page.getByTestId("organization-settings-back")).toHaveText("All workspaces");
    await expect(page.getByRole("region", { name: /Pending invitations/ })).toHaveCount(0);
    const search = page.getByRole("searchbox", { name: "Search Organization members" });
    await search.fill("MARA@ACME");
    await expect(page.getByRole("button", { name: /^Manage / })).toHaveCount(1);
    await expect(page.getByRole("button", { name: "Manage Mara Chen" })).toBeVisible();
    await search.fill("nobody@elsewhere.test");
    await expect(page.getByText("No matching members.", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: /^Manage / })).toHaveCount(0);
    await expect(page.getByText("No members.", { exact: true })).toHaveCount(0);
    await search.fill("");
    await page.getByRole("button", { name: "Manage Mara Chen" }).click();
    await expect(page).toHaveURL(new RegExp(`member=${mara.id}`));
    await page.goBack();
    await expect(page.getByRole("dialog", { name: "Mara Chen" })).toBeHidden();
  });

  test("Organization member inventories reflow without page overflow on mobile", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/dev/organization-members-demo");
    await expect(page.getByRole("combobox", { name: "Organization settings section" })).toHaveValue("members");
    await expect(page.getByRole("button", { name: "Manage Mara Chen" })).toBeVisible();
    await expect(page.getByRole("row").filter({ hasText: "Priya Nair" })).toContainText("Member · No workspace yet");
    const pendingToggle = page.getByRole("button", { name: "Show" });
    await expect(pendingToggle).toHaveAttribute("aria-expanded", "false");
    await expect(page.getByText("maria@acme.test")).toBeHidden();
    await pendingToggle.click();
    await expect(page.getByText("maria@acme.test")).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: evidencePath("screenshots/organization-members-production-mobile.png"), fullPage: true });
    await page.getByRole("button", { name: "Manage Devon Park" }).click();
    const sheet = page.getByRole("dialog", { name: "Devon Park" });
    expect((await sheet.boundingBox())?.width).toBe(390);
    await page.screenshot({ path: evidencePath("screenshots/organization-member-sheet-mobile.png"), fullPage: true });
  });
});
