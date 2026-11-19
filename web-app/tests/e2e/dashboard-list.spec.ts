import type { Page, Route } from "@playwright/test";
import type { components } from "../../src/api/control/generated";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { json, mockSignedInWorkspace, problem } from "./support/session";
import { expect, test } from "./support/test";

type DashboardListResponse = components["schemas"]["DashboardListResponse"];

const workspaceId = "019e8000-0000-7000-8000-000000000a71";
const revenueId = "019e8000-0000-7000-8000-000000000d71";
const supportId = "019e8000-0000-7000-8000-000000000d72";
const churnId = "019e8000-0000-7000-8000-000000000d73";
const listPath = `/v2/workspaces/${workspaceId}/dashboards`;

const library = {
  items: [
    {
      dashboardId: revenueId, workspaceId, title: "Revenue overview", description: "Weekly revenue by region and channel.", updatedAt: "2026-09-29T08:00:00Z",
      updatedBy: { id: "019e8000-0000-7000-8000-000000000e71", displayName: "Sam Kim" }, credentialSurface: "AGENT",
      agentApp: { clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude", verifiedHost: "claude.ai" },
      draftLastEditedBy: null, draftLastEditedAt: null, draftLastEditSurface: null, draftLastEditAgentApp: null,
      latestResultsAt: "2026-09-29T00:02:00Z", schedules: [{ key: "daily", cron: "0 9 * * *", timeZone: "UTC" }], draftHasChanges: false, pagePlacementCount: 1,
    },
    {
      dashboardId: supportId, workspaceId, title: "Support queue health", description: null, updatedAt: "2026-09-28T08:00:00Z",
      updatedBy: { id: "019e8000-0000-7000-8000-000000000e72", displayName: "Mina Park" }, credentialSurface: "WEB", agentApp: null,
      draftLastEditedBy: { id: "019e8000-0000-7000-8000-000000000e71", displayName: "Sam Kim" }, draftLastEditedAt: "2026-09-28T09:00:00Z", draftLastEditSurface: "AGENT", draftLastEditAgentApp: null,
      latestResultsAt: null, schedules: [], draftHasChanges: true, pagePlacementCount: 0,
    },
    {
      dashboardId: churnId, workspaceId, title: "Churn watch", description: "Accounts at risk of cancelling this quarter.", updatedAt: "2026-09-27T08:00:00Z",
      updatedBy: null, credentialSurface: null, agentApp: null,
      draftLastEditedBy: null, draftLastEditedAt: null, draftLastEditSurface: null, draftLastEditAgentApp: null,
      latestResultsAt: "2026-09-27T08:00:00Z", schedules: [{ key: "monday", cron: "0 8 * * 1", timeZone: "UTC" }, { key: "custom", cron: "0 9 L * *", timeZone: "UTC" }], draftHasChanges: false, pagePlacementCount: 2,
    },
  ],
} satisfies DashboardListResponse;

// Answers the list read and holds every other Dashboard read open, so opening an item shows the
// Viewer's first loading state without mocking the whole Viewer contract.
async function openLibrary(page: Page, answerList: (route: Route) => Promise<void>): Promise<{ unexpected: string[] }> {
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Dashboard library",
    organizationId: "organization-dashboard-library",
    role: "EXPLORER",
    user: { id: "dashboard-member", email: "member@example.test", displayName: "Dashboard member" },
  });
  const unexpected: string[] = [];
  await page.route("**/v2/workspaces/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === listPath && route.request().method() === "GET") {
      await answerList(route);
      return;
    }
    if (path.startsWith(`${listPath}/`)) return;
    unexpected.push(`${route.request().method()} ${path}`);
    await problem(route, 404, "NOT_FOUND", `No fixture for ${path}.`, "Not Found");
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}/dashboards`);
  await expect(page.getByRole("heading", { name: "Dashboards", exact: true })).toBeVisible();
  return { unexpected };
}

test("lists workspace dashboards with results, schedules, Pages, and Draft review, then opens the Viewer", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const fixture = await openLibrary(page, (route) => json(route, library));

  const dashboards = page.getByRole("list", { name: "Dashboards" });
  await expect(dashboards.getByRole("listitem")).toHaveCount(3);
  const revenueRow = dashboards.getByRole("listitem").filter({ hasText: "Revenue overview" });
  const revenue = dashboards.getByRole("link", { name: "Revenue overview", exact: true });
  await expect(revenue).toHaveAttribute("href", `/workspaces/${workspaceId}/dashboards/${revenueId}`);
  await expect(revenueRow).toContainText("Weekly revenue by region and channel.");
  await expect(revenueRow.getByTestId("dashboard-latest-results")).toHaveAttribute("datetime", "2026-09-29T00:02:00Z");
  await expect(revenueRow.getByTestId("dashboard-updated").locator("time")).toHaveAttribute("datetime", "2026-09-29T08:00:00Z");
  await expect(revenueRow).toContainText("Daily · 9:00 AM");
  await expect(revenueRow).toContainText("1 page");
  await expect(revenueRow).toContainText("Updated by Sam Kim");
  await expect(revenueRow.getByText("via Claude", { exact: true })).toBeVisible();
  const appMarker = revenueRow.getByRole("button", { name: "via Claude (claude.ai)" });
  await expect(appMarker.locator('[data-agent-app-logo="claude"]')).toBeVisible();
  await appMarker.click();
  await expect(revenueRow.getByText("via Claude (claude.ai)", { exact: true })).toHaveCSS("opacity", "1");
  const churnRow = dashboards.getByRole("listitem").filter({ hasText: "Churn watch" });
  await expect(churnRow).toContainText("2 schedules");
  await expect(churnRow).toContainText("2 pages");
  await expect(churnRow.getByText("via agent", { exact: true })).toHaveCount(0);
  const supportRow = dashboards.getByRole("listitem").filter({ hasText: "Support queue health" });
  await expect(supportRow).toContainText("No results yet");
  await expect(dashboards.getByRole("link", { name: /^Edit / })).toHaveCount(0);
  await expect(dashboards.getByRole("link", { name: /^Review draft changes/ })).toHaveCount(1);
  await expect(supportRow.getByRole("link", { name: "Review draft changes to Support queue health" })).toHaveAttribute("href", `/workspaces/${workspaceId}/dashboards/${supportId}/builder`);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-library.png"), fullPage: true });

  const search = page.getByRole("textbox", { name: "Search dashboards" });
  await search.fill("REGION");
  await expect(dashboards.getByRole("listitem")).toHaveCount(1);
  await expect(revenue).toBeVisible();
  await search.fill("quarterly board pack");
  await expect(page.getByText("No dashboards match “quarterly board pack”.")).toBeVisible();
  await expect(dashboards).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Your agent builds dashboards" })).toHaveCount(0);
  await search.fill("churn");
  // The row's whole surface opens the Viewer, not only the title text.
  await churnRow.click({ position: { x: 900, y: 20 } });

  await expect(page).toHaveURL(new RegExp(`/workspaces/${workspaceId}/dashboards/${churnId}$`));
  await expect(page.getByText("Loading the dashboard definition…")).toBeVisible();
  expect(fixture.unexpected).toEqual([]);
});

test("reflows library rows into a compact meta line on a phone", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const fixture = await openLibrary(page, (route) => json(route, library));
  const revenueRow = page.getByRole("list", { name: "Dashboards" }).getByRole("listitem").filter({ hasText: "Revenue overview" });
  await expect(revenueRow).toContainText(/Daily · 9:00 AM( UTC)? · In 1 page/);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-library-mobile.png"), fullPage: true });
  expect(fixture.unexpected).toEqual([]);
});

test("explains that agents build dashboards when the library is empty", async ({ page }) => {
  const fixture = await openLibrary(page, (route) => json(route, { items: [] } satisfies DashboardListResponse));

  await expect(page.getByRole("heading", { name: "Your agent builds dashboards" })).toBeVisible();
  await expect(page.getByText("Connect Claude, Claude Code, or Codex, then ask for a dashboard from your metrics.", { exact: false })).toBeVisible();
  await expect(page.getByText("“Build a dashboard of weekly revenue and churn by region from our metrics.”")).toBeVisible();
  await expect(page.getByRole("link", { name: "Connect an agent", exact: true })).toHaveAttribute("href", "/settings/agents");
  await expect(page.getByRole("link", { name: "How dashboards work (opens in a new tab)" })).toHaveAttribute("href", "https://monomer.cc/en/docs/dashboards/");
  await expect(page.getByRole("textbox", { name: "Search dashboards" })).toHaveCount(0);
  await expect(page.getByRole("list", { name: "Dashboards" })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-library-empty.png"), fullPage: true });
  expect(fixture.unexpected).toEqual([]);
});

// Control reads the library for Explorer and above. A permanent list denial explains the
// missing role without promising that another attempt will restore access.
test("explains a permanent library denial without offering Retry", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/dashboards$/, reason: "Control denies the Dashboard list because Explorer role is required." });
  const fixture = await openLibrary(page, (route) => problem(route, 403, "FORBIDDEN", "Explorer role is required.", "Forbidden"));

  await expect(page.getByRole("heading", { name: "Dashboards unavailable" })).toBeVisible();
  await expect(page.getByText("Explorer role is required.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Retry", exact: true })).toHaveCount(0);
  await expect(page.getByText("Try again in a moment", { exact: false })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Your agent builds dashboards" })).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: "Search dashboards" })).toHaveCount(0);
  expect(fixture.unexpected).toEqual([]);
});

test("recovers a network library failure on Retry", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ message: /^Failed to load resource: net::ERR_FAILED$/, url: new RegExp(`${listPath}$`), reason: "The fixture aborts the Dashboard list network request until connectivity is restored." });
  let networkRecovered = false;
  let listReads = 0;
  const fixture = await openLibrary(page, async (route) => {
    listReads += 1;
    if (networkRecovered) return json(route, library);
    return route.abort("failed");
  });

  await expect(page.getByRole("heading", { name: "Dashboards unavailable" })).toBeVisible();
  await expect(page.getByText("Failed to fetch", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Your agent builds dashboards" })).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: "Search dashboards" })).toHaveCount(0);
  expect(listReads).toBe(1);
  networkRecovered = true;
  await page.getByRole("button", { name: "Retry", exact: true }).click();

  await expect(page.getByRole("list", { name: "Dashboards" }).getByRole("listitem")).toHaveCount(3);
  await expect(page.getByRole("heading", { name: "Dashboards unavailable" })).toHaveCount(0);
  expect(listReads).toBe(2);
  expect(fixture.unexpected).toEqual([]);
});
