import AxeBuilder from "@axe-core/playwright";
import type { Page, Route } from "@playwright/test";
import type { DashboardDefinition, DashboardJsonObject } from "../../src/api/dashboard";
import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";
import { completeOAuthCallback } from "./oauth-callback-correlation";
import { mockSignedInWorkspace } from "./support/session";

test.use({ viewport: { width: 1440, height: 1000 } });

test("Dashboard Viewer renders INPUT, TABLE, TEXT, and CHART with last-good feedback", async ({ page }) => {
  await page.goto("/dev/dashboard-viewer-fixture?scene=ready");

  await expect(page.getByRole("heading", { name: "Dashboard Viewer" })).toBeVisible();
  await expect(page.getByText("INPUT/TABLE/TEXT/CHART widgets", { exact: false })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Orders", exact: true })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "Order status", exact: true })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "COMPLETED", exact: true })).toBeVisible();
  await expect(page.getByText("63", { exact: true }).first()).toBeVisible();
  const chart = page.getByTestId("dashboard-chart-widget");
  await expect(chart).toBeVisible();
  await expect(chart.getByRole("alert")).toHaveCount(0);
  await expect.poll(() => chart.locator("canvas").evaluate((canvas) => (canvas as HTMLCanvasElement).width)).toBeGreaterThan(500);
  await expect.poll(() => chart.locator("canvas").evaluate((canvas) => (canvas as HTMLCanvasElement).height)).toBeGreaterThan(200);
  await expect(page.getByRole("heading", { name: "Orders by status", exact: true })).toBeVisible();
  await expect(chart.getByRole("img", { name: "Orders by status", exact: true })).toBeVisible();
  await expect(page.locator(".dashboard-section h2.sr-only")).toHaveCount(0);
  const chartMenu = page.getByRole("button", { name: "More actions for Orders by status", exact: true });
  await chartMenu.click();
  await page.getByRole("menuitem", { name: "Refresh this widget" }).click();
  await expect(page.getByTestId("dashboard-widget-request")).toHaveText("refresh:orders");

  await page.screenshot({ path: evidencePath("../screenshots/dashboard-canonical-result-keys/viewer-ready-desktop.png"), fullPage: true });

  const canvasBox = await chart.locator("canvas").boundingBox();
  expect(canvasBox).not.toBeNull();
  await page.mouse.click(
    canvasBox!.x + canvasBox!.width * 0.2,
    canvasBox!.y + canvasBox!.height * 0.65,
  );
  await expect(page.getByRole("combobox", { name: "Selected status" })).toHaveText("Completed");

  await page.getByRole("button", { name: "refreshing" }).click();
  await expect(page.getByText("Variable changed; previous ready frame remains visible.")).toBeVisible();
  await expect(page.locator('[data-capture-widget-id="chart"]').getByRole("status")).toHaveText("Updating results");

  await page.getByRole("button", { name: "error" }).click();
  await expect(page.getByText("Latest target failed; the last-good frame remains visible.")).toBeVisible();
  await expect(page.getByText("The latest run failed. Previous results are shown.").first()).toBeVisible();

  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.addStyleTag({ content: "*, *::before, *::after { animation: none !important; transition: none !important; }" });
  await page.screenshot({ path: evidencePath("../screenshots/remove-dashboard-kpi-widget/viewer-error-last-good.png"), fullPage: true });

  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
});

test("Dashboard Viewer submits complete scalar and range Parameters", async ({ page }) => {
  await page.goto("/dev/dashboard-viewer-fixture?scene=parameters");

  await expect(page.getByRole("heading", { name: "Choose inputs", exact: true })).toBeVisible();
  await expect(page.getByText("Every chart uses these values.", { exact: true })).toBeVisible();
  const openButton = page.getByRole("button", { name: "Show results" });
  await expect(openButton).toBeDisabled();
  await expect(page.getByText("Fill in all required inputs (*) to continue.", { exact: true })).toBeVisible();
  await page.getByRole("textbox", { name: /Region/ }).fill("North");
  await page.getByTestId("dashboard-parameter-optionalNote").getByRole("checkbox").check();
  await page.getByTestId("dashboard-parameter-orderStatuses").getByRole("button", { name: "Shipped" }).click();
  await page.getByLabel("Report period start").fill("2025-01-01");
  await expect(openButton).toBeDisabled();
  await page.getByLabel("Report period end").fill("2025-02-04");
  await page.getByLabel("Observed window start").fill("2025-01-01T09:30");
  await expect(openButton).toBeDisabled();
  await page.getByLabel("Observed window end").fill("2025-02-04T18:45:30");
  await expect(page.getByTestId("dashboard-parameter-observedWindow").getByText("UTC", { exact: true }), "an unknown Reporting time zone reads times in UTC, labeled").toBeVisible();
  await expect(openButton).toBeEnabled();
  await expect(page.getByText("Fill in all required inputs (*) to continue.", { exact: true })).toHaveCount(0);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-parameters/range-ready.png"), fullPage: true });
  await openButton.click();
  await expect(page.getByTestId("dashboard-parameter-optionalNote-value")).toHaveText("null");
  await expect(page.getByTestId("dashboard-parameter-reportPeriod-value")).toHaveText('{"start":"2025-01-01","end":"2025-02-04"}');
  await expect(page.getByTestId("dashboard-parameter-observedWindow-value")).toHaveText('{"start":"2025-01-01T09:30:00Z","end":"2025-02-04T18:45:30Z"}');
});

test("Dashboard time inputs read and show the Reporting time zone's wall clock", async ({ page }) => {
  await page.goto("/dev/dashboard-viewer-fixture?scene=parameters&zone=Asia%2FSeoul");
  const window = page.getByTestId("dashboard-parameter-observedWindow");
  await expect(window.getByText("Asia/Seoul time", { exact: true })).toBeVisible();
  await page.getByRole("textbox", { name: /Region/ }).fill("North");
  await page.getByTestId("dashboard-parameter-optionalNote").getByRole("checkbox").check();
  await page.getByTestId("dashboard-parameter-orderStatuses").getByRole("button", { name: "Shipped" }).click();
  await page.getByLabel("Report period start").fill("2025-01-01");
  await page.getByLabel("Report period end").fill("2025-02-04");
  await page.getByLabel("Observed window start").fill("2025-01-01T09:30");
  await page.getByLabel("Observed window end").fill("2025-02-04T18:45:30");
  await expect(page.getByLabel("Observed window start"), "the entered wall clock stays as typed").toHaveValue("2025-01-01T09:30");
  await page.getByRole("button", { name: "Show results" }).click();
  await expect(page.getByTestId("dashboard-parameter-observedWindow-value")).toHaveText('{"start":"2025-01-01T09:30:00+09:00","end":"2025-02-04T18:45:30+09:00"}');
});

test("Dashboard Viewer reflows rows on a narrow viewport", async ({ page }) => {
  await page.goto("/dev/dashboard-viewer-fixture?scene=ready");

  const chart = page.getByTestId("dashboard-chart-widget");
  await expect(chart).toBeVisible();
  await expect(chart.getByRole("alert")).toHaveCount(0);
  await expect.poll(() => chart.locator("canvas").evaluate((canvas) => (canvas as HTMLCanvasElement).width)).toBeGreaterThan(500);

  await page.setViewportSize({ width: 390, height: 844 });
  const firstRow = page.locator('[data-layout-direction="ROW"]').first();
  await expect(firstRow).toBeVisible();
  await expect.poll(() => firstRow.evaluate((element) => getComputedStyle(element).display)).toBe("flex");
  const boxes = page.locator("[data-layout-box]");
  const expectedHeights = await boxes.evaluateAll(nodes => nodes.map(node => {
    const style = getComputedStyle(node);
    const value = (name: string) => parseFloat(style.getPropertyValue(name));
    const chrome = value("--surface-chrome");
    const header = value("--widget-header-height");
    const chart = (node as HTMLElement).dataset.layoutWidgetType === "CHART";
    const body = Math.max(chart ? 80 : 33 + 32 + 3 * 40, Math.min(chart ? 320 : 360, value("--widget-declared-slot") - chrome - header));
    return chrome + header + body;
  }));
  await expect.poll(() => boxes.evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().height))).toEqual(expectedHeights);
  for (const state of ["loading", "first-failure", "ready"]) {
    await page.getByRole("button", { name: state, exact: true }).click();
    await expect.poll(() => boxes.evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().height))).toEqual(expectedHeights);
  }
  await expect(page.getByRole("gridcell", { name: "DELIVERED", exact: true })).toBeVisible();
  await expect(chart).toBeVisible();
  await expect.poll(() => chart.boundingBox().then((box) => box?.height ?? 0)).toBe(320);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: evidencePath("../screenshots/remove-dashboard-kpi-widget/viewer-ready-mobile.png"), fullPage: true });
});

test("shows a valid current Variable even when it is outside an INPUT choice list", async ({ page }) => {
  await page.goto("/dev/dashboard-viewer-fixture?scene=outside");

  const selectedStatus = page.getByRole("combobox", { name: "Selected status" });
  await expect(selectedStatus).toHaveText("DELIVERED");
  await expect(page.getByText("The current value DELIVERED is not in this control's choices.")).toBeVisible();

  await selectedStatus.click();

  await page.getByRole("option", { name: "Completed", exact: true }).click();
  await expect(selectedStatus).toHaveText("Completed");
});

test("a widget that never loaded explains the failure in place with Retry and Details", async ({ page }) => {
  await page.goto("/dev/dashboard-viewer-fixture?scene=first-failure");
  const table = page.locator('[data-capture-widget-id="table"]');
  await expect(table.getByText("This table couldn't load.", { exact: true })).toBeVisible();
  await expect(table.getByText("The warehouse query timed out after 120 seconds.", { exact: true })).toBeVisible();
  await expect(page.getByText(/Select Refresh/)).toHaveCount(0);
  await table.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(page.getByTestId("dashboard-widget-request")).toHaveText("refresh:orders");
  await table.getByRole("button", { name: "Details", exact: true }).click();
  await expect(page.getByTestId("dashboard-widget-request")).toHaveText("details:orders");
  const chart = page.locator('[data-capture-widget-id="chart"]');
  await expect(chart.getByRole("heading", { name: "Orders by status", exact: true })).toBeVisible();
  await expect(chart.getByText("This chart couldn't load.", { exact: true })).toBeVisible();
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-viewer-redesign/widget-first-failure.png"), fullPage: true });
  const accessibility = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(accessibility.violations).toEqual([]);
});

test("Inputs bar shows results time and opens Change inputs prefilled with the current values", async ({ page }) => {
  await page.goto("/dev/dashboard-viewer-fixture?scene=session-history");
  const bar = page.getByRole("region", { name: "Inputs and results" });
  const inputs = bar.getByRole("list", { name: "Inputs" });
  await expect(inputs.getByTitle("Region: North", { exact: true })).toBeVisible();
  await expect(inputs.getByTitle("Optional note: Any", { exact: true })).toBeVisible();
  await expect(bar.getByText("Running", { exact: true }).filter({ visible: true })).toBeVisible();
  await page.getByRole("button", { name: "Finish running query" }).click();
  await expect(bar.getByText(/^Results from /).filter({ visible: true })).toBeVisible();
  await expect(bar.getByText("Times in Asia/Seoul", { exact: true }).filter({ visible: true }), "the displayed results name the zone they ran in").toBeVisible();
  await expect(bar.getByText(/session/i)).toHaveCount(0);
  await bar.getByRole("button", { name: "Change inputs", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Change inputs" });
  await expect(dialog.getByText("Results for these inputs open as a new entry in History. The current results stay available there.")).toBeVisible();
  const region = dialog.getByRole("textbox", { name: "Region" });
  await expect(region).toHaveValue("North");
  const submit = dialog.getByRole("button", { name: "Show results", exact: true });
  await expect(submit).toBeDisabled();
  await region.fill("West");
  await expect(dialog.getByText("Changed from North", { exact: true })).toBeVisible();
  await expect(submit).toBeEnabled();
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-viewer-redesign/change-inputs.png"), fullPage: true });
  await submit.click();
  await expect(dialog).toHaveCount(0);
  await expect(inputs.getByTitle("Region: West", { exact: true })).toBeVisible();
});

test("History panel keeps entry selection, query runs, and run inspection distinct", async ({ page }) => {
  await page.goto("/dev/dashboard-viewer-fixture?scene=session-history");
  const panel = page.getByRole("complementary", { name: "History" });
  const north = panel.locator('[data-session-id="00000000-0000-7000-8000-000000000010"]');
  const south = panel.locator('[data-session-id="00000000-0000-7000-8000-000000000011"]');
  await expect(panel.getByRole("heading", { name: "History", exact: true })).toBeVisible();
  await expect(north).toContainText("Region North · Any");
  await expect(north.getByText("1 running", { exact: true })).toBeVisible();
  await expect(south.getByText("running", { exact: false })).toHaveCount(0);
  await expect(panel.getByRole("button", { name: /new session/i })).toHaveCount(0);
  await expect(panel.getByText(/session/i)).toHaveCount(0);
  await north.click();
  await expect(north).toHaveAttribute("aria-current", "true");
  await panel.getByRole("button", { name: "Show query runs", exact: true }).click();
  await expect(panel.getByRole("button", { name: "Back to history", exact: true })).toContainText("Last run");
  const group = panel.getByRole("button", { name: "Query orders", exact: true });
  const refresh = panel.getByRole("button", { name: "Refresh orders", exact: true });
  await expect(group).toHaveAttribute("aria-expanded", "false");
  await expect(panel.getByRole("button", { name: /^Open orders run/ })).toHaveCount(0);
  await refresh.click();
  await expect(refresh).toBeDisabled();
  await expect(refresh).toBeEnabled();
  await expect(group).toHaveAttribute("aria-expanded", "false");
  await group.getByText("orders", { exact: true }).click();
  const run = panel.getByRole("button", { name: /^Open orders run/ }).first();
  await expect(run).toHaveAccessibleName(/, started by Sam Kim via agent$/);
  await expect(run.getByText("Sam Kim", { exact: true })).toBeVisible();
  await expect(run.getByText("via agent", { exact: true })).toBeVisible();
  await run.click();
  const detail = page.getByRole("dialog", { name: "orders", exact: true });
  await expect(detail.getByText("Running · Current target", { exact: true })).toBeVisible();
  await expect(detail.getByText("Sam Kim", { exact: true })).toBeVisible();
  await expect(detail.getByText("via agent", { exact: true })).toBeVisible();
  await expect(detail.getByRole("button", { name: /Refresh/ })).toHaveCount(0);
  await detail.getByRole("button", { name: "Close run detail" }).click();
  await expect(run).toBeFocused();
  await group.click();
  await panel.getByRole("button", { name: "Back to history" }).click();
  await expect(north).toHaveAttribute("aria-current", "true");
  await page.getByRole("button", { name: "Finish running query" }).click();
  await expect(north.getByText("1 running", { exact: true })).toHaveCount(0);
  await south.click();
  await expect(south).toHaveAttribute("aria-current", "true");
  await panel.getByRole("button", { name: "Show query runs", exact: true }).click();
  await expect(group).toHaveAttribute("aria-expanded", "false");
  await expect(panel.getByText("1 running", { exact: true })).toHaveCount(0);
  await group.click();
  await expect(panel.getByRole("button", { name: /^Open orders run/ }).first()).not.toHaveAccessibleName(/via agent/);
  await group.click();
  await panel.getByRole("button", { name: "Back to history" }).click();
  await north.click();
  await panel.getByRole("button", { name: "Show query runs", exact: true }).click();
  await expect(group).toHaveAttribute("aria-expanded", "false");
  await expect(panel.getByText("Latest: Succeeded", { exact: true })).toBeVisible();
  await panel.getByRole("button", { name: "Close history" }).click();
  await expect(panel).toHaveCount(0);
  await page.getByRole("button", { name: "History", exact: true }).click();
  await expect(panel).toHaveCSS("width", "320px");
  await expect(group).toHaveAttribute("aria-expanded", "false");
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-ui-iteration/activity-fixture-desktop.png"), fullPage: true });
});

for (const width of [390, 768]) {
  test(`History drawer keeps the selected entry and collapsed groups at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto("/dev/dashboard-viewer-fixture?scene=session-history");
    await expect(page.getByRole("complementary", { name: "History" })).toBeHidden();
    if (width < 640) {
      const bar = page.getByRole("region", { name: "Inputs and results" });
      await expect(bar.getByText("North · Any", { exact: true })).toBeVisible();
      await expect(bar.getByRole("button", { name: "Change inputs", exact: true })).toHaveText("Change");
    }
    const trigger = page.getByRole("button", { name: "History", exact: true });
    await trigger.click();
    const drawer = page.getByRole("dialog", { name: "History", exact: true });
    const north = drawer.locator('[data-session-id="00000000-0000-7000-8000-000000000010"]');
    await north.click();
    await drawer.getByRole("button", { name: "Show query runs", exact: true }).click();
    const group = drawer.getByRole("button", { name: "Query orders", exact: true });
    await expect(group).toHaveAttribute("aria-expanded", "false");
    await group.click();
    await drawer.getByRole("button", { name: /^Open orders run/ }).first().click();
    await page.getByRole("dialog", { name: "orders", exact: true }).getByRole("button", { name: "Close run detail" }).click();
    await expect(drawer).toBeVisible();
    await group.click();
    await drawer.getByRole("button", { name: "Back to history" }).click();
    await expect(north).toHaveAttribute("aria-current", "true");
    await drawer.getByRole("button", { name: "Show query runs", exact: true }).click();
    await expect(group).toHaveAttribute("aria-expanded", "false");
    await page.screenshot({ path: evidencePath(`../screenshots/dashboard-ui-iteration/activity-fixture-${width}.png`), fullPage: true });
    await drawer.getByRole("button", { name: "Close history" }).click();
    await expect(drawer).toHaveCount(0);
    await expect(trigger).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await trigger.click();
    await expect(group).toHaveAttribute("aria-expanded", "false");
    await page.keyboard.press("Escape");
    await expect(drawer).toHaveCount(0);
    await trigger.click();
    await page.setViewportSize({ width: 1280, height: 844 });
    await expect(drawer).toHaveCount(0);
    await page.getByRole("button", { name: "Finish running query" }).click();
    await expect(page.getByRole("complementary", { name: "History" })).toBeVisible();
  });
}

for (const width of [1440, 390]) {
  test(`Dashboard Viewer loading and empty states remain usable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto("/dev/dashboard-viewer-fixture?scene=loading");
    await expect(page.getByRole("status").filter({ hasText: "Initial QueryRun is loading…" })).toBeVisible();
    await expect(page.getByText("Loading results…", { exact: true })).toHaveCount(2);
    await expect(page.getByTestId("dashboard-chart-widget")).toHaveCount(0);
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.screenshot({ path: evidencePath(`../screenshots/remove-dashboard-kpi-widget/viewer-loading-${width}.png`), fullPage: true });

    await page.getByRole("button", { name: "empty", exact: true }).click();
    await expect(page.getByRole("heading", { name: "No dashboard content" })).toBeVisible();
    await expect(page.getByRole("grid", { name: "Dashboard table" })).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.screenshot({ path: evidencePath(`../screenshots/remove-dashboard-kpi-widget/viewer-empty-${width}.png`), fullPage: true });
  });
}

// DashboardLayout.test.tsx covers the separation markup of every direction and separation pair;
// the browser checks the rendered geometry of a GAP stack holding a DIVIDER stack in each direction.
for (const direction of ["ROW", "COLUMN"] as const) {
  const outerSeparation = "GAP";
  const innerSeparation = "DIVIDER";
  const fixtureId = `${direction.toLowerCase()}-gap-divider`;
  test(`same-direction nested stacks preserve ${fixtureId} groups and reflow`, async ({ page }, testInfo) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/dev/dashboard-viewer-fixture?scene=nested-stacks");
    await page.evaluate(() => document.fonts.ready);
    const fixture = page.getByTestId(`nested-stack-${fixtureId}`);
    const outer = fixture.locator(".dashboard-section-frame > [data-layout-direction]");
    const outerChildren = outer.locator(":scope > *");
    const inner = outerChildren.nth(0).locator(":scope > [data-layout-direction]");
    const innerChildren = inner.locator(":scope > *");
    await expect(fixture.locator("[data-layout-direction]")).toHaveCount(2);
    await expect(outerChildren).toHaveCount(2);
    await expect(innerChildren).toHaveCount(2);
    await expect(outer).toHaveAttribute("data-layout-direction", direction);
    await expect(inner).toHaveAttribute("data-layout-direction", direction);
    await expect(outer).toHaveAttribute("data-layout-separation", outerSeparation);
    await expect(inner).toHaveAttribute("data-layout-separation", innerSeparation);
    await expect(innerChildren.nth(0).getByRole("heading", { name: "Orders by region" })).toBeVisible();
    const chart = innerChildren.nth(1).getByTestId("dashboard-chart-widget");
    await expect(chart).toBeVisible();
    await expect.poll(() => chart.locator("canvas").evaluate((canvas) => (canvas as HTMLCanvasElement).width)).toBeGreaterThan(200);
    await expect.poll(() => chart.locator("canvas").evaluate((canvas) => (canvas as HTMLCanvasElement).height)).toBeGreaterThan(200);
    await expect(outerChildren.nth(1).getByRole("grid", { name: "Dashboard table" })).toBeVisible();
    await expect(fixture.getByRole("alert")).toHaveCount(0);

    const desktopGeometry = [];
    for (const [stack, children, separation, ratio] of [
      [outer, outerChildren, outerSeparation, 3 / 2],
      [inner, innerChildren, innerSeparation, 1 / 2],
    ] as const) {
      const first = (await children.nth(0).boundingBox())!;
      const second = (await children.nth(1).boundingBox())!;
      const axis = direction === "ROW" ? "width" : "height";
      if (direction === "ROW") {
        const available = await stack.evaluate(node => node.clientWidth) - (separation === "GAP" ? 16 : 0);
        const minimumFirst = await children.nth(0).evaluate(node => parseFloat(getComputedStyle(node).minWidth));
        const minimumSecond = await children.nth(1).evaluate(node => parseFloat(getComputedStyle(node).minWidth));
        const expectedFirst = Math.min(available - minimumSecond, Math.max(minimumFirst, available * ratio / (ratio + 1)));
        expect(first.width).toBeCloseTo(expectedFirst, 0);
        expect(second.width).toBeCloseTo(available - expectedFirst, 0);
        expect(first.width / second.width).toBeCloseTo(expectedFirst / (available - expectedFirst), 2);
      } else if (stack === outer) expect(first.height / second.height).toBeCloseTo(ratio, 2);
      else expect(first.height).toBeLessThan(second.height);
      const gap = direction === "ROW" ? second.x - first.x - first.width : second.y - first.y - first.height;
      expect(gap).toBeCloseTo(separation === "GAP" ? 16 : 0, 1);
      await expect(stack).toHaveCSS("display", "flex");
      await expect(children.nth(0)).toHaveCSS("border-left-width", "0px");
      await expect(children.nth(0)).toHaveCSS("border-top-width", "0px");
      await expect(children.nth(1)).toHaveCSS("border-left-width", separation === "DIVIDER" && direction === "ROW" ? "1px" : "0px");
      await expect(children.nth(1)).toHaveCSS("border-top-width", separation === "DIVIDER" && direction === "COLUMN" ? "1px" : "0px");
      desktopGeometry.push({ first, second, gap, ratio: first[axis] / second[axis] });
    }
    await fixture.screenshot({ path: evidencePath(`../screenshots/dashboard-same-direction/${direction.toLowerCase()}-desktop.png`), animations: "disabled" });

    await page.setViewportSize({ width: 390, height: 844 });
    const mobileGeometry = [];
    for (const [stack, children, separation] of [
      [outer, outerChildren, outerSeparation],
      [inner, innerChildren, innerSeparation],
    ] as const) {
      await expect(stack).toHaveCSS("display", "flex");
      await expect(stack).toHaveCSS("flex-direction", "column");
      const first = (await children.nth(0).boundingBox())!;
      const second = (await children.nth(1).boundingBox())!;
      expect(first.width).toBeCloseTo(second.width, 1);
      expect(first.x).toBeCloseTo(second.x, 1);
      expect(second.y - first.y - first.height).toBeCloseTo(separation === "GAP" ? 16 : 0, 1);
      await expect(children.nth(0)).toHaveCSS("border-top-width", "0px");
      await expect(children.nth(1)).toHaveCSS("border-left-width", "0px");
      await expect(children.nth(1)).toHaveCSS("border-top-width", separation === "DIVIDER" ? "1px" : "0px");
      mobileGeometry.push({ first, second });
    }
    await expect.poll(() => chart.boundingBox().then((box) => box?.height ?? 0)).toBeGreaterThanOrEqual(320);
    await expect(outerChildren.nth(1).getByRole("gridcell", { name: "DELIVERED", exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    await expect(fixture.locator("[data-layout-direction]")).toHaveCount(2);
    await fixture.screenshot({ path: evidencePath(`../screenshots/dashboard-same-direction/${direction.toLowerCase()}-mobile.png`), animations: "disabled" });
    await testInfo.attach("nested-stack-geometry", {
      body: JSON.stringify({ fixtureId, desktopGeometry, mobileGeometry }, null, 2),
      contentType: "application/json",
    });
  });
}

const viewerWorkspaceId = "019e8000-0000-7000-8000-000000000b21";
const viewerDashboardId = "019e8000-0000-7000-8000-000000000d21";
const firstSessionId = "019e8000-0000-7000-8000-000000000e21";
const secondSessionId = "019e8000-0000-7000-8000-000000000e22";
const viewerBase = `/workspaces/${viewerWorkspaceId}/dashboards/${viewerDashboardId}`;
const viewerTimestamp = "2026-09-06T10:00:00Z";
const viewerDefinition: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "Latest results" }, parameters: {},
  variables: {
    region: { type: "TEXT", nullable: false, initial: "North", label: "Region" },
    period: { type: "DATE_RANGE", nullable: false, initial: { start: "2026-01-01", end: "2026-01-31" }, label: "Period" },
  },
  queries: { orders: { type: "SEMANTIC" as const, connectorId: viewerWorkspaceId, query: { schemaVersion: 4, metrics: ["value"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 } } },
  widgets: {
    region: { type: "INPUT", variableName: "region" },
    period: { type: "INPUT", variableName: "period" },
    orders: { type: "TABLE", queryName: "orders", columns: [{ field: "dimension.entry", header: "Entry" }] },
  },
  layout: { sections: [
    { id: "inputs", height: { mode: "CONTENT" }, root: { type: "STACK", id: "inputs-stack", direction: "ROW", children: ["region", "period"].map(name => ({ weight: 1, node: { type: "WIDGET", id: `${name}-item`, widgetId: name } })) } },
    { id: "results", height: { mode: "SCALED", scale: 1 }, root: { type: "WIDGET", id: "orders-item", widgetId: "orders" } },
  ] },
};
const initialViewerVariables = { region: "North", period: { start: "2026-01-01", end: "2026-01-31" } };
const southRunId = "019e8000-0000-7000-8000-000000000a29";
const runIdFor = (sessionId: string) => sessionId.replace("-000000000e", "-000000000a");
const entryLabels: Record<string, string> = { [runIdFor(firstSessionId)]: "First entry", [runIdFor(secondSessionId)]: "Second entry", [southRunId]: "South entry" };

// The actual Viewer route against a fixture Control: `sessions` lists the Viewer's History newest first.
async function viewerRoute(page: Page, {
  definition = viewerDefinition,
  initialSessions = [firstSessionId],
  parameterBindings = {},
}: {
  definition?: DashboardDefinition;
  initialSessions?: string[];
  parameterBindings?: DashboardJsonObject;
} = {}) {
  const sessions = [...initialSessions];
  const sessionLists: string[][] = [];
  const opens: unknown[] = [];
  const resolutions: Record<string, unknown>[] = [];
  const queryRequests: string[] = [];
  await page.setViewportSize({ width: 1440, height: 1000 });
  await mockSignedInWorkspace(page, {
    workspaceId: viewerWorkspaceId,
    workspaceName: "Viewer workspace",
    organizationId: "organization-1",
    role: "EXPLORER",
    user: { id: "viewer-user", email: "viewer@example.test", displayName: "Viewer" },
  });
  await page.addInitScript(() => {
    const record = (method: "pushState" | "replaceState") => {
      const original = window.history[method].bind(window.history);
      window.history[method] = (state: unknown, unused: string, url?: string | URL | null) => {
        const recorded = window as unknown as { recordedUrls?: string[] };
        recorded.recordedUrls = [...(recorded.recordedUrls ?? []), String(url ?? "")];
        original(state, unused, url);
      };
    };
    record("pushState");
    record("replaceState");
  });
  await page.route("**/v2/workspaces/**", async (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    if (/:(resolve|refresh)$|\/query-runs(?:[/:]|$)/.test(path)) queryRequests.push(`${route.request().method()} ${path}`);
    const json = (value: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(value) });
    const run = (queryRunId: string) => ({ queryType: "SEMANTIC" as const, queryRunId, connectorId: viewerWorkspaceId, queryName: "orders", state: "SUCCEEDED", resultAvailable: true, outputColumnNames: ["dimension.entry"], failureCode: null, failureMessage: null, createdAt: viewerTimestamp, updatedAt: viewerTimestamp, credentialSurface: null, agentApp: null });
    const sessionMatch = path.match(/\/dashboard-sessions\/([^/:]+)/);
    const sessionId = sessionMatch?.[1] ?? "";
    if (path.endsWith("/search")) return json({ dashboards: [{ dashboardId: viewerDashboardId, title: definition.meta.title }], metrics: [], semanticModels: [], tables: [] });
    if (path.endsWith("/pages/tree")) return json({ workspaceId: viewerWorkspaceId, revision: 1, pages: [], placements: [], updatedAt: viewerTimestamp, updatedBy: { kind: "USER", id: "viewer-user" } });
    if (path.endsWith("/events")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: "" });
    const queryRunId = path.match(/\/query-runs\/([^/]+)/)?.[1] ?? "";
    if (path.endsWith(":resolve")) {
      const body = route.request().postDataJSON();
      resolutions.push(body.variables);
      return json({ variables: body.variables, queryRuns: { orders: body.variables.region === "South" ? southRunId : runIdFor(sessionId) } });
    }
    if (path.endsWith("/result")) return json({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId, columns: [{ name: "dimension.entry", logicalType: "TEXT", nullable: false }], rows: [[entryLabels[queryRunId]]] });
    if (sessionMatch && queryRunId) return json(run(queryRunId));
    if (path.endsWith("/query-runs")) return json({ items: sessionMatch ? [run(runIdFor(sessionId))] : [], nextCursor: null });
    if (sessionMatch) return json({ workspaceId: viewerWorkspaceId, dashboardId: viewerDashboardId, dashboardSessionId: sessionId, definition, parameterBindings, initialVariables: initialViewerVariables, runningQueryRuns: [] });
    if (path.endsWith(`/dashboards/${viewerDashboardId}/sessions`)) {
      if (route.request().method() === "POST") {
        opens.push(route.request().postDataJSON());
        return json({ detail: "The fixture does not open new results." }, 500);
      }
      sessionLists.push([...sessions]);
      return json({ items: sessions.map(id => ({ dashboardId: viewerDashboardId, dashboardSessionId: id, parameterBindings, createdAt: viewerTimestamp, updatedAt: viewerTimestamp, runningQueryRuns: [] })), nextCursor: null });
    }
    if (path.endsWith(`/dashboards/${viewerDashboardId}`)) return json({ workspaceId: viewerWorkspaceId, dashboardId: viewerDashboardId, definition, createdAt: viewerTimestamp, updatedAt: viewerTimestamp });
    return json({ items: [] });
  });
  return {
    sessions,
    sessionLists,
    opens,
    resolutions,
    queryRequests,
    recordedUrls: () => page.evaluate(() => (window as unknown as { recordedUrls?: string[] }).recordedUrls ?? []),
    // True once a bare Viewer URL recorded after `from` was followed by the given Session.
    restoredAfter: async (from: number, sessionId: string) => {
      const urls = (await page.evaluate(() => (window as unknown as { recordedUrls?: string[] }).recordedUrls ?? [])).slice(from).map(url => {
        const parsed = new URL(url, "http://viewer.test");
        return `${parsed.pathname.replace(/\/$/, "")}${parsed.search}`;
      });
      const bare = urls.indexOf(viewerBase);
      return bare >= 0 && urls.slice(bare + 1).includes(`${viewerBase}?session=${sessionId}`);
    },
  };
}

async function openDashboardFromSearch(page: Page) {
  await page.keyboard.press("ControlOrMeta+k");
  const palette = page.getByRole("dialog", { name: "Search" });
  await expect(palette).toBeVisible();
  await palette.getByRole("combobox", { name: "Search or go to" }).fill(viewerDefinition.meta.title);
  await expect(palette.getByRole("group", { name: "Dashboards" }).getByRole("option", { name: new RegExp(viewerDefinition.meta.title) })).toBeVisible();
  await page.keyboard.press("Enter");
  await expect(palette).toHaveCount(0);
}

test("every bare Viewer entry restores the latest results, including in-app navigation from selected results", async ({ page }) => {
  const fixture = await viewerRoute(page);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, viewerBase);
  await expect(page).toHaveURL(new RegExp(`session=${firstSessionId}`));
  await expect(page.getByRole("gridcell", { name: "First entry", exact: true })).toBeVisible();
  const before = (await fixture.recordedUrls()).length;

  await openDashboardFromSearch(page);
  await expect.poll(() => fixture.restoredAfter(before, firstSessionId)).toBe(true);
  await expect(page).toHaveURL(new RegExp(`session=${firstSessionId}`));
  await expect(page.getByText("Restoring the latest results…")).toHaveCount(0);
  await expect(page.getByRole("gridcell", { name: "First entry", exact: true })).toBeVisible();
  expect(fixture.opens).toEqual([]);
});

test("a bare Viewer adopts its first Session after an empty list and keeps it during later polling", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-10-07T00:00:00Z") });
  const fixture = await viewerRoute(page, {
    definition: {
      ...viewerDefinition,
      parameters: { reportPeriod: { type: "DATE_RANGE", nullable: false, label: "Report period" } },
    },
    initialSessions: [],
    parameterBindings: { reportPeriod: { start: "2026-01-01", end: "2026-01-31" } },
  });
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, viewerBase);
  await expect(page.getByRole("heading", { name: "Choose inputs", exact: true })).toBeVisible();
  await expect(page.getByLabel("Report period start")).toHaveValue("");
  await expect(page.getByLabel("Report period end")).toHaveValue("");
  await expect(page.getByRole("button", { name: "Show results", exact: true })).toBeDisabled();
  expect(new URL(page.url()).searchParams.has("session")).toBe(false);
  expect(fixture.sessionLists.at(-1)).toEqual([]);
  expect(fixture.resolutions).toEqual([]);
  expect(fixture.opens).toEqual([]);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-first-session-restore/choose-inputs.png"), fullPage: true });

  const emptyListReads = fixture.sessionLists.length;
  fixture.sessions.push(firstSessionId);
  const firstSessionPoll = page.waitForResponse(response => response.request().method() === "GET" && new URL(response.url()).pathname.endsWith(`/dashboards/${viewerDashboardId}/sessions`));
  await page.clock.fastForward(15_001);
  await firstSessionPoll;
  await expect.poll(() => fixture.sessionLists.length).toBeGreaterThan(emptyListReads);
  expect(fixture.sessionLists.at(-1)).toEqual([firstSessionId]);
  await expect(page).toHaveURL(new RegExp(`session=${firstSessionId}`));
  await expect(page.getByRole("heading", { name: "Choose inputs", exact: true })).toHaveCount(0);
  await expect(page.getByText("Restoring the latest results…", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("gridcell", { name: "First entry", exact: true })).toBeVisible();
  expect(fixture.resolutions).toEqual([initialViewerVariables]);
  expect(fixture.opens).toEqual([]);
  await page.screenshot({ path: evidencePath("../screenshots/dashboard-first-session-restore/first-session-restored.png"), fullPage: true });

  const selectedListReads = fixture.sessionLists.length;
  fixture.sessions.unshift(secondSessionId);
  const newerSessionPoll = page.waitForResponse(response => response.request().method() === "GET" && new URL(response.url()).pathname.endsWith(`/dashboards/${viewerDashboardId}/sessions`));
  await page.clock.fastForward(15_001);
  await newerSessionPoll;
  await expect.poll(() => fixture.sessionLists.length).toBeGreaterThan(selectedListReads);
  expect(fixture.sessionLists.at(-1)).toEqual([secondSessionId, firstSessionId]);
  await expect(page).toHaveURL(new RegExp(`session=${firstSessionId}`));
  await expect(page.getByRole("gridcell", { name: "First entry", exact: true })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "Second entry", exact: true })).toHaveCount(0);
  expect(fixture.resolutions).toEqual([initialViewerVariables]);
  expect(fixture.opens).toEqual([]);

  await page.getByRole("textbox", { name: "Region", exact: true }).fill("South");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect(page.getByRole("gridcell", { name: "South entry", exact: true })).toBeVisible();
  expect(fixture.resolutions.at(-1)).toEqual({ ...initialViewerVariables, region: "South" });
  await page.reload();
  await expect(page).toHaveURL(new RegExp(`session=${firstSessionId}`));
  await expect(page.getByRole("textbox", { name: "Region", exact: true })).toHaveValue("North");
  await expect(page.getByRole("textbox", { name: "Period start", exact: true })).toHaveValue("2026-01-01");
  await expect(page.getByRole("gridcell", { name: "First entry", exact: true })).toBeVisible();
  expect(fixture.resolutions).toEqual([initialViewerVariables, { ...initialViewerVariables, region: "South" }, initialViewerVariables]);
  expect(fixture.opens).toEqual([]);
});

test("an unapplied range draft survives when another Viewer input is accepted", async ({ page }) => {
  const fixture = await viewerRoute(page);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `${viewerBase}?session=${firstSessionId}`);
  await expect(page.getByRole("gridcell", { name: "First entry", exact: true })).toBeVisible();
  await expect.poll(() => fixture.resolutions.length).toBe(1);
  const periodStart = page.getByRole("textbox", { name: "Period start", exact: true });
  await expect(periodStart).toHaveValue("2026-01-01");
  await periodStart.fill("2026-01-15");

  await page.getByRole("textbox", { name: "Region", exact: true }).fill("South");
  await page.getByRole("button", { name: "Apply Region", exact: true }).click();
  await expect(page.getByRole("gridcell", { name: "South entry", exact: true })).toBeVisible();
  expect(fixture.resolutions.at(-1)).toEqual({ ...initialViewerVariables, region: "South" });
  await expect(periodStart).toHaveValue("2026-01-15");
  await expect(page.getByRole("button", { name: "Apply Period", exact: true })).toBeEnabled();
});

test("re-entering the Viewer restores the latest results from a fresh History list, not the cached one", async ({ page }) => {
  const fixture = await viewerRoute(page);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, viewerBase);
  await expect(page).toHaveURL(new RegExp(`session=${firstSessionId}`));
  await expect(page.getByRole("gridcell", { name: "First entry", exact: true })).toBeVisible();

  await page.keyboard.press("ControlOrMeta+k");
  const palette = page.getByRole("dialog", { name: "Search" });
  await palette.getByRole("group", { name: "Go to" }).getByRole("option", { name: /^Dashboards/ }).click();
  await expect(page).toHaveURL(`/workspaces/${viewerWorkspaceId}/dashboards`);
  await expect(palette).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Your agent builds dashboards" })).toBeVisible();
  fixture.sessions.unshift(secondSessionId);

  await openDashboardFromSearch(page);
  await expect(page).toHaveURL(new RegExp(`session=${secondSessionId}`));
  await expect(page.getByRole("gridcell", { name: "Second entry", exact: true })).toBeVisible();
  expect(fixture.opens).toEqual([]);
});

test("Run again stays disabled while the current Dashboard definition is unavailable", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 503, url: new RegExp(`/dashboards/${viewerDashboardId}$`), reason: "The fixture cannot read the current Dashboard definition." });
  await viewerRoute(page);
  await page.route(`**/v2/workspaces/${viewerWorkspaceId}/dashboards/${viewerDashboardId}`, route => route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ type: "https://monomer.cc/problems/fixture", title: "Definition unavailable", status: 503, detail: "The current definition could not be read.", instance: "/fixture", code: "FIXTURE_ERROR", requestId: "fixture-request", traceId: "fixture-trace", retryable: true }) }));
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, `${viewerBase}?session=${firstSessionId}`);
  await expect(page.getByRole("gridcell", { name: "First entry", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "More actions", exact: true }).click();
  await expect(page.getByRole("menuitem", { name: "Run again", exact: true })).toBeDisabled();
});


test("window resize and ROW stacking leave query resolution and execution requests unchanged", async ({ page }) => {
  await page.clock.install();
  const fixture = await viewerRoute(page);
  await completeOAuthCallback(page, { new_user: "false", auth_provider: "google" }, viewerBase);
  await expect(page.getByRole("gridcell", { name: "First entry", exact: true })).toBeVisible();
  const row = page.locator('[data-layout-direction="ROW"]').first();
  await expect(row).not.toHaveAttribute("data-layout-stacked");
  // Reads may refresh lists after the mobile layout mounts; resolution and execution must not change.
  const executionRequests = () => fixture.queryRequests.filter(request => !request.startsWith("GET "));
  const before = executionRequests();
  for (const width of [390, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    if (width === 390) await expect(row).toHaveAttribute("data-layout-stacked", "");
    else await expect(row).not.toHaveAttribute("data-layout-stacked");
    await expect(page.getByRole("gridcell", { name: "First entry", exact: true })).toBeVisible();
    // Observe beyond the UI's input debounce so accidental query side effects are caught.
    await page.clock.fastForward(1_000);
    expect(executionRequests()).toEqual(before);
    expect(fixture.opens).toHaveLength(0);
  }
});
