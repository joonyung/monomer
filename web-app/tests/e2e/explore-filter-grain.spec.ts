import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";

test("filters retain declared grains and exact request identities through metadata states", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/dev/explore-semantic-filter-fixture");

  await page.getByRole("button", { name: "Add group by", exact: true }).click();
  const groupByPanel = page.getByRole("dialog", { name: "Group by" });
  await expect(groupByPanel.getByRole("button", { name: "Created at · Week" }).first()).toBeVisible();
  await expect(groupByPanel.getByRole("button", { name: "Created at · Month" }).first()).toBeVisible();
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: "Add filter", exact: true }).click();
  const filterPanel = page.getByRole("dialog", { name: "Filter" });
  await filterPanel.getByRole("combobox", { name: "Filter dimension" }).click();
  await expect(page.getByRole("listbox", { name: "Filter dimension" }).getByRole("option")).toHaveText([
    "Created at (day) · Orders", "region · Orders", "Amount · Orders", "Active · Orders", "Uppercase region · Orders", "Profile · Orders",
    "Created at (hour) · Events", "region · Events", "Amount · Events", "Active · Events", "Uppercase region · Events", "Profile · Events",
  ]);
  await page.keyboard.press("Escape");
  await expect(filterPanel.getByRole("combobox", { name: "Compare as", exact: true }), "a time dimension's type follows its grain").toHaveCount(0);
  await expect(filterPanel.getByText("Compares whole days.", { exact: true })).toBeVisible();
  await filterPanel.getByLabel("Filter value", { exact: true }).fill("2026-09-28");
  await filterPanel.getByRole("button", { name: "Add filter", exact: true }).click();
  await expect(page.getByLabel("Filter request")).toHaveText(JSON.stringify([
    { kind: "DATE", dimension: { semanticModelName: "orders", name: "created_at" }, operator: "EQUALS", values: ["2026-09-28"] },
  ]));
  const token = page.getByRole("button", { name: "Created at (day) is 2026-09-28", exact: true });
  await expect(token).toBeVisible();

  await token.click();
  await filterPanel.getByRole("combobox", { name: "Filter dimension" }).click();
  await page.getByRole("listbox", { name: "Filter dimension" }).getByRole("option", { name: "Created at (hour) · Events" }).click();
  await expect(filterPanel.getByText("Compares whole hours, in UTC time.", { exact: true })).toBeVisible();
  await page.screenshot({ path: evidencePath("../screenshots/explore-filter-grain-desktop.png"), fullPage: true });
  await page.keyboard.press("Escape");

  const state = page.getByRole("combobox", { name: "Fixture metadata state" });
  for (const unavailableState of ["loading", "error", "missing"]) {
    await state.selectOption(unavailableState);
    const unresolved = page.getByRole("button", { name: new RegExp(`^orders / created_at · ${unavailableState === "loading" ? "Loading metadata…" : "Metadata unavailable"}`) });
    await expect(unresolved).toBeVisible();
    await page.screenshot({ path: evidencePath(`../screenshots/explore-filter-grain-${unavailableState}.png`), fullPage: true });
  }
  await state.selectOption("error");
  await page.getByRole("button", { name: /^orders \/ created_at/ }).click();
  await filterPanel.getByRole("button", { name: "Retry filter dimensions" }).click();
  await page.keyboard.press("Escape");
  await expect(token).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(token).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await token.click();
  expect(await filterPanel.evaluate((element) => element.getBoundingClientRect().right)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: evidencePath("../screenshots/explore-filter-grain-mobile.png"), fullPage: true });
});

test("hour filters are entered and shown as wall-clock times in the Reporting time zone", async ({ page }) => {
  await page.goto("/dev/explore-semantic-filter-fixture");
  await page.getByRole("combobox", { name: "Fixture Reporting time zone" }).selectOption("Asia/Seoul");
  await page.getByRole("button", { name: "Add filter", exact: true }).click();
  const panel = page.getByRole("dialog", { name: "Filter" });
  await panel.getByRole("combobox", { name: "Filter dimension" }).click();
  await page.getByRole("option", { name: "Created at (hour) · Events", exact: true }).click();
  await expect(panel.getByText("Compares whole hours, in Asia/Seoul time.", { exact: true })).toBeVisible();
  await panel.getByLabel("Filter value", { exact: true }).fill("2026-08-20T09:30:05");
  await panel.getByRole("button", { name: "Add filter", exact: true }).click();
  await expect(page.getByLabel("Filter request")).toHaveText(JSON.stringify([
    { kind: "TIMESTAMP", dimension: { semanticModelName: "events", name: "created_at" }, operator: "EQUALS", values: ["2026-08-20T09:30:05+09:00"] },
  ]));
  await page.getByRole("button", { name: /^Created at \(hour\) is / }).click();
  await expect(panel.getByLabel("Filter value", { exact: true }), "a saved value reopens as the zone's wall-clock time").toHaveValue("2026-08-20T09:30:05");
  await page.keyboard.press("Escape");
  await page.getByRole("combobox", { name: "Fixture Reporting time zone" }).selectOption("UTC");
  await page.getByRole("button", { name: /^Created at \(hour\) is / }).click();
  await expect(panel.getByLabel("Filter value", { exact: true }), "the same instant reads in the new zone").toHaveValue("2026-08-20T00:30:05");
  await expect(panel.getByText("Compares whole hours, in UTC time.", { exact: true })).toBeVisible();
});

test("plain columns select comparison types while computed and unknown columns keep the selector", async ({ page }) => {
  await page.goto("/dev/explore-semantic-filter-fixture");
  const panel = page.getByRole("dialog", { name: "Filter" });
  const expectedFilters: Array<Record<string, unknown>> = [];
  for (const [label, name, kind, input, value] of [
    ["region", "region", "TEXT", "EU", "EU"],
    ["Amount", "amount", "NUMBER", "1234.50", "1234.5"],
    ["Active", "active", "BOOLEAN", "false", false],
  ] as const) {
    await page.getByRole("button", { name: "Add filter", exact: true }).click();
    await panel.getByRole("combobox", { name: "Filter dimension" }).click();
    await page.getByRole("option", { name: `${label} · Orders`, exact: true }).click();
    await expect(panel.getByRole("combobox", { name: "Compare as", exact: true })).toHaveCount(0);
    if (kind === "BOOLEAN") {
      await panel.getByRole("combobox", { name: "Filter value" }).click();
      await page.getByRole("option", { name: input, exact: true }).click();
    } else {
      await panel.getByLabel("Filter value", { exact: true }).fill(input);
    }
    await panel.getByRole("button", { name: "Add filter", exact: true }).click();
    expectedFilters.push({ kind, dimension: { semanticModelName: "orders", name }, operator: "EQUALS", values: [value] });
    await expect(page.getByLabel("Filter request")).toHaveText(JSON.stringify(expectedFilters));
  }
  await page.getByRole("button", { name: "Add filter", exact: true }).click();
  for (const label of ["Uppercase region", "Profile"]) {
    await panel.getByRole("combobox", { name: "Filter dimension" }).click();
    await page.getByRole("option", { name: `${label} · Orders`, exact: true }).click();
    await panel.getByRole("combobox", { name: "Compare as", exact: true }).click();
    await expect(page.getByRole("listbox", { name: "Compare as" }).getByRole("option")).toHaveText(["Text", "Number", "True or false"]);
    await page.getByRole("option", { name: "Number", exact: true }).click();
    await expect(panel.getByLabel("Filter value", { exact: true })).toHaveAttribute("inputmode", "decimal");
  }
});

test("switching filter targets clears values and incompatible operators", async ({ page }) => {
  await page.goto("/dev/explore-semantic-filter-fixture");
  await page.getByRole("button", { name: "Add filter", exact: true }).click();
  const panel = page.getByRole("dialog", { name: "Filter" });
  const chooseDimension = async (label: string) => {
    await panel.getByRole("combobox", { name: "Filter dimension" }).click();
    await page.getByRole("option", { name: `${label} · Orders`, exact: true }).click();
  };
  await chooseDimension("Amount");
  await panel.getByRole("combobox", { name: "Filter operator" }).click();
  await page.getByRole("option", { name: "is greater than", exact: true }).click();
  await panel.getByLabel("Filter value", { exact: true }).fill("123");
  await chooseDimension("Active");
  await expect(panel.getByRole("combobox", { name: "Filter operator" })).toHaveText("is");
  await expect(panel.getByRole("combobox", { name: "Filter value" })).toHaveText("Select value");
  await expect(panel.getByRole("combobox", { name: "Compare as" })).toHaveCount(0);
  await expect(panel.getByRole("button", { name: "Add filter", exact: true })).toBeDisabled();
  await chooseDimension("Uppercase region");
  await expect(panel.getByRole("combobox", { name: "Compare as" })).toHaveText("as Text");
  await expect(panel.getByLabel("Filter value", { exact: true })).toBeEmpty();
});
