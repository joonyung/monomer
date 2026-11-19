import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";

// Visible scrollbars are part of these layouts and their evidence.
test.use({ launchOptions: { ignoreDefaultArgs: ["--hide-scrollbars"] } });

async function expectNoHorizontalOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

for (const viewer of ["owner", "explorer", "editor"]) {
  test(`${viewer} command palette uses workspace roles`, async ({ page }) => {
    await page.goto(`/dev/role-settings-fixture?viewer=${viewer}`);
    await expect(page.getByRole("heading", { name: "Members", exact: true }).first()).toBeVisible();
    await page.keyboard.press("ControlOrMeta+k");
    const palette = page.getByRole("dialog", { name: "Search" });
    await expect(palette).toBeVisible();
    await expect(palette.getByText("Lineage", { exact: true })).toBeVisible();
    await expect(palette.getByText("Settings", { exact: true })).toHaveCount(viewer === "owner" ? 1 : 0);
    await page.screenshot({ path: evidencePath(`screenshots/wp5-palette-${viewer}.png`), fullPage: true });
  });
}

test("General names the workspace, its ID, Organization, and renames it", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/dev/workspace-settings-demo");
  await expect(page.getByRole("heading", { name: "General", level: 1 })).toBeVisible();
  const sidebar = page.getByTestId("desktop-sidebar");
  await expect(sidebar.getByTestId("workspace-settings-identity")).toContainText("Revenue Analytics");
  await expect(sidebar.getByTestId("workspace-settings-identity")).toContainText("Workspace · Northstar Labs");
  await expect(sidebar.getByTestId("workspace-settings-back")).toHaveText("Back to Revenue Analytics");
  const navigation = sidebar.getByRole("navigation", { name: "Workspace settings" });
  await expect(navigation.getByRole("link", { name: /^General/ })).toHaveAttribute("aria-current", "page");
  await expect(navigation.getByRole("link", { name: /^Members/ })).toContainText("5");
  await expect(navigation.getByRole("link", { name: /^Public access/ })).toContainText("Off");
  await expect(page.getByText("00000000-0000-4000-8000-000000000511", { exact: true })).toBeVisible();
  await expect(page.getByText("Sep 1, 2026", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Northstar Labs" })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/workspace-settings-general.png"), fullPage: true });
  await page.getByRole("button", { name: "Rename Workspace" }).click();
  const rename = page.getByRole("dialog", { name: "Rename Workspace" });
  await rename.getByLabel("Workspace name").fill("Revenue");
  await rename.getByRole("button", { name: "Save" }).click();
  await expect(rename).toBeHidden();
  await expect(sidebar.getByTestId("workspace-settings-identity")).toContainText("Revenue");
  await expect(page.getByRole("heading", { name: "General", level: 1 })).toBeVisible();
  const accessibility = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(accessibility.violations).toEqual([]);
});

test("General changes the Reporting time zone after saying what it changes", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/dev/workspace-settings-demo");
  await expect(page.getByText("UTC · GMT+0", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Change Reporting time zone" }).click();
  const picker = page.getByRole("dialog", { name: "Reporting time zone" });
  const search = picker.getByRole("combobox", { name: "Search time zones" });
  await expect(search).toBeFocused();
  await expect(picker.getByRole("option", { name: "UTC · GMT+0" })).toHaveAttribute("aria-selected", "true");
  await expect(picker.getByRole("button", { name: "Continue" })).toBeDisabled();
  await search.fill("new york");
  await expect(picker.getByRole("listbox", { name: "Time zones" }).getByRole("option")).toHaveText([/^America\/New_York · GMT-[45]$/]);
  await search.fill("atlantis");
  await expect(picker.getByText("No time zone matches “atlantis”.")).toBeVisible();
  await search.fill("seoul");
  await search.press("ArrowDown");
  await search.press("Enter");
  await expect(picker.getByRole("option", { name: "Asia/Seoul · GMT+9" })).toHaveAttribute("aria-selected", "true");
  const accessibility = await new AxeBuilder({ page }).include("[role=dialog]").withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(accessibility.violations).toEqual([]);
  await picker.getByRole("button", { name: "Continue" }).click();
  const confirm = page.getByRole("alertdialog", { name: "Change the Reporting time zone?" });
  await expect(confirm).toContainText("UTC · GMT+0");
  await expect(confirm).toContainText("Asia/Seoul · GMT+9");
  await expect(confirm.getByText("New results group days and filter times in this zone. Existing results keep their original zone.", { exact: true })).toBeVisible();
  await page.screenshot({ path: evidencePath("screenshots/workspace-settings-reporting-time-zone-confirm.png"), fullPage: true });
  await confirm.getByRole("button", { name: "Back" }).click();
  await expect(picker.getByRole("option", { name: "Asia/Seoul · GMT+9" }), "Back keeps the choice").toHaveAttribute("aria-selected", "true");
  await picker.getByRole("button", { name: "Continue" }).click();
  await confirm.getByRole("button", { name: "Change time zone" }).click();
  await expect(confirm).toBeHidden();
  await expect(page.getByText("Asia/Seoul · GMT+9", { exact: true })).toBeVisible();
  await page.screenshot({ path: evidencePath("screenshots/workspace-settings-reporting-time-zone.png"), fullPage: true });
});

test("Owners manage members in sheets that share the role picker and protect the last owner", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/dev/workspace-settings-demo?surface=members");
  await expect(page.getByRole("row").filter({ hasText: "Jordan Lee" })).toContainText("· You");
  const filters = page.getByRole("group", { name: "Filter members" });
  await expect(filters.getByRole("button", { name: /^All/ })).toContainText("5");
  await filters.getByRole("button", { name: /^Owners/ }).click();
  await expect(page.getByRole("button", { name: /^Manage / })).toHaveCount(2);
  await filters.getByRole("button", { name: /^All/ }).click();
  await page.getByRole("searchbox", { name: "Search Workspace members" }).fill("nobody@elsewhere.test");
  await expect(page.getByText("No matching members.", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Clear filters" }).click();

  await page.getByRole("button", { name: "Manage Morgan Chen" }).click();
  await expect(page).toHaveURL(/member=user-morgan/);
  const morgan = page.getByRole("dialog", { name: "Morgan Chen" });
  await expect(morgan).toContainText("Northstar Labs · Admin");
  await morgan.getByRole("button", { name: "Editor", exact: true }).click();
  await expect(morgan.getByRole("button", { name: "Editor", exact: true })).toHaveAttribute("aria-pressed", "true");
  await morgan.getByRole("button", { name: "Close member" }).click();

  await page.getByRole("button", { name: "Manage Jordan Lee" }).click();
  const jordan = page.getByRole("dialog", { name: "Jordan Lee" });
  await expect(jordan).toContainText("Jordan is the only owner of Revenue Analytics. Make someone else an owner first.");
  await expect(jordan.getByRole("button", { name: "Leave Revenue Analytics" })).toBeDisabled();
  await expect(jordan.getByRole("button", { name: "Viewer", exact: true })).toBeDisabled();
  await page.screenshot({ path: evidencePath("screenshots/workspace-member-last-owner.png"), fullPage: true });
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: "Manage Morgan Chen" }).click();
  await morgan.getByRole("button", { name: "Owner", exact: true }).click();
  await expect(morgan.getByRole("button", { name: "Owner", exact: true })).toHaveAttribute("aria-pressed", "true");
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: "Manage Jordan Lee" }).click();
  await jordan.getByRole("button", { name: "Editor", exact: true }).click();
  const demotion = page.getByRole("alertdialog", { name: "Change your role to Editor?" });
  await expect(demotion).toContainText("You'll lose access to these settings and to Connectors. Another owner (Morgan Chen) can change it back.");
  await expect(demotion.getByRole("button", { name: "Keep Owner" })).toBeFocused();
  await page.screenshot({ path: evidencePath("screenshots/workspace-member-self-demotion.png"), fullPage: true });
  await demotion.getByRole("button", { name: "Keep Owner" }).click();
  await expect(jordan.getByRole("button", { name: "Owner", exact: true })).toHaveAttribute("aria-pressed", "true");
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: "Add member", exact: true }).click();
  const add = page.getByRole("dialog", { name: "Add member" });
  await expect(add.getByRole("group", { name: "Workspace role" }).getByRole("button", { name: "Explorer", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(add.getByLabel("Organization member").getByRole("option", { name: /Riley Ortiz/ })).toHaveCount(0);
  await add.getByLabel("Organization member").selectOption("user-mara");
  await add.getByRole("button", { name: "Add member", exact: true }).click();
  await expect(add).toBeHidden();
  await expect(page.getByRole("row").filter({ hasText: "Mara Chen" })).toContainText("Explorer");

  const maraTrigger = page.getByRole("button", { name: "Manage Mara Chen" });
  await maraTrigger.click();
  const mara = page.getByRole("dialog", { name: "Mara Chen" });
  await mara.getByRole("button", { name: "Remove from Revenue Analytics" }).click();
  const removal = page.getByRole("alertdialog", { name: "Remove Mara Chen?" });
  await expect(removal).toContainText("from Revenue Analytics");
  await expect(removal.getByRole("button", { name: "Keep member" })).toBeFocused();
  await removal.getByRole("button", { name: "Keep member" }).click();
  await expect(mara.getByRole("button", { name: "Remove from Revenue Analytics" })).toBeFocused();
  await mara.getByRole("button", { name: "Remove from Revenue Analytics" }).click();
  await removal.getByRole("button", { name: "Remove member" }).click();
  await expect(removal).toBeHidden();
  await expect(maraTrigger).toHaveCount(0);
  await expect(page).not.toHaveURL(/member=/);
  await page.screenshot({ path: evidencePath("screenshots/workspace-role-editor-desktop.png"), fullPage: true });
});

test("mobile member sheet supports keyboard role selection without overflow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/dev/workspace-settings-demo?surface=members");
  await expect(page.getByRole("combobox", { name: "Workspace settings section" })).toHaveValue("members");
  await expectNoHorizontalOverflow(page);
  await page.getByRole("button", { name: "Manage Devon Park" }).click();
  const dialog = page.getByRole("dialog", { name: "Devon Park" });
  expect((await dialog.boundingBox())?.width).toBe(390);
  await dialog.getByRole("button", { name: "Editor", exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(dialog.getByRole("button", { name: "Editor", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: evidencePath("screenshots/workspace-role-editor-mobile.png"), fullPage: true });
  const accessibility = await new AxeBuilder({ page }).include('[role="dialog"]').withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(accessibility.violations).toEqual([]);
});

test("Explorer role explains connection account authority", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/dev/role-settings-fixture?viewer=owner");
  await page.getByRole("button", { name: "Manage Riley Ortiz" }).click();
  const dialog = page.getByRole("dialog", { name: "Riley Ortiz" });
  await expect(dialog.getByText("Run queries as this workspace’s connection account and create Dashboards and Pages.", { exact: true })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: evidencePath("screenshots/native-sql-explorer-role-mobile.png"), fullPage: true });
});

test("Organization Admins who aren't owners manage members but only see public access", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/dev/workspace-settings-demo?viewer=admin&surface=members");
  await page.getByRole("button", { name: "Manage Riley Ortiz" }).click();
  const riley = page.getByRole("dialog", { name: "Riley Ortiz" });
  await riley.getByRole("button", { name: "Remove from Revenue Analytics" }).click();
  await page.getByRole("alertdialog", { name: "Remove Riley Ortiz?" }).getByRole("button", { name: "Remove member" }).click();
  await expect(page.getByRole("button", { name: "Manage Riley Ortiz" })).toHaveCount(0);
  await page.goto("/dev/workspace-settings-demo?viewer=admin&surface=public-access");
  await expect(page.getByRole("heading", { name: "Public access", level: 1 })).toBeVisible();
  await expect(page.getByRole("switch", { name: "Public Pages" })).toHaveCount(0);
  await expect(page.getByTestId("public-access-status")).toHaveText("Off");
  await expect(page.getByText("Only workspace owners can change public access.", { exact: true })).toBeVisible();
  await page.goto("/dev/workspace-settings-demo?viewer=admin");
  await expect(page.getByRole("link", { name: "Northstar Labs" })).toHaveAttribute("href", "/organizations/00000000-0000-4000-8000-000000000512/settings");
});

test("members without settings access see who the owners are", async ({ page }) => {
  await page.goto("/dev/role-settings-fixture?viewer=explorer");
  await expect(page.getByText("Only workspace owners can change these settings.", { exact: true })).toBeVisible();
  await expect(page.getByText("Owner: Morgan Chen", { exact: true })).toBeVisible();
  await expect(page.getByRole("term").filter({ hasText: "Reporting time zone" }), "every member reads the zone").toBeVisible();
  await expect(page.getByText("UTC · GMT+0", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Change Reporting time zone" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Add member" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^Manage / })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/workspace-settings-locked.png"), fullPage: true });
});
