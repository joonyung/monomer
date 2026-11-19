import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent, type RefObject } from "react";
import { createPortal } from "react-dom";
import { Camera, Check, Download, LoaderCircle, X } from "lucide-react";
import { Button, buttonClassName } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { captureBounds, intersects, selectionRect, type CaptureRect } from "./geometry";
import { exportWidgets, measureWidgets, type CaptureWidget } from "./export";
import type { CaptureProvenance } from "./provenance";

export function DashboardCapture({ target, provenance }: { target: RefObject<HTMLDivElement | null>; provenance: CaptureProvenance }) {
  const title = provenance.title;
  const [preview, setPreview] = useState<string | null>(null);
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview); }, [preview]);
  const [root, setRoot] = useState<HTMLDivElement | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  return <>
    <IconButton ref={trigger} label="Capture dashboard" icon={Camera} size="md" aria-pressed={Boolean(root)} onClick={() => setRoot(root ? null : target.current)} />
    {root && <CaptureSelection root={root} provenance={provenance} onCaptured={blob => { setRoot(null); trigger.current?.focus(); setPreview(URL.createObjectURL(blob)); }} onClose={() => { setRoot(null); trigger.current?.focus(); }} />}
    <Dialog closeLabel="Close capture preview"
      open={Boolean(preview)}
      onOpenChange={open => { if (!open) setPreview(null); }}
      size="xl"
      title="Capture preview"
      headerActions={preview && <a href={preview} download={`${title.replace(/[^\p{L}\p{N}_-]/gu, "_").slice(0, 80) || "dashboard"}.png`} className={buttonClassName("primary", "md", "")}><Download aria-hidden="true" />Save PNG</a>}
      bodyClassName="bg-surface p-4"
    >
      {preview && <img src={preview} alt={`${title} — selected widgets`} className="mx-auto h-auto max-w-full" />}
    </Dialog>
  </>;
}

function CaptureSelection({ root, provenance, onCaptured, onClose }: { root: HTMLDivElement; provenance: CaptureProvenance; onCaptured: (blob: Blob) => void; onClose: () => void }) {
  const [widgets, setWidgets] = useState<CaptureWidget[]>([]);
  const [selected, setSelected] = useState<Set<string>>(() => new Set(measureWidgets(root).map(widget => widget.id)));
  const [box, setBox] = useState<CaptureRect | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const overlay = useRef<HTMLDivElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const drag = useRef<{ start: { x: number; y: number }; client: { x: number; y: number }; moved: boolean; initial: Set<string>; removing: boolean; hit?: string } | null>(null);
  const scrollParent = root.closest<HTMLElement>("main");
  useLayoutEffect(() => {
    const measure = () => setWidgets(measureWidgets(root));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(root);
    root.querySelectorAll<HTMLElement>("[data-capture-widget-id]").forEach(node => observer.observe(node));
    cancel.current?.focus();
    return () => observer.disconnect();
  }, [root]);
  useEffect(() => {
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) { event.preventDefault(); onClose(); }
    };
    window.addEventListener("keydown", escape);
    return () => window.removeEventListener("keydown", escape);
  }, [busy, onClose]);
  function updateDrag() {
    const active = drag.current;
    if (!active) return;
    const rect = root.getBoundingClientRect();
    const next = selectionRect(active.start, { x: active.client.x - rect.left, y: active.client.y - rect.top });
    if (!active.moved && Math.max(next.width, next.height) < 5) return;
    active.moved = true;
    setBox(next);
    const nextSelection = new Set(active.initial);
    for (const widget of measureWidgets(root)) {
      if (!intersects(widget, next)) continue;
      if (active.removing) nextSelection.delete(widget.id);
      else nextSelection.add(widget.id);
    }
    setSelected(nextSelection);
  }
  const updateRef = useRef(updateDrag);
  updateRef.current = updateDrag;
  useEffect(() => {
    let frame: number;
    const tick = () => {
      const active = drag.current;
      if (active?.moved && scrollParent) {
        const r = scrollParent.getBoundingClientRect();
        const speed = active.client.y < r.top + 48 ? -14 : active.client.y > r.bottom - 72 ? 14 : 0;
        if (speed) { scrollParent.scrollTop += speed; updateRef.current(); }
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [scrollParent]);
  const toggle = (id: string) => setSelected(current => {
    const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next;
  });
  async function save() {
    setBusy(true); setError(null);
    try { onCaptured(await exportWidgets(root, selected, provenance)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "The image could not be saved."); setBusy(false); }
  }
  const pointerHandlers = {
      onPointerDown: (event: PointerEvent<HTMLElement>) => {
        if (busy || event.button !== 0) return;
        const r = root.getBoundingClientRect();
        const hit = (event.target as HTMLElement).closest<HTMLElement>("[data-select-widget]")?.dataset.selectWidget;
        drag.current = { start: { x: event.clientX - r.left, y: event.clientY - r.top }, client: { x: event.clientX, y: event.clientY }, moved: false, initial: new Set(selected), removing: Boolean(hit && selected.has(hit)), hit };
        event.currentTarget.setPointerCapture(event.pointerId);
      },
      onPointerMove: (event: PointerEvent<HTMLElement>) => { if (drag.current) { drag.current.client = { x: event.clientX, y: event.clientY }; updateDrag(); } },
      onPointerUp: (event: PointerEvent<HTMLElement>) => {
        const active = drag.current;
        if (active && !active.moved && active.hit) toggle(active.hit);
        drag.current = null; setBox(null);
        if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
      },
      onPointerCancel: () => { if (drag.current) setSelected(drag.current.initial); drag.current = null; setBox(null); },
  };
  const bounds = widgets.length ? captureBounds(widgets) : { x: 0, y: 0, width: 0, height: 0 };
  return <>
    {createPortal(<div ref={overlay} data-capture-overlay role="group" aria-label="Select widgets to capture" className="absolute inset-0 z-30 touch-none select-none cursor-crosshair" style={{ minHeight: bounds.y + bounds.height }}
      {...pointerHandlers}>
      {widgets.map(widget => <button key={widget.id} type="button" data-select-widget={widget.id} aria-label={`Select ${widget.id}`} aria-pressed={selected.has(widget.id)} disabled={busy} onClick={event => { if (event.detail === 0 && !busy) toggle(widget.id); }} className={`absolute cursor-crosshair rounded-[4px] border-2 transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring ${selected.has(widget.id) ? "border-accent bg-transparent" : "border-transparent bg-background/65 hover:bg-background/35"}`} style={{ left: widget.x, top: widget.y, width: widget.width, height: widget.height }}>
        <SelectionCheck selected={selected.has(widget.id)} />
      </button>)}

    </div>, root)}
    {box && createPortal(<div data-capture-overlay className="pointer-events-none fixed z-[70] border border-accent bg-accent/10" style={{ left: box.x + root.getBoundingClientRect().left, top: box.y + root.getBoundingClientRect().top, width: box.width, height: box.height }} />, document.body)}
    {createPortal(<div data-capture-overlay className="fixed inset-x-3 bottom-5 z-[80] mx-auto flex w-fit max-w-[calc(100vw-24px)] flex-wrap items-center justify-center gap-3 rounded-[8px] border border-border bg-background px-4 py-3 shadow-overlay" role="region" aria-label="Capture controls">
      <div className="min-w-0"><p className="type-control">Select widgets</p>{error && <p role="alert" className="type-meta max-w-[360px] text-error">{error}</p>}</div>
      <Button type="button" variant="ghost" size="sm" disabled={busy} onClick={() => setSelected(new Set(widgets.map(w => w.id)))}>Select all</Button>
      <Button type="button" variant="ghost" size="sm" disabled={busy || !selected.size} onClick={() => setSelected(new Set())}>Clear</Button>
      <button type="button" aria-label={busy ? "Capturing…" : "Capture"} title={busy ? "Capturing…" : "Capture"} disabled={busy || !selected.size} aria-busy={busy || undefined} className={`grid h-9 w-9 place-items-center rounded-[4px] bg-accent text-accent-foreground transition-colors duration-150 ${busy ? "cursor-progress" : "hover:bg-accent-hover disabled:cursor-not-allowed disabled:bg-surface-hover disabled:text-foreground-secondary"}`} onClick={() => void save()}>{busy ? <LoaderCircle aria-hidden="true" className="h-4 w-4 animate-spin motion-reduce:animate-none" /> : <Camera aria-hidden="true" className="h-4 w-4" />}</button>
      <IconButton ref={cancel} label="Cancel capture" icon={X} title="Cancel capture (Esc)" disabled={busy} onClick={onClose} />
    </div>, document.body)}
  </>;
}

function SelectionCheck({ selected }: { selected: boolean }) {
  return <span aria-hidden="true" className={`pointer-events-none absolute right-2 top-2 grid h-5 w-5 place-items-center rounded-[4px] border ${selected ? "border-accent bg-accent text-accent-foreground" : "border-border bg-background"}`}>{selected && <Check className="h-3.5 w-3.5" />}</span>;
}
