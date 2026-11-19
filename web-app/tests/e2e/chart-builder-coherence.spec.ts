import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";

test("E04 actual Builder Surface keeps USD Chart and TABLE through EUR pending, failure and hydration", async ({ page }) => {
  await page.addInitScript(() => {
    const scope = window as typeof window & { coherenceFrames: Record<number, { parameters: { currency: string }; queries: { sales: { rows: Record<string, string>[] } } }>; coherenceDisplayed?: unknown };
    scope.coherenceFrames = {};
    const Native = window.Worker;
    window.Worker = class extends Native {
      constructor(...args: ConstructorParameters<typeof Worker>) { super(...args); this.addEventListener("message", event => { if (event.data.type === "rendered" && scope.coherenceFrames[event.data.operationId]) scope.coherenceDisplayed = scope.coherenceFrames[event.data.operationId]; }); }
      override postMessage(message: unknown, transferOrOptions?: Transferable[] | StructuredSerializeOptions) {
        const request = message as { type: string; operationId: number; frame: typeof scope.coherenceFrames[number] };
        if (request.type === "render") scope.coherenceFrames[request.operationId] = request.frame;
        if (Array.isArray(transferOrOptions)) super.postMessage(message, transferOrOptions);
        else super.postMessage(message, transferOrOptions);
      }
    };
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/dev/dashboard-builder-fixture?scene=parameter-coherence");
  const displayed = () => page.evaluate(() => (window as typeof window & { coherenceDisplayed?: unknown }).coherenceDisplayed);
  await expect(page.getByTestId("dashboard-chart-widget")).toHaveAttribute("data-chart-status", "ready", { timeout: 15_000 });
  await expect.poll(displayed, { timeout: 15_000 }).toMatchObject({ parameters: { currency: "USD" }, queries: { sales: { rows: [{ "metric.sales": "100.00" }] } } });
  await expect(page.getByRole("gridcell", { name: "USD 100.00", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Change preview inputs", exact: true }).click();
  const currency = page.getByTestId("dashboard-parameter-currency");
  await currency.getByRole("textbox").fill("EUR"); await currency.getByRole("button", { name: "Set Currency", exact: true }).click();
  for (const [state, button] of [["pending", null], ["failed", "Fail target"], ["hydrating", "Succeed before result read"]] as const) {
    if (button) await page.getByRole("button", { name: button, exact: true }).click();
    await expect(page.getByTestId("coherence-state")).toHaveText(state);
    await expect(currency.getByRole("textbox")).toHaveValue("EUR");
    await expect(page.getByRole("gridcell", { name: "USD 100.00", exact: true })).toBeVisible();
    await expect.poll(displayed).toMatchObject({ parameters: { currency: "USD" }, queries: { sales: { rows: [{ "metric.sales": "100.00" }] } } });
    await page.screenshot({ path: evidencePath(`../screenshots/chart-e2e/builder-${state}.png`), fullPage: true });
  }
  await page.getByRole("button", { name: "Deliver result", exact: true }).click();
  await expect(page.getByRole("gridcell", { name: "EUR 120.00", exact: true })).toBeVisible();
  await expect.poll(displayed).toMatchObject({ parameters: { currency: "EUR" }, queries: { sales: { rows: [{ "metric.sales": "120.00" }] } } });
  await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/builder-success.png"), fullPage: true });
});


test("Builder stops hydration and removes retained data when the new ready result is denied", async ({ page }) => {
  await page.goto("/dev/dashboard-builder-fixture?scene=parameter-coherence");
  await expect(page.getByRole("gridcell", { name: "USD 100.00", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Succeed before result read", exact: true }).click();
  await expect(page.getByRole("gridcell", { name: "USD 100.00", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Deny result read", exact: true }).click();
  await expect(page.getByRole("gridcell", { name: "USD 100.00", exact: true })).toHaveCount(0);
  await expect(page.getByTestId("dashboard-chart-widget")).toHaveCount(0);
  await expect(page.getByText("Loading results…", { exact: true })).toHaveCount(0);
  await expect(page.getByText("You do not have access to this result. Check your data permissions.", { exact: true })).toHaveCount(2);
  await page.screenshot({ path: evidencePath("../screenshots/chart-e2e/builder-denied-new-result.png"), fullPage: true });
});
