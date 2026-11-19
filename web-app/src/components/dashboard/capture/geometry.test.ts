import { describe, expect, it } from "vitest";
import { captureBounds, intersects, selectionRect } from "./geometry";
describe("Dashboard capture selection", () => {
  it("selects the same area when dragging in reverse", () => {
    expect(selectionRect({ x: 200, y: 180 }, { x: 20, y: 30 })).toEqual(selectionRect({ x: 20, y: 30 }, { x: 200, y: 180 }));
  });
  it("includes partially covered widgets but excludes adjacent widgets", () => {
    const widget = { x: 40, y: 60, width: 100, height: 80 };
    expect(intersects(widget, { x: 0, y: 0, width: 41, height: 61 })).toBe(true);
    expect(intersects(widget, { x: 0, y: 0, width: 40, height: 60 })).toBe(false);
  });
  it("preserves gaps and complete widget bounds in multi-widget exports", () => {
    expect(captureBounds([{ x: 10, y: 20, width: 200, height: 100 }, { x: 230, y: 40, width: 90, height: 120 }])).toEqual({ x: 10, y: 20, width: 310, height: 140 });
    expect(() => captureBounds([])).toThrow("Select at least one widget.");
  });
});
