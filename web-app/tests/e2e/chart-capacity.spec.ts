import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";
import { evidencePath } from "./evidence";

async function waitForCapacityCompletion(page: Page, previousCompletions: number) {
  // Worker initialization has a 10 s runtime deadline, beyond the 5 s assertion budget.
  await page.waitForFunction(previous => {
    const completions = Number(document.querySelector('[data-testid="capacity-completions"]')?.textContent ?? "0");
    const status = document.querySelector('[data-testid="capacity-status"]')?.textContent;
    return completions > previous || status === "failed";
  }, previousCompletions);
  await expect(page.getByTestId("capacity-status")).toHaveText("rendered");
  expect(Number(await page.getByTestId("capacity-completions").textContent())).toBeGreaterThan(previousCompletions);
}

async function renderCapacityFixture(page: Page, name: string) {
  const completions = Number(await page.getByTestId("capacity-completions").textContent());
  await page.getByRole("button", { name, exact: true }).click();
  await waitForCapacityCompletion(page, completions);
}

test("real Worker renders full capacity fixtures and retains success on aggregate overflow", async ({ page }) => {
  await page.goto("/dev/chart-widget-runtime-fixture?capacity=1");
  await waitForCapacityCompletion(page, 0);
  const measurements: Record<string, unknown> = {};
  for (const name of ["5000x10", "wide-short", "multiple-queries", "large-strings", "near-byte-limit", "empty"]) {
    await renderCapacityFixture(page, name);
    await expect(page.getByTestId("capacity-metrics")).toContainText("workerRoundTripMs");
    measurements[name] = JSON.parse((await page.getByTestId("capacity-metrics").textContent())!);
  }
  await renderCapacityFixture(page, "5000x10");
  const canvas = page.locator("canvas");
  await page.mouse.move(1000, 10);
  const success = await canvas.screenshot();
  await page.getByRole("button", { name: "narrow-100000", exact: true }).click();
  await expect(page.getByTestId("capacity-status")).toHaveText("failed");
  await expect(page.getByRole("alert")).toContainText("250000 nodes");
  expect((await canvas.screenshot()).equals(success)).toBe(true);
  await page.getByRole("button", { name: "over-budget", exact: true }).click();
  await expect(page.getByTestId("capacity-status")).toHaveText("failed");
  await expect(page.getByRole("alert")).toContainText("16777216 bytes");
  await page.mouse.move(1000, 10);
  expect((await canvas.screenshot()).equals(success)).toBe(true);
  const completions = Number(await page.getByTestId("capacity-completions").textContent());
  await page.getByRole("button", { name: "overlap-over-budget", exact: true }).click();
  await page.waitForFunction(previous => Number(document.querySelector('[data-testid="capacity-completions"]')?.textContent ?? "0") > previous, completions);
  expect(Number(await page.getByTestId("capacity-completions").textContent())).toBeGreaterThan(completions);
  await expect(page.getByTestId("capacity-status")).toHaveText("failed");
  await expect(page.getByRole("alert")).toContainText("16777216 bytes");
  expect((await canvas.screenshot()).equals(success)).toBe(true);
  mkdirSync(evidencePath("../screenshots/chart-c05-c06"), { recursive: true });
  writeFileSync(evidencePath("../screenshots/chart-c05-c06/browser-capacity.json"), JSON.stringify(measurements, null, 2));
  await page.screenshot({ path: evidencePath("../screenshots/chart-c05-c06/over-budget-retains-chart.png"), fullPage: true });
});

test("inline raster reaches the advertised 2 MiB file boundary in the Worker", async ({ page }) => {
  await page.goto("/dev/chart-widget-runtime-fixture?raster=2097152");
  await waitForCapacityCompletion(page, 0);
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/chart-c05-c06/raster-2mib.png"), fullPage: true });
  await page.goto("/dev/chart-widget-runtime-fixture?raster=2097153");
  await page.waitForFunction(() => document.querySelector('[data-testid="capacity-status"]')?.textContent === "failed");
  await expect(page.getByTestId("capacity-status")).toHaveText("failed");
  await expect(page.getByRole("alert")).toContainText("Inline raster image exceeds");
});
