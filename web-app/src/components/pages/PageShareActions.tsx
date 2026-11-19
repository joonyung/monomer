import { useId, useRef, useState } from "react";
import { Check, ChevronDown, Link as LinkIcon } from "lucide-react";
import { useClipboardCopy } from "@/hooks/useClipboardCopy";

export function PageShareActions({ sessionId }: { sessionId?: string }) {
  const linkCopy = useClipboardCopy();
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const menu = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const [position, setPosition] = useState({ top: 0, left: 0 });
  async function copy(current: boolean) {
    const url = new URL(window.location.href);
    url.search = "";
    url.hash = "";
    if (current && sessionId) url.searchParams.set("session", sessionId);
    await linkCopy.copy(url.href);
    if (menu.current?.matches(":popover-open")) {
      menu.current.hidePopover();
      trigger.current?.focus();
    }
  }
  const control = "inline-flex h-9 items-center text-foreground transition-colors hover:bg-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus-ring";
  const copied = linkCopy.status === "copied";
  const failed = linkCopy.status === "failed";
  const CopyIcon = copied ? Check : LinkIcon;
  return <div className="flex flex-wrap items-center gap-2">
    <div role="group" aria-label="Share link" className={`inline-flex items-stretch rounded-[4px] border border-border ${open ? "bg-surface" : ""}`}>
      <button type="button" title={sessionId ? "Copy latest results link" : "Copy page link"} className={`${control} gap-1.5 px-3 type-control ${sessionId ? "rounded-l-[4px]" : "rounded-[4px]"}`} onClick={() => void copy(false)}>
        <CopyIcon className="h-4 w-4" aria-hidden="true" />{copied ? "Link copied" : "Copy link"}
      </button>
      {sessionId && <button ref={trigger} type="button" aria-label="Link options" aria-expanded={open} aria-controls={menuId} className={`${control} w-8 justify-center rounded-r-[4px] border-l border-border text-foreground-secondary hover:text-foreground`} onClick={() => {
        if (menu.current?.matches(":popover-open")) { menu.current.hidePopover(); return; }
        const bounds = trigger.current?.getBoundingClientRect();
        if (bounds) setPosition({ top: bounds.bottom + 6, left: Math.max(8, Math.min(bounds.right - 288, window.innerWidth - 296)) });
        menu.current?.showPopover();
      }}><ChevronDown className="h-3.5 w-3.5" aria-hidden="true" /></button>}
    </div>
    {sessionId && <div ref={menu} id={menuId} popover="auto" onToggle={event => setOpen(event.newState === "open")} aria-label="Link options" style={position} className="fixed m-0 w-72 max-w-[calc(100vw-16px)] rounded-[6px] border border-border bg-background p-1 text-foreground shadow-overlay">
      <button type="button" className="block w-full rounded-[4px] px-3 py-3 text-left hover:bg-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring" onClick={() => void copy(true)}>
        <span className="flex items-center justify-between gap-3 type-control">Current results</span>
        <span className="mt-1 block type-meta leading-relaxed text-foreground-secondary">Keep this version, even after new results arrive. Filter selections aren’t included.</span>
      </button>
      <button type="button" className="block w-full rounded-[4px] px-3 py-3 text-left hover:bg-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring" onClick={() => void copy(false)}>
        <span className="flex items-baseline justify-between gap-3 type-control">Always latest results<span className="type-status text-foreground-secondary">Default</span></span>
        <span className="mt-1 block type-meta leading-relaxed text-foreground-secondary">Open the latest available results each time.</span>
      </button>
    </div>}
    <span role="status" className={failed ? "max-w-[280px] type-meta text-foreground-secondary" : "sr-only"}>{failed ? "Couldn’t copy the link. Please try again." : copied ? "Link copied" : ""}</span>
  </div>;
}
