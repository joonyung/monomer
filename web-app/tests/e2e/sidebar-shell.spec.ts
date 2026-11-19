import { expect, test } from "./support/test";
import type { Locator, Page } from "@playwright/test";
import { evidencePath } from "./evidence";
import { json, mockGraphql, mockSessionRefresh } from "./support/session";
import { mockAbsentSampleWorkspace, mockOverviewSources } from "./support/overview";

test.beforeEach(async ({ page, consoleErrors }) => mockAbsentSampleWorkspace(page, "organization-first", consoleErrors));

// Visible scrollbars are part of these layouts and their evidence.
test.use({ contextOptions: { reducedMotion: "reduce" }, launchOptions: { ignoreDefaultArgs: ["--hide-scrollbars"] } });

const PRIMARY_WORKSPACE_ID = "11111111-1111-4111-8111-111111111111";
const SECONDARY_WORKSPACE_ID = "22222222-2222-4222-8222-222222222222";
const DASHBOARD_ID = "33333333-3333-4333-8333-333333333333";
const USER = { id: "44444444-4444-4444-8444-444444444444", email: "jordan@acme.test", displayName: "Jordan Lee" };
const MEMBERSHIPS = [
  { organization: { id: "organization-first", displayName: "Jordan Lee", plan: "FREE", createdAt: "2026-09-01T00:00:00Z" }, role: "ADMIN" },
  { organization: { id: "organization-acme", displayName: "Acme Analytics", plan: "FREE", createdAt: "2026-09-01T00:00:00Z" }, role: "MEMBER" },
];
const WORKSPACES = [
  { id: PRIMARY_WORKSPACE_ID, organizationId: "organization-first", name: "My Workspace", publicPages: false, reportingTimeZone: "UTC", role: "EXPLORER" },
  { id: SECONDARY_WORKSPACE_ID, organizationId: "organization-first", name: "Some workspace", publicPages: false, reportingTimeZone: "UTC", role: "OWNER" },
];

// Opens the real workspace shell on Overview with a signed-in session and empty owner reads.
async function seedWorkspaceShell(page: Page, workspaces: unknown[] = WORKSPACES) {
  let pagesTreeRequestCount = 0;
  await mockSessionRefresh(page);
  await mockOverviewSources(page, PRIMARY_WORKSPACE_ID);
  await mockGraphql(page, {
    Me: (route) => json(route, { data: { me: { user: USER, organizationMemberships: MEMBERSHIPS, loginIdentities: [] } } }),
    AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: workspaces } }),
    WorkspaceAccess: (route) => json(route, { data: { workspaceAccess: {
      granted: true, organizationId: "organization-first", role: "EXPLORER", } } }),
    WorkspaceOwners: (route) => json(route, { data: { workspaceAccess: { owners: [] } } }),
  });
  const pagesTree = {
    workspaceId: PRIMARY_WORKSPACE_ID,
    revision: 1,
    pages: [{
      pageId: "55555555-5555-4555-8555-555555555555", kind: "DASHBOARD", documentFormat: null, dashboardId: DASHBOARD_ID,
      title: "Weekly revenue", displayTitle: "Weekly revenue", description: null,
      createdAt: "2026-08-23T00:00:00Z", updatedAt: "2026-08-23T00:00:00Z",
    }],
    placements: [{ pageId: "55555555-5555-4555-8555-555555555555", children: [] }],
    updatedAt: "2026-08-23T00:00:00Z",
    updatedBy: { kind: "SYSTEM", id: "sidebar-shell-fixture" },
  };
  await page.route(`**/v2/workspaces/${PRIMARY_WORKSPACE_ID}/pages/access`, (route) => json(route, { mode: "MEMBER" }));
  await page.route(`**/v2/workspaces/${PRIMARY_WORKSPACE_ID}/pages/tree`, (route) => {
    pagesTreeRequestCount += 1;
    return json(route, pagesTree);
  });
  await page.route(`**/v2/workspaces/${PRIMARY_WORKSPACE_ID}/pages/tree/events`, (route) =>
    route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: snapshot\ndata: ${JSON.stringify(pagesTree)}\n\n` }));
  await page.route(`**/v2/workspaces/${PRIMARY_WORKSPACE_ID}/overview/activity?**`, (route) =>
    json(route, { items: [], watched: [], nextCursor: null, unavailableSources: [], observedAt: "2026-08-23T00:00:00Z" }));
  return {
    pagesTreeRequestCount: () => pagesTreeRequestCount,
  };
}

// Evidence of an open menu waits until it has finished opening and no hover tooltip overlaps it.
async function expectSettledMenu(page: Page) {
  await page.mouse.move(900, 600);
  const menu = page.getByRole("menu").filter({ hasText: "All workspaces" });
  await expect(menu).toHaveAttribute("data-state", "open");
  await menu.evaluate((element) => Promise.all(element.getAnimations({ subtree: true }).map((animation) => animation.finished)));
  await expect(page.getByTestId("desktop-sidebar").getByRole("tooltip", { name: "Collapse sidebar" })).toHaveCSS("opacity", "0");
  await expect(menu).toHaveCSS("opacity", "1");
}

// MainLayout clears data-transition after 160ms. Capture the transition in the browser
// when it occurs rather than sampling it after later navigation assertions.
async function clickSidebarContext(sidebar: Locator, button: Locator, direction: "forward" | "backward") {
  await sidebar.evaluate((element, expected) => {
    const observer = new MutationObserver(() => {
      if (element.querySelector('[data-testid="sidebar-navigation-context"]')?.getAttribute("data-transition") !== expected) return;
      element.setAttribute("data-observed-transition", expected);
      observer.disconnect();
    });
    element.removeAttribute("data-observed-transition");
    observer.observe(element, { childList: true, subtree: true, attributes: true, attributeFilter: ["data-transition"] });
  }, direction);
  await button.click();
  await expect(sidebar).toHaveAttribute("data-observed-transition", direction);
}

async function expectTooltipNotClipped(tooltip: Locator) {
  const clipped = await tooltip.evaluate((element) => {
    const tooltipRect = element.getBoundingClientRect();
    let ancestor = element.parentElement;

    while (ancestor) {
      const overflowX = window.getComputedStyle(ancestor).overflowX;
      const clipsHorizontally = ["auto", "clip", "hidden", "scroll"].includes(overflowX);
      if (clipsHorizontally && ancestor.getBoundingClientRect().right < tooltipRect.right - 1) return true;
      ancestor = ancestor.parentElement;
    }
    return false;
  });

  expect(clipped).toBe(false);
}

test("desktop sidebar names the workspace's Organization and my role, groups destinations, and collapses to a rail", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await seedWorkspaceShell(page);
  await page.goto(`/workspaces/${PRIMARY_WORKSPACE_ID}`);

  const sidebar = page.getByTestId("desktop-sidebar");
  await expect(sidebar).toHaveCSS("width", "256px");
  await expect(sidebar).toHaveAttribute("data-collapsed", "false");
  const switcher = page.getByRole("button", { name: "Switch workspace" });
  await expect(switcher).toContainText("My Workspace");
  await expect(switcher).toContainText("Jordan Lee · Explorer");
  await expect(sidebar.getByTestId("sidebar-search")).toContainText("Search");
  const navigation = sidebar.getByRole("navigation", { name: "Workspace navigation" });
  for (const group of ["Sources", "Model", "Analyze"]) await expect(navigation.getByText(group, { exact: true })).toBeVisible();
  await expect(navigation.getByRole("button", { name: "Pages", exact: true })).toBeVisible();
  await expect(navigation.getByRole("link", { name: "Pages", exact: true })).toHaveCount(0);
  const utilities = sidebar.getByRole("navigation", { name: "Workspace utilities" });
  await expect(utilities.getByRole("link", { name: "Help" })).toHaveAttribute("href", "https://monomer.cc/en/docs/start/overview/");
  // An Organization Admin manages the workspace without being its Owner.
  await expect(utilities.getByRole("link", { name: "Settings" })).toHaveAttribute("href", `/workspaces/${PRIMARY_WORKSPACE_ID}/settings`);

  const collapseButton = sidebar.getByRole("button", { name: "Collapse sidebar" });
  await collapseButton.hover();
  await expect(sidebar.getByRole("tooltip", { name: "Collapse sidebar" })).toHaveCSS("opacity", "1");
  await page.screenshot({ path: evidencePath("../screenshots/2026-08-20-sidebar-production-expanded.png"), fullPage: true });
  await collapseButton.click();

  await expect(sidebar).toHaveCSS("width", "56px");
  await expect(sidebar).toHaveAttribute("data-collapsed", "true");
  await expect(page.getByRole("button", { name: "Switch workspace" })).toHaveCount(0);
  await expect(sidebar.getByRole("button", { name: "Search" })).toBeVisible();

  const openButton = sidebar.getByRole("button", { name: "Open sidebar" });
  await openButton.hover();
  await expect(sidebar.getByRole("tooltip", { name: "Open sidebar" })).toHaveCSS("opacity", "1");

  await sidebar.getByRole("link", { name: "Connectors" }).hover();
  const connectorsTooltip = sidebar.getByRole("tooltip", { name: "Connectors" });
  await expect(connectorsTooltip).toHaveCSS("opacity", "1");
  await expectTooltipNotClipped(connectorsTooltip);
  await page.screenshot({ path: evidencePath("../screenshots/2026-08-20-sidebar-production-collapsed.png"), fullPage: true });

  await sidebar.getByRole("link", { name: "Connectors" }).click();
  await expect(page).toHaveURL(`/workspaces/${PRIMARY_WORKSPACE_ID}/connectors`);
  const navigatedSidebar = page.getByTestId("desktop-sidebar");
  await expect(navigatedSidebar).toHaveAttribute("data-collapsed", "true");
  await expect(navigatedSidebar).toHaveCSS("width", "56px");
  await page.screenshot({ path: evidencePath("../screenshots/2026-08-21-sidebar-collapsed-after-navigation.png"), fullPage: true });

  await navigatedSidebar.getByRole("button", { name: "Open sidebar" }).click();
  await expect(sidebar).toHaveCSS("width", "256px");
  await page.getByRole("button", { name: "Switch workspace" }).click();
  await expect(page.getByTestId(`workspace-option-${PRIMARY_WORKSPACE_ID}`)).toContainText("Explorer");
  await expect(page.getByTestId(`workspace-option-${PRIMARY_WORKSPACE_ID}`)).toHaveAttribute("aria-checked", "true");
  await expect(page.getByTestId(`workspace-option-${SECONDARY_WORKSPACE_ID}`)).toContainText("Owner");
  await expect(page.getByTestId("organization-option-organization-acme")).toHaveText("Acme Analytics");
  await expect(page.getByText("No workspace access yet", { exact: true })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "All workspaces" })).toHaveAttribute("href", "/workspaces");
  await expect(page.getByTestId("organization-settings-organization-first")).toHaveAttribute("href", "/organizations/organization-first/settings");
  await expect(page.getByRole("menuitem", { name: "New Organization" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Search workspaces" })).toHaveCount(0);
  await expectSettledMenu(page);
  await page.screenshot({ path: evidencePath("screenshots/workspace-switcher-organization-tree.png"), fullPage: true, animations: "disabled" });
});

test("mobile keeps the focus-trapped drawer and puts Search and the user menu in the top bar", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await seedWorkspaceShell(page);
  await page.goto(`/workspaces/${PRIMARY_WORKSPACE_ID}`);

  await expect(page.getByTestId("desktop-sidebar")).toBeHidden();
  await page.getByRole("button", { name: "Open user menu for Jordan Lee" }).filter({ visible: true }).click();
  await expect(page.getByRole("menuitem", { name: "Settings" })).toHaveAttribute("href", "/settings/profile");
  await expect(page.getByRole("menuitem", { name: "Connect an agent" })).toHaveAttribute("href", "/settings/agents");
  await expect(page.getByRole("menuitem", { name: "Sign out" })).toBeVisible();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Search", exact: true }).filter({ visible: true }).click();
  await expect(page.getByRole("dialog", { name: "Search" })).toBeVisible();
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: "Open workspace navigation" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  const close = page.getByRole("button", { name: "Close workspace navigation" });
  await expect(close).toBeFocused();
  await expect(close).toHaveCSS("outline-style", "none");
  await expect(close).toHaveCSS("box-shadow", "none");
  await page.screenshot({ path: evidencePath("../screenshots/mobile-sidebar-pointer-focus.png") });
  await page.keyboard.press("Tab");
  await page.keyboard.press("Shift+Tab");
  await expect(close).toBeFocused();
  await expect(close).toHaveCSS("outline-style", "solid");
  await page.screenshot({ path: evidencePath("../screenshots/mobile-sidebar-keyboard-focus.png") });
  await expect(page.getByRole("button", { name: "Switch workspace" })).toContainText("My Workspace");
  await expect(page.getByRole("button", { name: "Collapse sidebar" })).toHaveCount(0);

  // Pages switches the drawer to the Pages directory and keeps it open on the same screen.
  const dialog = page.getByRole("dialog");
  const overviewUrl = page.url();
  await dialog.getByRole("button", { name: "Pages", exact: true }).click();
  await expect(page).toHaveURL(overviewUrl);
  await expect(dialog.getByRole("navigation", { name: "Pages navigation" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Back to workspace navigation" })).toBeFocused();
  await dialog.getByRole("button", { name: "Back to workspace navigation" }).click();
  await expect(page).toHaveURL(overviewUrl);
  await expect(dialog.getByRole("navigation", { name: "Workspace navigation" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Pages", exact: true })).toBeFocused();

  // The top bar names the screen, so switching the drawer to Pages on Overview leaves it alone.
  const topBarPagesLabel = page.getByTestId("mobile-pages-label");
  await dialog.getByRole("button", { name: "Pages", exact: true }).click();
  await expect(dialog.getByRole("navigation", { name: "Pages navigation" })).toBeVisible();
  await expect(topBarPagesLabel).toHaveCount(0);

  // The Pages context offers the same Search entry, whose Go to reaches the Pages root.
  await expect(dialog.getByRole("searchbox")).toHaveCount(0);
  await dialog.getByTestId("sidebar-search").click();
  await expect(page.getByRole("dialog", { name: "Search" })).toBeVisible();
  await page.getByRole("dialog", { name: "Search" }).getByRole("group", { name: "Go to" }).getByRole("option", { name: /^Pages/ }).click();
  await expect(page).toHaveURL(`/workspaces/${PRIMARY_WORKSPACE_ID}/pages`);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(topBarPagesLabel).toHaveText("Pages");

  // On a Pages URL the label stays while the drawer shows workspace navigation. The Pages root must finish
  // rendering first so the drawer is not opened into a route change.
  await expect(page.getByRole("main").getByRole("heading", { name: "All pages", level: 1 })).toBeVisible();
  await page.getByRole("button", { name: "Open pages navigation" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Back to workspace navigation" }).click();
  await expect(page.getByRole("dialog").getByRole("navigation", { name: "Workspace navigation" })).toBeVisible();
  await expect(topBarPagesLabel).toHaveText("Pages");
});

test("Pages switches only the sidebar to the Pages directory and keeps the screen and URL", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const requests = await seedWorkspaceShell(page);
  await page.goto(`/workspaces/${PRIMARY_WORKSPACE_ID}`);

  const sidebar = page.getByTestId("desktop-sidebar");
  const heading = page.getByRole("main").getByRole("heading", { level: 1 });
  await expect(heading).toHaveText("Overview");
  const overviewUrl = page.url();
  // A remount of the screen would drop this marker.
  await heading.evaluate((element) => element.setAttribute("data-sidebar-toggle-probe", "kept"));
  await page.screenshot({ path: evidencePath("screenshots/pages-toggle-before.png"), animations: "disabled" });

  const workspaceNavigation = sidebar.getByRole("navigation", { name: "Workspace navigation" });
  await clickSidebarContext(sidebar, workspaceNavigation.getByRole("button", { name: "Pages", exact: true }), "forward");
  await expect(page).toHaveURL(overviewUrl);
  await expect(sidebar.getByRole("navigation", { name: "Pages navigation" })).toBeVisible();
  await expect(sidebar.getByRole("link", { name: "Weekly revenue" })).toBeVisible();
  await expect.poll(requests.pagesTreeRequestCount).toBeGreaterThan(0);
  await expect(sidebar.getByRole("button", { name: "Back to workspace navigation" })).toBeFocused();
  await expect(heading).toHaveText("Overview");
  await expect(heading).toHaveAttribute("data-sidebar-toggle-probe", "kept");
  await expect(page.getByTestId("pages-context-bar")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/pages-toggle-after.png"), animations: "disabled" });

  await clickSidebarContext(sidebar, sidebar.getByRole("button", { name: "Back to workspace navigation" }), "backward");
  await expect(page).toHaveURL(overviewUrl);
  await expect(workspaceNavigation).toBeVisible();
  await expect(workspaceNavigation.getByRole("link", { name: "Overview" })).toHaveAttribute("aria-current", "page");
  await expect(workspaceNavigation.getByRole("button", { name: "Pages", exact: true })).not.toHaveAttribute("aria-current");
  await expect(workspaceNavigation.getByRole("button", { name: "Pages", exact: true })).toBeFocused();
  await expect(heading).toHaveAttribute("data-sidebar-toggle-probe", "kept");
  await expect(page.getByText("Built with Monomer")).toHaveCount(0);

  // The Pages context matches the Workspace one: no directory filter or All pages row, New folder as the
  // last row of the list, and Search above the utilities.
  await workspaceNavigation.getByRole("button", { name: "Pages", exact: true }).click();
  const pagesNavigation = sidebar.getByRole("navigation", { name: "Pages navigation" });
  await expect(pagesNavigation).toBeVisible();
  await expect(sidebar.getByRole("searchbox")).toHaveCount(0);
  await expect(sidebar.getByRole("link", { name: "All pages" })).toHaveCount(0);
  const newFolder = pagesNavigation.getByRole("button", { name: "New top-level folder" });
  await expect(newFolder).toHaveText("New folder");
  const [lastPageBox, newFolderBox] = await Promise.all([pagesNavigation.getByRole("link", { name: "Weekly revenue" }).boundingBox(), newFolder.boundingBox()]);
  expect(newFolderBox!.y).toBeGreaterThanOrEqual(lastPageBox!.y + lastPageBox!.height);
  const searchEntry = sidebar.getByTestId("sidebar-search");
  const utilities = sidebar.getByRole("navigation", { name: "Workspace utilities" });
  const [searchBox, utilitiesBox] = await Promise.all([searchEntry.boundingBox(), utilities.boundingBox()]);
  expect(searchBox!.y + searchBox!.height).toBeLessThanOrEqual(utilitiesBox!.y);
  await page.screenshot({ path: evidencePath("screenshots/pages-context-header-and-search.png"), animations: "disabled" });

  await newFolder.click();
  await expect(page.getByTestId("new-pages-folder-dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("new-pages-folder-dialog")).toHaveCount(0);

  // Search's Go to opens the Pages root, which keeps the Pages context.
  await searchEntry.click();
  await page.getByRole("dialog", { name: "Search" }).getByRole("group", { name: "Go to" }).getByRole("option", { name: /^Pages/ }).click();
  await expect(page).toHaveURL(`/workspaces/${PRIMARY_WORKSPACE_ID}/pages`);
  await expect(page.getByRole("main").getByRole("heading", { name: "All pages", level: 1 })).toBeVisible();
  await expect(pagesNavigation).toBeVisible();

  const pagesUrl = page.url();
  await sidebar.getByRole("button", { name: "Back to workspace navigation" }).click();
  await expect(page).toHaveURL(pagesUrl);
  await expect(workspaceNavigation.getByRole("button", { name: "Pages", exact: true })).toHaveAttribute("aria-current", "page");
  await workspaceNavigation.getByRole("button", { name: "Pages", exact: true }).click();
  await expect(page).toHaveURL(pagesUrl);
  await expect(sidebar.getByRole("navigation", { name: "Pages navigation" })).toBeVisible();

  await sidebar.getByRole("button", { name: "Back to workspace navigation" }).click();
  await sidebar.getByRole("link", { name: "Connectors" }).click();
  await expect(page).toHaveURL(`/workspaces/${PRIMARY_WORKSPACE_ID}/connectors`);
  await expect(sidebar.getByTestId("sidebar-navigation-context")).toHaveAttribute("data-transition", "none");
  await expect(sidebar.getByTestId("sidebar-navigation-context")).toHaveCSS("animation-name", "none");
});

test("collapsed Pages context steps back to the workspace rail, and the rail's Pages expands into the directory in place", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await seedWorkspaceShell(page);
  // A direct Pages visit starts with the rail collapsed so the content leads.
  await page.goto(`/workspaces/${PRIMARY_WORKSPACE_ID}/pages`);
  await expect(page.getByRole("main").getByRole("heading", { name: "All pages", level: 1 })).toBeVisible();

  const sidebar = page.getByTestId("desktop-sidebar");
  await expect(sidebar).toHaveAttribute("data-collapsed", "true");
  await expect(sidebar).toHaveCSS("width", "56px");
  await expect(page.getByTestId("pages-context-bar")).toContainText("My Workspace");
  // Like the workspace rail, the collapsed Pages context keeps Search above the utilities.
  await expect(sidebar.getByRole("button", { name: "Search" })).toBeVisible();
  await expect(sidebar.getByRole("button", { name: "New top-level folder" })).toHaveCount(0);
  await sidebar.getByRole("button", { name: "Back to workspace navigation" }).click();
  await expect(sidebar).toHaveCSS("width", "56px");
  await expect(sidebar.getByRole("link", { name: "Overview" })).toBeVisible();
  await expect(sidebar.getByRole("button", { name: "Pages", exact: true })).toHaveAttribute("aria-current", "page");
  await expect(sidebar.getByRole("button", { name: "Pages", exact: true })).toBeFocused();

  await sidebar.getByRole("link", { name: "Overview" }).click();
  await expect(page).toHaveURL(`/workspaces/${PRIMARY_WORKSPACE_ID}`);
  await expect(page.getByRole("main").getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  const overviewUrl = page.url();
  await expect(sidebar.getByRole("button", { name: "Pages", exact: true })).not.toHaveAttribute("aria-current");
  await sidebar.getByRole("button", { name: "Pages", exact: true }).click();
  await expect(page).toHaveURL(overviewUrl);
  await expect(sidebar).toHaveAttribute("data-collapsed", "false");
  await expect(sidebar).toHaveCSS("width", "256px");
  await expect(sidebar.getByRole("navigation", { name: "Pages navigation" })).toBeVisible();
  await expect(sidebar.getByRole("link", { name: "Weekly revenue" })).toBeVisible();
  await expect(sidebar.getByRole("button", { name: "Back to workspace navigation" })).toBeFocused();
  await expect(page.getByRole("main").getByRole("heading", { level: 1 })).toHaveText("Overview");
});

test("Search finds resources by name and opens the chosen one", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await seedWorkspaceShell(page);
  const searches: string[] = [];
  await page.route(`**/v2/workspaces/${PRIMARY_WORKSPACE_ID}/search?**`, (route) => {
    searches.push(new URL(route.request().url()).searchParams.get("query") ?? "");
    return json(route, {
      dashboards: [{ dashboardId: DASHBOARD_ID, title: "Revenue overview" }],
      metrics: [{ metricId: "66666666-6666-7666-8666-666666666666", name: "revenue", label: "Revenue", type: "SIMPLE" }],
      semanticModels: [],
      tables: [{ connectorId: "77777777-7777-4777-8777-777777777777", connectorName: "Production Postgres", connectorType: "POSTGRESQL", catalogName: null, schemaName: "billing", tableName: "revenue_lines", relationKind: "TABLE" }],
    });
  });
  await page.route(`**/v2/workspaces/${PRIMARY_WORKSPACE_ID}/dashboards/${DASHBOARD_ID}**`, (route) => json(route, {}, 404));
  await page.goto(`/workspaces/${PRIMARY_WORKSPACE_ID}`);

  await page.getByTestId("sidebar-search").click();
  const palette = page.getByRole("dialog", { name: "Search" });
  await expect(palette).toBeVisible();
  await expect(palette.getByRole("group", { name: "Go to" })).toBeVisible();
  await palette.getByRole("combobox", { name: "Search or go to" }).fill("revenue");
  await expect(palette.getByRole("group", { name: "Dashboards" }).getByRole("option", { name: /Revenue overview/ })).toBeVisible();
  await expect(palette.getByRole("group", { name: "Pages" }).getByRole("option", { name: /Weekly revenue/ })).toBeVisible();
  await expect(palette.getByRole("group", { name: "Metrics" }).getByRole("option", { name: /Revenue/ })).toBeVisible();
  await expect(palette.getByRole("group", { name: "Tables" }).getByRole("option", { name: /billing\.revenue_lines/ })).toBeVisible();
  expect(searches).toEqual(["revenue"]);
  await page.screenshot({ path: evidencePath("screenshots/command-palette-resource-search.png") });

  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(`/workspaces/${PRIMARY_WORKSPACE_ID}/dashboards/${DASHBOARD_ID}`);
});

test.describe("workspace switcher", () => {
  const many = [
    WORKSPACES[0],
    ...Array.from({ length: 20 }, (_, index) => ({
      id: `organization-workspace-${index}`, organizationId: "organization-acme", name: `Analytics workspace ${index + 1}`, publicPages: false, reportingTimeZone: "UTC", role: "VIEWER",
    })),
  ];

  test("offers a search field past eight workspaces and keeps the list scrollable above the fixed actions", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await seedWorkspaceShell(page, many);
    await page.goto(`/workspaces/${PRIMARY_WORKSPACE_ID}`);
    await page.getByTestId("workspace-switcher").click();
    const list = page.getByTestId("workspace-switcher-list");
    await expect(page.getByTestId("workspace-option-organization-workspace-0")).toBeVisible();
    await expect(list).not.toHaveClass(/is-scrolling/);
    expect(await list.evaluate((element) => element.clientHeight)).toBe(360);
    expect(await list.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
    await page.screenshot({ path: evidencePath("screenshots/workspace-switcher-overflow-idle.png"), animations: "disabled" });
    await list.hover();
    await page.mouse.wheel(0, 300);
    await expect(list).toHaveClass(/is-scrolling/);
    await expect(list).toHaveCSS("scrollbar-color", "rgba(108, 112, 116, 0.42) rgba(0, 0, 0, 0)");
    await expect(list).not.toHaveClass(/is-scrolling/);
    await expect(page.getByRole("menuitem", { name: "New Organization", exact: true })).toBeInViewport();

    const search = page.getByRole("searchbox", { name: "Search workspaces" });
    await search.fill("workspace 19");
    await expect(page.getByTestId("workspace-option-organization-workspace-18")).toBeVisible();
    await expect(page.getByTestId("workspace-option-organization-workspace-0")).toHaveCount(0);
    await expect(page.getByTestId(`workspace-option-${PRIMARY_WORKSPACE_ID}`)).toHaveCount(0);
    await expectSettledMenu(page);
    await page.screenshot({ path: evidencePath("screenshots/workspace-switcher-search.png"), animations: "disabled" });
  });

  // The drawer's scroll lock admits only its own subtree, and a short window leaves less room than the list wants.
  test("scrolls inside the mobile navigation drawer and keeps the actions within a short window", async ({ page }) => {
    await page.setViewportSize({ width: 720, height: 600 });
    await seedWorkspaceShell(page, many);
    await page.goto(`/workspaces/${PRIMARY_WORKSPACE_ID}`);
    await page.getByRole("button", { name: "Open workspace navigation" }).click();
    const drawer = page.getByRole("dialog", { name: "Workspace navigation" });
    await drawer.getByTestId("workspace-switcher").click();

    const menu = page.getByRole("menu", { name: "Switch workspace" });
    const list = page.getByTestId("workspace-switcher-list");
    const lastWorkspace = page.getByTestId("workspace-option-organization-workspace-19");
    await expect(page.getByTestId("workspace-option-organization-workspace-0")).toBeVisible();
    await expect(lastWorkspace).not.toBeInViewport();
    expect(await list.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
    for (const action of ["All workspaces", "New Organization"]) {
      await expect(menu.getByRole("menuitem", { name: action, exact: true })).toBeInViewport({ ratio: 1 });
    }

    await list.hover();
    await page.mouse.wheel(0, 2000);
    await expect.poll(() => list.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
    await expect(lastWorkspace).toBeInViewport({ ratio: 1 });
    await lastWorkspace.click({ trial: true });
    for (const action of ["All workspaces", "New Organization"]) {
      await expect(menu.getByRole("menuitem", { name: action, exact: true })).toBeInViewport({ ratio: 1 });
    }
    await page.screenshot({ path: evidencePath("screenshots/workspace-switcher-mobile-drawer-scrolled.png"), animations: "disabled" });

    await page.keyboard.press("Escape");
    await expect(menu).toHaveCount(0);
    await expect(drawer).toBeVisible();
    await expect(drawer.getByTestId("workspace-switcher")).toBeFocused();

    // Inside the drawer the menu keeps its keyboard model and hands focus back to the drawer.
    await page.keyboard.press("Enter");
    await expect(menu).toBeVisible();
    await page.keyboard.press("End");
    await expect(menu.getByRole("menuitem", { name: "New Organization", exact: true })).toBeFocused();
    await page.keyboard.press("ArrowUp");
    await expect(menu.getByRole("menuitem", { name: "Organization settings", exact: true })).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(menu).toHaveCount(0);
    await expect(drawer.getByTestId("workspace-switcher")).toBeFocused();
  });
});
