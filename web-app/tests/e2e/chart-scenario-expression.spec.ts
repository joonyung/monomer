import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { evidencePath } from "./evidence";

mkdirSync(evidencePath("../screenshots/chart-e2e/expression"), { recursive: true });

const expressionUrl = (scenario: string) => `/dev/chart-widget-runtime-fixture?expression=${encodeURIComponent(scenario)}`;
const status = (page: Page) => page.getByTestId("expression-status");
const expectedText = (page: Page) => page.getByTestId("expression-expected");

test.use({ viewport: { width: 1200, height: 820 } });

test.describe("A · numeric and text expression scenarios", () => {
  test("A01 KPI and trend preserve the fixed F1 values", async ({ page }) => {
    await page.goto(expressionUrl("A01-kpi-trend"));
    await expect(status(page)).toHaveText("rendered");
    await expect(expectedText(page)).toHaveText("$74,520.00\nPrevious $65,000.00 · +14.6% · Trend 20,000→24,000→30,520");
    await expect(page.getByTestId("expression-frame")).toContainText('"current":3');
    await expect(page.getByTestId("expression-frame")).toContainText('"previous":1');
    await expect(page.getByText("A01 · kpi-trend", { exact: true })).toBeVisible();
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/A01-kpi-trend.png"), fullPage: true });
  });

  test("A02 keeps the DECIMAL source string visible to the Worker", async ({ page }) => {
    await page.goto(expressionUrl("A02-decimal-exact"));
    await expect(status(page)).toHaveText("rendered");
    await expect(expectedText(page)).toHaveText("9007199254740993.27\n$9,007,199,254,740,993.27 · DECIMAL string preserved");
    await expect(page.getByTestId("expression-frame")).toContainText('"decimal":1');
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/A02-decimal-exact.png"), fullPage: true });
  });

  test("A03 separates zero, negative, and null formatting", async ({ page }) => {
    await page.goto(expressionUrl("A03-zero-negative-null"));
    await expect(status(page)).toHaveText("rendered");
    await expect(expectedText(page)).toHaveText("$0.00 | -$1,850.25 | —");
    await expect(page.getByRole("alert")).toHaveCount(0);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/A03-zero-negative-null.png"), fullPage: true });
  });

  for (const comparison of ["comparison-zero", "comparison-null"]) {
    test(`A04 ${comparison} avoids Infinity and NaN`, async ({ page }) => {
      await page.goto(expressionUrl(`A04-${comparison}`));
      await expect(status(page)).toHaveText("rendered");
      await expect(expectedText(page)).toHaveText("$100.00 · —\ncomparison unavailable");
      await expect(page.getByRole("alert")).toHaveCount(0);
      await page.screenshot({ path: evidencePath(`../screenshots/chart-e2e/expression/A04-${comparison}.png`), fullPage: true });
    });
  }

  test("A05 replaces a successful result with the explicit empty state", async ({ page }) => {
    await page.goto(expressionUrl("A05-empty-success"));
    await expect(status(page)).toHaveText("rendered");
    await expect(expectedText(page)).toHaveText("No data\nRun SUCCEEDED · row count 0");
    const canvas = page.locator("canvas[aria-label='Expression chart']");
    const previous = await canvas.screenshot();
    await page.getByRole("button", { name: "Render empty result", exact: true }).click();
    await expect(status(page)).toHaveText("rendered");
    await expect(page.getByTestId("expression-frame")).toContainText('"sales":0');
    await expect(page.getByRole("alert")).toHaveCount(0);
    expect((await canvas.screenshot()).equals(previous)).toBe(false);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/A05-empty-success.png"), fullPage: true });
  });

  test("A06 uses canonical names after reordering and reports a missing field", async ({ page }) => {
    await page.goto(expressionUrl("A06-reordered-columns"));
    await expect(status(page)).toHaveText("rendered");
    await expect(expectedText(page)).toHaveText("Named amount 74520.00");
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/A06-reordered-columns.png"), fullPage: true });
    await page.goto(expressionUrl("A06-missing-field"));
    await expect(status(page)).toHaveText("failed");
    await expect(expectedText(page)).toHaveText("Missing canonical field metric.amount");
    await expect(page.getByRole("alert")).toContainText("Missing canonical field metric.amount");
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/A06-missing-field.png"), fullPage: true });
  });

  test("A07 keeps Unicode, long names, and URL strings in the canvas", async ({ page }) => {
    const requests: string[] = [];
    const pages: string[] = [];
    page.on("request", request => { if (request.url().startsWith("https://example.test")) requests.push(request.url()); });
    page.on("popup", popup => pages.push(popup.url()));
    await page.goto(expressionUrl("A07-text-url"));
    await expect(status(page)).toHaveText("rendered");
    await expect(expectedText(page)).toHaveText("대한민국 🇰🇷\nhttps://example.test/path?query=1");
    await expect(page.getByRole("alert")).toHaveCount(0);
    expect(requests).toEqual([]);
    expect(pages).toEqual([]);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/A07-text-url-desktop.png"), fullPage: true });
    await page.setViewportSize({ width: 390, height: 820 });
    await expect(page.locator("canvas[aria-label='Expression chart']")).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/A07-text-url-mobile.png"), fullPage: true });
  });

  for (const scenario of ["A08-ko-kr-usd-seoul", "A08-en-us-usd-utc", "A08-de-de-krw-seoul"]) {
    test(`A08 ${scenario} uses explicit locale and timezone`, async ({ page }) => {
      await page.goto(expressionUrl(scenario));
      await expect(status(page)).toHaveText("rendered");
      const expected = scenario === "A08-ko-kr-usd-seoul"
        ? "US$1,234.50\nAsia/Seoul · 2026/09/07"
        : scenario === "A08-en-us-usd-utc"
          ? "$1,234.50\nUTC · 09/06/2026"
          : "1.234,50 ₩\nAsia/Seoul · 07/09/2026";
      await expect(expectedText(page)).toHaveText(expected);
      await expect(page.getByRole("alert")).toHaveCount(0);
      await page.screenshot({ path: evidencePath(`../screenshots/chart-e2e/expression/${scenario}.png`), fullPage: true });
    });
  }
});

test.describe("F · source expression and capability boundaries", () => {
  test("F01 runs standard APIs and synchronous custom renderItem in the native Worker", async ({ page }) => {
    await page.goto(expressionUrl("F01-standard-apis-custom"));
    await expect(status(page)).toHaveText("rendered");
    await expect(page.getByRole("alert")).toHaveCount(0);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/F01-standard-apis-custom.png"), fullPage: true });
  });

  test("F02 preserves the successful canvas across every invalid render root", async ({ page }) => {
    await page.goto(expressionUrl("F02-invalid-render-roots"));
    await expect(status(page)).toHaveText("rendered");
    const canvas = page.locator("canvas[aria-label='Expression chart']");
    const previous = await canvas.screenshot();
    for (const kind of ["undefined", "null", "array", "string", "number", "date", "promise"]) {
      await page.getByRole("button", { name: `Invalid ${kind}`, exact: true }).click();
      await expect(status(page)).toHaveText("failed");
      await expect(page.getByRole("alert")).toContainText("render.option must be a non-null plain option object");
      expect((await canvas.screenshot()).equals(previous)).toBe(true);
    }
    await page.getByRole("button", { name: "Valid empty option", exact: true }).click();
    await expect(status(page)).toHaveText("rendered");
    await expect(page.getByRole("alert")).toHaveCount(0);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/F02-invalid-roots-recover.png"), fullPage: true });
  });

  test("F03 keeps runtime async rejection distinct from the authoring CLI", async ({ page }) => {
    await page.goto(expressionUrl("F03-runtime-async"));
    await expect(status(page)).toHaveText("rendered");
    await page.getByRole("button", { name: "Submit async source", exact: true }).click();
    await expect(status(page)).toHaveText("failed");
    await expect(page.getByRole("alert")).toContainText("async render is not supported");
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/F03-runtime-async.png"), fullPage: true });
  });

  // One real hung loop proves termination; render and microtask loops share the host deadline path covered by unit tests.
  for (const scenario of ["F04-callback-loop"]) {
    test(`F04 ${scenario} times out without freezing the sibling control`, async ({ page }) => {
      await page.goto(expressionUrl(scenario));
      await expect(page.getByRole("alert")).toHaveText("Chart Worker operation timed out", { timeout: 15_000 });
      await expect(status(page)).toHaveText("failed");
      await page.getByRole("button", { name: "Ping sibling control", exact: true }).click();
      await expect(page.getByTestId("expression-ping-count")).toHaveText("pings=1");
      await page.screenshot({ path: evidencePath(`../screenshots/chart-e2e/expression/${scenario}.png`), fullPage: true });
    });
  }

  test("F05 blocks ambient capabilities without external requests", async ({ page }) => {
    const requests: string[] = [];
    page.on("request", request => { if (request.url().startsWith("https://example.test")) requests.push(request.url()); });
    await page.goto(expressionUrl("F05-ambient-authority"));
    await expect(status(page)).toHaveText("rendered");
    await expect(expectedText(page)).toHaveText("fetch:blocked · storage:blocked · dom:blocked · dynamic-import:blocked · random:blocked · clock:blocked · constructor:blocked");
    await expect(page.getByRole("alert")).toHaveCount(0);
    expect(requests).toEqual([]);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/F05-ambient-authority.png"), fullPage: true });
  });

  test("F06 permits URL text and rejects resource effects at their option paths", async ({ page }) => {
    const requests: string[] = [];
    const pages: string[] = [];
    page.on("request", request => { if (request.url().startsWith("https://example.test")) requests.push(request.url()); });
    page.on("popup", popup => pages.push(popup.url()));
    await page.goto(expressionUrl("F06-text-and-effects"));
    await expect(status(page)).toHaveText("rendered");
    await expect(expectedText(page)).toHaveText("https://example.test/plain-text");
    for (const mode of ["external-image", "external-symbol", "callback-symbol", "title-link", "html-tooltip", "toolbox-save"]) {
      await page.getByRole("button", { name: mode, exact: true }).click();
      await expect(status(page)).toHaveText("failed");
      await expect(page.getByRole("alert")).toContainText("option");
    }
    await page.getByRole("button", { name: "text", exact: true }).click();
    await expect(status(page)).toHaveText("rendered");
    expect(requests).toEqual([]);
    expect(pages).toEqual([]);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/F06-text-and-effects.png"), fullPage: true });
  });

  test("F07 decodes supported inline raster formats and rejects disallowed inputs", async ({ page }) => {
    await page.goto(expressionUrl("F07-inline-raster-formats"));
    for (const format of ["png", "jpeg", "webp"]) {
      await page.getByRole("button", { name: `Raster ${format}`, exact: true }).click();
      await expect(status(page)).toHaveText("rendered");
      await expect(page.getByRole("alert")).toHaveCount(0);
    }
    for (const format of ["svg", "corrupt"]) {
      await page.getByRole("button", { name: `Raster ${format}`, exact: true }).click();
      await expect(status(page)).toHaveText("failed");
      await expect(page.getByRole("alert")).toContainText("inline");
    }
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/F07-inline-raster-formats.png"), fullPage: true });
  });

  test("F08 rejects input mutation and keeps the next render identical", async ({ page }) => {
    await page.goto(expressionUrl("F08-input-immutable"));
    await expect(status(page)).toHaveText("rendered");
    await expect(expectedText(page)).toHaveText("row:blocked · column:blocked · variables:blocked · 42.00");
    await expect(page.getByTestId("expression-sibling-status")).toHaveText("sibling=rendered");
    await expect.poll(async () => Number((await page.getByTestId("expression-sibling-render-count").textContent())?.replace("sibling-renders=", "") ?? "0")).toBeGreaterThanOrEqual(2);
    await expect(page.getByTestId("expression-host-input-integrity")).toHaveText("host-input=unchanged");
    const canvas = page.locator("canvas[aria-label='Expression chart']");
    const siblingCanvas = page.locator("canvas[aria-label='Expression sibling chart']");
    await expect(siblingCanvas).toBeVisible();
    const siblingBefore = await siblingCanvas.screenshot();
    const siblingRenderCount = await page.getByTestId("expression-sibling-render-count").textContent();
    const previous = await canvas.screenshot();
    await page.getByRole("button", { name: "Rerender immutable input", exact: true }).click();
    await expect(status(page)).toHaveText("rendered");
    await expect(page.getByTestId("expression-host-input-integrity")).toHaveText("host-input=unchanged");
    await expect(page.getByTestId("expression-sibling-render-count")).toHaveText(siblingRenderCount!);
    expect((await canvas.screenshot()).equals(previous)).toBe(true);
    expect((await siblingCanvas.screenshot()).equals(siblingBefore)).toBe(true);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/F08-input-immutable.png"), fullPage: true });
  });
});

test.describe("G · budget, viewport, and keyboard boundaries", () => {
  test("G01 rejects option and frame overflow while retaining the last canvas", async ({ page }) => {
    await page.goto(expressionUrl("G01-option-budget"));
    await expect(status(page)).toHaveText("rendered");
    const canvas = page.locator("canvas[aria-label='Expression chart']");
    const previous = await canvas.screenshot();
    await page.getByRole("button", { name: "Option over budget", exact: true }).click();
    await expect(status(page)).toHaveText("failed", { timeout: 15_000 });
    await expect(page.getByRole("alert")).toContainText("Chart option exceeds");
    expect((await canvas.screenshot()).equals(previous)).toBe(true);
    await page.getByRole("button", { name: "Frame over budget", exact: true }).click();
    await expect(status(page)).toHaveText("failed");
    await expect(page.getByRole("alert")).toHaveText("Chart frame string exceeds the byte budget (262144 bytes)");
    expect((await canvas.screenshot()).equals(previous)).toBe(true);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/G01-budget-overflow.png"), fullPage: true });
  });

  const callbackBudgetErrors = {
    output: "Chart callback output exceeds the byte budget (524288 bytes)",
    input: "Chart callback input exceeds the byte budget (524288 bytes)",
    depth: "Chart callback input exceeds the safety budget",
  } as const;
  for (const mode of ["output", "input", "depth"] as const) {
    test(`G02 ${mode} callback budget stays bounded`, async ({ page }) => {
      await page.goto(expressionUrl("G02-callback-budgets"));
      await expect(status(page)).toHaveText("rendered");
      await page.getByRole("button", { name: `Callback ${mode}`, exact: true }).click();
      await expect.poll(async () => status(page).textContent(), { timeout: 15_000 }).toMatch(/^(failed|rendered)$/);
      await page.screenshot({ path: evidencePath(`../screenshots/chart-e2e/expression/G02-callback-${mode}.png`), fullPage: true });
      await expect(status(page)).toHaveText("failed", { timeout: 15_000 });
      await expect(page.getByRole("alert")).toHaveText(callbackBudgetErrors[mode]);
    });
  }

  test("G04 renders cold on mobile, resizes, and avoids page overflow", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 820 });
    await page.goto(expressionUrl("G04-mobile-resize"));
    await expect(status(page)).toHaveText("rendered");
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/G04-mobile-cold.png"), fullPage: true });
    await page.getByRole("button", { name: "Resize to 1440", exact: true }).click();
    await expect(status(page)).toHaveText("resized");
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.getByRole("button", { name: "Toggle hidden", exact: true }).click();
    await expect(page.locator("canvas[aria-label='Expression chart']")).toBeHidden();
    await page.getByRole("button", { name: "Toggle hidden", exact: true }).click();
    await expect(page.locator("canvas[aria-label='Expression chart']")).toBeVisible();
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/G04-mobile-resize.png"), fullPage: true });
  });

  test("G05 exposes named keyboard controls and readable error recovery", async ({ page }) => {
    await page.goto(expressionUrl("G05-keyboard-error-recovery"));
    await expect(status(page)).toHaveText("rendered");
    const select = page.getByLabel("Expression scenario");
    await select.focus();
    await page.keyboard.press("Tab");
    await expect(page.getByRole("button", { name: "Ping sibling control", exact: true })).toBeFocused();
    await page.getByRole("button", { name: "Trigger error", exact: true }).click();
    await expect(status(page)).toHaveText("failed");
    await expect(page.getByRole("alert")).toHaveText("Readable fixture error: choose Recover");
    await page.getByRole("button", { name: "Recover", exact: true }).click();
    await expect(status(page)).toHaveText("rendered");
    await expect(page.getByRole("alert")).toHaveCount(0);
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/G05-keyboard-error-recovery.png"), fullPage: true });
  });
});

test.describe("Runtime host · events, exact values, and Worker lifecycle", () => {
  test.use({ viewport: { width: 1200, height: 800 } });
  const runtimeUrl = "/dev/chart-widget-runtime-fixture";
  const runtimeChart = (page: Page) => page.locator('canvas[aria-label="ChartWidget runtime fixture"]');

  test("Chart runtime submits an event value map and renders the accepted value", async ({ page }) => {
    await page.goto(runtimeUrl);
    await expect(page.getByText("rendered", { exact: true })).toBeVisible();
    await expect(page.getByText("selectedRegion = null", { exact: true })).toBeVisible();
    await expect(runtimeChart(page)).toBeVisible();
    await runtimeChart(page).click({ position: { x: 120, y: 240 } });
    await expect(page.getByText("selectedRegion = North", { exact: true })).toBeVisible();
    await expect(page.getByText("rendered", { exact: true })).toBeVisible();
    await page.screenshot({ path: evidencePath("../screenshots/chart-c01-c02/event-selected.png"), fullPage: true });
    await page.getByRole("button", { name: "Clear selection" }).click();
    await expect(page.getByText("selectedRegion = null", { exact: true })).toBeVisible();
  });

  test("Worker preserves owner descriptors and exact cells while rendering URL text", async ({ page }) => {
    await page.goto(runtimeUrl);
    await page.getByLabel("Scenario", { exact: true }).selectOption("exact-values");
    await expect(page.getByText("rendered", { exact: true })).toBeVisible();
    await expect(page.getByRole("alert")).toHaveCount(0);
    const chart = runtimeChart(page);
    await page.screenshot({ path: evidencePath("../screenshots/chart-c01-c02/exact-values-url-labels.png"), fullPage: true });
    // The themed tooltip shares the canvas surface color, so detect it as a region that changes on hover.
    const idle = Array.from(await chart.screenshot());
    const tooltipChangedPixels = async () => {
      const hovered = Array.from(await chart.screenshot());
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
      }, { first: idle, second: hovered });
    };
    expect(await tooltipChangedPixels()).toBeLessThan(500);
    // This fixture fixes the Worker viewport at 720px while the bridge forwards CSS offsets unscaled,
    // so aim at the single bar's center in Worker coordinates: grid left 48 + (720 - 48 - 24) / 2.
    await chart.hover({ position: { x: 372, y: 220 } });
    await expect.poll(tooltipChangedPixels).toBeGreaterThan(5000);
    await page.screenshot({ path: evidencePath("../screenshots/chart-c01-c02/exact-values-url-tooltip.png"), fullPage: true });
  });

  test("Worker renders an inline raster and synchronous custom series", async ({ page }) => {
    await page.goto(runtimeUrl);
    await page.getByLabel("Scenario", { exact: true }).selectOption("inline-custom");
    await expect(page.getByText("rendered", { exact: true })).toBeVisible();
    await expect(page.getByRole("alert")).toHaveCount(0);
    const chart = runtimeChart(page);
    await expect.poll(async () => {
      const png = await chart.screenshot();
      return page.evaluate(async (bytes) => {
        const bitmap = await createImageBitmap(new Blob([new Uint8Array(bytes)], { type: "image/png" }));
        const canvas = document.createElement("canvas");
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const context = canvas.getContext("2d")!;
        context.drawImage(bitmap, 0, 0);
        bitmap.close();
        const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
        let red = 0;
        let blue = 0;
        for (let index = 0; index < pixels.length; index += 4) {
          if (pixels[index] > 200 && pixels[index + 1] < 60 && pixels[index + 2] < 60) red += 1;
          if (pixels[index] === 36 && pixels[index + 1] === 102 && pixels[index + 2] === 195) blue += 1;
        }
        return red > 500 && blue > 500;
      }, Array.from(png));
    }).toBe(true);
    await page.screenshot({ path: evidencePath("../screenshots/chart-c01-c02/inline-custom.png"), fullPage: true });
  });

  test("F03 stored setter is closed after its original synchronous handler", async ({ page }) => {
    await page.addInitScript(() => {
      const scope = window as typeof window & { setterSubmissions: number };
      scope.setterSubmissions = 0;
      const Native = window.Worker;
      window.Worker = class extends Native {
        constructor(...args: ConstructorParameters<typeof Worker>) { super(...args); this.addEventListener("message", event => { if (event.data.type === "variables_set") scope.setterSubmissions++; }); }
      };
    });
    await page.goto("/dev/chart-widget-runtime-fixture");
    await page.getByLabel("Scenario", { exact: true }).selectOption("saved-setter");
    await expect(page.getByText("rendered", { exact: true })).toBeVisible();
    const canvas = page.locator('canvas[aria-label="ChartWidget runtime fixture"]');
    await canvas.click({ position: { x: 120, y: 240 } });
    await canvas.click({ position: { x: 120, y: 240 } });
    await expect(page.getByRole("alert")).toContainText("Variable writes are available only from the current synchronous event handler");
    expect(await page.evaluate(() => (window as typeof window & { setterSubmissions: number }).setterSubmissions)).toBe(0);
    await expect(page.getByText("selectedRegion = null", { exact: true })).toBeVisible();
    await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/expression/F03-closed-setter.png"), fullPage: true });
  });

  test("E06 rerenders do not duplicate setup handlers and source replacement terminates the previous Worker", async ({ page }) => {
    await page.addInitScript(() => {
      const scope = window as typeof window & { lifecycleEvidence: { created: number; terminated: number; submissions: number } };
      scope.lifecycleEvidence = { created: 0, terminated: 0, submissions: 0 };
      const Native = window.Worker;
      window.Worker = class extends Native {
        constructor(...args: ConstructorParameters<typeof Worker>) { super(...args); scope.lifecycleEvidence.created++; this.addEventListener("message", event => { if (event.data.type === "variables_set") scope.lifecycleEvidence.submissions++; }); }
        terminate() { scope.lifecycleEvidence.terminated++; super.terminate(); }
      };
    });
    await page.goto("/dev/chart-widget-runtime-fixture");
    const canvas = page.locator('canvas[aria-label="ChartWidget runtime fixture"]');
    await expect(page.getByText("rendered", { exact: true })).toBeVisible();
    for (let count = 1; count <= 3; count++) {
      await canvas.click({ position: { x: 120, y: 240 } });
      await expect(page.getByText("selectedRegion = North", { exact: true })).toBeVisible();
      await expect.poll(() => page.evaluate(() => (window as typeof window & { lifecycleEvidence: { submissions: number } }).lifecycleEvidence.submissions)).toBe(count);
      await page.getByRole("button", { name: "Clear selection", exact: true }).click();
      await expect(page.getByText("selectedRegion = null", { exact: true })).toBeVisible();
    }
    const before = await page.evaluate(() => (window as typeof window & { lifecycleEvidence: { created: number; terminated: number } }).lifecycleEvidence);
    await page.getByLabel("Scenario", { exact: true }).selectOption("empty-option");
    await expect(page.getByText("rendered", { exact: true })).toBeVisible();
    await expect.poll(() => page.evaluate(() => (window as typeof window & { lifecycleEvidence: { created: number; terminated: number } }).lifecycleEvidence)).toMatchObject({ created: before.created + 1, terminated: before.terminated + 1 });
    await page.goto("/dev/dashboard-builder-fixture?scene=ready");
    await expect.poll(() => page.locator('canvas[aria-label="ChartWidget runtime fixture"]').count()).toBe(0);
  });

  for (const scenario of ["detached-event", "async-event", "async-setup"]) {
    test(`Actual Worker closes ${scenario}`, async ({ page }) => {
      await page.goto(runtimeUrl);
      await page.getByLabel("Scenario", { exact: true }).selectOption(scenario);
      if (scenario !== "async-setup") {
        await expect(page.getByText("rendered", { exact: true })).toBeVisible();
        await runtimeChart(page).click({ position: { x: 120, y: 240 } });
      }
      await expect(page.getByRole("alert")).toContainText(scenario === "detached-event" ? "timed out" : "Async Chart", { timeout: 5_000 });
      await expect(page.getByLabel("Scenario", { exact: true })).toHaveValue(scenario);
      await page.screenshot({ path: evidencePath(`../screenshots/chart-c03-c04/${scenario}.png`), fullPage: true });
    });
  }
});
