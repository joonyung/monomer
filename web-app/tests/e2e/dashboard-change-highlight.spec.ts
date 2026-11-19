import { expect, test } from "./support/test";
import type { Page } from "@playwright/test";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { evidencePath } from "./evidence";
import { mockSignedInWorkspace } from "./support/session";

const surface = (page: Page, id: string) => page.locator(`[data-change-id="${id}"]`);
const active = (page: Page) => page.locator('[data-change-active="true"]');

async function act(page: Page, name: string) {
  // DOM click models an incoming snapshot without moving focus to fixture controls.
  await page.getByRole("button", { name, exact: true }).evaluate((button: HTMLButtonElement) => button.click());
}

async function pauseHighlights(page: Page, time = 150) {
  await expect(active(page).first()).toBeAttached();
  await page.locator(".change-highlight-overlay").evaluateAll((overlays, currentTime) => {
    for (const overlay of overlays) for (const animation of overlay.getAnimations()) {
      animation.pause();
      animation.currentTime = currentTime;
    }
  }, time);
}

async function finishHighlights(page: Page) {
  await page.locator(".change-highlight-overlay").evaluateAll(overlays => {
    for (const overlay of overlays) for (const animation of overlay.getAnimations()) animation.finish();
  });
  await expect(active(page)).toHaveCount(0);
}

async function waitForPaintedChart(page: Page) {
  await expect.poll(() => surface(page, "chart").locator("canvas").evaluate((canvas: HTMLCanvasElement) => {
    const sample = document.createElement("canvas");
    sample.width = canvas.width;
    sample.height = canvas.height;
    const context = sample.getContext("2d")!;
    context.drawImage(canvas, 0, 0);
    const pixels = context.getImageData(0, 0, sample.width, sample.height).data;
    let bluePixels = 0;
    for (let index = 0; index < pixels.length; index += 4) {
      if (pixels[index + 2] > pixels[index] + 50 && pixels[index + 3] > 200) bluePixels += 1;
    }
    return bluePixels;
  })).toBeGreaterThan(100);
  await expect(surface(page, "chart").getByRole("alert")).toHaveCount(0);
}

test.beforeEach(async ({ page }, testInfo) => {
  if (testInfo.title.startsWith("production")) return;
  await page.setViewportSize({ width: 1280, height: 1000 });
  await page.goto("/dev/dashboard-builder-fixture?scene=changes");
  await expect(surface(page, "chart").locator("canvas")).toBeVisible();
  await waitForPaintedChart(page);
  await expect(surface(page, "table")).toContainText("COMPLETED");
  await expect(active(page)).toHaveCount(0);
});

test("highlights a changed opaque chart and text without replacing content, focus, results or scroll", async ({ page }) => {
  await page.reload();
  await waitForPaintedChart(page);
  const originalChart = await surface(page, "chart").locator("canvas").elementHandle();
  const table = await surface(page, "table").locator("table").elementHandle();
  const input = surface(page, "input").getByRole("combobox");
  const inputNode = await input.elementHandle();
  const selectedCell = surface(page, "table").getByRole("gridcell", { name: "COMPLETED", exact: true });
  await selectedCell.focus();
  const preview = page.getByTestId("dashboard-builder-preview");
  await preview.evaluate(element => { element.scrollTop = 40; });
  const scrollTop = await preview.evaluate(element => element.scrollTop);
  await page.screenshot({ path: evidencePath("../screenshots/joo-50/dashboard-normal.png") });

  await act(page, "Update chart");
  await pauseHighlights(page);
  await waitForPaintedChart(page);
  expect(await originalChart!.evaluate(element => element.isConnected)).toBe(false);
  let chart = await surface(page, "chart").locator("canvas").elementHandle();
  await expect(active(page)).toHaveCount(1);
  await expect(surface(page, "chart")).toHaveAttribute("data-change-active", "true");
  const overlay = surface(page, "chart").locator(":scope > .change-highlight-overlay");
  await expect(overlay).toHaveCSS("opacity", "1");
  await expect(overlay).toHaveCSS("pointer-events", "none");
  expect(await overlay.evaluate(element => {
    const parent = element.parentElement!;
    const canvas = parent.querySelector("canvas")!;
    const overlayBox = element.getBoundingClientRect();
    const canvasBox = canvas.getBoundingClientRect();
    return overlayBox.left <= canvasBox.left && overlayBox.right >= canvasBox.right && overlayBox.top <= canvasBox.top && overlayBox.bottom >= canvasBox.bottom;
  })).toBe(true);
  await expect(surface(page, "chart").getByRole("alert")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/joo-50/dashboard-active.png") });
  await finishHighlights(page);
  await expect(overlay).toHaveCSS("opacity", "0");
  await expect(surface(page, "chart").getByRole("alert")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("../screenshots/joo-50/dashboard-finished.png") });
  for (let update = 0; update < 2; update += 1) {
    const previousChart = chart;
    await act(page, "Update chart");
    await pauseHighlights(page);
    await waitForPaintedChart(page);
    expect(await previousChart!.evaluate(element => element.isConnected)).toBe(false);
    chart = await surface(page, "chart").locator("canvas").elementHandle();
    await finishHighlights(page);
  }
  for (const action of ["Unchanged snapshot", "Revision only", "Run only"]) {
    await act(page, action);
    await waitForPaintedChart(page);
    expect(await chart!.evaluate(element => element === document.querySelector('[data-change-id="chart"] canvas'))).toBe(true);
    await expect(active(page)).toHaveCount(0);
  }
  await act(page, "Update text");
  await pauseHighlights(page);
  await expect(active(page)).toHaveCount(1);
  await expect(surface(page, "text")).toHaveAttribute("data-change-active", "true");
  await expect(surface(page, "text")).toContainText("Updated orders");
  expect(await chart!.evaluate(element => element === document.querySelector('[data-change-id="chart"] canvas'))).toBe(true);
  expect(await table!.evaluate(element => element === document.querySelector('[data-change-id="table"] table'))).toBe(true);
  expect(await inputNode!.evaluate(element => element === document.querySelector('[data-change-id="input"] [role="combobox"], [data-change-id="input"] select'))).toBe(true);
  await expect(selectedCell).toBeFocused();
  expect(await preview.evaluate(element => element.scrollTop)).toBe(scrollTop);
  await expect(surface(page, "table")).toContainText("COMPLETED");
  await finishHighlights(page);
  await page.getByRole("link", { name: "Leave chart fixture" }).click();
  await expect(surface(page, "chart")).toHaveCount(0);
  await page.goBack();
  await waitForPaintedChart(page);
  await expect(active(page)).toHaveCount(0);
});

test("ignores unchanged snapshots, revisions and runs, and highlights only a newly placed widget", async ({ page }) => {
  for (const action of ["Unchanged snapshot", "Revision only", "Run only"]) {
    await act(page, action);
    await expect(active(page)).toHaveCount(0);
  }
  await act(page, "Add widget");
  await pauseHighlights(page);
  await expect(active(page)).toHaveCount(1);
  await expect(surface(page, "added")).toHaveAttribute("data-change-active", "true");
  await finishHighlights(page);
});

test("new widgets enter without changing layout size and existing widget updates stay still", async ({ page }) => {
  expect(await page.locator("[data-change-id]").evaluateAll(elements => elements.flatMap(element => element.getAnimations()).length)).toBe(0);
  const chartSize = await surface(page, "chart").locator("canvas").evaluate((canvas: HTMLCanvasElement) => ({ width: canvas.width, height: canvas.height }));
  await page.getByRole("button", { name: "Add widget", exact: true }).evaluate(async (button: HTMLButtonElement) => {
    button.click();
    await new Promise(requestAnimationFrame);
    for (const animation of document.querySelector('[data-change-id="added"]')!.getAnimations()) {
      animation.pause(); animation.currentTime = 0;
    }
  });
  const added = surface(page, "added");
  await added.evaluate(element => { element.getAnimations()[0].currentTime = 140; });
  await added.scrollIntoViewIfNeeded();
  await page.screenshot({ path: evidencePath("../screenshots/northstar-draft-arrival-midpoint.png") });
  const motion = await added.evaluate(element => {
    const animation = element.getAnimations()[0];
    const effect = animation.effect as KeyframeEffect;
    const frames = effect.getKeyframes();
    animation.currentTime = 0;
    const initialSize = { width: element.clientWidth, height: element.clientHeight };
    animation.currentTime = 140;
    const middleSize = { width: element.clientWidth, height: element.clientHeight };
    animation.finish();
    return { duration: effect.getTiming().duration, transforms: frames.map(frame => frame.transform),
      opacities: frames.map(frame => frame.opacity), changesHeight: frames.some(frame => "height" in frame),
      initialSize, middleSize, finalSize: { width: element.clientWidth, height: element.clientHeight } };
  });
  expect(motion.duration).toBe(280);
  expect(motion.transforms).toEqual(["translateY(8px)", "translateY(0px)"]);
  expect(motion.opacities).toEqual(["0", "1"]);
  expect(motion.changesHeight).toBe(false);
  expect(motion.initialSize.height).toBeGreaterThan(0);
  expect(motion.initialSize).toEqual(motion.middleSize);
  expect(motion.initialSize).toEqual(motion.finalSize);
  expect(await surface(page, "chart").locator("canvas").evaluate((canvas: HTMLCanvasElement) => ({ width: canvas.width, height: canvas.height }))).toEqual(chartSize);
  await finishHighlights(page);
  await act(page, "Update text");
  await pauseHighlights(page);
  expect(await surface(page, "text").evaluate(element => element.getAnimations().length)).toBe(0);
  await finishHighlights(page);
});

test("reduced motion adds widgets without entrance movement", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await act(page, "Add widget");
  await pauseHighlights(page, 250);
  expect(await surface(page, "added").evaluate(element => element.getAnimations().length)).toBe(0);
  await expect(surface(page, "added")).toHaveCSS("transform", "none");
  await expect(surface(page, "added").locator(".change-highlight-overlay")).toHaveCSS("opacity", "1");
  await finishHighlights(page);
});

test("limits layout highlights to affected GAP and DIVIDER sections", async ({ page }) => {
  for (const [action, ids] of [["Resize GAP section", ["chart", "table"]], ["Resize DIVIDER section", ["input", "text"]]] as const) {
    await act(page, action);
    await pauseHighlights(page);
    expect(await active(page).evaluateAll(elements => elements.map(element => element.getAttribute("data-change-id")).sort())).toEqual([...ids].sort());
    await finishHighlights(page);
  }
});

test("replaces a rapid update effect and resets on dashboard and workspace changes", async ({ page }) => {
  await act(page, "Update text");
  await pauseHighlights(page, 400);
  const overlay = surface(page, "text").locator(".change-highlight-overlay");
  const previousOpacity = Number(await overlay.evaluate(element => getComputedStyle(element).opacity));
  await act(page, "Update text");
  await pauseHighlights(page, 0);
  expect(await overlay.evaluate(element => element.getAnimations().length)).toBe(1);
  expect(Number(await overlay.evaluate(element => getComputedStyle(element).opacity))).toBeCloseTo(previousOpacity, 2);
  for (const action of ["Switch dashboard", "Switch workspace"]) {
    await act(page, action);
    await expect(active(page)).toHaveCount(0);
    await act(page, "Update text");
    await pauseHighlights(page);
    await expect(active(page)).toHaveCount(1);
  }
  await finishHighlights(page);
});

test("suppresses hidden snapshots and establishes a fresh visible baseline", async ({ page }) => {
  await act(page, "Update text");
  await pauseHighlights(page);
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", { configurable: true, value: true });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect(active(page)).toHaveCount(0);
  await act(page, "Update text");
  await expect(active(page)).toHaveCount(0);
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", { configurable: true, value: false });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await act(page, "Unchanged snapshot");
  await expect(active(page)).toHaveCount(0);
  await act(page, "Update text");
  await pauseHighlights(page);
  await finishHighlights(page);
});

test("uses a bounded static tint for reduced motion", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await act(page, "Update text");
  await pauseHighlights(page, 250);
  const overlay = surface(page, "text").locator(".change-highlight-overlay");
  expect(await overlay.evaluate(element => {
    const effect = element.getAnimations()[0].effect as KeyframeEffect;
    return { duration: effect.getTiming().duration, opacities: effect.getKeyframes().map(frame => frame.opacity) };
  })).toEqual({ duration: 500, opacities: ["1", "1"] });
  await expect(overlay).toHaveCSS("opacity", "1");
  await page.screenshot({ path: evidencePath("../screenshots/joo-50/dashboard-reduced-motion.png") });
  await finishHighlights(page);
  await expect(overlay).toHaveCSS("opacity", "0");
});

test("production Builder parses SSE, refetches the Draft, and highlights only changed widgets", async ({ page }) => {
  const workspaceId = "019e8000-0000-7000-8000-000000000b01";
  const dashboardId = "019e8000-0000-7000-8000-000000000b05";
  const principalId = "019e8000-0000-7000-8000-000000000b02";
  const json = (value: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
  // Keep the stream open so only parsed SSE, never EOF reconnect, causes the refetch.
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (!url.endsWith("/draft/events")) return originalFetch(input, init);
      const stream = new ReadableStream<Uint8Array>({ start(controller) {
        const emit = (event: Event) => controller.enqueue(new TextEncoder().encode((event as CustomEvent<string>).detail));
        window.addEventListener("fixture-draft-sse", emit);
        init?.signal?.addEventListener("abort", () => { window.removeEventListener("fixture-draft-sse", emit); controller.close(); }, { once: true });
      } });
      return new Response(stream, { status: 200, headers: { "Content-Type": "text/event-stream" } });
    };
  });
  let text = "Initial orders";
  let reads = 0;
  const definition = () => ({ schemaVersion: 1, meta: { title: "Orders" }, parameters: {}, variables: {}, queries: {}, widgets: {
    text: { type: "TEXT", content: text }, unchanged: { type: "TEXT", content: "Stable context" },
  }, layout: { sections: [{ id: "content", height: { mode: "CONTENT" }, root: { type: "STACK", id: "row", direction: "ROW", children: [
    { weight: 1, node: { type: "WIDGET", id: "text", widgetId: "text" } }, { weight: 1, node: { type: "WIDGET", id: "unchanged", widgetId: "unchanged" } },
  ] } }] } });
  await mockSignedInWorkspace(page, {
    workspaceId,
    workspaceName: "Commerce Insights",
    organizationId: "organization-1",
    role: "OWNER",
    user: { id: principalId, email: "dashboard@example.test", displayName: "Alex Morgan" },
  });
  await page.route("**/v2/workspaces/**", route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/draft")) {
      reads += 1;
      return route.fulfill(json({ workspaceId, dashboardId, authoringRevision: 1, definition: definition(), parameterBindings: {}, variables: {}, readyResults: {}, targetQueryRuns: {}, recentQueryRuns: [], createdAt: "2026-09-09T00:00:00Z", updatedAt: "2026-09-09T00:00:00Z" }));
    }
    if (path.endsWith(`/dashboards/${dashboardId}`)) return route.fulfill(json({ workspaceId, dashboardId, definition: definition(), createdAt: "2026-09-09T00:00:00Z", updatedAt: "2026-09-09T00:00:00Z" }));
    return route.fulfill(json({ items: [], nextCursor: null }));
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `/workspaces/${workspaceId}/dashboards/${dashboardId}/builder`);
  await expect(surface(page, "text")).toContainText("Initial orders");
  await expect(active(page)).toHaveCount(0);
  const emit = () => page.evaluate(payload => window.dispatchEvent(new CustomEvent("fixture-draft-sse", { detail: `data: ${JSON.stringify(payload)}\n\n` })), { dashboardId, observedAt: "2026-09-09T00:01:00Z", reason: "DRAFT_CHANGED" });
  const initialReads = reads;
  await emit();
  await expect.poll(() => reads).toBeGreaterThan(initialReads);
  await expect(active(page)).toHaveCount(0);
  text = "Updated from the stream";
  const unchangedReads = reads;
  await emit();
  await expect(surface(page, "text")).toContainText(text);
  expect(reads).toBeGreaterThan(unchangedReads);
  await pauseHighlights(page);
  await expect(active(page)).toHaveCount(1);
  await expect(surface(page, "text")).toHaveAttribute("data-change-active", "true");
  await finishHighlights(page);
});
