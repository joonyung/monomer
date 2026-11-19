import type { ChartInput } from "./worker-protocol";

export interface ChartInputBridge {
  dispose(): void;
}

export function attachChartInputBridge(
  canvas: HTMLCanvasElement,
  dispatch: (input: ChartInput) => void,
): ChartInputBridge {
  const coordinates = (event: MouseEvent | PointerEvent | WheelEvent) => {
    const bounds = canvas.getBoundingClientRect();
    return { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
  };
  const pointer = (
    event: PointerEvent | MouseEvent,
    eventType: Extract<ChartInput, { kind: "pointer" }>['eventType'],
  ) => {
    const point = coordinates(event);
    dispatch({
      kind: "pointer",
      eventType,
      ...point,
      button: event.button,
      buttons: event.buttons,
      pointerId: "pointerId" in event ? event.pointerId : 1,
      altKey: event.altKey,
      ctrlKey: event.ctrlKey,
      metaKey: event.metaKey,
      shiftKey: event.shiftKey,
      trusted: event.isTrusted,
    });
  };
  const onWheel = (event: WheelEvent) => {
    dispatch({
      kind: "wheel",
      ...coordinates(event),
      deltaX: event.deltaX,
      deltaY: event.deltaY,
      deltaMode: event.deltaMode,
      altKey: event.altKey,
      ctrlKey: event.ctrlKey,
      metaKey: event.metaKey,
      shiftKey: event.shiftKey,
      trusted: event.isTrusted,
    });
  };
  const onPointerDown = (event: PointerEvent) => {
    canvas.setPointerCapture(event.pointerId);
    pointer(event, "mousedown");
  };
  const onPointerUp = (event: PointerEvent) => {
    pointer(event, "mouseup");
    if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
  };
  const onPointerMove = (event: PointerEvent) => pointer(event, "mousemove");
  const onPointerLeave = (event: PointerEvent) => pointer(event, "mouseout");
  const onClick = (event: MouseEvent) => pointer(event, "click");
  const onDoubleClick = (event: MouseEvent) => pointer(event, "dblclick");
  const onContextMenu = (event: MouseEvent) => {
    event.preventDefault();
    pointer(event, "contextmenu");
  };
  canvas.addEventListener("pointerdown", onPointerDown);
  canvas.addEventListener("pointerup", onPointerUp);
  canvas.addEventListener("pointermove", onPointerMove);
  canvas.addEventListener("pointerleave", onPointerLeave);
  canvas.addEventListener("click", onClick);
  canvas.addEventListener("dblclick", onDoubleClick);
  canvas.addEventListener("contextmenu", onContextMenu);
  canvas.addEventListener("wheel", onWheel, { passive: true });
  return {
    dispose(): void {
      canvas.removeEventListener("pointerdown", onPointerDown);
      canvas.removeEventListener("pointerup", onPointerUp);
      canvas.removeEventListener("pointermove", onPointerMove);
      canvas.removeEventListener("pointerleave", onPointerLeave);
      canvas.removeEventListener("click", onClick);
      canvas.removeEventListener("dblclick", onDoubleClick);
      canvas.removeEventListener("contextmenu", onContextMenu);
      canvas.removeEventListener("wheel", onWheel);
    },
  };
}
