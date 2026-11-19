import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "./support/test";
import type { Page, Route } from "@playwright/test";
import { installCatalogApi } from "./catalog-api-fixture";
import { evidencePath } from "./evidence";
import { json } from "./support/session";

const WORKSPACE_ID = "83000000-0000-4000-8000-000000000001";
const SNOWFLAKE_ID = "83000000-0000-4000-8000-000000000010";

type SelectionPayload = {
  selectedSchemas: Array<{
    schemaName: string;
    relations:
      | { kind: "ALL_RELATIONS" }
      | { kind: "SELECTED_RELATIONS"; relationNames: string[] };
  }>;
};

test.use({ viewport: { width: 1440, height: 900 } });

async function chooseConnector(page: Page, option: string) {
  await page.getByRole("combobox", { name: "Connector" }).click();
  await page.getByRole("option", { name: option }).click();
}

function manageCatalogs(page: Page) {
  return page.getByTestId("catalog-command-bar").getByRole("button", { name: "Manage catalogs" });
}

async function expectNoHorizontalOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(await page.getByTestId("catalog-command-bar").evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  expect(await page.getByTestId("catalog-browser-scroll").evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
}

test("production catalog keeps refresh scope independent and exposes live errors", async ({ page }) => {
  await installCatalogApi(page);
  await page.goto("/dev/catalog-production-demo");

  await expect(page.getByTestId("workspace-page-header").getByText("Sources", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Catalog", exact: true })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Connector" })).toHaveText("Finance warehouse");
  await expect(page.getByRole("combobox", { name: "Catalog" })).toHaveText("analytics");
  await expect(page.getByTestId("catalog-command-bar").getByRole("button", { name: /^Choose tables/ })).toContainText("3");
  await expect(page.getByRole("button", { name: /Find table/ })).toBeVisible();
  await expect(page.getByTestId("catalog-refresh-progress")).toContainText("Refreshing · 19 tables loaded · 13 in progress ·");
  await expect(page.getByRole("button", { name: "Cancel refresh" })).toBeEnabled();
  await expect(page.getByRole("button", { name: /Recent refreshes/ })).not.toContainText("errors");
  const commandBarBox = await page.getByTestId("catalog-command-bar").boundingBox();
  const browserScrollBox = await page.getByTestId("catalog-browser-scroll").boundingBox();
  expect(browserScrollBox?.y).toBeCloseTo((commandBarBox?.y ?? 0) + (commandBarBox?.height ?? 0), 0);

  await page.getByText("finance", { exact: true }).click();
  await expect(page.locator("button").filter({ hasText: /^finance/ }).first()).toContainText("Last refresh failed for 1 table");
  await expect(page.locator('[data-layout="columns"]')).toBeVisible();
  const desktopDetailWidth = await page.getByTestId("catalog-relation-detail").evaluate((element) => element.getBoundingClientRect().width);
  expect(desktopDetailWidth).toBeGreaterThanOrEqual(600);
  await expectNoHorizontalOverflow(page);
  await page.setViewportSize({ width: 1280, height: 900 });
  await expect.poll(() => page.getByTestId("catalog-relation-detail").evaluate((element) => element.getBoundingClientRect().width)).toBeGreaterThanOrEqual(420);
  await expectNoHorizontalOverflow(page);
  await page.setViewportSize({ width: 900, height: 900 });
  await expect(page.getByRole("button", { name: "Schemas", exact: true })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await page.setViewportSize({ width: 1440, height: 900 });

  await chooseConnector(page, "Product database · PostgreSQL");
  await expect(page.getByRole("combobox", { name: "Catalog" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Refresh", exact: true })).toBeEnabled();
  await chooseConnector(page, "Finance warehouse · Snowflake");
  await expect(page.getByRole("combobox", { name: "Catalog" })).toHaveText("analytics");
  await expect(page.getByRole("button", { name: "Cancel refresh" })).toBeVisible();

  await page.getByRole("button", { name: /Recent refreshes/ }).click();
  const dialog = page.getByRole("dialog", { name: "Recent refreshes" });
  await expect(dialog.getByText("Running", { exact: true })).toBeVisible();
  await expect(dialog).toContainText("13 in progress");
  await expect(dialog).toContainText("2 errors");
  await dialog.getByText("Running", { exact: true }).locator("xpath=ancestor::button[1]").click();
  await expect(dialog).toContainText("analytics.finance.monthly_close");
  await expect(dialog).toContainText("Metadata query exceeded the warehouse statement timeout.");
  await expect(dialog.getByText("1–10 of 14")).toBeVisible();

  const accessibility = await new AxeBuilder({ page })
    .include('[role="dialog"]')
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);

  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.addStyleTag({ content: "*, *::before, *::after { animation: none !important; transition: none !important; }" });
  await page.screenshot({ path: evidencePath("../screenshots/2026-08-21-catalog-production-running.png"), fullPage: true });

  await dialog.getByRole("button", { name: "Close recent refreshes" }).click();
  await chooseConnector(page, "Product database · PostgreSQL");
  await page.getByText("public", { exact: true }).click();
  await page.getByText("campaigns", { exact: true }).click();
  await expect(page.getByRole("heading", { name: "campaigns" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Copy name" })).toBeVisible();
  await expect(page).toHaveURL(/schema=public&table=campaigns/);
  await expect(page.getByRole("link", { name: "Query in Explore" })).toHaveAttribute("href", `/workspaces/${WORKSPACE_ID}/explore?connector=83000000-0000-4000-8000-000000000020&schema=public&table=campaigns`);
  await expect(page.getByRole("link", { name: "Describe this table" })).toHaveAttribute("href", `/workspaces/${WORKSPACE_ID}/semantic-models/new?connector=83000000-0000-4000-8000-000000000020&schema=public&table=campaigns`);
  await expect(page.getByRole("link", { name: "Campaign performance" })).toHaveAttribute("href", `/workspaces/${WORKSPACE_ID}/semantic-models/83000000-0000-7000-8000-000000000060`);
  await expect(page.getByTestId("relation-detail-facts")).toContainText(/Primary key\s*campaigns_pkey/);
  await expect(page.getByRole("link", { name: "FK → public.customers.id" })).toHaveAttribute("href", `/workspaces/${WORKSPACE_ID}/catalog?connector=83000000-0000-4000-8000-000000000020&schema=public&table=customers`);
  await expect(page.getByRole("link", { name: "View lineage" })).toHaveAttribute("href", `/workspaces/${WORKSPACE_ID}/lineage?kind=RELATION&id=83000000-0000-4000-8000-000000000099`);
  await expect(page.getByText("PK", { exact: true })).toBeVisible();
  const customerColumn = page.locator(".relation-column-row").filter({ hasText: "customer_id" });
  await expect(customerColumn).toContainText("FK → public.customers.id");
  await expect(customerColumn).toContainText("FK → public.accounts.owner_id");
  await page.screenshot({ path: evidencePath("../screenshots/2026-08-21-catalog-production-relation.png"), fullPage: true });
});

test("production catalog cancels the active refresh through Cancelling to Cancelled", async ({ page }) => {
  const catalogApi = await installCatalogApi(page);
  await page.goto("/dev/catalog-production-demo");

  const cancelRefresh = page.getByRole("button", { name: "Cancel refresh" });
  await expect(cancelRefresh).toBeEnabled();
  const cancelRequest = page.waitForRequest((request) => request.method() === "POST" && request.url().endsWith(":cancel"));
  await cancelRefresh.click();
  expect(new URL((await cancelRequest).url()).pathname)
    .toBe(`/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${SNOWFLAKE_ID}/ingests/${catalogApi.runningJobId}:cancel`);

  const progress = page.getByTestId("catalog-refresh-progress");
  await expect(progress).toContainText("Cancelling ·");
  await expect(cancelRefresh).toBeDisabled();
  await expect(page.getByRole("button", { name: /^Recent refreshes\s*, refresh cancelling$/ })).toBeVisible();

  catalogApi.finishCancellation();
  await expect(page.getByRole("button", { name: "Refresh", exact: true })).toBeEnabled();
  await expect(progress).toHaveCount(0);
  await page.getByRole("button", { name: /^Recent refreshes/ }).click();
  const recentRefreshes = page.getByRole("dialog", { name: "Recent refreshes" });
  await expect(recentRefreshes.getByText("Cancelled", { exact: true }).first()).toBeVisible();
  await expect(recentRefreshes.getByText("Running", { exact: true })).toHaveCount(0);
});

test("production catalog shows which relations are still loading during a refresh", async ({ page }) => {
  await installCatalogApi(page);
  await page.goto("/dev/catalog-production-demo");
  await expect(page.getByTestId("catalog-refresh-progress")).toBeVisible();

  await page.getByText("marketing", { exact: true }).click();
  const campaigns = page.locator("button").filter({ hasText: "campaigns" }).first();
  const audiencesView = page.locator("button").filter({ hasText: "audiences_view" }).first();
  await expect(campaigns).toContainText("Last refreshed");
  await expect(audiencesView).toContainText("Loading metadata…");

  const accessibility = await new AxeBuilder({ page })
    .include("main")
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.addStyleTag({ content: "*, *::before, *::after { animation: none !important; transition: none !important; }" });
  await page.screenshot({ path: evidencePath("screenshots/catalog-job-observability.png") });
});

test("catalog drills down one level at a time on a phone", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installCatalogApi(page);
  await page.goto("/dev/catalog-production-demo");

  await expect(page.getByRole("combobox", { name: "Connector" })).toHaveText("Finance warehouse");
  await expect(page.locator('[data-layout="single"]')).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: evidencePath("screenshots/joo-91-catalog-mobile-command-bar.png") });

  await chooseConnector(page, "Product database · PostgreSQL");
  await page.getByText("public", { exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Search schemas" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Schemas", exact: true })).toBeVisible();
  await page.getByText("campaigns", { exact: true }).click();
  await expect(page.getByRole("heading", { name: "campaigns" })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: evidencePath("screenshots/joo-91-catalog-mobile-detail.png") });

  await page.getByRole("button", { name: "public", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Search tables" })).toBeVisible();
  await page.getByRole("button", { name: "Schemas", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Search schemas" })).toBeVisible();
});

test("catalog explains the first refresh and keeps one primary action", async ({ page }) => {
  const postgresId = "83000000-0000-4000-8000-000000000020";
  await installCatalogApi(page);
  await page.route(`**/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${postgresId}/**`, async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/schemas")) return json(route, { schemas: [], totalCount: 0, page: 1, pageSize: 50 });
    if (path.endsWith("/ingests")) return json(route, { jobs: [], totalCount: 0, page: 1, pageSize: 1 });
    return route.fallback();
  });
  await page.goto("/dev/catalog-production-demo");
  await chooseConnector(page, "Product database · PostgreSQL");

  await expect(page.getByRole("heading", { name: "Ready to load 1 schema" })).toBeVisible();
  await expect(page.getByText("Refresh reads table and column metadata from the warehouse. Large schemas can take a few minutes.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh now" })).toBeVisible();
  await expect(page.getByTestId("catalog-command-bar").getByRole("button", { name: "Refresh", exact: true })).toHaveClass(/hover:bg-surface/);
  await page.screenshot({ path: evidencePath("screenshots/joo-91-catalog-ready-to-load.png") });
});

test("catalog selection drawer authors one exact catalog entry", async ({ page }) => {
  await page.route("**/v2/workspaces/**/catalog/connectors/**/selection/options**", async (route) => {
    const url = new URL(route.request().url());
    const schemaName = url.searchParams.get("schemaName");
    await json(route, schemaName
      ? {
          parentKind: "SCHEMA",
          childKind: "RELATION",
          catalogName: "analytics",
          schemaName,
          fetchedAt: "2026-08-21T01:00:00Z",
          options: [
            { name: "campaigns", relationKind: "TABLE", available: true },
            { name: "campaign_daily", relationKind: "VIEW", available: true },
          ],
          totalCount: 2,
          page: 1,
          pageSize: 50,
          latestJob: null,
        }
      : {
          parentKind: "CATALOG",
          childKind: "SCHEMA",
          catalogName: "analytics",
          schemaName: null,
          fetchedAt: "2026-08-21T01:00:00Z",
          options: [
            { name: "finance", relationKind: null, available: true },
            { name: "marketing", relationKind: null, available: true },
            { name: "retired", relationKind: null, available: false },
          ],
          totalCount: 3,
          page: 1,
          pageSize: 50,
          latestJob: null,
        });
  });

  await page.goto("/dev/catalog-selection-demo");
  const drawer = page.getByTestId("catalog-selection-drawer");
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole("heading", { name: "Choose tables to load" })).toBeVisible();
  await expect(drawer.getByText("marketing", { exact: true })).toBeVisible();
  await expect(drawer.getByText("retired", { exact: true })).toHaveClass(/line-through/);
  await expect(drawer.getByTestId("catalog-selection-count")).toHaveText("2 tables selected");

  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.addStyleTag({ content: "*, *::before, *::after { animation: none !important; transition: none !important; }" });
  await page.screenshot({ path: evidencePath("../screenshots/2026-09-02-catalog-entry-selection.png"), fullPage: true });
});

test("catalog selection keeps page and search choices through save and reopen", async ({ page }) => {
  await installCatalogApi(page);

  const relationNames = Array.from({ length: 12_480 }, (_, index) => `event_${String(index + 1).padStart(5, "0")}`);
  let savedSelection: SelectionPayload = { selectedSchemas: [] };
  const savedSelectionRequests: SelectionPayload[] = [];

  await page.route(`**/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${SNOWFLAKE_ID}/catalogs/analytics/selection`, async (route) => {
    if (route.request().method() === "PUT") {
      savedSelection = route.request().postDataJSON() as SelectionPayload;
      savedSelectionRequests.push(savedSelection);
    }
    await json(route, { ...savedSelection, committedAt: "2026-09-08T01:00:00Z" });
  });
  await page.route(`**/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${SNOWFLAKE_ID}/selection/options**`, async (route) => {
    const url = new URL(route.request().url());
    if (url.searchParams.get("parentKind") !== "SCHEMA" || url.searchParams.get("schemaName") !== "marketing") {
      await route.fallback();
      return;
    }
    const query = url.searchParams.get("query") ?? "";
    const pageNumber = Number(url.searchParams.get("page") ?? "1");
    const filteredRelationNames = relationNames.filter((name) => name.includes(query));
    const pageOptions = filteredRelationNames.slice((pageNumber - 1) * 50, (pageNumber - 1) * 50 + 50);
    await json(route, {
      parentKind: "SCHEMA",
      childKind: "RELATION",
      catalogName: "analytics",
      schemaName: "marketing",
      fetchedAt: "2026-09-08T01:00:00Z",
      options: pageOptions.map((name) => ({ name, relationKind: "TABLE", available: true })),
      totalCount: filteredRelationNames.length,
      page: pageNumber,
      pageSize: 50,
      latestJob: null,
    });
  });

  await page.goto("/dev/catalog-production-demo");
  await expect(page.getByRole("combobox", { name: "Catalog" })).toHaveText("analytics");
  await page.getByTestId("catalog-command-bar").getByRole("button", { name: /^Choose tables/ }).click();

  const drawer = page.getByTestId("catalog-selection-drawer");
  const marketing = drawer.locator('[data-testid="catalog-option-list-relation"][data-option-target="marketing"]');
  await drawer.getByRole("button", { name: "Expand marketing schema" }).click();
  await expect(marketing.getByText("12,480 items").first()).toBeVisible();
  await expect(marketing.getByText("Page 1 of 250")).toBeVisible();

  await marketing.getByRole("button", { name: "Next" }).click();
  const pageTwoSelection = marketing.getByRole("checkbox", { name: "event_00051 table" });
  await expect(pageTwoSelection).toBeVisible();
  await pageTwoSelection.click();
  await expect(pageTwoSelection).toHaveAttribute("aria-checked", "true");

  const relationSearch = marketing.getByRole("textbox", { name: "Search tables" });
  await relationSearch.fill("event_0012");
  await expect(marketing.getByText("event_00120", { exact: true })).toBeVisible();
  const searchedSelection = marketing.getByRole("checkbox", { name: "event_00120 table" });
  await searchedSelection.click();
  await expect(searchedSelection).toHaveAttribute("aria-checked", "true");

  await drawer.getByRole("button", { name: "Save only" }).click();
  await expect(drawer).not.toBeVisible();
  expect(savedSelectionRequests).toHaveLength(1);
  expect(savedSelectionRequests[0]).toEqual({
    selectedSchemas: [{
      schemaName: "marketing",
      relations: { kind: "SELECTED_RELATIONS", relationNames: ["event_00051", "event_00120"] },
    }],
  });

  await page.getByTestId("catalog-command-bar").getByRole("button", { name: /^Choose tables/ }).click();
  const reopenedDrawer = page.getByTestId("catalog-selection-drawer");
  const reopenedMarketing = reopenedDrawer.locator('[data-testid="catalog-option-list-relation"][data-option-target="marketing"]');
  await reopenedDrawer.getByRole("button", { name: "Expand marketing schema" }).click();
  await expect(reopenedMarketing.getByText("12,480 items").first()).toBeVisible();
  await reopenedMarketing.getByRole("button", { name: "Next" }).click();
  const reopenedPageTwoSelection = reopenedMarketing.getByRole("checkbox", { name: "event_00051 table" });
  await expect(reopenedPageTwoSelection).toHaveAttribute("aria-checked", "true");
  const reopenedPageTwoSelectionState = await reopenedPageTwoSelection.getAttribute("aria-checked");
  const reopenedSearch = reopenedMarketing.getByRole("textbox", { name: "Search tables" });
  await reopenedSearch.fill("event_0012");
  const reopenedSearchedSelection = reopenedMarketing.getByRole("checkbox", { name: "event_00120 table" });
  await expect(reopenedSearchedSelection).toHaveAttribute("aria-checked", "true");
  const reopenedSearchedSelectionState = await reopenedSearchedSelection.getAttribute("aria-checked");
  await expect(reopenedDrawer.getByRole("button", { name: "Save only" })).toBeDisabled();

  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.addStyleTag({ content: "*, *::before, *::after { animation: none !important; transition: none !important; }" });
  await page.screenshot({ path: evidencePath("../screenshots/catalog-discovery/relations-page-search-reopened.png"), fullPage: true });
  console.log("CAT07_RECEIPT", JSON.stringify({
    savedSelection: savedSelectionRequests[0],
    reopenedSelection: { pageTwo: reopenedPageTwoSelectionState, searched: reopenedSearchedSelectionState },
    saveDisabledAfterReopen: await reopenedDrawer.getByRole("button", { name: "Save only" }).isDisabled(),
  }));
});

test("Catalog Manager owns connector membership and explicitly refreshes candidates", async ({ page }) => {
  await installCatalogApi(page);
  await page.goto("/dev/catalog-production-demo");

  const catalogPicker = page.getByRole("combobox", { name: "Catalog" });
  await expect(catalogPicker).toHaveText("analytics");
  await catalogPicker.click();
  await expect(page.getByRole("option", { name: "analytics" })).toBeVisible();
  await expect(page.getByRole("option", { name: "retired_catalog" })).toHaveCount(0);
  await page.keyboard.press("Escape");

  const manageButton = manageCatalogs(page);
  await manageButton.click();
  const manager = page.getByTestId("catalog-manager-dialog");
  await expect(manager).toBeVisible();
  await expect(manager.getByRole("heading", { name: "Finance warehouse" })).toBeVisible();
  await expect(manager.getByRole("checkbox", { name: "analytics" })).toBeChecked();
  await expect(manager.getByRole("checkbox", { name: "raw" })).not.toBeChecked();
  await expect(manager.getByText("retired_catalog", { exact: true })).not.toBeVisible();

  await manager.getByRole("button", { name: "Refresh catalog list" }).click();
  await expect(page).toHaveURL(/dev\/catalog-production-demo/);
  await expect(manager.getByRole("button", { name: "Refresh catalog list" })).toBeEnabled();
  await page.screenshot({ path: evidencePath("../screenshots/2026-09-03-catalog-manager-open.png"), fullPage: true });
  await manager.getByRole("checkbox", { name: "raw" }).click();
  await manager.getByRole("button", { name: "Save selection" }).click();
  await expect(manager).not.toBeVisible();
  await catalogPicker.click();
  await expect(page.getByRole("option", { name: "raw" })).toBeVisible();
  await page.keyboard.press("Escape");

  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.addStyleTag({ content: "*, *::before, *::after { animation: none !important; transition: none !important; }" });
  await page.screenshot({ path: evidencePath("../screenshots/2026-09-03-catalog-manager.png"), fullPage: true });
});

test("Catalog Manager keeps its draft visible when the server rejects a save", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 409, url: /\/catalog-selection$/, reason: "The fixture rejects the save with a revision conflict." });
  await installCatalogApi(page);
  await page.route(`**/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${SNOWFLAKE_ID}/catalog-selection`, async (route) => {
    if (route.request().method() === "PUT") {
      await route.fulfill({ status: 409, contentType: "application/problem+json", body: JSON.stringify({
        type: "https://monomer.cc/problems/catalog-selection-active-ingestion",
        title: "Conflict",
        status: 409,
        detail: "Finish or cancel the active Catalog ingestion before changing Catalog selection.",
        instance: "/catalog-selection",
        code: "CATALOG_SELECTION_ACTIVE_INGESTION",
        requestId: "request-catalog-selection",
        traceId: "trace-catalog-selection",
        retryable: false,
      }) });
      return;
    }
    await route.fallback();
  });
  await page.goto("/dev/catalog-production-demo");

  await manageCatalogs(page).click();
  const manager = page.getByTestId("catalog-manager-dialog");
  await manager.getByRole("checkbox", { name: "raw" }).click();
  await manager.getByRole("button", { name: "Save selection" }).click();

  await expect(manager).toBeVisible();
  await expect(manager).toHaveAttribute("role", "dialog");
  await expect(manager.getByRole("alert")).toContainText("Finish or cancel the active Catalog ingestion");
  await expect(manager.getByRole("checkbox", { name: "raw" })).toBeChecked();
});

test("Catalog Manager distinguishes never-fetched and fetched-empty option states", async ({ page }) => {
  await installCatalogApi(page, {
    initialCatalogOptions: "never-fetched",
    initialSelectedCatalogNames: [],
  });
  await page.goto("/dev/catalog-production-demo");

  await expect(page.getByRole("heading", { name: "Choose what to load from Finance warehouse" })).toBeVisible();
  await expect(page.getByTestId("catalog-browser-scroll").getByRole("button", { name: "Choose tables" })).toBeVisible();
  const managerButton = manageCatalogs(page);
  await managerButton.click();
  const manager = page.getByTestId("catalog-manager-dialog");
  await expect(manager.getByText(/0 available/)).toBeVisible();
  await expect(manager.getByText("No catalogs were discovered", { exact: true })).toBeVisible();
  await expect(manager.getByText("Not loaded", { exact: true })).toHaveCount(0);
  await expect(manager.getByRole("button", { name: "Save selection" })).toBeDisabled();
  await page.screenshot({ path: evidencePath("../screenshots/catalog-discovery/fetched-empty.png"), fullPage: true });
});

test("discovery manager preserves selections across initial discovery, reopen, pagination and refresh", async ({ page }) => {
  await installCatalogApi(page, { initialSelectedCatalogNames: ["analytics", "saved_elsewhere"] });
  let fetched = false;
  let completeEmpty = false;
  let failure = false;
  let refreshCount = 0;
  let releaseRefresh: (() => void) | undefined;
  const refreshTargets: unknown[] = [];
  const job = () => ({ jobId: "discovery-job", parentKind: "CONNECTOR", childKind: "CATALOG", catalogName: null, schemaName: null, state: failure ? "COMPLETED_WITH_ERRORS" : "COMPLETED", requestedAt: "2026-09-14T00:00:00Z", terminalAt: "2026-09-14T00:01:00Z", failureCode: failure ? "WAREHOUSE_UNAVAILABLE" : null, safeMessage: failure ? "Warehouse unavailable" : null });
  await page.route(`**/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${SNOWFLAKE_ID}/selection/options**`, async (route) => {
    const url = new URL(route.request().url());
    const names = (completeEmpty ? [] : ["analytics", "raw", ...Array.from({ length: 50 }, (_, index) => `warehouse_${String(index).padStart(3, "0")}`)]).filter((name) => name.includes(url.searchParams.get("query") ?? ""));
    const pageNumber = Number(url.searchParams.get("page") ?? 1);
    await json(route, { parentKind: "CONNECTOR", childKind: "CATALOG", catalogName: null, schemaName: null, fetchedAt: fetched ? "2026-09-14T00:00:00Z" : null, options: fetched ? names.slice((pageNumber - 1) * 50, (pageNumber - 1) * 50 + 50).map((name) => ({ name, relationKind: null, available: true })) : [], totalCount: fetched ? names.length : 0, page: pageNumber, pageSize: 50, latestJob: fetched ? job() : null });
  });
  await page.route(`**/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${SNOWFLAKE_ID}/selection/list-refresh-jobs`, async (route) => {
    refreshCount += 1;
    refreshTargets.push(route.request().postDataJSON());
    if (refreshCount === 1) await new Promise<void>((resolve) => { releaseRefresh = resolve; });
    fetched = true;
    await json(route, job(), 202);
  });
  await page.goto("/dev/catalog-production-demo");
  const openManager = manageCatalogs(page);
  await openManager.click();
  const manager = page.getByTestId("catalog-manager-dialog");
  await expect.poll(() => refreshCount).toBe(1);
  await expect(manager.getByLabel("Selected catalogs")).toContainText("saved_elsewhere");
  await expect(manager).not.toContainText("0 available");
  await page.screenshot({ path: evidencePath("../screenshots/catalog-discovery/initial-loading-selected.png") });
  releaseRefresh?.();
  await expect(manager.getByRole("checkbox", { name: "raw" })).toBeVisible();
  await manager.getByRole("button", { name: "Close catalog manager" }).click();
  await openManager.click();
  await expect(manager.getByRole("checkbox", { name: "analytics" })).toBeChecked();
  expect(refreshCount).toBe(1);
  await page.screenshot({ path: evidencePath("../screenshots/catalog-discovery/cached-reopen.png") });
  await manager.getByRole("checkbox", { name: "raw" }).click();
  await manager.getByRole("button", { name: "Next", exact: true }).click();
  await expect(manager.getByRole("checkbox", { name: "warehouse_048" })).toBeVisible();
  await manager.getByRole("checkbox", { name: "warehouse_048" }).click();
  await manager.getByRole("textbox", { name: "Search available catalogs" }).fill("analytics");
  await expect(manager.getByRole("checkbox", { name: "analytics" })).toBeChecked();
  await expect(manager.getByLabel("Selected catalogs")).toContainText("warehouse_048");
  await expect(manager.getByLabel("Selected catalogs")).toContainText("raw");
  await expect(manager.getByLabel("Selected catalogs")).not.toContainText("Unavailable");
  await page.screenshot({ path: evidencePath("../screenshots/catalog-discovery/pagination-search-selection.png") });
  failure = true;
  await manager.getByRole("button", { name: "Refresh catalog list" }).click();
  await expect(manager.getByRole("alert")).toContainText("Warehouse unavailable");
  await expect(manager.getByLabel("Selected catalogs")).toContainText("warehouse_048");
  await page.screenshot({ path: evidencePath("../screenshots/catalog-discovery/refresh-failure.png") });
  failure = false;
  await manager.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(manager.getByRole("alert")).toHaveCount(0);
  await expect(manager.getByLabel("Selected catalogs")).toContainText("saved_elsewhere");
  await expect(manager.getByRole("button", { name: "Save selection" })).toBeEnabled();
  await manager.getByRole("button", { name: "Remove selected catalog saved_elsewhere" }).click();
  await expect(manager.getByLabel("Selected catalogs")).not.toContainText("saved_elsewhere");
  await page.screenshot({ path: evidencePath("../screenshots/catalog-discovery/refresh-retry.png") });
  const saveRequest = page.waitForRequest((request) => request.method() === "PUT" && request.url().endsWith("/catalog-selection"));
  await manager.getByRole("button", { name: "Save selection" }).click();
  expect((await saveRequest).postDataJSON()).toEqual({ selectedCatalogNames: ["analytics", "raw", "warehouse_048"] });
  await expect(manager).not.toBeVisible();
  await openManager.click();
  await expect(manager.getByLabel("Selected catalogs")).toContainText("warehouse_048");
  await expect(manager.getByRole("button", { name: "Save selection" })).toBeDisabled();
  completeEmpty = true;
  await manager.getByRole("button", { name: "Refresh catalog list" }).click();
  await expect(manager.getByRole("button", { name: "Remove selected catalog raw" })).toContainText("Unavailable");
  await expect(manager.getByRole("button", { name: "Save selection" })).toBeDisabled();
  await page.screenshot({ path: evidencePath("../screenshots/catalog-discovery/complete-snapshot-unavailable.png") });
  expect(refreshCount).toBe(4);
  expect(refreshTargets).toEqual(Array(4).fill({ parentKind: "CONNECTOR", catalogName: null, schemaName: null }));
});

test("discovery drawer expands only requested levels and preserves selection on initial failure", async ({ page }) => {
  const requests: string[] = [];
  const discovered = new Set<string>();
  const jobs = new Map<string, unknown>();
  let failSchema = true;
  await page.route("**/v2/workspaces/**/catalog/connectors/**/selection/options**", async (route) => {
    const url = new URL(route.request().url());
    const parentKind = url.searchParams.get("parentKind") ?? "CATALOG";
    const schemaName = url.searchParams.get("schemaName");
    const fetched = discovered.has(parentKind);
    await json(route, { parentKind, childKind: schemaName ? "RELATION" : "SCHEMA", catalogName: "analytics", schemaName, fetchedAt: fetched ? "2026-09-14T00:00:00Z" : null, options: fetched ? [{ name: schemaName ? "campaigns" : "marketing", relationKind: schemaName ? "TABLE" : null, available: true }] : [], totalCount: fetched ? 1 : 0, page: 1, pageSize: 50, latestJob: jobs.get(parentKind) ?? null });
  });
  await page.route("**/v2/workspaces/**/catalog/connectors/**/selection/list-refresh-jobs", async (route) => {
    const target = route.request().postDataJSON() as { parentKind: string; catalogName: string | null; schemaName: string | null };
    requests.push(target.parentKind);
    const failed = failSchema && target.parentKind === "CATALOG";
    if (!failed) discovered.add(target.parentKind);
    const result = { ...target, jobId: "fixture-job", childKind: target.schemaName ? "RELATION" : "SCHEMA", state: failed ? "COMPLETED_WITH_ERRORS" : "COMPLETED", requestedAt: "2026-09-14T00:00:00Z", terminalAt: "2026-09-14T00:01:00Z", failureCode: failed ? "WAREHOUSE_UNAVAILABLE" : null, safeMessage: failed ? "Warehouse unavailable. Try again." : null };
    jobs.set(target.parentKind, result);
    await json(route, result, 202);
  });
  await page.goto("/dev/catalog-selection-demo");
  const drawer = page.getByTestId("catalog-selection-drawer");
  await expect(drawer.getByRole("alert")).toContainText("Warehouse unavailable. Try again.");
  await expect(drawer.getByLabel("Selected schemas")).toContainText("marketing (campaigns, campaign_daily)");
  await expect(drawer).not.toContainText("No schemas found");
  await expect(drawer).not.toContainText("0 items");
  expect(requests).toEqual(["CATALOG"]);
  await page.screenshot({ path: evidencePath("../screenshots/catalog-discovery/schema-initial-failure.png") });
  failSchema = false;
  await drawer.getByRole("button", { name: "Try again", exact: true }).click();
  await expect(drawer.getByRole("button", { name: "Expand marketing schema" })).toBeVisible();
  expect(requests).toEqual(["CATALOG", "CATALOG"]);
  await drawer.getByRole("button", { name: "Expand marketing schema" }).click();
  await expect(drawer.getByRole("checkbox", { name: "campaigns table" })).toBeChecked();
  await expect(drawer.getByLabel("Selected tables in marketing")).toContainText("campaign_daily");
  expect(requests).toEqual(["CATALOG", "CATALOG", "SCHEMA"]);
  await page.screenshot({ path: evidencePath("../screenshots/catalog-discovery/relation-existing-selection.png") });
  await drawer.getByRole("button", { name: "Collapse marketing schema" }).click();
  await drawer.getByRole("button", { name: "Expand marketing schema" }).click();
  await expect(drawer.getByRole("checkbox", { name: "campaigns table" })).toBeChecked();
  expect(requests).toEqual(["CATALOG", "CATALOG", "SCHEMA"]);
});

for (const role of ["OWNER", "EXPLORER"] as const) {
  test(`unclassified Catalog metadata failure preserves cached selection and offers ${role} recovery`, async ({ page }) => {
    await installCatalogApi(page);
    let retries = 0;
    const failedJob = {
      jobId: "83000000-0000-4000-8000-000000000099", parentKind: "CATALOG", childKind: "SCHEMA",
      catalogName: "analytics", schemaName: null, state: "COMPLETED_WITH_ERRORS",
      requestedAt: "2026-10-04T00:00:00Z", terminalAt: "2026-10-04T00:00:01Z",
      failureCode: "BIGQUERY_EXECUTION_FAILED", safeMessage: "Project #123456789012 has been deleted.",
    };
    await page.route("**/v2/workspaces/**/catalog/connectors/**/selection/options**", async (route) => {
      await json(route, {
        parentKind: "CATALOG", childKind: "SCHEMA", catalogName: "analytics", schemaName: null,
        fetchedAt: "2026-10-03T00:00:00Z", options: [{ name: "marketing", relationKind: null, available: true }],
        totalCount: 1, page: 1, pageSize: 50, latestJob: failedJob,
      });
    });
    await page.route("**/v2/workspaces/**/catalog/connectors/**/selection/list-refresh-jobs", async (route) => {
      retries += 1;
      await json(route, failedJob, 202);
    });
    await page.goto(`/dev/catalog-production-demo?role=${role}`);
    await page.getByTestId("catalog-command-bar").getByRole("button", { name: /^Choose tables/ }).click();
    const drawer = page.getByTestId("catalog-selection-drawer");
    const recovery = drawer.getByTestId("connection-recovery");
    await expect(recovery).toContainText("Monomer couldn't read this from the warehouse.");
    await expect(recovery).toContainText(failedJob.safeMessage);
    await expect(recovery).toContainText("Showing the last fetched list.");
    await expect(drawer.getByRole("checkbox", { name: "marketing schema" })).toBeChecked();
    if (role === "OWNER") {
      for (const [label, mode] of [["Edit settings", "revise"], ["Replace credentials", "rotate"]]) {
        const href = await recovery.getByRole("link", { name: label }).getAttribute("href");
        const destination = new URL(href!, "http://localhost");
        expect(destination.searchParams.get("connector")).toBe(SNOWFLAKE_ID);
        expect(destination.searchParams.get("mode")).toBe(mode);
        expect(destination.searchParams.get("returnTo")).toContain("catalog?connector=");
      }
      await expect(recovery).not.toContainText("Ask a workspace owner");
    } else {
      await expect(recovery).toContainText("Ask a workspace owner (Mina Park, Sam Kim).");
      await expect(recovery.getByRole("link", { name: "Edit settings" })).toHaveCount(0);
      await expect(recovery.getByRole("link", { name: "Replace credentials" })).toHaveCount(0);
      await expect(drawer.getByRole("checkbox", { name: "marketing schema" })).toBeDisabled();
    }
    await recovery.getByRole("button", { name: "Try again", exact: true }).click();
    await expect.poll(() => retries).toBe(1);
    await expect(drawer.getByRole("checkbox", { name: "marketing schema" })).toBeChecked();
    await page.setViewportSize({ width: 390, height: 844 });
    await recovery.scrollIntoViewIfNeeded();
    expect(await recovery.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
    await page.screenshot({ path: evidencePath(`screenshots/catalog-unclassified-recovery-${role.toLowerCase()}.png`) });
  });
}

for (const role of ["EXPLORER", "EDITOR"] as const) {
  test(`${role} can refresh Catalog options; only Editor can save selection`, async ({ page }) => {
    await installCatalogApi(page);
    const refreshes: string[] = [];
    const saves: string[] = [];
    page.on("request", (request) => {
      if (request.method() === "POST" && request.url().includes("/selection/list-refresh-jobs")) refreshes.push(request.url());
      if (request.method() === "PUT" && request.url().endsWith("/catalog-selection")) saves.push(request.url());
    });
    await page.goto(`/dev/catalog-production-demo?role=${role}`);
    await expect(page.getByRole("button", { name: "Cancel refresh" })).toBeEnabled();
    await manageCatalogs(page).click();
    const manager = page.getByTestId("catalog-manager-dialog");
    await expect(manager.getByRole("checkbox", { name: "analytics", exact: true })).toBeChecked();
    await manager.getByRole("button", { name: "Refresh catalog list" }).click();
    await expect.poll(() => refreshes.length).toBe(1);
    await expect(manager.getByRole("button", { name: "Refresh catalog list" })).toBeEnabled();
    if (role === "EXPLORER") {
      await expect(manager.getByRole("checkbox", { name: "raw", exact: true })).toBeDisabled();
      await expect(manager.getByRole("button", { name: "Save selection" })).toHaveCount(0);
      expect(saves).toHaveLength(0);
      await manager.getByRole("button", { name: "Close", exact: true }).click();
      await page.getByTestId("catalog-command-bar").getByRole("button", { name: /^Choose tables/ }).click();
      const drawer = page.getByTestId("catalog-selection-drawer");
      await expect(drawer.getByText(/Requires Editor role/)).toBeVisible();
      await expect(drawer.getByRole("button", { name: "Save only" })).toHaveCount(0);
      await expect(drawer.getByRole("button", { name: "Save and refresh" })).toHaveCount(0);
      await page.screenshot({ path: evidencePath("screenshots/wp4-explorer-selection.png"), fullPage: true });
    } else {
      const raw = manager.getByRole("checkbox", { name: "raw", exact: true });
      await raw.click();
      await expect(raw).toBeChecked();
      const save = manager.getByRole("button", { name: "Save selection" });
      await expect(save).toBeEnabled();
      await save.click();
      await expect.poll(() => saves.length).toBe(1);
      await expect(manager).not.toBeVisible();
    }
  });
}

test.describe("catalog selection demo coverage", () => {
  test.use({ viewport: { width: 1440, height: 1000 } });

  async function expectNoWcagViolations(page: Page, selector: string) {
    const results = await new AxeBuilder({ page })
      .include(selector)
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();
    expect(results.violations).toEqual([]);
  }

  async function prepareDeterministicCapture(page: Page) {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.addStyleTag({
      content: "*, *::before, *::after { animation: none !important; transition: none !important; }",
    });
  }

  test("catalog coverage browses large warehouse lists and preserves cached failures", async ({ page }) => {
    let rawRecovered = false;
    let marketingReloadFailed = false;
    let massiveReloaded = false;
    const completedJob = (catalogName: string | null, schemaName: string | null, failed = false) => ({
      jobId: "83000000-0000-7000-8000-000000000010",
      parentKind: schemaName ? "SCHEMA" : catalogName ? "CATALOG" : "CONNECTOR",
      childKind: schemaName ? "RELATION" : catalogName ? "SCHEMA" : "CATALOG",
      catalogName,
      schemaName,
      state: failed ? "COMPLETED_WITH_ERRORS" : "COMPLETED",
      requestedAt: "2026-08-21T01:02:03Z",
      terminalAt: "2026-08-21T01:02:04Z",
      failureCode: failed ? "WAREHOUSE_UNAVAILABLE" : null,
      safeMessage: failed ? "Reload failed. Showing cached names." : null,
    });
    const fulfillJson = (route: Route, body: unknown, status = 200) => route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(body),
    });

    await page.route("**/v2/workspaces/*/catalog/connectors/*/selection/options**", async (route) => {
      const url = new URL(route.request().url());
      const parentKind = url.searchParams.get("parentKind");
      const catalogName = url.searchParams.get("catalogName");
      const schemaName = url.searchParams.get("schemaName");
      const pageNumber = Number(url.searchParams.get("page") ?? "1");
      if (parentKind === "CONNECTOR") {
        await fulfillJson(route, {
          parentKind: "CONNECTOR", childKind: "CATALOG", catalogName: null, schemaName: null,
          fetchedAt: "2026-08-21T01:00:00Z",
          options: [
            { name: "analytics", relationKind: null, available: true },
            { name: "archive", relationKind: null, available: true },
          ],
          totalCount: 2, page: pageNumber, pageSize: 50, latestJob: completedJob(null, null),
        });
        return;
      }
      if (parentKind === "CATALOG") {
        await fulfillJson(route, {
          parentKind: "CATALOG", childKind: "SCHEMA", catalogName, schemaName: null,
          fetchedAt: "2026-08-21T01:00:00Z",
          options: ["marketing", "raw", "massive_schema"].map((name) => ({ name, relationKind: null, available: true })),
          totalCount: 3, page: pageNumber, pageSize: 50, latestJob: completedJob(catalogName, null),
        });
        return;
      }
      if (schemaName === "raw" && !rawRecovered) {
        await fulfillJson(route, {
          parentKind: "SCHEMA", childKind: "RELATION", catalogName, schemaName,
          fetchedAt: null, options: [], totalCount: 0, page: pageNumber, pageSize: 50,
          latestJob: {
            ...completedJob(catalogName, schemaName, true),
            safeMessage: "Warehouse list unavailable.",
          },
        });
        return;
      }
      const relationNames = schemaName === "massive_schema"
        ? massiveReloaded
          ? pageNumber === 1
            ? Array.from({ length: 10 }, (_, index) => `event_${String(index + 1).padStart(5, "0")}`)
            : []
          : Array.from({ length: 50 }, (_, index) => `event_${String((pageNumber - 1) * 50 + index + 1).padStart(5, "0")}`)
        : schemaName === "raw" ? ["raw_events"] : ["campaigns", "campaign_daily", "customers"];
      await fulfillJson(route, {
        parentKind: "SCHEMA", childKind: "RELATION", catalogName, schemaName,
        fetchedAt: "2026-08-21T01:00:00Z",
        options: relationNames.map((name) => ({ name, relationKind: "TABLE", available: true })),
        totalCount: schemaName === "massive_schema" ? massiveReloaded ? 10 : 12_480 : relationNames.length,
        page: pageNumber, pageSize: 50,
        latestJob: marketingReloadFailed && schemaName === "marketing"
          ? completedJob(catalogName, schemaName, true)
          : completedJob(catalogName, schemaName),
      });
    });
    await page.route("**/v2/workspaces/*/catalog/connectors/*/selection/list-refresh-jobs", async (route) => {
      const body = route.request().postDataJSON() as { catalogName: string | null; schemaName: string | null };
      if (body.schemaName === "raw") rawRecovered = true;
      if (body.schemaName === "marketing") marketingReloadFailed = true;
      if (body.schemaName === "massive_schema") massiveReloaded = true;
      await fulfillJson(route, completedJob(body.catalogName, body.schemaName), 202);
    });

    await page.goto("/dev/catalog-selection-demo");
    const drawer = page.getByTestId("catalog-selection-drawer");
    await expect(drawer).toBeVisible();

    await drawer.getByRole("button", { name: "Expand marketing schema" }).click();
    await expect(drawer.getByRole("checkbox", { name: "marketing schema" })).toHaveAttribute("aria-checked", "mixed");
    await expect(drawer.getByRole("checkbox", { name: "campaigns table" })).toHaveAttribute("aria-checked", "true");

    const marketing = drawer.locator('[data-testid="catalog-option-list-relation"][data-option-target="marketing"]');
    await marketing.getByRole("button", { name: "Refresh tables in marketing" }).click();
    await expect(marketing.getByText("Reload failed. Showing cached names.")).toBeVisible();
    await expect(marketing.getByRole("checkbox", { name: "campaigns table" })).toBeVisible();
    await prepareDeterministicCapture(page);
    await marketing.scrollIntoViewIfNeeded();
    await page.screenshot({ path: evidencePath("screenshots/catalog-selection-cached-failure.png") });

    await page.setViewportSize({ width: 390, height: 844 });
    await marketing.scrollIntoViewIfNeeded();
    const mobileDrawerBox = await drawer.boundingBox();
    expect(mobileDrawerBox?.width).toBeLessThanOrEqual(390);
    await page.screenshot({ path: evidencePath("screenshots/catalog-selection-mobile.png") });
    await page.setViewportSize({ width: 1440, height: 1000 });

    await drawer.getByRole("button", { name: "Expand raw schema" }).click();
    await expect(drawer.getByText("Warehouse list unavailable.")).toBeVisible();
    await drawer.getByRole("button", { name: "Try again" }).last().click();
    await expect(drawer.getByText("raw_events", { exact: true })).toBeVisible();

    await drawer.getByRole("button", { name: "Expand massive_schema schema" }).click();
    const massive = drawer.locator('[data-testid="catalog-option-list-relation"][data-option-target="massive_schema"]');
    await expect(massive.getByText("12,480 items").first()).toBeVisible();
    await expect(massive.getByText("Page 1 of 250")).toBeVisible();
    await expect(massive.locator('[role="checkbox"]')).toHaveCount(50);
    await massive.getByRole("button", { name: "Next" }).click();
    await expect(massive.getByText("event_00051", { exact: true })).toBeVisible();
    await massive.getByRole("button", { name: "Refresh tables in massive_schema" }).click();
    await expect(massive.getByText("event_00001", { exact: true })).toBeVisible();
    await expect(massive.getByText("10 items").first()).toBeVisible();

    await expect(drawer.getByRole("button", { name: "Save only" })).toBeDisabled();

    await expectNoWcagViolations(page, '[data-testid="catalog-selection-drawer"]');
    await page.screenshot({
      path: evidencePath("screenshots/catalog-coverage-workspace.png"),
    });

    await page.getByRole("button", { name: "Close table selection" }).click();
    await expect(drawer).not.toBeVisible();
  });
});

for (const noCurrentConnectors of [false, true]) {
  for (const target of [
    { parentKind: "CONNECTOR", catalogName: null, schemaName: null },
    { parentKind: "CATALOG", catalogName: "analytics", schemaName: null },
    { parentKind: "SCHEMA", catalogName: "analytics", schemaName: "finance" },
  ]) {
    test(`deleted connector ${target.parentKind.toLowerCase()} refresh remains readable (${noCurrentConnectors ? "no current connectors" : "other connector exists"})`, async ({ page }) => {
      await installCatalogApi(page);
      const deletedConnectorId = "83000000-0000-4000-8000-000000000090";
      const retainedJobId = "83000000-0000-4000-8000-000000000091";
      const requests: string[] = [];
      const linkedParams = new URLSearchParams({ role: "OWNER", connector: deletedConnectorId, refresh: retainedJobId });
      if (target.catalogName) linkedParams.set("refreshCatalog", target.catalogName);
      if (target.schemaName) linkedParams.set("refreshSchema", target.schemaName);
      await page.route(`**/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/**`, async (route) => {
        const url = new URL(route.request().url());
        requests.push(`${route.request().method()} ${url.pathname}`);
        if (url.pathname === `/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${deletedConnectorId}/selection/list-refresh-jobs/${retainedJobId}`) {
          expect(url.searchParams.get("parentKind")).toBe(target.parentKind);
          expect(url.searchParams.get("catalogName")).toBe(target.catalogName);
          expect(url.searchParams.get("schemaName")).toBe(target.schemaName);
          return json(route, {
            jobId: retainedJobId, ...target,
            childKind: target.parentKind === "SCHEMA" ? "RELATION" : "SCHEMA",
            state: "COMPLETED_WITH_ERRORS",
            requestedAt: "2026-10-03T01:00:00Z",
            terminalAt: "2026-10-03T01:00:01Z",
            failureCode: "WAREHOUSE_UNAVAILABLE",
            safeMessage: "The warehouse could not be reached.",
            requestedBy: { principalId: "83000000-0000-4000-8000-000000000003", displayName: "Catalog Operator", credentialSurface: "WEB", agentApp: null },
          });
        }
        return json(route, { code: "UNEXPECTED_LIVE_CATALOG_REQUEST" }, 404);
      });
      if (noCurrentConnectors) {
        await page.route(`**/v2/workspaces/${WORKSPACE_ID}/connectors`, (route) => json(route, { connectors: [] }));
      }
      await page.goto(`/dev/catalog-production-demo?${linkedParams}`);
      await expect(page.getByText("Deleted connector", { exact: true })).toBeVisible();
      await expect(page.getByRole("status").filter({ hasText: "Catalog list refresh" })).toContainText("Completed with errors");
      await expect(page.getByText("The warehouse could not be reached.")).toBeVisible();
      await expect(page).toHaveURL(new RegExp(`connector=${deletedConnectorId}&refresh=${retainedJobId}`));
      await expect(page.getByRole("button", { name: /Choose tables|Refresh now|Cancel refresh|Find table|Recent refreshes/ })).toHaveCount(0);
      await expect(page.getByRole("combobox", { name: "Connector" })).toHaveCount(0);
      expect(requests).toEqual([`GET /v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${deletedConnectorId}/selection/list-refresh-jobs/${retainedJobId}`]);
      await page.reload();
      await expect(page.getByText("Deleted connector", { exact: true })).toBeVisible();
      await expect(page).toHaveURL(new RegExp(`connector=${deletedConnectorId}&refresh=${retainedJobId}`));
      await page.screenshot({ path: evidencePath(`screenshots/deleted-history-${target.parentKind.toLowerCase()}-${noCurrentConnectors ? "empty" : "other"}.png`), fullPage: true });
      if (!noCurrentConnectors) {
        await page.unroute(`**/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/**`);
        await page.getByRole("button", { name: "Browse current catalog" }).click();
        await expect(page.getByRole("combobox", { name: "Connector" })).toHaveText("Finance warehouse");
        await expect(page).not.toHaveURL(/refresh=/);
      }
    });
  }
}

test("unavailable linked history never substitutes a current connector", async ({ page, consoleErrors }) => {
  await installCatalogApi(page);
  const connectorId = "83000000-0000-4000-8000-000000000090";
  const jobId = "83000000-0000-4000-8000-000000000091";
  consoleErrors.allow({ status: 404, url: new RegExp(`/catalog/connectors/${connectorId}/selection/list-refresh-jobs/${jobId}`), reason: "A linked refresh outside the exact connector scope is unavailable." });
  const requests: string[] = [];
  await page.route(`**/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/**`, async (route) => {
    requests.push(new URL(route.request().url()).pathname);
    return json(route, { code: "CATALOG_NOT_FOUND" }, 404);
  });
  await page.goto(`/dev/catalog-production-demo?role=OWNER&connector=${connectorId}&refresh=${jobId}`);
  const status = page.getByRole("status").filter({ hasText: "Catalog list refresh" });
  await expect(status).toContainText("Unavailable");
  await expect(page.getByText("Deleted connector", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("combobox", { name: "Connector" })).toHaveCount(0);
  await expect(page).toHaveURL(new RegExp(`connector=${connectorId}&refresh=${jobId}`));
  expect(new Set(requests)).toEqual(new Set([`/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${connectorId}/selection/list-refresh-jobs/${jobId}`]));
});

test("linked history keeps its connector scope after a cached connector list fails to refetch", async ({ page, consoleErrors }) => {
  await installCatalogApi(page);
  await page.clock.install();
  const connectorId = "83000000-0000-4000-8000-000000000090";
  const jobId = "83000000-0000-4000-8000-000000000091";
  const listPath = `/v2/workspaces/${WORKSPACE_ID}/connectors`;
  consoleErrors.allow({ status: 503, url: new RegExp(`${listPath}$`), reason: "A background connector list refetch fails while its previous data stays cached." });
  let connectorListReads = 0;
  await page.route(`**${listPath}`, async route => {
    connectorListReads++;
    if (connectorListReads === 1) return route.fallback();
    return json(route, { code: "OWNER_UNAVAILABLE", detail: "Connector list is unavailable" }, 503);
  });
  const historyPaths: string[] = [];
  await page.route(`**/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/**`, async route => {
    historyPaths.push(new URL(route.request().url()).pathname);
    return json(route, {
      jobId, parentKind: "CONNECTOR", childKind: "CATALOG", catalogName: null, schemaName: null,
      state: "COMPLETED_WITH_ERRORS", requestedAt: "2026-10-03T01:00:00Z", terminalAt: "2026-10-03T01:00:01Z",
      failureCode: "WAREHOUSE_UNAVAILABLE", safeMessage: "The warehouse could not be reached.",
      requestedBy: { principalId: "83000000-0000-4000-8000-000000000003", displayName: "Catalog Operator", credentialSurface: "WEB", agentApp: null },
    });
  });
  await page.goto(`/dev/catalog-production-demo?role=OWNER&connector=${connectorId}&refresh=${jobId}`);
  await expect(page.getByText("Deleted connector", { exact: true })).toBeVisible();
  await page.clock.fastForward(31_000);
  await page.evaluate(() => window.dispatchEvent(new Event("offline")));
  await page.evaluate(() => window.dispatchEvent(new Event("online")));
  await expect.poll(() => connectorListReads).toBeGreaterThan(1);
  await page.clock.fastForward(2_000);
  await expect(page).toHaveURL(new RegExp(`connector=${connectorId}&refresh=${jobId}`));
  await expect(page.getByText("Deleted connector", { exact: true })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Connector" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /Choose tables|Refresh now|Cancel refresh/ })).toHaveCount(0);
  expect(new Set(historyPaths)).toEqual(new Set([`/v2/workspaces/${WORKSPACE_ID}/catalog/connectors/${connectorId}/selection/list-refresh-jobs/${jobId}`]));
});
