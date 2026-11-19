import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import { evidencePath } from "./evidence";

type Frame = { variables: Record<string, string>; queries: Record<string, { columns: string[]; rows: Record<string, string>[] }> };
declare global { interface Window { d10Frame?: Frame; d10Failures: string[]; } }
test.describe("D10 example dashboard", () => {
  test.use({ viewport: { width: 1200, height: 1050 } });
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      const NativeWorker = window.Worker;
      window.d10Failures = [];
      window.Worker = class extends NativeWorker {
        frames = new Map<number, Frame>();
        constructor(...args: ConstructorParameters<typeof Worker>) {
          super(...args);
          this.addEventListener("message", (event) => {
            if (event.data.type === "rendered" && this.frames.has(event.data.operationId)) window.d10Frame = this.frames.get(event.data.operationId);
            if (event.data.type === "failed") window.d10Failures.push(event.data.message);
          });
        }
        override postMessage(message: unknown, transferOrOptions?: Transferable[] | StructuredSerializeOptions) {
          const request = message as { type: string; operationId: number; frame: Frame };
          if (request.type === "render") this.frames.set(request.operationId, request.frame);
          if (Array.isArray(transferOrOptions)) super.postMessage(message, transferOrOptions);
          else super.postMessage(message, transferOrOptions);
        }
      };
    });
    await page.goto("/dev/dashboard-d10-fixture");
    await frame(page, { view: "country", selectedCountry: "KR" });
  });
  async function frame(page: Page, variables: Record<string, string>) {
    await expect.poll(() => page.evaluate(() => window.d10Frame?.variables)).toMatchObject(variables);
    await expect(page.getByRole("alert")).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => window.d10Failures)).toEqual([]);
  }
  async function choose(page: Page, name: string, label: string) {
    const input = page.getByTestId(`dashboard-variable-${name}`);
    await input.getByRole("combobox").click();
    await page.getByRole("option", { name: label, exact: true }).click();
    await expect(input.locator("..").getByRole("button", { name: /^Apply / })).toHaveCount(0);
  }
  async function clickCountry(page: Page, index: number) {
    const box = (await page.getByTestId("dashboard-chart-widget").locator("canvas").boundingBox())!;
    await page.mouse.click(box.x + 64 + (box.width - 88) * (index + 0.5) / 2, box.y + box.height * 0.7);
  }
  async function shot(page: Page, name: string) {
    await page.screenshot({ path: evidencePath(`../screenshots/dashboard-d10/${name}.png`), fullPage: true });
  }

  test("country click, back, channel and day/month use canonical ready results", async ({ page }) => {
    await expect.poll(() => page.evaluate(() => Object.keys(window.d10Frame!.queries))).toEqual(["salesByCountry", "salesByCity", "salesByChannel", "salesByDay", "salesByMonth"]);
    await shot(page, "country");
    await clickCountry(page, 0);
    await frame(page, { view: "city", selectedCountry: "KR" });
    await expect(page.getByTestId("d10-requests")).toHaveText('[{"values":{"selectedCountry":"KR","view":"city"},"displayedInputOrder":0,"accepted":true}]');
    await expect.poll(() => page.evaluate(() => window.d10Frame!.queries.salesByCity.rows.map(row => row["dimension.orders.city"]))).toEqual(["Busan", "Seoul"]);
    await shot(page, "city");
    await choose(page, "view", "Back to countries");
    await frame(page, { view: "country", selectedCountry: "KR" });
    await choose(page, "analysisAxis", "Channel");
    await frame(page, { analysisAxis: "channel" });
    await shot(page, "channel");
    await choose(page, "analysisAxis", "Time");
    await frame(page, { analysisAxis: "time", timeUnit: "day" });
    await shot(page, "day");
    await choose(page, "timeUnit", "Monthly");
    await frame(page, { timeUnit: "month" });
    await expect.poll(() => page.evaluate(() => window.d10Frame!.queries.salesByMonth.rows[0])).toEqual({ "metric_time.month": "2026-01-01", "metric.sales": "1000", "metric.average_order": "250", "metric.customers": "3" });
    await shot(page, "month");
    await choose(page, "timeUnit", "Daily");
    await frame(page, { timeUnit: "day" });
  });

  test("pending city preserves old coherent frame, rejects rapid stale click, adopts latest back state", async ({ page }) => {
    const updating = page.locator('[data-capture-widget-id="salesChart"]').getByRole("status");
    const acceptedUsClick = '[{"values":{"selectedCountry":"US","view":"city"},"displayedInputOrder":0,"accepted":true}]';
    await page.getByRole("button", { name: "Hold city response" }).click();
    await clickCountry(page, 1);
    await expect(page.getByTestId("d10-current")).toContainText('"selectedCountry":"US","view":"city"');
    await frame(page, { view: "country", selectedCountry: "KR" });
    await expect(updating).toHaveText("Updating results");
    await expect(page.getByTestId("dashboard-chart-widget").locator("xpath=ancestor::*[@inert]")).toHaveCount(1);
    await clickCountry(page, 0);
    await expect(page.getByTestId("d10-requests")).toHaveText(acceptedUsClick);
    await shot(page, "pending-stale-click");
    await choose(page, "view", "Back to countries");
    await expect(page.getByTestId("d10-current")).toContainText('"revision":2');
    await frame(page, { view: "country", selectedCountry: "KR" });
    await page.getByRole("button", { name: "Deliver US result" }).click();
    await frame(page, { view: "country", selectedCountry: "US" });
    await expect(updating).toHaveCount(0);
    expect(await page.getByTestId("d10-requests").textContent()).not.toContain('"accepted":false');
    await expect.poll(() => page.evaluate(() => window.d10Frame!.queries.salesByCity.rows.map(row => row["dimension.orders.city"]))).toEqual(["Austin", "Boston"]);
    await choose(page, "view", "Cities in selected country");
    await frame(page, { view: "city", selectedCountry: "US" });
    await shot(page, "us-city-after-back");
  });

  test("failure retains last-good frame and empty city renders without wrong-field error", async ({ page }) => {
    await page.getByRole("button", { name: "Hold city response" }).click();
    await clickCountry(page, 1);
    await page.getByRole("button", { name: "Fail current result" }).click();
    await expect(page.getByRole("alert")).toHaveText("The latest run failed. Previous results are shown.");
    await expect.poll(() => page.evaluate(() => window.d10Frame!.variables.selectedCountry)).toBe("KR");
    await shot(page, "failed-last-good");
    await page.getByRole("button", { name: "Deliver US result" }).click();
    await frame(page, { view: "city", selectedCountry: "US" });
    await page.getByRole("button", { name: "Empty city result" }).click();
    await expect.poll(() => page.evaluate(() => window.d10Frame!.queries.salesByCity.rows)).toEqual([]);
    await expect.poll(() => page.evaluate(() => window.d10Failures)).toEqual([]);
    await shot(page, "empty-city");
  });

  test("narrow layout keeps controls and chart visible", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 1000 });
    await frame(page, { view: "country" });
    await choose(page, "analysisAxis", "Time");
    await choose(page, "timeUnit", "Monthly");
    await frame(page, { analysisAxis: "time", timeUnit: "month" });
    await page.evaluate(() => new Promise<void>(resolve => {
      let frames = 0;
      const check = () => { if (++frames === 60) resolve(); else requestAnimationFrame(check); };
      requestAnimationFrame(check);
    }));
    await expect(page.getByRole("alert")).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => window.d10Failures)).toEqual([]);
    await expect.poll(() => page.getByTestId("dashboard-chart-widget").evaluate(element => element.clientHeight)).toBe(320);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await shot(page, "mobile-month");
    await expect(page.getByRole("alert")).toHaveCount(0);
    await expect.poll(() => page.getByTestId("dashboard-chart-widget").evaluate(element => element.clientHeight)).toBe(320);
  });
});
