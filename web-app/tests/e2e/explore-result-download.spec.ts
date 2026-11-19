import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import { evidencePath } from "./evidence";
import { openExploreRuns, runPanel } from "./explore-runs-api-fixture";

const runsRail = (page: Page) => page.getByRole("complementary", { name: "Explore runs" });

async function openResult(page: Page, mode: "save" | "cancel" | "slow" = "save", empty = false) {
  await page.addInitScript((mode) => {
    const events: string[] = [];
    Object.assign(window, { downloadEvents: events, showSaveFilePicker: async (options: { suggestedName: string }) => {
      events.push(options.suggestedName);
      if (mode === "cancel") throw new DOMException("Cancelled", "AbortError");
      return { createWritable: async () => new WritableStream({
        async write(chunk: Uint8Array) { events.push(`write:${chunk.byteLength}`); if (mode === "slow") await new Promise((resolve) => setTimeout(resolve, 400)); },
        close() { events.push("close"); }, abort() { events.push("abort"); },
      }) };
    } });
  }, mode);
  await openExploreRuns(page);
  if (empty) await page.getByRole("button", { name: /^Runs/ }).click();
  await runsRail(page).getByRole("button", { name: empty ? /Refunded orders/ : /Gross revenue/ }).click();
}
/** Downloads go through one Download menu that lists the formats and the result's availability. */
async function downloadAs(page: Page, format: "CSV" | "Arrow original") {
  await page.getByRole("button", { name: "Download result" }).click();
  await page.getByRole("menuitem", { name: new RegExp(`^Download ${format}`) }).click();
}
const events = (page: Page) => page.evaluate(() => (window as unknown as { downloadEvents: string[] }).downloadEvents);

test("streams default CSV and Arrow original and returns to repeatable actions", async ({ page }) => {
  const formats: string[] = [];
  await page.route("**/explore/runs/*/result?format=*", async (route) => { formats.push(new URL(route.request().url()).search); await route.fulfill({ body: "\ufeffcolumn\r\n", contentType: "text/csv" }); });
  await openResult(page);
  await expect(runPanel(page, "Gross revenue by Region").getByText("First 20 of 48 rows")).toBeVisible();
  await expect(page.getByText("CSV prefixes formula-like text and headers with a tab. Use Arrow for original values.")).toHaveCount(0);
  await downloadAs(page, "CSV");
  await expect.poll(() => events(page)).toContain("close");
  await page.getByRole("button", { name: "Download result" }).click();
  await expect(page.getByRole("menuitem", { name: /^Download Arrow original/ })).toContainText("12 KB");
  await expect(page.getByRole("menu")).toContainText("Available until");
  await page.screenshot({ path: evidencePath("../screenshots/explore-result-download-menu.png") });
  await page.getByRole("menuitem", { name: /^Download Arrow original/ }).click();
  await expect.poll(() => formats).toEqual(["?format=csv", "?format=arrow"]);
  await expect(page.getByRole("button", { name: "Download result" })).toBeEnabled();
});
test("picker cancellation never fetches a result", async ({ page }) => {
  let requests = 0;
  await page.route("**/explore/runs/*/result?format=*", () => { requests++; });
  await openResult(page, "cancel");
  await downloadAs(page, "CSV");
  await expect(page.getByRole("button", { name: "Download result" })).toBeEnabled();
  expect(requests).toBe(0); await expect(page.getByRole("alert")).toHaveCount(0);
});
test("HTTP failure aborts the file and reports safe failure", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 410, url: /\/result\?format=csv$/, reason: "The fixture answers the download as expired." });
  await page.route("**/explore/runs/*/result?format=*", (route) => route.fulfill({ status: 410, body: "private upstream failure" }));
  await openResult(page);
  await downloadAs(page, "CSV");
  await expect(page.getByRole("alert")).toContainText("Request failed with 410.");
  expect(await events(page)).toContain("abort"); expect(await events(page)).not.toContain("close");
  await page.screenshot({ path: evidencePath("../screenshots/explore-result-download-error.png") });
});
test("cancel and opening another run abort the download without cancelling the run", async ({ page }) => {
  await page.route("**/explore/runs/*/result?format=*", (route) => route.fulfill({ body: "column\r\n" }));
  await openResult(page, "slow");
  await downloadAs(page, "CSV");
  await page.getByRole("button", { name: "Cancel download" }).click();
  await expect.poll(() => events(page)).toContain("abort");
  await downloadAs(page, "CSV");
  await runsRail(page).getByRole("button", { name: /Product events/ }).click();
  await expect(runPanel(page, "Product events").getByText(/execution timeout/)).toBeVisible();
  await expect.poll(async () => (await events(page)).filter((event) => event === "abort").length).toBe(2);
  expect(await events(page)).not.toContain("close");
});
test("browsers without direct file saving fall back to a normal download", async ({ page }) => {
  await page.addInitScript(() => Object.defineProperty(window, "showSaveFilePicker", { value: undefined }));
  const formats: string[] = [];
  await page.route("**/explore/runs/*/result?format=*", async (route) => {
    formats.push(new URL(route.request().url()).search);
    await route.fulfill({ body: "\ufeffcolumn\r\nvalue\r\n", contentType: "text/csv" });
  });
  await openExploreRuns(page); await runsRail(page).getByRole("button", { name: /Gross revenue/ }).click();
  const downloadButton = page.getByRole("button", { name: "Download result" });
  await expect(downloadButton).toBeEnabled();
  await expect(page.getByText("File downloads require Chrome or Edge with direct file saving support.")).toHaveCount(0);
  const downloadEvent = page.waitForEvent("download");
  await downloadAs(page, "CSV");
  const download = await downloadEvent;
  expect(download.suggestedFilename()).toBe("explore-019e8000-0000-7000-8000-000000003842.csv");
  expect(formats).toEqual(["?format=csv"]);
  await expect(downloadButton).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/explore-result-download-fallback.png") });
});


test("zero-row CSV still saves its header on a narrow screen", async ({ page }) => {
  await page.setViewportSize({ width: 720, height: 800 });
  await page.route("**/explore/runs/*/result?format=csv", (route) => route.fulfill({ body: "\ufeffcolumn\r\n", contentType: "text/csv" }));
  await openResult(page, "save", true);
  await expect(page.getByTestId("run-sample-grid").locator("tbody tr")).toHaveCount(0);
  await downloadAs(page, "CSV");
  await expect.poll(() => events(page)).toContain("close");
  await page.screenshot({ path: evidencePath("../screenshots/explore-result-download-empty-narrow.png") });
});


test("a late error from the previous run does not alter the new download", async ({ page }) => {
  await openResult(page);
  await page.evaluate(() => {
    const rejectPickers: ((reason: Error) => void)[] = [];
    Object.assign(window, { rejectPickers, showSaveFilePicker: () => new Promise((_resolve, reject) => rejectPickers.push(reject)) });
  });
  await downloadAs(page, "CSV");
  await runsRail(page).getByRole("button", { name: /Retained accounts/ }).click();
  await downloadAs(page, "CSV");
  await page.evaluate(() => {
    (window as unknown as { rejectPickers: ((reason: Error) => void)[] }).rejectPickers[0](new Error("Old picker failure"));
  });
  await expect(page.getByRole("status").filter({ hasText: "Saving" })).toHaveText("Saving CSV…");
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.evaluate(() => {
    (window as unknown as { rejectPickers: ((reason: Error) => void)[] }).rejectPickers[1](new DOMException("Cancelled", "AbortError"));
  });
  await expect(page.getByRole("button", { name: "Download result" })).toBeEnabled();
  await expect(page.getByRole("alert")).toHaveCount(0);
});
