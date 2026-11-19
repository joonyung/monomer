import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { evidencePath } from "./evidence";

test.use({ viewport: { width: 1280, height: 1000 } });
const url = "/dev/dashboard-viewer-fixture?scene=table-pivot";
const widget = (page: Page) => page.getByTestId("dashboard-table-widget");
const cell = (page: Page, rowId: string, column: number) => widget(page).locator(`[data-row-id='${rowId}'][data-column-index='${column}']`);
const bodyOrder = (page: Page) => widget(page).locator('tbody [data-column-index="0"]').evaluateAll((elements) => elements.map((element) => element.getAttribute("data-row-id")));
const clipboard = (page: Page) => page.evaluate(() => navigator.clipboard.readText());
async function open(page: Page) {
  await page.goto(url);
  await expect(cell(page, "total", 6)).toHaveText("104");
}
async function copy(page: Page, format: "Copy raw value" | "Copy displayed text") {
  await widget(page).getByRole("button", { name: "Copy selected cell", exact: true }).click();
  await page.getByRole("menuitem", { name: format, exact: true }).click();
}
async function expectRow(page: Page, rowId: string, texts: string[]) {
  for (const [column, text] of texts.entries()) await expect(cell(page, rowId, column)).toHaveText(text);
}

test("cross-tab places each result cell once and takes totals from their own queries", async ({ page }) => {
  await open(page);
  const grid = page.getByRole("grid", { name: "Revenue by region", exact: true });
  await expect(grid.getByRole("columnheader", { name: "Month: 2026-07-01", exact: true })).toHaveAttribute("colspan", "2");
  await expect(grid.getByRole("columnheader", { name: "Month: 2026-08-01", exact: true })).toHaveAttribute("colspan", "2");
  await expect(grid.getByRole("button", { name: "Region", exact: true })).toBeVisible();
  await expect(grid.getByRole("button", { name: "Revenue · Month: 2026-07-01", exact: true })).toBeVisible();
  await expect(grid.getByRole("button", { name: "Customers · Total", exact: true })).toBeVisible();
  expect(await bodyOrder(page)).toEqual(["row:0", "row:1", "row:2"]);
  await expectRow(page, "row:0", ["West", "$1,200.00", "40", "$1,350.50", "42", "$2,550.50", "55"]);
  await expectRow(page, "row:1", ["East", "", "", "$980.00", "30", "$980.00", "30"]);
  await expectRow(page, "row:2", ["North", "null", "12", "$1,500.00", "25", "$1,500.00", "31"]);
  await expect(cell(page, "row:1", 1)).toHaveAttribute("data-cell-kind", "EMPTY");
  await expect(cell(page, "row:2", 1).locator("[data-value-kind='null']")).toHaveText("null");
  // Unique customers are distinct counts: each total comes from its query, not from adding the cells above or beside it.
  await expectRow(page, "total", ["Total", "$1,200.00", "50", "$3,830.50", "88", "$5,030.50", "104"]);
  await expect(widget(page).locator("tfoot")).toHaveCount(1);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-table-pivot/columns-with-totals.png"), fullPage: true });
  expect(await new AxeBuilder({ page }).include('[data-testid="dashboard-table-widget"]').withTags(["wcag2a", "wcag2aa"]).analyze()).toMatchObject({ violations: [] });
});

test("value and total headers sort body rows with empty and null cells last while totals stay at the bottom", async ({ page }) => {
  await open(page);
  const augustRevenue = widget(page).getByRole("button", { name: "Revenue · Month: 2026-08-01", exact: true });
  await augustRevenue.click();
  await expect.poll(() => bodyOrder(page)).toEqual(["row:1", "row:0", "row:2"]);
  await expect(augustRevenue.locator("..")).toHaveAttribute("aria-sort", "ascending");
  await augustRevenue.click();
  await expect.poll(() => bodyOrder(page)).toEqual(["row:2", "row:0", "row:1"]);
  await expect(augustRevenue.locator("..")).toHaveAttribute("aria-sort", "descending");
  await augustRevenue.click();
  await expect.poll(() => bodyOrder(page)).toEqual(["row:0", "row:1", "row:2"]);
  await expect(augustRevenue.locator("..")).toHaveAttribute("aria-sort", "none");

  const julyRevenue = widget(page).getByRole("button", { name: "Revenue · Month: 2026-07-01", exact: true });
  await julyRevenue.click();
  await expect.poll(() => bodyOrder(page)).toEqual(["row:0", "row:1", "row:2"]);
  await julyRevenue.click();
  await expect.poll(() => bodyOrder(page)).toEqual(["row:0", "row:1", "row:2"]);

  await widget(page).getByRole("button", { name: "Customers · Total", exact: true }).click();
  await expect.poll(() => bodyOrder(page)).toEqual(["row:1", "row:2", "row:0"]);
  await expect(widget(page).getByText("3 rows · Sorted by Customers · Total ↑")).toBeVisible();
  await expect(widget(page).locator("tfoot [data-column-index='0']")).toHaveText("Total");
  await widget(page).getByRole("button", { name: "Region", exact: true }).click();
  await expect.poll(() => bodyOrder(page)).toEqual(["row:1", "row:2", "row:0"]);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-table-pivot/sorted-by-region.png"), fullPage: true });
});

test("copy keeps raw and displayed values for value, empty, null and total cells", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await open(page);
  await cell(page, "row:0", 1).click();
  await copy(page, "Copy raw value");
  await expect.poll(() => clipboard(page)).toBe("1200.00");
  await copy(page, "Copy displayed text");
  await expect.poll(() => clipboard(page)).toBe("$1,200.00");
  await cell(page, "row:1", 1).click();
  await copy(page, "Copy raw value");
  await expect.poll(() => clipboard(page)).toBe("");
  await cell(page, "row:2", 1).click();
  await copy(page, "Copy displayed text");
  await expect.poll(() => clipboard(page)).toBe("null");
  await cell(page, "total", 5).click();
  await copy(page, "Copy raw value");
  await expect.poll(() => clipboard(page)).toBe("5030.50");
  await copy(page, "Copy displayed text");
  await expect.poll(() => clipboard(page)).toBe("$5,030.50");
});

test("keyboard moves from the body into total rows and row headers stay pinned while scrolling", async ({ page }) => {
  await open(page);
  await cell(page, "row:0", 0).click();
  await page.keyboard.press("ArrowRight");
  await expect(cell(page, "row:0", 1)).toBeFocused();
  for (let step = 0; step < 3; step++) await page.keyboard.press("ArrowDown");
  await expect(cell(page, "total", 1)).toBeFocused();
  await page.setViewportSize({ width: 390, height: 844 });
  const scrollArea = page.getByRole("region", { name: "Table scroll area" });
  const before = await cell(page, "row:0", 0).boundingBox();
  await scrollArea.evaluate((element) => { element.scrollLeft = element.scrollWidth; });
  await expect.poll(() => scrollArea.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0);
  const after = await cell(page, "row:0", 0).boundingBox();
  expect(Math.abs(after!.x - before!.x)).toBeLessThan(1);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-table-pivot/mobile-pinned-row-header.png"), fullPage: true });
});

test("values on rows make a scorecard with a Values column and a query total column", async ({ page }) => {
  await open(page);
  await page.getByRole("combobox", { name: "Pivot scenario" }).selectOption("scorecard");
  const grid = page.getByRole("grid", { name: "Monthly scorecard", exact: true });
  await expect(grid.getByRole("columnheader", { name: "Values", exact: true })).toBeVisible();
  for (const month of ["2026-07-01", "2026-08-01", "2026-09-01"]) await expect(grid.getByRole("columnheader", { name: `Month: ${month}`, exact: true })).toBeVisible();
  await expect(grid.getByRole("columnheader", { name: "Total", exact: true })).toBeVisible();
  await expectRow(page, "row:0:metric.revenue", ["Revenue", "$1,200.00", "$3,830.50", "$2,600.00", "$7,630.50"]);
  await expectRow(page, "row:0:metric.unique_customers", ["Customers", "50", "88", "61", "140"]);
  await expect(grid.getByRole("button")).toHaveCount(0);
  await expect(widget(page).locator("tfoot")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-table-pivot/scorecard.png"), fullPage: true });
});

test("duplicate keys, too many columns and truncated totals close the table with a visible error", async ({ page }) => {
  await open(page);
  const errors: [string, string][] = [
    ["duplicate key", 'More than one row has Region "West" and Month "2026-08-01". Each row and column pair must appear once.'],
    ["too many columns", "This pivot would create 340 columns. The limit is 200. Narrow the query."],
    ["missing total", 'The total query "revenueByRegion" has no row for Region "North". Raise its rowLimit or match its filters.'],
  ];
  for (const [scenario, message] of errors) {
    await page.getByRole("combobox", { name: "Pivot scenario" }).selectOption(scenario);
    await expect(page.getByRole("alert").filter({ hasText: message })).toBeVisible();
    await expect(page.getByRole("grid")).toHaveCount(0);
  }
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-table-pivot/missing-total-error.png"), fullPage: true });
});

test("a formatting source formats cells at their original addresses and never total or empty cells", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await open(page);
  await page.getByRole("checkbox", { name: "Formatting source", exact: true }).check();
  await expect(cell(page, "row:0", 0)).toHaveText("West · row 0");
  await expect(cell(page, "row:1", 0)).toHaveText("East · row 2");
  await expect(cell(page, "row:2", 0)).toHaveText("North · row 3");
  await expect(cell(page, "row:0", 2)).toHaveText("40 customers · row 1");
  await expect(cell(page, "row:0", 4)).toHaveText("42 customers · row 0");
  await expect(cell(page, "row:2", 2)).toHaveText("12 customers · row 3");
  await expect(cell(page, "row:0", 1)).toHaveText("$1,200.00");
  await expect(cell(page, "row:0", 1)).toHaveCSS("background-color", "rgb(234, 240, 229)");
  await expect(cell(page, "row:2", 1)).toHaveText("null");
  await expect(cell(page, "row:2", 1)).not.toHaveAttribute("style", /background/);
  await expect(cell(page, "row:0", 5)).toHaveText("$2,550.50");
  await expect(cell(page, "row:0", 5)).not.toHaveAttribute("style", /background/);
  await expect(cell(page, "row:1", 2)).toHaveText("");
  await expect(cell(page, "row:0", 6)).toHaveText("55");
  await expect(cell(page, "total", 2)).toHaveText("50");
  await cell(page, "row:0", 3).click();
  await copy(page, "Copy displayed text");
  await expect.poll(() => clipboard(page)).toBe("$1,350.50");
  await expect(widget(page).locator("tbody tr").first()).not.toHaveAttribute("style", /background/);
  await expect(widget(page).getByRole("alert")).toHaveCount(0);
});

test("the widget waits for every totals query and Page readers see the same totals", async ({ page }) => {
  await open(page);
  await page.getByRole("checkbox", { name: "Total query pending", exact: true }).check();
  await expect(widget(page)).toHaveCount(0);
  await expect(page.getByText("Loading results…", { exact: true })).toBeVisible();
  await page.getByRole("checkbox", { name: "Total query pending", exact: true }).uncheck();
  await expect(cell(page, "total", 6)).toHaveText("104");
  await expect(page.getByRole("button", { name: /^More actions for/ })).toHaveCount(1);
  await page.getByRole("checkbox", { name: "Page reader", exact: true }).check();
  await expect(page.getByRole("button", { name: /^More actions for/ })).toHaveCount(0);
  await expectRow(page, "row:0", ["West", "$1,200.00", "40", "$1,350.50", "42", "$2,550.50", "55"]);
  await expectRow(page, "total", ["Total", "$1,200.00", "50", "$3,830.50", "88", "$5,030.50", "104"]);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-table-pivot/page-reader.png"), fullPage: true });
});
