import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";

const route = "/dev/dashboard-viewer-fixture?scene=layout-sizing";
const evidence = evidencePath("../screenshots/dashboard-layout-sizing");

for (const width of [360, 768, 769, 860, 1100, 1440, 1920]) {
  test(`content, allotted slots and nested columns at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto(route);
    await page.evaluate(() => document.fonts.ready);
    const cases = page.locator("[data-sizing-case]");
    await expect(cases).toHaveCount(16);
    await expect(page.locator('[data-sizing-case="worker-failure"] [data-chart-status]')).toHaveAttribute("data-chart-status", "failed", { timeout: 20000 });
    await expect(page.locator('[data-sizing-case="worker-failure"] [role="alert"]')).toContainText("The chart could not be displayed.");
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(width);
    const content = page.locator('[data-layout-content]');
    for (const surface of await content.all()) {
      expect(await surface.evaluate(node => node.scrollHeight <= node.clientHeight + 1)).toBe(true);
    }
    const input = page.locator('[data-sizing-case="row-chart-input"] [data-capture-widget-id="o2"]');
    expect(await input.evaluate(node => {
      const child = node.firstElementChild!;
      const style = getComputedStyle(node);
      return Math.abs(node.getBoundingClientRect().height - child.getBoundingClientRect().height - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom) - 2);
    })).toBeLessThanOrEqual(1);
    const twoCharts = page.locator('[data-sizing-case="column-two-charts"] .dashboard-section');
    if (width >= 860) expect((await twoCharts.boundingBox())!.height).toBeCloseTo(300, 0);
    for (const chart of await page.getByTestId("dashboard-chart-widget").all()) {
      const box = (await chart.boundingBox())!;
      await expect.poll(async () => Math.abs(Number(await chart.getAttribute("data-chart-viewport-height")) - box.height)).toBeLessThanOrEqual(1);
      await expect.poll(async () => Math.abs(Number(await chart.getAttribute("data-chart-viewport-width")) - box.width)).toBeLessThanOrEqual(1);
    }
    await page.screenshot({ path: `${evidence}/${width}.png`, fullPage: true });
  });
}

test("ROW observes its width and round trips without losing the declared minimum", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(route);
  const row = page.locator('[data-sizing-case="filter-bar"] [data-layout-direction="ROW"]');
  await page.getByLabel("Container width").fill("712");
  await expect(row).not.toHaveAttribute("data-layout-stacked");
  await page.getByLabel("Container width").fill("711");
  await expect(row).toHaveAttribute("data-layout-stacked", "");
  await page.getByLabel("Container width").fill("1100");
  await expect(row).not.toHaveAttribute("data-layout-stacked");
  const section = page.locator('[data-sizing-case="column-two-charts"] .dashboard-section');
  expect((await section.boundingBox())!.height).toBeCloseTo(300, 0);
});

test("narrow TABLE slots stay identical for ready, loading and first failure", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(route);
  await page.getByLabel("Container width").fill("600");
  const boxes = page.locator('[data-sizing-case="table"] [data-layout-box]');
  const ready = await boxes.evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().height));
  for (const state of ["loading", "failed", "ready"]) {
    await page.getByLabel("Query state").selectOption(state);
    expect(await boxes.evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().height))).toEqual(ready);
  }
  const failure = page.locator('[data-sizing-case="first-failure"]');
  await failure.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(page.getByLabel("Last action")).toHaveText("Retry");
  await failure.getByRole("button", { name: "Details", exact: true }).click();
  await expect(page.getByLabel("Last action")).toHaveText("Details");
  await expect(failure.getByRole("alert")).toContainText("warehouse query timed out");
  const worker = page.locator('[data-sizing-case="worker-failure"]');
  await expect(worker.locator("[data-chart-status]")).toHaveAttribute("data-chart-status", "failed", { timeout: 20000 });
  const description = worker.locator(".dashboard-widget-state-description");
  await page.keyboard.press("Tab");
  await description.focus();
  await expect(description).toContainText("revenue series is missing");
  await expect(description).toHaveCSS("-webkit-line-clamp", "none");
  await page.screenshot({ path: `${evidence}/narrow-states.png`, fullPage: true });
});
