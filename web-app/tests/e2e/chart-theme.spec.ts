import fs from "node:fs";
import path from "node:path";
import { expect, test } from "./support/test";
import type { Page, Worker } from "@playwright/test";
import { evidencePath } from "./evidence";

// The deployed CloudFront policy is the single source; read it instead of copying the directive.
const WORKER_CSP = /export const WORKER_CSP = "([^"]+)";/.exec(
  fs.readFileSync(path.resolve(import.meta.dirname, "../../../infra/cloud-dev/src/static-routing.ts"), "utf8"),
)![1];

// The runtime reports rendered or failed within its 10 s initialization deadline
// (CHART_INITIALIZATION_DEADLINE_MS); fifteen Workers start at once, so wait for that bound.
const CHART_INITIALIZATION_WAIT_MS = 10_000;

type Rgb = readonly [number, number, number];

const CHART_1: Rgb = [56, 112, 223];
const CHART_2: Rgb = [25, 159, 148];
const CHART_3: Rgb = [224, 109, 79];
const SEQUENTIAL_DARKEST: Rgb = [48, 85, 182];
const SOFT_AREA_FILL: Rgb = [225, 233, 250];
const VENDOR_DEFAULTS: readonly Rgb[] = [[80, 112, 221], [182, 214, 52], [80, 83, 114], [255, 153, 77], [255, 209, 10]];

test.use({ viewport: { width: 1432, height: 2200 } });

async function colorCounts(page: Page, chartId: string, colors: readonly Rgb[]): Promise<number[]> {
  const png = await page.locator(`[data-chart-id="${chartId}"] canvas`).screenshot();
  return page.evaluate(async ({ bytes, targets }) => {
    const bitmap = await createImageBitmap(new Blob([new Uint8Array(bytes)], { type: "image/png" }));
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d")!;
    context.drawImage(bitmap, 0, 0);
    bitmap.close();
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    return targets.map(([red, green, blue]) => {
      let count = 0;
      for (let index = 0; index < pixels.length; index += 4) {
        if (Math.abs(pixels[index] - red) <= 6 && Math.abs(pixels[index + 1] - green) <= 6 && Math.abs(pixels[index + 2] - blue) <= 6) count += 1;
      }
      return count;
    });
  }, { bytes: Array.from(png), targets: colors.map((color) => [...color]) });
}

async function openFixture(page: Page) {
  await page.goto("/dev/chart-widget-runtime-fixture?theme");
  await expect(page.locator("[data-chart-status]")).toHaveCount(15);
  await expect(page.locator('[data-chart-status]:not([data-chart-status="rendered"])')).toHaveCount(0, { timeout: CHART_INITIALIZATION_WAIT_MS });
  await expect(page.getByRole("alert")).toHaveCount(0);
}

test("Chart Worker renders color-free sources with the Monomer palette and no vendor defaults", async ({ page }) => {
  await openFixture(page);
  await expect.poll(async () => (await colorCounts(page, "single-trend", [CHART_1]))[0]).toBeGreaterThan(150);
  for (const chartId of ["single-trend", "area-trend", "stable-categorical", "grouped-bar", "ranking-bar", "multi-line", "combo", "donut", "scatter", "sequential", "gauge", "treemap", "funnel"]) {
    expect(await colorCounts(page, chartId, VENDOR_DEFAULTS), chartId).toEqual(VENDOR_DEFAULTS.map(() => 0));
  }
  const [direct, partner, marketplace] = await colorCounts(page, "stable-categorical", [CHART_1, CHART_2, CHART_3]);
  expect(direct).toBeGreaterThan(partner);
  expect(partner).toBeGreaterThan(marketplace);
  expect(marketplace).toBeGreaterThan(500);
  expect((await colorCounts(page, "ranking-bar", [CHART_1]))[0]).toBeGreaterThan(5000);
  expect((await colorCounts(page, "sequential", [SEQUENTIAL_DARKEST]))[0]).toBeGreaterThan(100);
  expect((await colorCounts(page, "area-trend", [SOFT_AREA_FILL]))[0]).toBeGreaterThan(5000);
  await page.screenshot({ path: evidencePath("../screenshots/chart-theme/fixture.png"), fullPage: true });
});

test("Refresh and series reorder keep each series color identity", async ({ page }) => {
  await openFixture(page);
  const colors = [CHART_1, CHART_2, CHART_3];
  await expect.poll(async () => (await colorCounts(page, "stable-categorical", colors))[2]).toBeGreaterThan(500);
  const before = await colorCounts(page, "stable-categorical", colors);
  await page.getByRole("button", { name: "Reorder channels" }).click();
  await page.getByRole("button", { name: "Refresh data" }).click();
  await expect(page.locator('[data-chart-status]:not([data-chart-status="rendered"])')).toHaveCount(0);
  await expect.poll(async () => {
    const after = await colorCounts(page, "stable-categorical", colors);
    return after.every((count, index) => Math.abs(count - before[index]) <= before[index] * 0.05);
  }).toBe(true);
  await expect.poll(async () => (await colorCounts(page, "single-trend", [CHART_1]))[0]).toBeGreaterThan(150);
});

test("Tooltip opens on the themed surface for axis and item triggers", async ({ page }) => {
  await openFixture(page);
  for (const chartId of ["single-trend", "ranking-bar"]) {
    const chart = page.locator(`[data-chart-id="${chartId}"] canvas`);
    const box = (await chart.boundingBox())!;
    const idle = await chart.screenshot();
    await chart.hover({ position: { x: box.width * 0.55, y: chartId === "ranking-bar" ? 24 : box.height * 0.5 } });
    await expect.poll(async () => {
      const hovered = await chart.screenshot();
      return page.evaluate(async ({ first, second }) => {
        const load = async (bytes: number[]) => {
          const bitmap = await createImageBitmap(new Blob([new Uint8Array(bytes)], { type: "image/png" }));
          const canvas = document.createElement("canvas");
          canvas.width = bitmap.width;
          canvas.height = bitmap.height;
          canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
          bitmap.close();
          return canvas.getContext("2d")!.getImageData(0, 0, canvas.width, canvas.height).data;
        };
        const [left, right] = await Promise.all([load(first), load(second)]);
        let changed = 0;
        for (let index = 0; index < left.length; index += 4) {
          if (Math.abs(left[index] - right[index]) + Math.abs(left[index + 1] - right[index + 1]) + Math.abs(left[index + 2] - right[index + 2]) > 30) changed += 1;
        }
        return changed;
      }, { first: Array.from(idle), second: Array.from(hovered) });
    }, { message: chartId }).toBeGreaterThan(1500);
    await page.screenshot({ path: evidencePath(`../screenshots/chart-theme/tooltip-${chartId}.png`), clip: { x: box.x - 16, y: box.y - 16, width: box.width + 32, height: box.height + 32 } });
    await page.mouse.move(0, 0);
  }
});

async function chartFontState(worker: Worker) {
  return worker.evaluate(() => {
    const context = new OffscreenCanvas(8, 8).getContext("2d")!;
    const width = (text: string) => { context.font = '12px "Geist Variable"'; return context.measureText(text).width; };
    return { loaded: self.fonts.check('12px "Geist Variable"'), ones: width("1111"), zeros: width("0000") };
  });
}

test("Chart Worker renders text in bundled Geist with tabular numerals", async ({ page }) => {
  await openFixture(page);
  const workers = page.workers();
  expect(workers.length).toBeGreaterThanOrEqual(15);
  const state = await chartFontState(workers[0]);
  expect(state.loaded).toBe(true);
  expect(state.ones).toBeCloseTo(state.zeros, 2);
});

test("Chart font and theme work under the production Worker CSP", async ({ page }) => {
  const violations: string[] = [];
  page.on("console", (message) => {
    if (/Content Security Policy|Refused to/i.test(message.text())) violations.push(message.text());
  });
  await page.route("**/runtime/worker/entry.ts*", async (route) => {
    const response = await route.fetch();
    await route.fulfill({ response, headers: { ...response.headers(), "content-security-policy": WORKER_CSP } });
  });
  await openFixture(page);
  const worker = page.workers()[0];
  expect(await worker.evaluate(() => fetch("/").then(() => "allowed", () => "blocked"))).toBe("blocked");
  const state = await chartFontState(worker);
  expect(state.loaded).toBe(true);
  await expect.poll(async () => (await colorCounts(page, "stable-categorical", [CHART_1]))[0]).toBeGreaterThan(500);
  expect(violations).toEqual([]);
});
