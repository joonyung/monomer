import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";

const route = "/dev/dashboard-viewer-fixture?scene=content-height";
const evidence = evidencePath("../screenshots/dashboard-content-height");
const image = '<svg xmlns="http://www.w3.org/2000/svg" width="1100" height="420"><rect width="1100" height="420" fill="#2466c3"/><text x="40" y="80" fill="white" font-size="32">Regional revenue</text></svg>';

for (const width of [360, 768, 769, 1200, 1920]) {
  test(`CONTENT natural height and SCALED chart at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(route);
    const section = page.locator('[data-section-id="intro"]');
    const chart = page.locator('[data-section-id="chart"]').getByTestId("dashboard-chart-widget");
    await expect(chart).toHaveAttribute("data-chart-status", "ready");
    const shortHeight = (await section.boundingBox())!.height;
    expect(shortHeight).toBeLessThan(160);
    const chartHeight = (await chart.boundingBox())!.height;
    expect(chartHeight).toBe(width <= 768 ? 320 : 408);
    await page.getByLabel("Text", { exact: true }).selectOption("long");
    expect((await section.boundingBox())!.height).toBeGreaterThan(shortHeight + 250);
    expect((await chart.boundingBox())!.height).toBe(chartHeight);
    await expect(page.getByText("Final observation.")).toBeVisible();
    expect(await section.evaluate(node => node.scrollHeight <= node.clientHeight)).toBe(true);
    await page.screenshot({ path: `${evidence}/long-${width}.png`, fullPage: true });
    await page.getByLabel("Text", { exact: true }).selectOption("empty");
    expect((await section.boundingBox())!.height).toBe(width < 640 ? 34 : 42);
    await page.getByLabel("Text", { exact: true }).selectOption("overflow");
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
    expect(await section.locator("pre").evaluate(node => node.scrollWidth > node.clientWidth)).toBe(true);
    if (width === 1200) {
      await page.getByLabel("Narrow container").check();
      expect((await chart.boundingBox())!.height).toBe(320);
      await page.screenshot({ path: `${evidence}/narrow-container.png`, fullPage: true });
    }
  });
}

test("capture waits for TEXT image at desktop width and saves the complete PNG", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 900 });
  const png = await page.evaluate(() => {
    const canvas = document.createElement("canvas"); canvas.width = 1100; canvas.height = 420;
    const context = canvas.getContext("2d")!;
    context.fillStyle = "#2466c3"; context.fillRect(0, 0, 1100, 420);
    context.fillStyle = "white"; context.font = "32px sans-serif"; context.fillText("Regional revenue", 40, 80);
    return canvas.toDataURL().split(",")[1];
  });
  await page.route("**/content-height-image.png", async route => {
    await new Promise(resolve => setTimeout(resolve, 700));
    await route.fulfill({ contentType: "image/png", body: Buffer.from(png, "base64") });
  });
  await page.goto(route);
  await page.getByLabel("Text", { exact: true }).selectOption("image");
  await page.getByRole("button", { name: "Capture dashboard", exact: true }).click();
  await page.getByRole("button", { name: "Select all", exact: true }).click();
  await page.getByRole("button", { name: "Capture", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible({ timeout: 25000 });
  const preview = page.getByRole("dialog").locator("img");
  await expect(preview).toBeVisible();
  const dimensions = await preview.evaluate((node: HTMLImageElement) => ({ width: node.naturalWidth, height: node.naturalHeight }));
  expect(dimensions.width).toBe(4992);
  expect(dimensions.height).toBeGreaterThan(5000);
  await page.screenshot({ path: `${evidence}/capture-preview.png`, fullPage: true });
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "Save PNG" }).click();
  await (await download).saveAs(`${evidence}/content-image-capture.png`);
});

// The image deadline shares this failure message and stage cleanup; load and decode cover the path without a 20s wait.
for (const failure of ["load", "decode"]) {
  test(`capture reports ${failure} failure and removes its stage`, async ({ page, consoleErrors }) => {
    if (failure === "load") consoleErrors.allow({ message: /^Failed to load resource: net::ERR_FAILED$/, url: /\/content-height-image\.png$/, reason: "The fixture aborts the CONTENT image load." });
    test.setTimeout(35000);
    await page.route("**/content-height-image.png", route => failure === "load" ? route.abort() : route.fulfill({ contentType: "image/svg+xml", body: image }));
    if (failure !== "load") await page.addInitScript(() => {
      HTMLImageElement.prototype.decode = function () {
        return Promise.reject(new Error("Invalid image"));
      };
    });
    await page.goto(route);
    await page.getByLabel("Text", { exact: true }).selectOption("image");
    await page.getByRole("button", { name: "Capture dashboard", exact: true }).click();
    await page.getByRole("button", { name: "Select all", exact: true }).click();
    await page.getByRole("button", { name: "Capture", exact: true }).click();
    await expect(page.getByRole("alert")).toHaveText("Some images could not finish loading. Please try again.", { timeout: 25000 });
    await expect(page.locator("[data-capture-layout]")).toHaveCount(0);
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.screenshot({ path: `${evidence}/capture-${failure}-error.png`, fullPage: true });
  });
}

test("captures one CONTENT widget at 1200px with its title and source", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 900 });
  await page.goto(route);
  await page.getByLabel("Text", { exact: true }).selectOption("long");
  await page.getByRole("button", { name: "Capture dashboard", exact: true }).click();
  await expect(page.getByRole("button", { name: "Select title and description" })).toHaveCount(0);
  await page.getByRole("button", { name: "Clear", exact: true }).click();
  await page.getByRole("button", { name: "Select intro", exact: true }).press("Space");
  await page.getByRole("button", { name: "Capture", exact: true }).click();
  const preview = page.getByRole("dialog").locator("img");
  await expect(preview).toBeVisible({ timeout: 25000 });
  const dimensions = await preview.evaluate((node: HTMLImageElement) => ({ width: node.naturalWidth, height: node.naturalHeight }));
  expect(dimensions.width).toBe(4992);
  // The title header and the "Built with Monomer" footer always add roughly 110px above and below the widget.
  expect(dimensions.height).toBeGreaterThan(2000);
  expect(dimensions.height).toBeLessThan(2700);
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "Save PNG" }).click();
  await (await download).saveAs(`${evidence}/selected.png`);
});
