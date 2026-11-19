import { describe, expect, it } from "vitest";
import { panelOffsetWithinViewport } from "./CatalogTablePicker";

describe("CatalogTablePicker panel position", () => {
  it("opens under the trigger when the panel fits", () => {
    expect(panelOffsetWithinViewport(300, 1440)).toBe(0);
  });

  it("moves left near the right edge so the page never widens", () => {
    expect(panelOffsetWithinViewport(1200, 1440)).toBe(1440 - 16 - 420 - 1200);
  });

  it("keeps a phone-width panel inside both margins", () => {
    const offset = panelOffsetWithinViewport(20, 390);
    const panelWidth = 390 - 32;
    expect(20 + offset).toBe(16);
    expect(20 + offset + panelWidth).toBe(390 - 16);
  });
});
