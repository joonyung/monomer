import { mkdirSync } from "node:fs";
import type { Locator } from "@playwright/test";
import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";

test.beforeAll(() => mkdirSync(evidencePath("screenshots"), { recursive: true }));
test.use({ viewport: { width: 1600, height: 960 } });

const WORKSPACE_ID = "71000000-0000-0000-0000-000000000002";
const DASHBOARD_TITLE = "Northstar Commerce · Business overview for all completed orders";

async function expectNodeInLane(node: Locator, heading: Locator) {
  await expect(node).toBeVisible();
  await expect(heading).toBeVisible();
  await expect.poll(async () => {
    const nodeX = await node.evaluate((element) => element.closest(".react-flow__node")!.getBoundingClientRect().x);
    const headingX = await heading.evaluate((element) => element.closest(".react-flow__node")!.getBoundingClientRect().x);
    return Math.abs(nodeX - headingX);
  }).toBeLessThan(1);
}

test("lane headings follow resource kinds and hide empty lanes before table expansion", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/dev/lineage-access-fixture?scene=ready");
  const model = page.getByRole("button", { name: "Select Orders", exact: true });
  const metric = page.getByRole("button", { name: "Select Order count", exact: true });
  const downstream = page.getByRole("button", { name: "Select Revenue", exact: true });
  const dashboard = page.getByRole("button", { name: `Select ${DASHBOARD_TITLE}`, exact: true });
  const tableHeading = page.getByTestId("lineage-lane-RELATION");
  const modelHeading = page.getByTestId("lineage-lane-SEMANTIC_MODEL");
  const metricHeadings = page.getByTestId("lineage-lane-METRIC");
  const dashboardHeading = page.getByTestId("lineage-lane-DASHBOARD");
  await expect(tableHeading).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Select orders_fact from Northstar warehouse" })).toHaveCount(0);
  await expectNodeInLane(model, modelHeading);
  await expectNodeInLane(metric, metricHeadings.nth(0));
  await expectNodeInLane(downstream, metricHeadings.nth(1));
  await expectNodeInLane(dashboard, dashboardHeading);
  await page.getByRole("button", { name: "Expand Orders", exact: true }).click();
  await expectNodeInLane(page.getByRole("button", { name: "Select orders_fact from Northstar warehouse" }), tableHeading);
  await expectNodeInLane(page.getByRole("button", { name: "Select order_facts from Finance lakehouse" }), tableHeading);
  await expectNodeInLane(model, modelHeading);
  await expectNodeInLane(metric, metricHeadings.nth(0));
  await page.getByRole("button", { name: /zoom in/i }).click();
  await expectNodeInLane(model, modelHeading);
  await page.getByRole("button", { name: /fit view/i }).click();
  await expectNodeInLane(dashboard, dashboardHeading);
});

test("the minimap appears below the node canvas only when the graph exceeds it", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/dev/lineage-access-fixture?scene=ready");
  const canvas = page.getByTestId("lineage-map-canvas");
  const overview = page.getByTestId("lineage-map-overview");
  const minimap = overview.locator(".react-flow__minimap");
  await page.getByRole("button", { name: /fit view/i }).click();
  await expect(overview).toHaveCount(0);
  for (let zoom = 0; zoom < 2; zoom++) await page.getByRole("button", { name: /zoom in/i }).click();
  await expect(minimap).toBeVisible();
  await expect(canvas.locator(".react-flow__minimap")).toHaveCount(0);
  await expect.poll(async () => {
    const canvasBounds = await canvas.boundingBox();
    const minimapBounds = await minimap.boundingBox();
    return minimapBounds!.y - (canvasBounds!.y + canvasBounds!.height);
  }).toBeGreaterThanOrEqual(0);
});

test("Dashboard nodes expose their full title and wrap the visible label to two lines", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/dev/lineage-access-fixture?scene=ready");
  const dashboard = page.getByRole("button", { name: `Select ${DASHBOARD_TITLE}`, exact: true });
  await expect(dashboard).toBeVisible();
  await expect(dashboard).toHaveAttribute("title", DASHBOARD_TITLE);
  const label = dashboard.locator(".line-clamp-2");
  await expect(label).toHaveText(DASHBOARD_TITLE);
  await expect.poll(() => label.evaluate((element) => getComputedStyle(element).webkitLineClamp)).toBe("2");
  await expect.poll(() => label.evaluate((element) => getComputedStyle(element).display)).not.toBe("block");
  await expect.poll(() => label.evaluate((element) => element.clientHeight / Number.parseFloat(getComputedStyle(element).lineHeight))).toBeLessThanOrEqual(2.05);
  await expect.poll(() => dashboard.evaluate((element) => {
    const nodeBounds = element.closest(".react-flow__node")!.getBoundingClientRect();
    const kindBounds = element.querySelector(".type-meta")!.getBoundingClientRect();
    return Math.max(element.getBoundingClientRect().bottom, kindBounds.bottom) - nodeBounds.bottom;
  })).toBeLessThanOrEqual(1);
  await expect(page.getByRole("link", { name: `Open ${DASHBOARD_TITLE}`, exact: true })).toHaveAttribute("href", `/workspaces/${WORKSPACE_ID}/dashboards/71000000-0000-0000-0000-000000000108`);
});

test("Explorer sees complete topology and resource details without ACL controls", async ({ page }) => {
  await page.goto("/dev/lineage-access-fixture?scene=ready");
  await expect(page.getByRole("heading", { name: "Lineage", exact: true })).toBeVisible();
  await expect(page.getByTestId("lineage-map")).toBeVisible();
  await expect(page.getByRole("button", { name: "Select orders_fact from Northstar warehouse" })).toHaveCount(0);
  await page.getByRole("button", { name: "Expand Orders", exact: true }).click();
  await expect(page.getByRole("button", { name: "Select orders_fact from Northstar warehouse" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Select order_facts from Finance lakehouse" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Select Refund rate" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /zoom in/i })).toBeVisible();
  await expect(page.getByRole("button", { name: /zoom out/i })).toBeVisible();
  const details = page.getByTestId("lineage-resource-drawer");
  await expect(details).toBeVisible();
  await expect(details).toContainText("Metric definition");
  await expect(details).toContainText("Order count");
  await expect(details).toContainText("Aggregate metric");
  await expect(details.getByRole("link", { name: "Open metric" })).toHaveAttribute("href", `/workspaces/${WORKSPACE_ID}/metrics/71000000-0000-0000-0000-000000000103`);
  await expect(details).not.toContainText("Effective access");
  await expect(page.getByRole("button", { name: "Manage access" })).toHaveCount(0);
  await expect(page.getByText("All Relations by default")).toHaveCount(0);
  await page.screenshot({ path: evidencePath("screenshots/lineage-resource-detail.png"), fullPage: true });
  await page.getByRole("button", { name: "Hide resource details" }).click();
  await expect(details).toHaveCount(0);
  const canvas = page.getByTestId("lineage-map-canvas");
  await page.getByRole("button", { name: /fit view/i }).click();
  const focus = page.getByRole("button", { name: "Select Order count", exact: true });
  await expect.poll(async () => {
    const bounds = await canvas.boundingBox();
    const node = await focus.boundingBox();
    return Boolean(bounds && node && node.x >= bounds.x && node.x + node.width <= bounds.x + bounds.width && node.y >= bounds.y && node.y + node.height <= bounds.y + bounds.height && node.width >= 120);
  }).toBe(true);
  await page.screenshot({ path: evidencePath("screenshots/lineage-topology-desktop.png"), fullPage: true });
  await page.getByRole("button", { name: "Select orders_fact from Northstar warehouse" }).click();
  await page.getByRole("button", { name: "Focus selected", exact: true }).click();
  await expect(page.getByText("orders_fact · Table", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Show resource details" }).click();
  await expect(details.getByRole("table", { name: "Table columns" })).toBeVisible();
  await expect(details).toContainText("customer_id");
  await expect(details.getByRole("link", { name: "Open in Catalog" })).toHaveAttribute("href", `/workspaces/${WORKSPACE_ID}/catalog?connector=71000000-0000-0000-0000-000000000008`);
});

test("search supports cursor pages and switches focus while retaining the drawer state", async ({ page }) => {
  await page.goto("/dev/lineage-access-fixture?scene=ready");
  const search = page.getByRole("textbox", { name: "Search resources" });
  await search.fill("metric");
  await expect(page.getByRole("button", { name: "Metric 21 metric_21" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Metric 22 metric_22" })).toHaveCount(0);
  await page.getByRole("button", { name: "Show more" }).click();
  await expect(page.getByRole("button", { name: "Metric 34 metric_34" })).toBeVisible();
  await search.fill("revenue");
  await page.getByRole("button", { name: "Net revenue net_revenue" }).click();
  await expect(page.getByRole("button", { name: "Select Net revenue", exact: true })).toBeVisible();
  await expect(page.getByTestId("lineage-resource-drawer")).toContainText("Net revenue");
});

test("an empty search offers the other resource kinds with the same query", async ({ page }) => {
  await page.goto("/dev/lineage-access-fixture?scene=ready");
  const search = page.getByRole("textbox", { name: "Search resources" });
  await expect(search).toHaveAttribute("placeholder", "Search metrics");
  await search.fill("orders");
  await expect(page.getByText('No metrics match "orders".', { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Search tables" })).toBeVisible();
  await page.getByRole("button", { name: "Search models" }).click();
  await expect(search).toHaveValue("orders");
  await expect(search).toHaveAttribute("placeholder", "Search models");
  await expect(page.getByRole("button", { name: "Orders orders" })).toBeVisible();
});

test("mobile map pairs impact and navigation without horizontal page overflow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/dev/lineage-access-fixture?scene=ready");
  await expect(page.getByTestId("lineage-map")).toBeVisible();
  const canvas = page.getByTestId("lineage-map-canvas");
  await expect.poll(() => canvas.locator(".react-flow__viewport").getAttribute("style")).toContain("scale(1)");
  await expect(page.getByRole("list", { name: "Affected definitions" })).toContainText("Revenue");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: evidencePath("screenshots/lineage-topology-mobile.png"), fullPage: true });
  await page.getByRole("combobox", { name: "Resource kind" }).selectOption("RELATION");
  await page.getByRole("textbox", { name: "Search resources" }).fill("orders_fact");
  await page.getByRole("button", { name: "orders_fact Northstar warehouse · warehouse.analytics.orders_fact", exact: true }).click();
  await page.getByRole("button", { name: "Show resource details" }).click();
  await expect(page.getByTestId("lineage-resource-drawer")).toContainText("Table columns");
});

test("wheel scroll reaches Impact without zooming and Ctrl wheel remains available", async ({ page }) => {
  const resizeErrors: string[] = [];
  page.on("pageerror", (error) => {
    if (error.message.includes("ResizeObserver")) resizeErrors.push(error.message);
  });
  await page.setViewportSize({ width: 1440, height: 800 });
  await page.goto("/dev/lineage-access-fixture?scene=ready");
  const canvas = page.getByTestId("lineage-map-canvas");
  const viewport = canvas.locator(".react-flow__viewport");
  await expect(page.getByRole("button", { name: "Select Order count", exact: true })).toBeVisible();
  await page.getByRole("button", { name: /fit view/i }).click();
  await expect.poll(() => viewport.getAttribute("style")).toMatch(/scale\((0\.[6-9]\d*|1(?:\.\d+)?)\)/);
  const transform = await viewport.getAttribute("style");
  const before = await canvas.boundingBox();
  await canvas.hover();
  await page.mouse.wheel(0, 600);
  await expect.poll(async () => (await canvas.boundingBox())!.y).toBeLessThan(before!.y);
  await expect(viewport).toHaveAttribute("style", transform!);
  await expect(page.getByRole("list", { name: "Affected definitions" })).toBeVisible();
  await canvas.hover();
  await page.keyboard.down("Control");
  await page.mouse.wheel(0, -100);
  await page.keyboard.up("Control");
  await expect.poll(() => viewport.getAttribute("style")).not.toBe(transform);
  expect(resizeErrors).toEqual([]);
});

test("the impact list retains every downstream definition and peers remain searchable", async ({ page }) => {
  await page.goto("/dev/lineage-access-fixture?scene=overflow");
  await expect(page.getByRole("button", { name: "Select source_16 from Northstar warehouse" })).toBeAttached();
  await expect(page.getByRole("list", { name: "Affected definitions" })).toContainText("Metric 15");
  await page.screenshot({ path: evidencePath("screenshots/lineage-impact-list.png"), fullPage: true });
  await page.goto("/dev/lineage-access-fixture?scene=peer");
  await page.getByRole("combobox", { name: "Resource kind" }).selectOption("SEMANTIC_MODEL");
  await page.getByRole("textbox", { name: "Search resources" }).fill("customers");
  await page.getByRole("button", { name: "Customers customers" }).click();
  await expect(page.getByTestId("lineage-resource-drawer")).toContainText("Model definition");
  await expect(page.getByTestId("lineage-resource-drawer").getByRole("link", { name: "Open model" })).toBeVisible();
});

for (const [scene, text] of [["empty", "No resources yet"], ["loading", "Loading Lineage…"], ["error", "Lineage could not be loaded"], ["unavailable", "This resource isn't available"]] as const) {
  test(`renders ${scene} without a stale graph`, async ({ page }) => {
    await page.goto(`/dev/lineage-access-fixture?scene=${scene}`);
    await expect(page.getByText(text, { exact: true })).toBeVisible();
    await expect(page.getByTestId("lineage-map")).toHaveCount(0);
    await page.screenshot({ path: evidencePath(`screenshots/lineage-${scene}.png`), fullPage: true });
  });
}

test("empty workspace links to Catalog without Editor-only creation for an Explorer", async ({ page }) => {
  await page.goto("/dev/lineage-access-fixture?scene=empty");
  await expect(page.getByText("Lineage appears once this workspace has tables in Catalog or semantic definitions.", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Open Catalog" })).toHaveAttribute("href", `/workspaces/${WORKSPACE_ID}/catalog`);
  await expect(page.getByRole("link", { name: "Describe a table" })).toHaveCount(0);
});

for (const [state, label] of [["loading", "Searching…"], ["empty", 'No metrics match "orders".'], ["error", "Retry search"]] as const) {
  test(`search renders ${state}`, async ({ page }) => {
    await page.goto(`/dev/lineage-access-fixture?scene=ready&pickerState=${state}`);
    await page.getByRole("textbox", { name: "Search resources" }).fill("orders");
    await expect(page.getByText(label, { exact: true })).toBeVisible();
  });
}
