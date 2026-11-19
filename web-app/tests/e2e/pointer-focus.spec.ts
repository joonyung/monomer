import { expect, test } from "./support/test";
import { evidencePath } from "./evidence";

for (const viewport of [{ width: 1280, height: 800 }, { width: 390, height: 844 }]) {
  test(`pointer focus and keyboard focus remain distinct at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto("/dev/design-system-fixture");
    const trigger = page.getByRole("button", { name: "New document", exact: true });
    await trigger.click();
    const close = page.getByRole("button", { name: "Close dialog" });
    await expect(close).toBeFocused();
    await expect(close).toHaveCSS("outline-style", "none");
    await expect(close).toHaveCSS("box-shadow", "none");
    await page.screenshot({ path: evidencePath(`../screenshots/pointer-focus-${viewport.width}.png`) });

    await page.keyboard.press("Tab");
    await expect(page.getByRole("button", { name: "Choose file" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(close).toBeFocused();
    await expect(close).toHaveCSS("outline-width", "2px");
    await expect(close).toHaveCSS("outline-style", "solid");
    await page.screenshot({ path: evidencePath(`../screenshots/keyboard-focus-${viewport.width}.png`) });
    await page.keyboard.press("Escape");
    await expect(trigger).toBeFocused();
    await expect(trigger).toHaveCSS("outline-style", "solid");
    await expect(trigger).toHaveCSS("outline-width", "2px");

    await page.reload();
    await trigger.click();
    await close.click();
    await expect(trigger).toBeFocused();
    await expect(trigger).toHaveCSS("outline-style", "none");
    const input = page.getByLabel("Search sources");
    await input.click();
    await input.fill("warehouse");
    await expect(input).toBeFocused();
    await expect(input).toHaveValue("warehouse");
    await expect(input).toHaveCSS("outline-style", "none");
    await expect(input).toHaveCSS("box-shadow", "none");
    await expect(input).toHaveCSS("caret-color", "rgb(193, 116, 45)");

    await page.emulateMedia({ forcedColors: "active" });
    await page.keyboard.press("Shift+Tab");
    await expect(trigger).toBeFocused();
    await expect(trigger).toHaveCSS("outline-style", "solid");
    await expect(trigger).toHaveCSS("outline-width", "2px");
  });
}
