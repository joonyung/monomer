import { describe, expect, it, vi } from "vitest";
import { attachChartInputBridge } from "./chart-input-bridge";

describe("Chart input bridge wheel scrolling", () => {
  it("preserves browser scrolling while forwarding wheel coordinates, deltas, and modifiers", () => {
    const target = new EventTarget();
    const canvas = Object.assign(target, {
      getBoundingClientRect: () => ({ left: 20, top: 30 }),
    }) as unknown as HTMLCanvasElement;
    const dispatch = vi.fn();
    const bridge = attachChartInputBridge(canvas, dispatch);
    const wheel = Object.assign(new Event("wheel", { cancelable: true }), {
      clientX: 100, clientY: 150,
      deltaX: 10, deltaY: 120, deltaMode: 0,
      altKey: false, ctrlKey: true, metaKey: false, shiftKey: true,
    });

    expect(canvas.dispatchEvent(wheel)).toBe(true);
    expect(wheel.defaultPrevented).toBe(false);
    expect(dispatch).toHaveBeenCalledExactlyOnceWith({
      kind: "wheel", x: 80, y: 120,
      deltaX: 10, deltaY: 120, deltaMode: 0,
      altKey: false, ctrlKey: true, metaKey: false, shiftKey: true,
      trusted: false,
    });

    bridge.dispose();
    canvas.dispatchEvent(wheel);
    expect(dispatch).toHaveBeenCalledTimes(1);
  });
});
