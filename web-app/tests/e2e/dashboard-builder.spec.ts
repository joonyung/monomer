import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const permissionWorkspaceId = "019e8000-0000-7000-8000-000000000b01";
const permissionDashboardId = "019e8000-0000-7000-8000-000000000d01";

async function authenticateDashboardMember(page: Page): Promise<void> {
  await mockSignedInWorkspace(page, {
    workspaceId: permissionWorkspaceId,
    workspaceName: "E2E Workspace",
    organizationId: "organization-1",
    role: "VIEWER",
    user: { id: "member-1", email: "member@example.test", displayName: "Workspace Member" },
  });
  await completeOAuthCallback(
    page,
    { new_user: "false", auth_provider: "google" },
    `/workspaces/${permissionWorkspaceId}/dashboards/${permissionDashboardId}/builder`,
  );
}

test("keeps the shared Draft hidden from a member without dashboard edit", async ({ page }) => {
  await authenticateDashboardMember(page);

  await expect(page).toHaveURL(new RegExp(`/workspaces/${permissionWorkspaceId}/dashboards/${permissionDashboardId}/builder$`));
  await expect(page.getByRole("heading", { name: "Your workspace is in Pages" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Open Pages" })).toHaveAttribute("href", `/workspaces/${permissionWorkspaceId}/pages`);
  await expect(page.getByTestId("dashboard-builder-preview")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-builder-member-denied.png"), fullPage: true });
});

test("shows the live draft with its status line, all run states, and collapsible runs on desktop", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/dev/dashboard-builder-fixture?scene=last-good");

  await expect(page.getByRole("heading", { name: /^Orders overview$/ })).toBeVisible();
  await expect(page.getByText("Draft review", { exact: true })).toBeVisible();
  const status = page.getByTestId("draft-status");
  await expect(status).toContainText(/^Revision \d+·Unapplied changes/);
  await expect(status).toContainText("Sam Kim");
  await expect(status.getByText("via agent", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Discard changes", exact: true })).toBeEnabled();
  await expect(page.getByRole("list", { name: "What changed" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Apply", exact: true })).toBeEnabled();
  await expect(page.getByRole("link", { name: "Back to dashboard", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "View definition", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh preview", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Refresh", exact: true })).toBeEnabled();
  const chartMenu = page.getByRole("button", { name: "More actions for Chart of orders", exact: true });
  await chartMenu.click();
  await expect(page.getByRole("menuitem", { name: "View run details" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Show metric definitions" })).toBeVisible();
  await page.getByRole("menuitem", { name: "Refresh this widget" }).click();
  await expect(page.getByTestId("dashboard-widget-request")).toHaveText("refresh:orders");
  await chartMenu.click();
  await page.getByRole("menuitem", { name: "View run details" }).click();
  await expect(page.getByRole("dialog")).toContainText("Current target");
  await page.getByRole("button", { name: "Close run detail" }).click();
  await expect(page.getByText("Live", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Last-good results remain visible while current targets run.")).toHaveCount(0);
  const rail = page.locator('aside[aria-label="Dashboard runs"]:visible');
  await expect(rail).toBeVisible();
  await expect(rail).toContainText("Running");
  await expect(rail).toContainText("Failed");
  await expect(rail).toContainText("Cancelled");
  await expect(rail.getByRole("button").filter({ hasText: "Failed" })).toContainText("via agent");
  await expect(rail.getByRole("button").filter({ hasText: "Failed" })).toContainText("Sam Kim");
  await expect(rail.getByText("via agent", { exact: true })).toHaveCount(1);
  await expect(rail).not.toContainText("00000000-0000-7000-8000-000000000001");

  await rail.getByRole("button").filter({ hasText: "Running" }).first().click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByRole("dialog").getByText("Current target", { exact: true })).toBeVisible();
  await expect(page.getByRole("dialog")).toContainText("Started by Mina Park");
  await expect(page.getByRole("dialog").getByRole("button", { name: "Cancel" })).toBeVisible();
  await page.getByRole("button", { name: "Close run detail" }).click();

  await rail.getByRole("button", { name: "Collapse runs" }).click();
  await expect(rail).toHaveAttribute("data-collapsed", "true");
  await page.screenshot({ path: evidencePath("../screenshots/2026-08-24-dashboard-builder-desktop.png"), fullPage: true });
});

test("discards unapplied changes only after confirmation", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/dev/dashboard-builder-fixture");

  await page.getByRole("button", { name: "Discard changes", exact: true }).click();
  const dialog = page.getByRole("alertdialog", { name: "Discard unapplied changes to Orders overview?" });
  await expect(dialog).toContainText("This can’t be undone.");
  await expect(dialog).toContainText("drops 1 input added, 1 widget changed");
  await dialog.getByRole("button", { name: "Keep changes" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByTestId("draft-status")).toContainText("Unapplied changes");

  await page.getByRole("button", { name: "Discard changes", exact: true }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Discard changes", exact: true }).click();
  await expect(page.getByTestId("draft-status")).toContainText("Matches what everyone sees");
  await expect(page.getByRole("button", { name: "Discard changes", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Apply", exact: true })).toBeDisabled();
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-builder-discarded.png"), fullPage: true });
});

test("uses the runs drawer on mobile and reconciles an SSE snapshot", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/dev/dashboard-builder-fixture?scene=sse");

  await expect(page.getByRole("heading", { name: /^Orders overview$/ })).toBeVisible();
  await expect(page.getByRole("complementary", { name: "Dashboard runs" })).toBeHidden();
  await page.getByRole("button", { name: /^Runs$/ }).click();
  const drawer = page.getByRole("dialog", { name: "Dashboard runs" });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole("complementary", { name: "Dashboard runs" })).toBeVisible();
  await drawer.getByRole("button", { name: "Close runs" }).click();
  await expect(drawer).toBeHidden();

  await page.getByRole("button", { name: "Emit SSE invalidation" }).click();
  await expect(page.getByText("Last-good results remain visible while current targets run.")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/2026-08-24-dashboard-builder-mobile.png"), fullPage: true });
});

test("renders typed Parameter controls and blocks an invalid range draft", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/dev/dashboard-builder-fixture?scene=ready");

  const toolbar = page.getByTestId("dashboard-parameter-toolbar");
  await expect(toolbar.getByRole("listitem").filter({ hasText: "Region" })).toHaveText("RegionNorth");
  await expect(toolbar.getByTestId("dashboard-parameter-region")).toHaveCount(0);
  await toolbar.getByRole("button", { name: "Change preview inputs", exact: true }).click();
  await expect(toolbar.getByTestId("dashboard-parameter-includeReturns")).toBeVisible();
  await expect(toolbar.getByTestId("dashboard-parameter-region")).toBeVisible();
  await expect(toolbar.getByTestId("dashboard-parameter-reportPeriod")).toBeVisible();
  const includeReturns = toolbar.getByRole("combobox", { name: "Include returns" });
  await expect(includeReturns).toHaveText("Exclude");
  await expect(toolbar.getByRole("combobox", { name: "Region" })).toHaveText("North");
  const optionalNote = toolbar.getByTestId("dashboard-parameter-optionalNote");
  await expect(optionalNote.getByRole("checkbox")).not.toBeChecked();
  await page.getByRole("button", { name: "Sync optional note null" }).click();
  await expect(optionalNote.getByRole("checkbox")).toBeChecked();

  const tags = toolbar.getByTestId("dashboard-parameter-tags");
  const tagsInput = tags.getByRole("textbox", { name: "Tags" });
  await tagsInput.fill("");
  await tagsInput.pressSequentially("North,");
  await expect(tagsInput).toHaveValue("North,");
  await tagsInput.pressSequentially(" South, East");
  await expect(tagsInput).toHaveValue("North, South, East");
  await expect(tags.getByRole("button", { name: /^Set / })).toBeEnabled();

  const asOf = toolbar.getByTestId("dashboard-parameter-asOf");
  await expect(asOf.getByRole("textbox", { name: "As of" })).toHaveValue("2026-08-24T09:00");
  await asOf.getByRole("textbox", { name: "As of" }).fill("2026-09-01T10:15");
  await expect(asOf.getByRole("button", { name: /^Set / })).toBeEnabled();

  const timestampSet = toolbar.getByTestId("dashboard-parameter-timestampSet");
  await timestampSet.getByRole("textbox", { name: "Add to Timestamp set" }).fill("2026-08-25T12:00");
  await expect(timestampSet.getByText("2026-08-25T12:00:00")).toBeVisible();

  const timestampWindow = toolbar.getByTestId("dashboard-parameter-timestampWindow");
  await timestampWindow.getByRole("textbox", { name: "Timestamp window start" }).fill("2026-08-01T00:00");
  await timestampWindow.getByRole("textbox", { name: "Timestamp window end" }).fill("2026-08-25T12:00");
  await expect(timestampWindow.getByRole("button", { name: /^Set / })).toBeEnabled();

  const reportPeriod = toolbar.getByTestId("dashboard-parameter-reportPeriod");
  const start = reportPeriod.getByRole("textbox", { name: "Report period start" });
  const end = reportPeriod.getByRole("textbox", { name: "Report period end" });
  await start.fill("2026-09-01");
  await end.fill("2026-08-01");
  await expect(reportPeriod.getByRole("button", { name: /^Set / })).toBeDisabled();

  await end.fill("2026-09-30");
  await expect(reportPeriod.getByRole("button", { name: /^Set / })).toBeEnabled();
  await page.screenshot({ path: evidencePath("../screenshots/2026-08-25-dashboard-value-controls.png"), fullPage: true });
});
