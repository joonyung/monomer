import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";

const PANEL_GAP = 4;
const FOCUSABLE = "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])";

/** Moves focus to the first focusable element after [anchor] in document order, outside [skip]. */
function focusNextAfter(anchor: HTMLElement, skip: HTMLElement | null): void {
  const candidates = [...document.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((element) => !skip?.contains(element));
  const next = candidates[candidates.indexOf(anchor) + 1];
  next?.focus();
}
const VIEWPORT_MARGIN = 8;

/**
 * A question token: a compact button that opens an anchored overlay panel. Outside pointer and
 * Escape close it; Escape returns focus to the token. The panel takes focus on open.
 */
export function TokenPopover({
  label,
  accessibleName,
  panelLabel,
  className,
  panelWidth,
  addControl = false,
  openRequest,
  children,
}: {
  label: ReactNode;
  accessibleName?: string;
  panelLabel: string;
  className: string;
  panelWidth: number;
  /** Marks the "+" control that adds a token, so focus can land on it after a removal. */
  addControl?: boolean;
  openRequest?: number;
  children: (close: () => void) => ReactNode;
}) {
  const panelId = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (openRequest !== undefined) setOpen(true);
  }, [openRequest]);
  const [position, setPosition] = useState({ left: 0, top: 0, width: panelWidth, maxHeight: 420 });

  // A click elsewhere moves focus where the person clicked, so closing then leaves focus alone.
  const returnFocusOnClose = useRef(true);
  const close = () => {
    setOpen(false);
    if (returnFocusOnClose.current) window.requestAnimationFrame(() => triggerRef.current?.focus());
  };

  useLayoutEffect(() => {
    if (!open) return;
    const reposition = () => {
      const bounds = triggerRef.current?.getBoundingClientRect();
      if (!bounds) return;
      const width = Math.min(panelWidth, window.innerWidth - VIEWPORT_MARGIN * 2);
      const left = Math.max(VIEWPORT_MARGIN, Math.min(bounds.left, window.innerWidth - width - VIEWPORT_MARGIN));
      const spaceBelow = window.innerHeight - bounds.bottom - PANEL_GAP - VIEWPORT_MARGIN;
      const spaceAbove = bounds.top - PANEL_GAP - VIEWPORT_MARGIN;
      const panelHeight = panelRef.current?.scrollHeight ?? 0;
      // A trigger near the bottom of the viewport opens its panel upward when the panel does not fit below.
      if (panelHeight > spaceBelow && spaceAbove > spaceBelow) {
        const height = Math.min(panelHeight, spaceAbove);
        setPosition({ left, top: bounds.top - PANEL_GAP - height, width, maxHeight: spaceAbove });
        return;
      }
      const top = bounds.bottom + PANEL_GAP;
      setPosition({ left, top, width, maxHeight: Math.max(200, spaceBelow) });
    };
    reposition();
    window.addEventListener("resize", reposition);
    window.addEventListener("scroll", reposition, true);
    return () => {
      window.removeEventListener("resize", reposition);
      window.removeEventListener("scroll", reposition, true);
    };
  }, [open, panelWidth]);

  useEffect(() => {
    if (!open) return;
    const frame = window.requestAnimationFrame(() => {
      const target = panelRef.current?.querySelector<HTMLElement>("[data-autofocus]")
        ?? panelRef.current?.querySelector<HTMLElement>("input, select, button, [tabindex='0']");
      target?.focus();
    });
    // A click elsewhere, Run included, keeps an edit the panel can apply; Escape is the way to discard it.
    const dismiss = (event: PointerEvent) => {
      const target = event.target as Node;
      if (panelRef.current?.contains(target) || triggerRef.current?.contains(target)) return;
      returnFocusOnClose.current = false;
      panelRef.current?.querySelector<HTMLFormElement>("form[data-dirty]")?.requestSubmit();
      returnFocusOnClose.current = true;
      setOpen(false);
    };
    // Escape closes the panel even after focus left it, e.g. when the focused control unmounted.
    const dismissWithEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
      window.requestAnimationFrame(() => triggerRef.current?.focus());
    };
    document.addEventListener("pointerdown", dismiss);
    document.addEventListener("keydown", dismissWithEscape, true);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener("pointerdown", dismiss);
      document.removeEventListener("keydown", dismissWithEscape, true);
    };
  }, [open]);

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        aria-label={accessibleName}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        data-question-token={addControl ? undefined : ""}
        data-question-add={addControl ? "" : undefined}
        onClick={() => setOpen((current) => !current)}
        className={className}
      >
        {label}
      </button>
      {open ? createPortal(
        <div
          ref={panelRef}
          id={panelId}
          role="dialog"
          aria-label={panelLabel}
          onKeyDown={(event) => {
            // Tab past either end leaves the panel the way focus would have moved from the token.
            if (event.key !== "Tab" || !panelRef.current || !triggerRef.current) return;
            const focusables = [...panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
            const leavingForward = !event.shiftKey && document.activeElement === focusables.at(-1);
            const leavingBackward = event.shiftKey && document.activeElement === focusables[0];
            if (!leavingForward && !leavingBackward) return;
            event.preventDefault();
            const trigger = triggerRef.current;
            const panel = panelRef.current;
            if (leavingForward) focusNextAfter(trigger, panel);
            else trigger.focus();
            setOpen(false);
          }}
          className="fixed z-50 flex flex-col overflow-hidden rounded-[6px] border border-border bg-background text-foreground shadow-overlay"
          style={{ left: position.left, top: position.top, width: position.width, maxHeight: position.maxHeight }}
        >
          {children(close)}
        </div>,
        document.body,
      ) : null}
    </>
  );
}
