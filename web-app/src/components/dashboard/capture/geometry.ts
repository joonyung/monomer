export interface CaptureRect { x: number; y: number; width: number; height: number }
export function selectionRect(a: { x: number; y: number }, b: { x: number; y: number }): CaptureRect {
  return { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), width: Math.abs(a.x - b.x), height: Math.abs(a.y - b.y) };
}
export function intersects(a: CaptureRect, b: CaptureRect) {
  return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y;
}
export function captureBounds(rects: CaptureRect[]): CaptureRect {
  if (!rects.length) throw new Error("Select at least one widget.");
  const x = Math.min(...rects.map(r => r.x));
  const y = Math.min(...rects.map(r => r.y));
  return { x, y, width: Math.max(...rects.map(r => r.x + r.width)) - x, height: Math.max(...rects.map(r => r.y + r.height)) - y };
}
