import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";

test("Metrics catalog shows each metric once and searches by its label", async ({ page }) => {
  await page.setViewportSize({width:1440,height:900});
  await page.goto("/dev/semantics-demo?scene=ready");
  await expect(page.getByRole("tab",{name:"Metrics",exact:true})).toHaveAttribute("aria-selected","true");
  const table=page.getByRole("table",{name:"Metrics catalog"});
  await expect(table.getByRole("link",{name:"Net Revenue (net_revenue)",exact:true})).toHaveCount(1);
  await expect(table).toContainText("How it's calculated");
  await page.getByRole("searchbox",{name:"Search metrics"}).fill("monthly recurring revenue");
  await expect(table.getByRole("link",{name:"Monthly Recurring Revenue (monthly_recurring_revenue)",exact:true})).toHaveCount(1);
  await expect(table.getByRole("link",{name:"Net Revenue (net_revenue)",exact:true})).toHaveCount(0);
  await page.screenshot({path:evidencePath("screenshots/semantics-catalog.png")});
});

test("Metrics catalog shows saved formulas without restating a same-named input alias while names use Labels", async ({ page }) => {
  await page.goto("/dev/semantics-demo?scene=ready");
  const table = page.getByRole("table", { name: "Metrics catalog" });
  const formula = table.getByRole("row").filter({ has: page.getByRole("link", { name: "Monthly Recurring Revenue (monthly_recurring_revenue)", exact: true }) });
  const calculation = formula.getByRole("cell").nth(1);
  const storedFormula = "subscription_revenue";
  await expect(calculation).toHaveText(storedFormula);
  await expect(calculation.locator("code")).toHaveAttribute("title", storedFormula);
  await expect(table.getByRole("row").filter({ has: page.getByRole("link", { name: "Net Revenue (net_revenue)", exact: true }) }).getByRole("cell").nth(1)).toHaveText("SUM(net_revenue)");
  await expect(table.getByRole("row").filter({ has: page.getByRole("link", { name: "Revenue per Customer (revenue_per_customer)", exact: true }) }).getByRole("cell").nth(1)).toHaveText("net_revenue / active_customers");
  await expect(table.getByRole("row").filter({ has: page.getByRole("link", { name: "Rolling 28-day Orders (rolling_28d_orders)", exact: true }) }).getByRole("cell").nth(1)).toHaveText("CUMULATIVE(net_revenue, window: 28 day)");
  await expect(table.getByRole("row").filter({ has: page.getByRole("link", { name: "Trial to Paid Conversion (trial_to_paid_conversion)", exact: true }) }).getByRole("cell").nth(1)).toHaveText("CONVERSION(trial_started → paid_conversion, entity: account, calculation: conversion_rate, window: 30 day)");
  await page.getByRole("searchbox", { name: "Search metrics" }).fill("SUM(monthly_amount)");
  await expect(table.getByRole("link", { name: "Subscription Revenue (subscription_revenue)", exact: true })).toHaveCount(1);
  await expect(table.getByRole("link", { name: "Monthly Recurring Revenue (monthly_recurring_revenue)", exact: true })).toHaveCount(0);
});

test("catalog search and compact filters align on desktop and wrap without mobile overflow", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/dev/semantics-demo?scene=ready");
  await expect(page.getByRole("table", { name: "Metrics catalog" })).toBeVisible();
  const toolbar = page.getByRole("search", { name: "Semantics filters" });
  const search = toolbar.getByRole("searchbox", { name: "Search metrics" });
  const filters = ["Filter by model", "Filter by kind", "Filter by status", "Sort definitions"].map((name) => toolbar.getByRole("combobox", { name }));
  const controls = [search, ...filters];
  for (const control of controls) await expect(control).toBeVisible();
  const desktop = await Promise.all(controls.map((control) => control.boundingBox()));
  for (const bounds of desktop.slice(1)) {
    expect(Math.abs(bounds!.y - desktop[0]!.y)).toBeLessThan(1);
    expect(bounds!.width).toBeLessThanOrEqual(208);
    expect(bounds!.height).toBe(32);
  }
  await page.screenshot({ path: evidencePath("screenshots/semantics-filters-desktop.png") });

  await page.setViewportSize({ width: 390, height: 844 });
  for (const control of controls) await expect(control).toBeVisible();
  const mobile = await Promise.all(controls.map((control) => control.boundingBox()));
  for (const bounds of mobile.slice(1)) {
    expect(bounds!.y).toBeGreaterThanOrEqual(mobile[0]!.y + mobile[0]!.height);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
  }
  expect(new Set(mobile.slice(1).map((bounds) => Math.round(bounds!.y))).size).toBeGreaterThan(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: evidencePath("screenshots/semantics-filters-mobile.png") });
});

test("Models is a flat table with row keys and join targets", async ({page})=>{
  await page.goto("/dev/semantics-demo?scene=ready&view=models");
  const table=page.getByRole("table",{name:"Models catalog"});
  await expect(table.getByRole("link",{name:"Orders (commerce__orders)",exact:true})).toHaveCount(1);
  await expect(table).toContainText("Row key");
  await expect(table).toContainText("Links to");
  await expect(page.locator("[data-resource-inspector]")).toHaveCount(0);
  await page.getByRole("combobox",{name:"Sort definitions"}).click();
  await page.getByRole("option",{name:"Recently updated",exact:true}).click();
  await expect(page).toHaveURL(/sort=updated/);
});

test("mobile catalog rows show identity, one-line formula and compact metadata without scrolling", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/dev/semantics-demo?scene=ready");
  const table = page.getByRole("table", { name: "Metrics catalog" });
  const row = table.getByRole("row").filter({ has: page.getByRole("link", { name: "Net Revenue (net_revenue)", exact: true }) });
  const cells = row.getByRole("cell");
  const boxes = await Promise.all([0, 1, 2, 3, 4].map((index) => cells.nth(index).boundingBox()));
  expect(boxes[1]!.y).toBeGreaterThanOrEqual(boxes[0]!.y + boxes[0]!.height);
  expect(boxes[2]!.y).toBeGreaterThanOrEqual(boxes[1]!.y + boxes[1]!.height);
  expect(boxes[3]!.y).toBe(boxes[2]!.y);
  expect(boxes[4]!.y).toBe(boxes[2]!.y);
  await expect(row).toContainText("SUM(net_revenue)");
  await expect(row).toContainText("No issues");
  expect(await row.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("metric filters use URL state and keyboard navigation switches tabs",async({page})=>{
  await page.goto("/dev/semantics-demo?scene=ready");
  await page.getByRole("combobox",{name:"Filter by kind"}).click();
  await page.getByRole("option",{name:"Ratio",exact:true}).click();
  await expect(page).toHaveURL(/kindFilter=ratio/);
  await expect(page.getByRole("table",{name:"Metrics catalog"}).getByRole("link",{name:"Net Revenue (net_revenue)",exact:true})).toHaveCount(0);
  const metrics=page.getByRole("tab",{name:"Metrics",exact:true});
  await metrics.focus(); await metrics.press("ArrowRight");
  await expect(page.getByRole("tab",{name:"Models",exact:true})).toHaveAttribute("aria-selected","true");
});

test("empty catalog offers the first table path",async({page})=>{
  await page.goto("/dev/semantics-demo?scene=empty");
  await expect(page.getByText("No metrics yet",{exact:true})).toBeVisible();
  await page.getByRole("button",{name:"Describe a table",exact:true}).click();
  await expect(page.getByRole("dialog",{name:"Find a table",exact:true})).toBeVisible();
  await page.getByRole("combobox",{name:"Find a table",exact:true}).press("Escape");
  await page.getByRole("button",{name:"New",exact:true}).click();
  await expect(page.getByRole("menuitem",{name:/Model/})).toHaveAttribute("href",/\/semantic-models\/new$/);
});
