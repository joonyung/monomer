import { mkdirSync } from "node:fs";
import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import {
  PAGES_FIXTURE_ORGANIZATION_ID,
  PAGES_FIXTURE_HTML_PAGE_ID,
  PAGES_FIXTURE_FOLDER_ID,
  PAGES_FIXTURE_EMPTY_FOLDER_ID,
  PAGES_FIXTURE_MARKDOWN_PAGE_ID,
  PAGES_FIXTURE_NESTED_FOLDER_ID,
  PAGES_FIXTURE_NOTEBOOK_PAGE_ID,
  PAGES_FIXTURE_WORKSPACE_ID,
} from "../../src/components/pages/pagesFixtures";
import { evidencePath } from "./evidence";
import { json, mockGraphql } from "./support/session";

test.beforeAll(() => {
  mkdirSync(evidencePath("screenshots"), { recursive: true });
});

test.beforeEach(async ({ page }) => {
  await mockGraphql(page, {
    AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: [
      { id: PAGES_FIXTURE_WORKSPACE_ID, organizationId: PAGES_FIXTURE_ORGANIZATION_ID, name: "MONOMER Demo", publicPages: false, reportingTimeZone: "UTC", role: "EXPLORER" },
    ] } }),
  });
});

async function expectNoWcagViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(results.violations).toEqual([]);
}

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  test(`Workspace member Pages names the Workspace without the public masthead at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_MARKDOWN_PAGE_ID}?controls=0`);
    await expect(page.getByRole("heading", { name: "Q2 operating brief", exact: true })).toBeVisible();
    const workspaceName = viewport.width >= 1024 ? page.getByTestId("pages-context-bar") : page.getByTestId("mobile-workspace-name");
    await expect(workspaceName).toContainText("Pages workspace");
    await expect(page.getByText("MONOMER Demo · Pages workspace", { exact: true })).toHaveCount(0);
    await expect(page.getByText("Built with Monomer", { exact: true })).toHaveCount(0);
    await page.screenshot({ path: evidencePath(`screenshots/pages-member-source-${viewport.width}.png`), fullPage: false });
  });
}

test("deep link reveals ancestors with native disclosure semantics", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_NESTED_FOLDER_ID}?controls=0`);

  const activePage = page.getByRole("link", { name: "Weekly reviews", exact: true }).first();
  const executiveDisclosure = page.getByRole("button", { name: "Collapse Executive" }).first();
  const executiveChildren = page.locator(`#pages-children-${PAGES_FIXTURE_FOLDER_ID}`).first();
  await expect(activePage).toHaveAttribute("aria-current", "page");
  await expect(executiveDisclosure).toHaveAttribute("aria-expanded", "true");
  await expect.poll(() => executiveChildren.evaluate((element) => getComputedStyle(element).transitionDuration))
    .toContain("0.16s");

  await executiveDisclosure.focus();
  await page.keyboard.press("Space");
  await expect(page.getByRole("button", { name: "Expand Executive" }).first()).toHaveAttribute("aria-expanded", "false");
  await expect(executiveChildren).toHaveAttribute("aria-hidden", "true");
  await page.keyboard.press("Space");
  await expect(page.getByRole("button", { name: "Collapse Executive" }).first()).toHaveAttribute("aria-expanded", "true");

  await expectNoWcagViolations(page);
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  await page.screenshot({ path: evidencePath("screenshots/pages-folder-desktop.png"), fullPage: false });
});

test("editor can create a top-level folder without leaving the Pages root", async ({ page }) => {
  await page.goto("/dev/pages-demo?surface=root&controls=0");
  await page.getByRole("button", { name: "New folder", exact: true }).click();
  const dialog = page.getByTestId("new-pages-folder-dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("Name").fill("Customer success");
  await dialog.getByLabel("Description").fill("Renewal and adoption reviews.");
  await dialog.getByRole("button", { name: "Create folder" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Customer success", exact: true }).first()).toBeVisible();
  await page.getByRole("button", { name: "New folder", exact: true }).click();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel("Name")).toHaveValue("");
});

test("sidebar reorder moves a Page among its real siblings", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/dev/pages-demo?surface=root&controls=0");
  const sidebar = page.getByTestId("desktop-sidebar");
  const navigation = sidebar.getByTestId("pages-navigation");
  const rootTitles = navigation.locator(":scope > ul > li > div > a");
  await expect(rootTitles).toHaveText(["Executive", "Operations", "Reference"]);
  // Members find Pages through Search, so the directory has no filter of its own.
  await expect(sidebar.getByRole("searchbox")).toHaveCount(0);

  await navigation.getByRole("button", { name: "Actions for Operations", exact: true }).click();
  const dialog = page.getByTestId(`pages-actions-dialog-${PAGES_FIXTURE_EMPTY_FOLDER_ID}`);
  await expect(dialog.getByRole("button", { name: /^Move down/ })).toBeEnabled();
  await dialog.getByRole("button", { name: /^Move up/ }).click();
  await expect(dialog).toHaveCount(0);
  await expect(rootTitles).toHaveText(["Operations", "Executive", "Reference"]);
});

test("editor can upload a Document Page from the same Pages root", async ({ page }) => {
  await page.goto("/dev/pages-demo?surface=root&controls=0");
  await page.getByRole("button", { name: "Upload document", exact: true }).click();
  const dialog = page.getByTestId("new-pages-document-dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("File").setInputFiles({
    name: "customer-health.md",
    mimeType: "text/markdown",
    buffer: Buffer.from("# Customer health\n\nStatic fixture content."),
  });
  await expect(dialog.getByLabel("Title")).toHaveValue("customer-health");
  await dialog.getByLabel("Description").fill("Weekly customer health narrative.");
  await dialog.getByRole("button", { name: "Create document" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole("link", { name: "customer-health", exact: true }).first()).toBeVisible();
});

test("Markdown document renders exact immutable history without raw HTML", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_MARKDOWN_PAGE_ID}?controls=0`);

  await expect(page.getByRole("heading", { name: "Q2 operating brief", exact: true })).toBeVisible();
  const documentHeader = page.locator("main header").first();
  await expect(documentHeader.getByText("Narrative context, decisions, and follow-ups for the quarterly review.")).toBeVisible();
  await expect(documentHeader.getByText(/^Updated /)).toBeVisible();
  await expect(documentHeader.getByText("Markdown", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Version 3", { exact: true }).first()).toBeVisible();
  const article = page.locator("article.document-content");
  await expect(article.getByRole("heading", { name: "Q2 operating review" })).toBeVisible();
  await expect(article.getByRole("heading", { name: "Decisions" })).toBeVisible();
  await expect(article.locator("script, iframe, img")).toHaveCount(0);

  await expectNoWcagViolations(page);
  await page.screenshot({ path: evidencePath("screenshots/pages-document-markdown-desktop.png"), fullPage: false });

  await page.getByRole("button", { name: /Version 2/ }).click();
  await expect(article.getByRole("heading", { name: "Q2 operating review — draft" })).toBeVisible();
  await expect(page.getByText("Version 2", { exact: true }).first()).toBeVisible();
  await page.screenshot({ path: evidencePath("screenshots/pages-document-history-desktop.png"), fullPage: false });
});
test("replacing document content reconciles latest content and immutable history", async ({ page }) => {
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_MARKDOWN_PAGE_ID}?controls=0`);
  await page.getByRole("button", { name: "Replace content" }).click();
  const dialog = page.getByTestId(
    `replace-document-content-dialog-${PAGES_FIXTURE_MARKDOWN_PAGE_ID}`,
  );
  await dialog.getByLabel("File").setInputFiles({
    name: "operating-brief-v4.md",
    mimeType: "text/markdown",
    buffer: Buffer.from("# Operating brief v4"),
  });
  await dialog.getByRole("button", { name: "Add version" }).click();

  await expect(page.getByText("Version 4", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "Operating brief v4" })).toBeVisible();

  await page.getByRole("button", { name: /Version 3/ }).click();
  await expect(page.getByText("Version 3", { exact: true }).first()).toBeVisible();
  await page.getByRole("button", { name: /Version 4/ }).click();
  await expect(page.getByText("Version 4", { exact: true }).first()).toBeVisible();
});

test("replacing an HTML document starts from its HTML format", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ message: /^Blocked script execution in 'about:srcdoc'/, reason: "The HTML page renders in the sandboxed static document frame, which refuses every script." });
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_HTML_PAGE_ID}?controls=0`);
  await page.getByRole("button", { name: "Replace content" }).click();
  const dialog = page.getByTestId(`replace-document-content-dialog-${PAGES_FIXTURE_HTML_PAGE_ID}`);
  await expect(dialog.getByText("Add a new immutable HTML version. Earlier versions remain available.")).toBeVisible();
  await expect(dialog.getByLabel("Format")).toHaveValue("HTML");
  await expect(dialog.getByLabel("File")).toHaveAttribute("accept", ".html,.htm,text/html");

  await dialog.getByLabel("Format").selectOption("MARKDOWN");
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await page.getByRole("button", { name: "Replace content" }).click();
  await expect(dialog.getByLabel("Format")).toHaveValue("HTML");
});

test("HTML viewer removes executable and network-bearing content", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ message: /^Blocked script execution in 'about:srcdoc'/, reason: "Chromium refuses every script in the sandboxed static document frame, including Playwright's own frame evaluation." });
  const externalRequests: string[] = [];
  page.on("request", (request) => {
    if (request.url().startsWith("https://example.com")) externalRequests.push(request.url());
  });
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_HTML_PAGE_ID}?controls=0`);
  const preview = page.getByTitle("Campaign readout static document");
  await expect(preview).toHaveAttribute("sandbox", "");
  await expect(preview).toHaveAttribute("referrerpolicy", "no-referrer");
  await expect(preview).not.toHaveAttribute("allow");
  await expect(page.getByText("Static document · scripts and network access disabled")).toBeVisible();
  const frame = page.frameLocator('iframe[title="Campaign readout static document"]');
  await expect(frame.getByRole("heading", { name: "Campaign efficiency by channel" })).toBeVisible();
  await expect(frame.locator("script, form, iframe, object, embed")).toHaveCount(0);
  await expect(frame.getByRole("img", { name: "Stored chart" })).toBeVisible();
  await expect(frame.locator("img")).toHaveCount(1);
  await expect(frame.getByText("Send", { exact: true })).toHaveCount(0);
  await expect(frame.locator('meta[http-equiv="Content-Security-Policy"]')).toHaveAttribute("content", /default-src 'none'/);
  await frame.getByRole("img", { name: "Stored chart" }).evaluate((image: HTMLImageElement) => image.decode());
  expect(externalRequests).toEqual([]);
  await page.screenshot({ path: evidencePath("screenshots/pages-document-html-static.png"), fullPage: false });
});

test("Notebook viewer renders stored safe output and omits interactive MIME", async ({ page }) => {
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_NOTEBOOK_PAGE_ID}?controls=0`);
  await expect(page.getByText("Stored cells and outputs · no cells are executed")).toBeVisible();
  await expect(page.getByRole("article").getByRole("heading", { name: "Retention cohort analysis", exact: true })).toBeVisible();
  await expect(page.getByText(/loaded 3 cohorts/)).toBeVisible();
  await expect(page.getByRole("img", { name: "Stored Notebook output" })).toBeVisible();
  await expect(page.getByText("Unsupported interactive output was omitted.")).toBeVisible();
  await expect(page.getByText("application/vnd.jupyter.widget-view+json")).toBeVisible();
  await expect(page.locator("main iframe, main article script")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/pages-document-notebook-static.png"), fullPage: false });
});

test("Document Page remains readable without horizontal overflow on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_MARKDOWN_PAGE_ID}?controls=0`);
  await expect(page.getByRole("heading", { name: "Q2 operating brief", exact: true })).toBeVisible();
  const versionSelect = page.getByRole("combobox", { name: "Document version" });
  await expect(versionSelect).toBeInViewport();
  await versionSelect.selectOption("2");
  await expect(page.getByRole("heading", { name: "Q2 operating review — draft" })).toBeVisible();
  await page.getByRole("button", { name: "Back to latest" }).click();
  await expect(page.getByRole("heading", { name: "Q2 operating review", exact: true })).toBeVisible();

  await expect.poll(() => page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  )).toBe(true);
  await expectNoWcagViolations(page);
  await page.screenshot({ path: evidencePath("screenshots/pages-document-markdown-mobile.png"), fullPage: false });
});

test("Document Page deletion uses document lifecycle language", async ({ page }) => {
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_MARKDOWN_PAGE_ID}?controls=0`);
  const trigger = page.locator("main header").getByRole("button", { name: "Actions for Q2 operating brief" });
  await trigger.click();
  const dialog = page.getByTestId(`pages-actions-dialog-${PAGES_FIXTURE_MARKDOWN_PAGE_ID}`);
  await dialog.getByRole("button", { name: "Delete document" }).click();
  await expect(dialog.locator("p").filter({ hasText: "Q2 operating brief and every immutable document version" }))
    .toBeVisible();
  await expect(dialog.getByRole("button", { name: "Delete document" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
});

test("member gets the same empty read surface without mutation controls", async ({ page }) => {
  await page.goto("/dev/pages-demo?surface=empty-member&controls=0");
  await expect(page.getByRole("heading", { name: "All pages", exact: true })).toBeVisible();
  await expect(page.getByText("Nothing has been shared here yet. Ask a workspace Explorer to add dashboards or documents.")).toBeVisible();
  await expect(page.getByRole("button", { name: /New folder/i })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Upload document", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Add dashboard", exact: true })).toHaveCount(0);
});

test("editor sees an ordered list with kind and update time, and the editor empty state", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/dev/pages-demo?surface=root&controls=0");
  const header = page.getByTestId("workspace-page-header");
  await expect(header.getByRole("heading", { name: "All pages", exact: true })).toBeVisible();
  await expect(header.getByText("Dashboards and documents your team shares.", { exact: true })).toHaveCount(0);
  await expect(header.getByText(/Anyone with a link/)).toHaveCount(0);
  await expect(header.getByRole("button")).toHaveText(["New folder", "Upload document", "Add dashboard"]);
  const rows = page.getByRole("list", { name: "All pages contents" }).getByRole("listitem");
  await expect(rows).toHaveCount(3);
  await expect(rows.nth(0)).toContainText("Executive");
  await expect(rows.nth(0)).toContainText("2 items");
  await expect(rows.nth(0)).toContainText(/Updated /);
  await expect(rows.nth(1)).toContainText("0 items");
  await expectNoWcagViolations(page);
  await page.screenshot({ path: evidencePath("screenshots/pages-root-list-desktop.png"), fullPage: false });

  await page.goto("/dev/pages-demo?surface=empty-editor&controls=0");
  await expect(page.getByText("Add a dashboard, upload a document, or create a folder. Readers see them here in this order.")).toBeVisible();
});

test("folder deletion is offered only for an empty folder", async ({ page }) => {
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_FOLDER_ID}?controls=0`);
  await page.getByTestId("workspace-page-header").getByRole("button", { name: "Actions for Executive", exact: true }).click();
  const dialog = page.getByTestId(`pages-actions-dialog-${PAGES_FIXTURE_FOLDER_ID}`);
  await expect(dialog.getByRole("button", { name: /^Delete folder/ })).toBeDisabled();
  await expect(dialog.getByText("Only empty folders can be deleted. Move or remove its pages first.")).toBeVisible();
  await expect(dialog.getByText(/Promote/)).toHaveCount(0);
  await page.keyboard.press("Escape");

  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_EMPTY_FOLDER_ID}?controls=0`);
  await expect(page.getByText("Add a dashboard or document, or move an existing page here.")).toBeVisible();
  await page.getByTestId("workspace-page-header").getByRole("button", { name: "Actions for Operations", exact: true }).click();
  const emptyDialog = page.getByTestId(`pages-actions-dialog-${PAGES_FIXTURE_EMPTY_FOLDER_ID}`);
  await emptyDialog.getByRole("button", { name: /^Delete folder/ }).click();
  await expect(emptyDialog.getByText("Operations will be deleted.")).toBeVisible();
  await expect(emptyDialog.getByRole("button", { name: "Delete folder", exact: true })).toBeEnabled();
});

test("document readers can copy the Page link and see who updated it", async ({ page }) => {
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_MARKDOWN_PAGE_ID}?controls=0`);
  const documentHeader = page.locator("main header").first();
  await expect(documentHeader.getByText(/^Updated (by (you|Agent) · )?/)).toBeVisible();
  await documentHeader.getByRole("button", { name: "Copy link", exact: true }).click();
  await expect(documentHeader.getByRole("button", { name: "Link copied", exact: true })).toBeVisible();
  await expect(documentHeader.getByRole("status")).toHaveText("Link copied");
  expect(new URL(await page.evaluate(() => navigator.clipboard.readText())).pathname).toBe(`/dev/pages-demo/${PAGES_FIXTURE_MARKDOWN_PAGE_ID}`);
  await expect(documentHeader.getByRole("button", { name: "Copy link", exact: true })).toBeVisible({ timeout: 5000 });
  await expect(page.getByRole("region", { name: /table of contents/i })).toHaveCount(0);
});

test("mobile drawer exposes the same active Page and restores a usable viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`/dev/pages-demo/${PAGES_FIXTURE_NESTED_FOLDER_ID}?controls=0`);
  const trigger = page.getByRole("button", { name: "Open pages navigation" });
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "Pages navigation" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("link", { name: "Weekly reviews", exact: true })).toHaveAttribute("aria-current", "page");
  await expectNoWcagViolations(page);
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  await page.screenshot({ path: evidencePath("screenshots/pages-folder-mobile.png"), fullPage: false });
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect.poll(() => page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
  )).toBe(true);
});

test("500-Page fixture renders its bounded root projection", async ({ page }) => {
  const startedAt = Date.now();
  await page.goto("/dev/pages-demo?surface=dense&controls=0");
  await expect(page.getByRole("heading", { name: "All pages", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Portfolio 20", exact: true }).first()).toBeVisible();
  expect(Date.now() - startedAt).toBeLessThan(5_000);
});
