import { expect, test } from "./support/test";

test("wheel over a chart scrolls its containing page", async ({ page }) => {
  await page.setViewportSize({ width: 1200, height: 400 });
  await page.goto("/dev/chart-widget-runtime-fixture");
  await expect(page.getByText("rendered", { exact: true })).toBeVisible();
  const chart = page.locator('canvas[aria-label="ChartWidget runtime fixture"]');
  await chart.hover({ position: { x: 120, y: 100 } });
  const before = await page.evaluate(() => window.scrollY);
  await page.mouse.wheel(0, 160);
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(before);
});
