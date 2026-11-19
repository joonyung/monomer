import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { evidencePath } from "./evidence";

test.use({ viewport: { width: 1280, height: 1150 } });
const url = "/dev/dashboard-viewer-fixture?scene=table-presentation";
const cell = (page: Page, row: number, column: number) => page.locator(`[data-row-index='${row}'][data-column-index='${column}']`);
async function open(page: Page) {
  await page.goto(url);
  await expect(cell(page, 0, 1).getByRole("img")).toHaveAttribute("alt", /^\$74,520.00 · Daily revenue:/);
}

test("SQL auxiliary queries keep recorded trust and expose templates only to internal readers", async ({ page }) => {
  await open(page);
  await page.getByRole("checkbox", { name: "SQL auxiliary query", exact: true }).check();
  const surface = page.locator(".dashboard-layout-surface").filter({ has: page.getByTestId("dashboard-table-widget") });
  await expect(surface.getByText("SQL", { exact: true })).toBeVisible();
  await surface.getByRole("button", { name: /^More actions for/ }).click();
  await expect(page.getByRole("menuitem", { name: "Show metric definitions", exact: true })).toBeVisible();
  await page.getByRole("menuitem", { name: "Show SQL", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "SQL", exact: true });
  await expect(dialog).toContainText("Stored templates from this definition");
  await expect(dialog).toContainText("{{minimum}}");
  await expect(dialog.getByRole("region", { name: "SQL template for revenueDaily" })).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-sql/sql-template.png"), fullPage: true });
  await page.getByRole("button", { name: "Close SQL", exact: true }).click();

  await page.getByRole("checkbox", { name: "Retained SQL result", exact: true }).check();
  await page.getByRole("checkbox", { name: "SQL auxiliary query", exact: true }).uncheck();
  await expect(surface.getByText("SQL", { exact: true })).toBeVisible();
  await surface.getByRole("button", { name: /^More actions for/ }).click();
  await expect(page.getByRole("menuitem", { name: "Show SQL", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page.getByRole("checkbox", { name: "Page reader", exact: true }).check();
  await expect(surface.getByText("SQL", { exact: true })).toBeVisible();
  await expect(surface.getByRole("button", { name: /^More actions for/ })).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-sql/sql-reader-retained.png"), fullPage: true });
});

test("INPUT changes presentation and auxiliary readiness preserves exact old Variable frame", async ({ page }) => {
  await open(page);
  await expect(cell(page, 0, 2).getByRole("img")).toHaveAttribute("alt", "14.2% · Below floor");
  await expect(cell(page, 1, 1)).toHaveText("$18,340.50");
  await expect(cell(page, 1, 1).getByRole("img")).toHaveCount(0);
  await page.getByRole("combobox", { name: "Margin floor", exact: true }).click();
  await page.getByRole("option", { name: "10%", exact: true }).click();
  await expect(cell(page, 0, 2).getByRole("img")).toHaveAttribute("alt", "14.2% · Healthy");
  const oldGraphic = await cell(page, 0, 1).getByRole("img").getAttribute("src");
  await page.getByRole("checkbox", { name: "Variable also drives queries" }).check();
  await page.getByRole("combobox", { name: "Margin floor", exact: true }).click();
  await page.getByRole("option", { name: "20%", exact: true }).click();
  await expect(page.getByTestId("table-ready-snapshot")).toContainText('"current":"0.2","displayed":"0.1"');
  await expect(cell(page, 0, 2).getByRole("img")).toHaveAttribute("alt", "14.2% · Healthy");
  await page.getByRole("button", { name: "Complete primary query", exact: true }).click();
  await expect(cell(page, 0, 1).getByRole("img")).toHaveAttribute("src", oldGraphic!);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d09/pending-ready-bundle.png"), fullPage: true });
  await page.getByRole("button", { name: "Complete auxiliary query", exact: true }).click();
  await expect(page.getByTestId("table-ready-snapshot")).toContainText('"current":"0.2","displayed":"0.2"');
  await expect(cell(page, 0, 2).getByRole("img")).toHaveAttribute("alt", "14.2% · Below floor");
  await expect(cell(page, 0, 1).getByRole("img")).not.toHaveAttribute("src", oldGraphic!);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d09/customer-table.png"), fullPage: true });
  expect(await new AxeBuilder({ page }).include('[data-testid="dashboard-table-widget"]').withTags(["wcag2a", "wcag2aa"]).analyze()).toMatchObject({ violations: [] });
});

test("raw decimal stable sort, source original rowIndex and both copy values", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await open(page);
  await cell(page, 2, 1).click();
  await page.getByRole("button", { name: "Copy selected cell", exact: true }).click();
  await page.getByRole("menuitem", { name: "Copy raw value", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe("9007199254740993.01");
  await page.getByRole("button", { name: "Copy selected cell", exact: true }).click();
  await page.getByRole("menuitem", { name: "Copy displayed text", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe("$9,007,199,254,740,993.01");
  await page.getByRole("button", { name: "Revenue · USD", exact: true }).click();
  const rowOrder = () => page.locator('[data-column-index="0"]').evaluateAll(elements => elements.map(element => element.getAttribute("data-row-index")));
  await expect.poll(rowOrder).toEqual(["4", "5", "8", "1", "0", "6", "2", "3", "7"]);
  await page.getByRole("button", { name: "Revenue · USD", exact: true }).click();
  await expect.poll(rowOrder).toEqual(["3", "2", "0", "6", "1", "8", "5", "4", "7"]);
  await page.getByRole("combobox", { name: "Validation source" }).selectOption("metadata");
  await expect(cell(page, 3, 1)).toContainText("3 / DECIMAL / 9007199254740993.02");
  await page.getByRole("combobox", { name: "Validation source" }).selectOption("raw");
  await expect(cell(page, 2, 1)).toHaveText("9,007,199,254,740,993.01");
  await cell(page, 7, 1).click();
  await page.getByRole("button", { name: "Copy selected cell", exact: true }).click();
  await page.getByRole("menuitem", { name: "Copy raw value", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe("null");
  await cell(page, 8, 0).click();
  await page.getByRole("button", { name: "Copy selected cell", exact: true }).click();
  await page.getByRole("menuitem", { name: "Copy raw value", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe("");
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d09/raw-sort-copy.png"), fullPage: true });
});

test("a style-only source keeps the Host text and an Intl source appends a formatted secondary value", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await open(page);
  await page.getByRole("combobox", { name: "Validation source" }).selectOption("styleOnly");
  await expect(cell(page, 0, 1)).toHaveText("74,520.00");
  await expect(cell(page, 0, 1)).toHaveCSS("background-color", "rgb(234, 240, 229)");
  await expect(cell(page, 4, 1)).toHaveText("-10.50");
  await expect(cell(page, 4, 1)).toHaveCSS("background-color", "rgb(251, 228, 213)");
  await expect(cell(page, 0, 4)).toHaveText("182");
  await expect(cell(page, 7, 1)).toHaveText("null");
  await expect(cell(page, 7, 1)).not.toHaveAttribute("style", /background/);
  await cell(page, 2, 1).click();
  await page.getByRole("button", { name: "Copy selected cell", exact: true }).click();
  await page.getByRole("menuitem", { name: "Copy displayed text", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe("9,007,199,254,740,993.01");
  await page.getByRole("combobox", { name: "Validation source" }).selectOption("intl");
  await expect(cell(page, 0, 1)).toHaveText("74,520.00 · 14.2% margin");
  await expect(cell(page, 7, 1)).toHaveText("null");
  await expect(page.getByTestId("dashboard-table-widget").locator("[data-cell-error='true']")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d09/display-text-sources.png"), fullPage: true });
});

test("full Worker batches of zero-expansion metric cells format within the batch deadline", async ({ page }) => {
  await open(page);
  await page.getByRole("checkbox", { name: "Zero-expansion wide result", exact: true }).check();
  await page.getByRole("combobox", { name: "Validation source" }).selectOption("styleOnly");
  const widget = page.getByTestId("dashboard-table-widget");
  await expect(cell(page, 1, 0)).toHaveCSS("background-color", "rgb(234, 240, 229)");
  await expect(cell(page, 1, 0)).toHaveText("$0.000000000000000000001");
  await expect(cell(page, 7, 255)).toHaveCSS("background-color", "rgb(234, 240, 229)");
  await expect(cell(page, 7, 255)).toHaveText("$0.000000000000000000001");
  await expect(widget.locator("[data-cell-error='true']")).toHaveCount(0);
  await expect(widget.getByRole("alert")).toHaveCount(0);
});

test("compile/render and bounded cell/row errors fall back; query and missing fields remain errors", async ({ page }) => {
  await open(page);
  for (const variant of ["compile", "render"]) {
    await page.getByRole("combobox", { name: "Validation source" }).selectOption(variant);
    await expect(page.getByTestId("dashboard-table-widget").getByRole("alert")).toBeVisible();
    await expect(cell(page, 0, 1)).toHaveText("74,520.00");
  }
  for (const variant of ["cell", "row", "oversized", "malicious"]) {
    await page.getByRole("combobox", { name: "Validation source" }).selectOption(variant);
    await expect(cell(page, 0, variant === "row" ? 0 : 1)).toHaveAttribute("data-cell-error", "true");
    await expect(cell(page, 0, 1)).toContainText(variant === "row" ? "74520.00" : "74,520.00");
    await expect(cell(page, 0, 1).getByRole("img")).toHaveCount(0);
  }
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d09/rejected-svg.png"), fullPage: true });
  await page.getByRole("combobox", { name: "Validation source" }).selectOption("demo");
  await expect(cell(page, 0, 1).getByRole("img")).toBeVisible();
  await page.getByRole("checkbox", { name: "Duplicate observation" }).check();
  await expect(page.getByTestId("dashboard-table-widget").getByRole("alert")).toContainText("one row per customer and day");
  await page.getByRole("checkbox", { name: "Duplicate observation" }).uncheck();
  await page.getByRole("checkbox", { name: "Auxiliary query failure" }).check();
  await expect(page.getByRole("alert").filter({ hasText: "The latest run failed" })).toBeVisible();
  await page.getByRole("checkbox", { name: "Missing field" }).check();
  await expect(page.getByText(/metric.missing.*missing from the result/)).toBeVisible();
});

// Short real deadlines prove a hung Worker is terminated; the 10s preparation deadline is covered by table-widget-runtime unit tests.
for (const variant of ["cellTimeout", "batch", "detachedCell"]) {
  test(`real Worker ${variant} terminates and shows current raw result`, async ({ page }) => {
    await open(page);
    await page.getByRole("combobox", { name: "Validation source" }).selectOption(variant);
    await expect(page.getByTestId("dashboard-table-widget").getByRole("alert")).toBeVisible({ timeout: 15_000 });
    await expect(cell(page, 0, 1)).toHaveText("74,520.00");
    await page.getByRole("combobox", { name: "Validation source" }).selectOption("demo");
    await expect(cell(page, 0, 1).getByRole("img")).toBeVisible();
  });
}

test("near viewport rendering, keyboard focus and mobile horizontal scroll", async ({ page }) => {
  await open(page);
  await cell(page, 0, 0).focus();
  await page.keyboard.press("ArrowRight");
  await expect(cell(page, 0, 1)).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(cell(page, 1, 1)).toBeFocused();
  await page.getByRole("checkbox", { name: "2,000 rows" }).check();
  await expect(page.locator('[data-column-index="0"]')).not.toHaveCount(2000);
  expect(await page.locator('[data-column-index="0"]').count()).toBeLessThan(50);
  await page.getByRole("region", { name: "Table scroll area" }).evaluate(element => { element.scrollTop = element.scrollHeight; });
  await expect(cell(page, 1999, 0)).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d09/virtualized-end.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("region", { name: "Table scroll area" }).evaluate(element => { element.scrollLeft = element.scrollWidth; });
  await expect.poll(() => page.getByRole("region", { name: "Table scroll area" }).evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d09/mobile-scroll.png"), fullPage: true });
});

test("native XML validator rejects active content, external references, malformed geometry and budgets", async ({ page }) => {
  await open(page);
  const verdict = await page.evaluate(async () => {
    const modulePath = "/src/features/table-widget/runtime/static-svg.ts";
    const { validateStaticSvg } = await import(/* @vite-ignore */ modulePath);
    const wrap = (body: string, extra = "") => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" ${extra}>${body}</svg>`;
    const rejects = [
      ...["script", "foreignObject", "image", "a", "use", "style", "animate", "animateTransform", "set"].map(tag => wrap(`<${tag}/>`)),
      wrap('<rect width="1" height="1" onclick="alert(1)"/>'),
      wrap('<path d="M0 0L1 1" fill="url(https://example.com/a)"/>'),
      wrap('<text font-family="ExternalFont">x</text>'),
      '<!DOCTYPE svg [<!ENTITY x "bad">]>' + wrap('<text>&x;</text>'),
      '<?xml-stylesheet href="https://example.com/a.css"?>' + wrap(""),
      wrap('<g xmlns="http://www.w3.org/1999/xhtml"/>'),
      wrap('<rect id="dup"/><circle id="dup"/>'),
      wrap('<rect fill="url(#absent)"/>'),
      wrap('<rect id="bad"/><rect fill="url(#bad)"/>'),
      wrap('<rect width="NaN"/>'), wrap('<rect width="1e309"/>'), wrap('<rect width="1000001"/>'),
      wrap('<path d="M 0 0 A -1 1 0 0 0 1 1"/>'), wrap('<path d="M 0 0 A 1 1 0 2 0 1 1"/>'),
      wrap('<path d="M 0 0 L 1"/>'), wrap('<path d="M 0 0 L ' + '1 1 '.repeat(1025) + '"/>'),
      wrap('<g>'.repeat(17) + '</g>'.repeat(17)), wrap('<g/>'.repeat(512)),
      wrap('<text>' + "x".repeat(32768) + '</text>'), wrap('<g>'),
    ];
    const accepts = [wrap('<text font-family="system-ui,sans-serif">A &amp; B</text>'), wrap('<defs><linearGradient id="gradient"><stop offset="0" stop-color="#FFFFFF"/></linearGradient><clipPath id="clip"><rect width="10" height="10"/></clipPath></defs><rect width="10" height="10" fill="url(#gradient)" clip-path="url(#clip)"/>')];
    return { rejected: rejects.map(source => { try { validateStaticSvg(source); return false; } catch { return true; } }), accepted: accepts.map(source => { try { return validateStaticSvg(source) === source; } catch { return false; } }) };
  });
  expect(verdict.rejected.every(Boolean)).toBe(true);
  expect(verdict.accepted.every(Boolean)).toBe(true);
});

test("late batch from replaced source and frame never replaces the current display", async ({ page }) => {
  await page.addInitScript(() => {
    const NativeWorker = window.Worker;
    const held: (() => void)[] = [];
    const controls = window as typeof window & { tableHold: boolean; tableHeld: number; tableFlush: () => void };
    controls.tableHold = false; controls.tableHeld = 0;
    controls.tableFlush = () => held.splice(0).forEach(deliver => deliver());
    window.Worker = class extends NativeWorker {
      constructor(...args: ConstructorParameters<typeof Worker>) {
        super(...args);
        let handler: ((event: MessageEvent) => void) | null = null;
        Object.defineProperty(this, "onmessage", { get: () => handler, set: next => { handler = next; } });
        this.addEventListener("message", event => {
          if (controls.tableHold && event.data.type === "batch") {
            controls.tableHeld++; held.push(() => handler?.(event));
          } else handler?.(event);
        });
      }
    };
  });
  await open(page);
  await page.evaluate(() => { (window as typeof window & { tableHold: boolean }).tableHold = true; });
  await page.getByRole("combobox", { name: "Margin floor", exact: true }).click();
  await page.getByRole("option", { name: "10%", exact: true }).click();
  await expect.poll(() => page.evaluate(() => (window as typeof window & { tableHeld: number }).tableHeld)).toBeGreaterThan(0);
  await page.evaluate(() => { (window as typeof window & { tableHold: boolean }).tableHold = false; });
  await page.getByRole("combobox", { name: "Validation source" }).selectOption("metadata");
  await expect(cell(page, 0, 1)).toHaveText("0 / DECIMAL / 74520.00");
  await page.evaluate(() => (window as typeof window & { tableFlush: () => void }).tableFlush());
  await expect(cell(page, 0, 1)).toHaveText("0 / DECIMAL / 74520.00");
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d09/late-worker-discarded.png"), fullPage: true });
});

test("unchanged ready frame does not retry failed source on parent rerender or scroll", async ({ page }) => {
  await page.addInitScript(() => {
    const NativeWorker = window.Worker;
    const controls = window as typeof window & { tableWorkers: number };
    controls.tableWorkers = 0;
    window.Worker = class extends NativeWorker {
      constructor(...args: ConstructorParameters<typeof Worker>) {
        super(...args);
        if (args[1]?.name?.startsWith("monomer-table-widget-")) controls.tableWorkers++;
      }
    };
  });
  await open(page);
  await page.getByRole("combobox", { name: "Validation source" }).selectOption("render");
  await expect(page.getByRole("alert")).toContainText("fixture render failure");
  const workers = await page.evaluate(() => (window as typeof window & { tableWorkers: number }).tableWorkers);
  await page.getByRole("checkbox", { name: "Variable also drives queries" }).check();
  await page.getByRole("region", { name: "Table scroll area" }).evaluate(element => { element.scrollTop = 100; });
  await expect(page.getByRole("alert")).toContainText("fixture render failure");
  expect(await page.evaluate(() => (window as typeof window & { tableWorkers: number }).tableWorkers)).toBe(workers);
});

test("removing the selected column with the same QueryRun restores selection and copy", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await open(page);
  await cell(page, 0, 4).click();
  await expect(cell(page, 0, 4)).toHaveAttribute("aria-selected", "true");
  await page.getByRole("checkbox", { name: "Narrow columns" }).check();
  await expect(page.getByRole("gridcell", { selected: true })).toHaveText("Acme");
  await expect(cell(page, 0, 0)).toHaveAttribute("tabindex", "0");
  await page.getByRole("button", { name: "Copy selected cell", exact: true }).click();
  await page.getByRole("menuitem", { name: "Copy raw value", exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe("Acme");
  await cell(page, 0, 0).focus();
  await page.keyboard.press("ArrowRight");
  await expect(cell(page, 0, 1)).toBeFocused();
});
