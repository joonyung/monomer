import { expect, test } from "./support/test";
import AxeBuilder from "@axe-core/playwright";
import { openExploreRuns } from "./explore-runs-api-fixture";

const sql = "SELECT 210::numeric AS value, pg_sleep(12)::text AS delayed";

test("Query type tabs and their composers have no WCAG accessibility violations", async ({ page }) => {
  await openExploreRuns(page);
  const tabs = page.getByRole("tablist", { name: "Query type" });
  await expect(tabs.getByRole("tab")).toHaveText(["Metrics", "SQL"]);

  for (const mode of ["Metrics", "SQL"] as const) {
    const tab = tabs.getByRole("tab", { name: mode, exact: true });
    const panel = page.getByRole("tabpanel", { name: mode, exact: true });
    await expect(tab).toHaveAttribute("aria-selected", "true");
    await expect(panel).toBeVisible();
    const panelId = await panel.getAttribute("id");
    expect(panelId).toBeTruthy();
    await expect(tab).toHaveAttribute("aria-controls", panelId!);
    await expect(panel).toHaveAttribute("aria-labelledby", (await tab.getAttribute("id"))!);
    if (mode === "SQL") await expect(panel.getByTestId("sql-editor").locator(".monaco-editor")).toBeVisible();

    const accessibility = await new AxeBuilder({ page })
      .include('[role="tablist"][aria-label="Query type"]')
      .include(`#${panelId}`)
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();
    expect(accessibility.violations, `${mode} Query type and composer`).toEqual([]);

    await tab.focus();
    await page.keyboard.press(mode === "Metrics" ? "ArrowRight" : "ArrowLeft");
    await expect(tabs.getByRole("tab", { name: mode === "Metrics" ? "SQL" : "Metrics", exact: true })).toBeFocused();
  }
});

for (const input of ["typing", "paste"] as const) {
  test(`SQL editor preserves ${input} order across controlled updates`, async ({ page }) => {
    await openExploreRuns(page);
    await page.getByRole("tab", { name: "SQL", exact: true }).click();
    const editor = page.getByTestId("sql-editor").locator(".monaco-editor");
    await editor.click({ position: { x: 100, y: 24 } });
    await page.keyboard.press("ControlOrMeta+A");
    if (input === "typing") await page.keyboard.type(sql);
    else await page.keyboard.insertText(sql);
    await expect.poll(async () => editor.locator(".view-line").allTextContents()
      .then((lines) => lines.join("\n").replaceAll("\u00a0", " "))).toBe(sql);
  });
}
