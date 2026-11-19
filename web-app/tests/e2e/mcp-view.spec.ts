import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import type { FrameLocator, Page } from "@playwright/test";
import { evidencePath } from "./evidence";
import {
  BLOCKED_WORKER_CSP,
  DRAFT_PATH,
  DRAFT_STATUS_PATH,
  draftRead,
  draftReads,
  draftStatusRead,
  HOST_ORIGIN,
  HOST_SANDBOX_CSP,
  hostPage,
  ORDERS_RUN,
  ORDERS_RUN_NEXT,
  ordersNextResult,
  SANDBOX_ORIGIN,
  showResult,
  WEB_URL,
  type ToolResult,
} from "../../src/mcp-view/fixtures";
import { expect, test } from "./support/test";

// The view ships as one HTML file that chat hosts load in a sandboxed iframe on their own origin.
// A fake host page on another origin embeds the freshly built file and answers the MCP Apps
// protocol: it delivers a monomer_show result and answers monomer_read from fixtures.

type HostCall = { frame: string; name: string; path: string };
type HostWindow = Window & { mcpHost: { calls: HostCall[]; openedLinks: string[]; setRead(path: string, result: ToolResult): void } };

const WEB_APP_DIRECTORY = fileURLToPath(new URL("../..", import.meta.url));
let viewHtml = "";

test.use({ viewport: { width: 900, height: 1400 } });

test.beforeAll(() => {
  execFileSync("npm", ["run", "build:mcp-view"], { cwd: WEB_APP_DIRECTORY, stdio: "pipe" });
  viewHtml = readFileSync(`${WEB_APP_DIRECTORY}/dist-mcp-view/dashboard-view.html`, "utf8");
});

async function openHost(page: Page, options: { shows: ToolResult[]; reads: Record<string, ToolResult>; csp?: string }) {
  await page.context().route(`${SANDBOX_ORIGIN}/**`, (route) => route.fulfill({
    status: 200,
    contentType: "text/html; charset=utf-8",
    headers: { "content-security-policy": options.csp ?? HOST_SANDBOX_CSP },
    body: viewHtml,
  }));
  await page.context().route(`${HOST_ORIGIN}/**`, (route) => route.fulfill({
    status: 200,
    contentType: "text/html; charset=utf-8",
    body: hostPage({ viewUrl: `${SANDBOX_ORIGIN}/dashboard-view.html`, shows: options.shows, reads: options.reads }),
  }));
  await page.goto(`${HOST_ORIGIN}/host.html`);
}

const view = (page: Page, index = 0): FrameLocator => page.frameLocator("iframe").nth(index);
const hostCalls = (page: Page) => page.evaluate(() => (window as unknown as HostWindow).mcpHost.calls);
const setRead = (page: Page, path: string, result: ToolResult) => page.evaluate(
  ({ path, serialized }) => (window as unknown as HostWindow).mcpHost.setRead(path, JSON.parse(serialized)),
  { path, serialized: JSON.stringify(result) },
);

async function expectRenderedDraft(frame: FrameLocator) {
  await expect(frame.getByTestId("mcp-view-status")).toHaveText("Draft · Updated Just now");
  await expect(frame.getByText("First draft notes.")).toBeVisible();
  await expect(frame.getByTestId("dashboard-chart-widget")).toHaveAttribute("data-chart-status", "ready", { timeout: 20_000 });
  await expect(frame.getByRole("img", { name: "Revenue by region" })).toBeVisible();
}

test("renders a Draft chart and table with the app's Workers through monomer_read only", async ({ page }) => {
  await openHost(page, { shows: [showResult({ requestId: "req-1", shownAt: 1000 })], reads: draftReads({ formattedTable: true }) });
  const frame = view(page);
  await expectRenderedDraft(frame);
  const table = frame.getByRole("grid", { name: "Orders by region" });
  await expect(table.getByRole("gridcell", { name: "12 orders", exact: true })).toBeVisible({ timeout: 20_000 });
  await expect(table.getByRole("gridcell", { name: "EMEA", exact: true })).toBeVisible();
  const calls = await hostCalls(page);
  expect(new Set(calls.map((call) => call.name))).toEqual(new Set(["monomer_read"]));
  expect(calls.slice(0, 4).map((call) => call.path)).toEqual([DRAFT_PATH, DRAFT_STATUS_PATH, `${DRAFT_PATH}/query-runs/01920000-0000-7000-8000-000000000101/result`, `${ORDERS_RUN}/result`]);
  await page.screenshot({ path: evidencePath("../screenshots/mcp-view/draft-inline.png"), fullPage: true });

  await frame.getByRole("button", { name: "Open in Monomer" }).first().click();
  await expect.poll(() => page.evaluate(() => (window as unknown as HostWindow).mcpHost.openedLinks)).toEqual([WEB_URL]);
  await frame.getByRole("button", { name: "Expand" }).click();
  await expect(frame.getByRole("button", { name: "Exit full screen" })).toBeVisible();
  await expect(frame.getByTestId("dashboard-chart-widget")).toHaveAttribute("data-chart-status", "ready");
});

test("follows a new Draft revision reported by the status poll", async ({ page }) => {
  await openHost(page, { shows: [showResult({ requestId: "req-1", shownAt: 1000 })], reads: draftReads({ formattedTable: false }) });
  const frame = view(page);
  await expectRenderedDraft(frame);
  await setRead(page, DRAFT_PATH, draftRead({ revision: "2", notes: "Second draft notes after an edit.", formattedTable: false, ordersRun: ORDERS_RUN }));
  await setRead(page, DRAFT_STATUS_PATH, draftStatusRead({ revision: "2", ordersRun: ORDERS_RUN }));
  await expect(frame.getByText("Second draft notes after an edit.")).toBeVisible({ timeout: 10_000 });
  await expect(frame.getByText("First draft notes.")).toHaveCount(0);
  await expect(frame.getByTestId("dashboard-chart-widget")).toHaveAttribute("data-chart-status", "ready");
});

test("hydrates a changed ready bundle with only the new result", async ({ page }) => {
  await openHost(page, { shows: [showResult({ requestId: "req-1", shownAt: 1000 })], reads: draftReads({ formattedTable: false }) });
  const frame = view(page);
  await expectRenderedDraft(frame);
  const table = frame.getByRole("grid", { name: "Orders by region" });
  await expect(table.getByRole("gridcell", { name: "12", exact: true })).toBeVisible();
  await setRead(page, `${ORDERS_RUN_NEXT}/result`, ordersNextResult);
  await setRead(page, DRAFT_STATUS_PATH, draftStatusRead({ revision: "1", ordersRun: ORDERS_RUN_NEXT }));
  await expect(table.getByRole("gridcell", { name: "LATAM", exact: true })).toBeVisible({ timeout: 10_000 });
  await expect(table.getByRole("gridcell", { name: "31", exact: true })).toBeVisible();
  const resultReads = (await hostCalls(page)).filter((call) => call.path.endsWith("/result")).map((call) => call.path);
  expect(resultReads.filter((path) => path === `${ORDERS_RUN}/result`)).toHaveLength(1);
  expect(resultReads.filter((path) => path === `${ORDERS_RUN_NEXT}/result`)).toHaveLength(1);
  await page.screenshot({ path: evidencePath("../screenshots/mcp-view/draft-hydrated.png"), fullPage: true });
});

test("closes with an out-of-date notice for another view contract", async ({ page }) => {
  await page.clock.install();
  await openHost(page, {
    shows: [showResult({ requestId: "req-1", shownAt: 1000, viewContract: 2 }), showResult({ requestId: "req-2", shownAt: 2000 })],
    reads: draftReads({ formattedTable: false }),
  });
  const stale = view(page, 0);
  await expect(stale.getByText("This view is out of date.")).toBeVisible();
  await expect(stale.getByText("Reconnect the Monomer connector, or open the dashboard in Monomer.")).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/mcp-view/out-of-date.png"), fullPage: true });

  const live = view(page, 1);
  await expectRenderedDraft(live);
  const status = draftStatusRead({ revision: "1", ordersRun: ORDERS_RUN });
  await setRead(page, DRAFT_STATUS_PATH, { ...status, _meta: { "cc.monomer/viewContract": 2 } });
  await expect(live.getByText("This view is out of date.")).toBeVisible({ timeout: 10_000 });
  const callsWhenStopped = (await hostCalls(page)).length;
  await page.clock.fastForward(4_500);
  const calls = await hostCalls(page);
  expect(calls).toHaveLength(callsWhenStopped);
  expect(calls.filter((call) => call.frame === "Dashboard view 1")).toEqual([]);
});

test("an older view of the same Draft stops when a newer one announces", async ({ page }) => {
  await page.clock.install();
  await openHost(page, {
    shows: [showResult({ requestId: "req-1", shownAt: 1000 }), showResult({ requestId: "req-2", shownAt: 2000 })],
    reads: draftReads({ formattedTable: false }),
  });
  const older = view(page, 0);
  const newer = view(page, 1);
  await expect(older.getByText("A newer view of this dashboard is open below.").first()).toBeVisible();
  await expectRenderedDraft(newer);
  const olderCalls = (await hostCalls(page)).filter((call) => call.frame === "Dashboard view 1").length;
  await page.clock.fastForward(4_500);
  expect((await hostCalls(page)).filter((call) => call.frame === "Dashboard view 1")).toHaveLength(olderCalls);
  await expect.poll(async () => (await hostCalls(page)).filter((call) => call.frame === "Dashboard view 2" && call.path === DRAFT_STATUS_PATH).length).toBeGreaterThan(1);
  await page.screenshot({ path: evidencePath("../screenshots/mcp-view/superseded.png"), fullPage: true });
});

test("falls back without running source when the host blocks Workers", async ({ page, consoleErrors }) => {
  consoleErrors.allow({
    message: /violates the following Content Security Policy directive: "worker-src 'none'"/,
    reason: "The fake host forbids Workers so the capability probe fails and the view falls back.",
  });
  await openHost(page, { shows: [showResult({ requestId: "req-1", shownAt: 1000 })], reads: draftReads({ formattedTable: true }), csp: BLOCKED_WORKER_CSP });
  const frame = view(page);
  await expect(frame.getByText("This chart can't run in this chat.")).toBeVisible({ timeout: 10_000 });
  await expect(frame.getByTestId("dashboard-chart-widget")).toHaveCount(0);
  await expect(frame.getByTestId("mcp-view-chart-placeholder").getByRole("button", { name: "Open in Monomer" })).toBeVisible();
  await expect(frame.getByText("Table formatting could not be applied. Showing the default table. This chat can't run table formatting.")).toBeVisible();
  const table = frame.getByRole("grid", { name: "Orders by region" });
  await expect(table.getByRole("gridcell", { name: "12", exact: true })).toBeVisible();
  await expect(table.getByRole("gridcell", { name: "12 orders", exact: true })).toHaveCount(0);
  await expect(frame.getByText("First draft notes.")).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/mcp-view/workers-blocked.png"), fullPage: true });
});
