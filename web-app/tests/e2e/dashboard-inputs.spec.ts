import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { evidencePath } from "./evidence";

test.describe("atomic Variable submission", () => {
  test.use({ viewport: { width: 1200, height: 1100 } });

  async function clickBar(page: Page) {
    const chart = page.getByTestId("dashboard-chart-widget");
    await expect(chart.getByRole("alert")).toHaveCount(0);
    const canvas = chart.locator("canvas");
    await expect.poll(() => canvas.evaluate((element) => (element as HTMLCanvasElement).width)).toBeGreaterThan(500);
    const box = (await canvas.boundingBox())!;
    await page.mouse.click(box.x + box.width * 0.5, box.y + box.height * 0.5);
  }

  test("Chart submits one map and INPUT submits one entry from its snapshot", async ({ page }) => {
    await page.goto("/dev/dashboard-viewer-fixture?scene=atomic-variables");
    await clickBar(page);
    await expect(page.getByTestId("atomic-requests")).toHaveText('[{"values":{"country":"KR","city":null},"displayedInputOrder":8,"accepted":true}]');
    await expect(page.getByTestId("atomic-snapshot")).toHaveText('{"variables":{"country":"KR","city":null},"revision":9}');
    await page.getByRole("textbox", { name: "Country", exact: true }).fill("FR");
    await page.getByTestId("dashboard-variable-country").locator("..").getByRole("button", { name: /^Apply / }).click();
    await expect(page.getByTestId("atomic-requests")).toContainText('"values":{"country":"FR"},"displayedInputOrder":9,"accepted":true');
    await expect(page.getByTestId("atomic-snapshot")).toHaveText('{"variables":{"country":"FR","city":null},"revision":10}');
    await page.screenshot({ path: evidencePath("../screenshots/dashboard-d04/atomic-map-and-input.png"), fullPage: true });
  });

  test("a newer accepted input resets INPUT draft and Builder preview cannot mutate", async ({ page }) => {
    await page.goto("/dev/dashboard-viewer-fixture?scene=atomic-variables");
    const country = page.getByRole("textbox", { name: "Country", exact: true });
    await country.fill("FR");
    await page.getByRole("button", { name: "Accept newer input" }).click();
    await expect(country).toHaveValue("US");
    await page.getByRole("button", { name: "Builder read-only" }).click();
    await expect(country).toBeDisabled();
    await clickBar(page);
    await expect(page.getByTestId("atomic-requests")).toHaveText("[]");
    await page.screenshot({ path: evidencePath("../screenshots/dashboard-d04/builder-readonly.png"), fullPage: true });
  });
});

test("preserves server values outside controls and restricts only each UI write", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/dev/dashboard-builder-fixture?scene=controls");
  const toolbar = page.getByTestId("dashboard-parameter-toolbar");
  await toolbar.getByRole("button", { name: "Change preview inputs", exact: true }).click();
  const region = toolbar.getByTestId("dashboard-parameter-region");
  await expect(region.getByRole("combobox")).toHaveText("west");
  await expect(region.getByRole("button", { name: /^Set / })).toBeDisabled();
  const regions = toolbar.getByTestId("dashboard-parameter-regions");
  await expect(regions.getByText("west", { exact: true })).toBeVisible();
  await expect(regions.getByRole("button", { name: /^Set / })).toBeDisabled();
  await expect(toolbar.getByRole("textbox", { name: "Observed at" })).toHaveValue("2026-09-06T09:00:00.000000001Z");
  await expect(toolbar.getByText("The current value 2026-09-06T09:00:00.000000001Z is outside this control's allowed range.")).toBeVisible();
  const first = page.getByRole("region", { name: "Quantity 0–10", exact: true });
  const second = page.getByRole("region", { name: "Quantity 10–20", exact: true });
  await expect(first.getByRole("textbox")).toHaveValue("25");
  await expect(second.getByRole("textbox")).toHaveValue("25");
  await expect(first.getByRole("button", { name: /^Apply / })).toBeDisabled();
  await expect(second.getByRole("button", { name: /^Apply / })).toBeDisabled();
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d05/outside-desktop.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await region.scrollIntoViewIfNeeded();
  await expect(region.getByRole("combobox")).toHaveText("west");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d05/outside-mobile.png"), fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });

  await regions.getByRole("button", { name: "South", exact: true }).click();
  await expect(regions.getByText("west", { exact: true })).toBeVisible();
  await expect(regions.getByRole("button", { name: /^Set / })).toBeDisabled();
  await regions.getByRole("button", { name: "Remove west", exact: true }).click();
  await regions.getByRole("button", { name: /^Set / }).click();
  await expect(page.getByTestId("stored-parameters")).toContainText('"regions":["north","south"]');
  await region.getByRole("combobox").click();
  await page.getByRole("option", { name: "North", exact: true }).click();
  await region.getByRole("button", { name: /^Set / }).click();
  await expect(page.getByTestId("stored-parameters")).toContainText('"region":"north"');

  await first.getByRole("textbox").fill("-");
  await expect(first.getByRole("button", { name: /^Apply / })).toBeDisabled();
  await first.getByRole("textbox").fill("5");
  await first.getByRole("button", { name: /^Apply / }).click();
  await expect(second.getByRole("textbox")).toHaveValue("5");
  await expect(second.getByRole("button", { name: /^Apply / })).toBeDisabled();
  await second.getByRole("textbox").fill("15");
  await second.getByRole("button", { name: /^Apply / }).click();
  await expect(first.getByRole("textbox")).toHaveValue("15");
  await expect(first.getByRole("button", { name: /^Apply / })).toBeDisabled();
  await expect(page.getByTestId("stored-variable")).toHaveText('"15"');

  const form = page.getByRole("region", { name: "New session" });
  await expect(form.getByRole("button", { name: "Show results" })).toBeDisabled();
  await form.getByRole("button", { name: "North", exact: true }).click();
  await form.getByRole("button", { name: "South", exact: true }).click();
  await form.getByRole("button", { name: "Show results" }).click();
  await expect(page.getByTestId("opened-bindings")).toHaveText('{"regions":["north","south"]}');
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-d05/explicit-inputs.png"), fullPage: true });
});

for (const width of [1440, 390]) {
  test(`INPUT keyboard, immediate choice, overflow and dialog ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto("/dev/dashboard-viewer-fixture?scene=input-layout");
    const field = page.getByTestId("dashboard-variable-wide");
    const combo = field.getByRole("combobox");
    await expect(field.getByRole("button", { name: /^Apply / })).toHaveCount(0);
    await combo.focus(); await combo.press("End");
    await expect(page.getByRole("listbox")).toBeVisible();
    const bounds = await page.getByRole("listbox").boundingBox();
    expect(bounds!.x).toBeGreaterThanOrEqual(0); expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width);
    await page.screenshot({ path: evidencePath(`../screenshots/dashboard-ui-iteration/input-options-${width}.png`), fullPage: true });
    await combo.press("Enter");
    await expect(combo).toContainText("United States");
    await expect(page.getByTestId("applied-country")).toHaveText("US");
    await expect(page.getByTestId("dashboard-variable-narrow").getByRole("combobox")).toContainText("United States");
    await combo.click(); await combo.press("Home"); await combo.press("j"); await combo.press("Enter");
    await expect(combo).toContainText("Japan");
    await expect(page.getByTestId("applied-country")).toHaveText("JP");
    await combo.click(); await combo.press("Escape"); await expect(combo).toBeFocused();
    await expect(page.getByRole("listbox")).toHaveCount(0);
    await expect(page.getByTestId("dashboard-variable-disabled").getByRole("combobox")).toBeDisabled();
    const invalid = page.getByTestId("dashboard-variable-invalid");
    await invalid.getByRole("textbox").fill("99");
    await expect(invalid.getByRole("button", { name: /^Apply / })).toBeDisabled();
    await page.screenshot({ path: evidencePath(`../screenshots/dashboard-ui-iteration/input-validation-${width}.png`), fullPage: true });
    await page.getByRole("button", { name: "Open input dialog" }).click();
    const dialog = page.getByRole("dialog"); const dialogCombo = dialog.getByRole("combobox");
    await dialogCombo.click(); await page.getByRole("option", { name: "United States", exact: true }).click();
    await expect(dialogCombo).toContainText("United States");
    await expect(page.getByTestId("applied-country")).toHaveText("US");
    await dialogCombo.click(); await dialogCombo.press("Escape");
    await expect(dialog).toBeVisible(); await expect(dialogCombo).toBeFocused();
    await expect(dialog.getByRole("button", { name: /^Apply / })).toHaveCount(0);
    await page.getByRole("button", { name: "Close dialog" }).click();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze()).violations).toEqual([]);
  });
}

test("choice menu opens upward at the viewport edge", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 430 });
  await page.goto("/dev/dashboard-viewer-fixture?scene=input-layout");
  const combo = page.getByTestId("dashboard-variable-wide").getByRole("combobox");
  await combo.scrollIntoViewIfNeeded();
  await combo.click();
  const menu = page.getByRole("listbox");
  await expect(menu).toBeVisible();
  const triggerBox = await combo.boundingBox(); const menuBox = await menu.boundingBox();
  expect(menuBox!.y).toBeGreaterThanOrEqual(0);
  expect(menuBox!.y + menuBox!.height).toBeLessThanOrEqual(triggerBox!.y);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-ui-iteration/input-upward.png") });
});
