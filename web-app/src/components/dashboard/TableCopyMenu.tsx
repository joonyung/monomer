import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { Copy } from "lucide-react";
import { IconButton } from "@/components/ui/IconButton";

export function TableCopyMenu({ disabled, onCopy }: { disabled: boolean; onCopy: (display: boolean) => void }) {
  const id = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState({ left: 0, top: 0 });
  useEffect(() => {
    if (!open) return;
    const close = () => menu.current?.hidePopover();
    window.addEventListener("resize", close);
    window.addEventListener("scroll", close, true);
    return () => { window.removeEventListener("resize", close); window.removeEventListener("scroll", close, true); };
  }, [open]);
  const place = () => {
    const rect = trigger.current?.getBoundingClientRect();
    if (rect) setPosition({ left: Math.max(8, Math.min(rect.right - 184, window.innerWidth - 192)), top: rect.bottom + 104 > window.innerHeight ? Math.max(8, rect.top - 104) : rect.bottom + 4 });
  };
  const move = (event: KeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? []);
    const index = items.indexOf(document.activeElement as HTMLButtonElement);
    if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      const next = event.key === "Home" ? 0 : event.key === "End" ? items.length - 1 : (index + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
      items[next]?.focus();
    }
    if (event.key === "Tab") menu.current?.hidePopover();
  };
  const choose = (display: boolean) => {
    onCopy(display);
    menu.current?.hidePopover();
    trigger.current?.focus();
  };
  return <>
    <IconButton ref={trigger} label="Copy selected cell" icon={Copy} disabled={disabled} popoverTarget={id} aria-haspopup="menu" aria-expanded={open} onClick={place} onKeyDown={(event) => {
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault(); place(); menu.current?.showPopover();
      }
    }} />
    <div ref={menu} id={id} popover="auto" role="menu" aria-label="Copy format" className="dashboard-table-copy-menu" style={position} onKeyDown={move} onToggle={(event) => {
      const visible = event.newState === "open"; setOpen(visible);
      if (visible) menu.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus({ preventScroll: true });
    }}>
      <button type="button" role="menuitem" onClick={() => choose(true)}>Copy displayed text</button>
      <button type="button" role="menuitem" onClick={() => choose(false)}>Copy raw value</button>
    </div>
  </>;
}
