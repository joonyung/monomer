import { fieldClassName } from "@/components/ui/Input";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { createPortal } from "react-dom";
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { Check, ChevronDown } from "lucide-react";

export interface SelectProps {
  id?: string;
  label: string;
  value: string;
  options: { value: string; label: string }[];
  currentLabel?: string;
  disabled: boolean;
  invalid: boolean;
  onChange: (value: string) => void;
  onCommit?: () => void;
  className?: string;
}

export function Select({ id, label, value, options, currentLabel, disabled, invalid, onChange, onCommit, className = "" }: SelectProps) {
  const listId = useId();
  const scrollbar = useTransientScrollbar();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const searchRef = useRef({ text: "", time: 0 });
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [position, setPosition] = useState({ left: 0, top: 0 as number | string, bottom: "auto" as number | string, width: 0, maxHeight: 240 });
  const selectedIndex = options.findIndex((option) => option.value === value);
  const visible = open && !disabled;

  useLayoutEffect(() => {
    if (!visible) return;
    menuRef.current?.showPopover();
    const reposition = () => {
      const bounds = triggerRef.current?.getBoundingClientRect();
      if (!bounds) return;
      const below = window.innerHeight - bounds.bottom - 12;
      const above = bounds.top - 12;
      const upward = below < 160 && above > below;
      const maxHeight = Math.max(0, Math.min(240, upward ? above : below));
      const width = Math.min(Math.max(bounds.width, 240), window.innerWidth - 16);
      setPosition({ left: Math.max(8, Math.min(bounds.left, window.innerWidth - width - 8)), top: upward ? "auto" : bounds.bottom + 4, bottom: upward ? window.innerHeight - bounds.top + 4 : "auto", width, maxHeight });
    };
    const dismiss = (event: PointerEvent) => {
      if (!triggerRef.current?.contains(event.target as Node) && !menuRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const dismissWithEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
      triggerRef.current?.focus();
    };
    reposition();
    window.addEventListener("keydown", dismissWithEscape, true);
    window.addEventListener("resize", reposition);
    window.addEventListener("scroll", reposition, true);
    document.addEventListener("pointerdown", dismiss);
    return () => {
      window.removeEventListener("keydown", dismissWithEscape, true);
      window.removeEventListener("resize", reposition);
      window.removeEventListener("scroll", reposition, true);
      document.removeEventListener("pointerdown", dismiss);
    };
  }, [visible]);

  useEffect(() => {
    if (visible) menuRef.current?.children[activeIndex]?.scrollIntoView({ block: "nearest" });
  }, [activeIndex, visible]);

  const choose = (index: number) => {
    const option = options[index];
    if (!option || disabled) return;
    onChange(option.value);
    setOpen(false);
    triggerRef.current?.focus();
  };

  return <>
    <button
      ref={triggerRef}
      id={id}
      type="button"
      role="combobox"
      aria-label={label}
      aria-haspopup="listbox"
      aria-expanded={visible}
      aria-controls={visible ? listId : undefined}
      aria-activedescendant={visible ? `${listId}-${activeIndex}` : undefined}
      aria-invalid={invalid}
      disabled={disabled}
      title={currentLabel ?? options[selectedIndex]?.label}
      className={`${fieldClassName(invalid)} flex h-8 cursor-pointer items-center gap-2 text-left ${className}`}
      onClick={() => { setActiveIndex(Math.max(0, selectedIndex)); setOpen(!open); }}
      onBlur={() => { setOpen(false); onCommit?.(); }}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          if (visible) { event.preventDefault(); event.stopPropagation(); setOpen(false); }
          return;
        }
        if (event.key === "Tab") { setOpen(false); return; }
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          if (visible) choose(activeIndex);
          else { setActiveIndex(Math.max(0, selectedIndex)); setOpen(true); }
          return;
        }
        if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
          event.preventDefault();
          const current = visible ? activeIndex : Math.max(0, selectedIndex);
          setActiveIndex(event.key === "Home" ? 0 : event.key === "End" ? options.length - 1 : Math.max(0, Math.min(options.length - 1, current + (event.key === "ArrowDown" ? 1 : -1))));
          setOpen(true);
          return;
        }
        if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
          event.preventDefault();
          const now = Date.now();
          const text = (now - searchRef.current.time < 700 ? searchRef.current.text : "") + event.key.toLocaleLowerCase();
          searchRef.current = { text, time: now };
          const match = options.findIndex((option) => option.label.toLocaleLowerCase().startsWith(text));
          if (match >= 0) setActiveIndex(match);
          setOpen(true);
        }
      }}
    >
      <span className="min-w-0 flex-1 truncate">{currentLabel ?? options[selectedIndex]?.label ?? "Select…"}</span>
      <ChevronDown className="h-4 w-4 shrink-0 text-foreground-secondary" aria-hidden="true" />
    </button>
    {visible ? createPortal(<div
      ref={menuRef}
      id={listId}
      role="listbox"
      aria-label={label}
      popover="manual"
      className={`fixed z-[100] overflow-y-auto overscroll-contain rounded-[6px] border border-border bg-background py-1 text-foreground shadow-overlay ${scrollbar.className}`}
      style={{ ...position, margin: 0, right: "auto" }}
      onScroll={scrollbar.onScroll}
      onPointerDown={(event) => event.preventDefault()}
    >
      {options.map((option, index) => <div
        key={option.value}
        id={`${listId}-${index}`}
        role="option"
        aria-selected={option.value === value}
        className={`flex min-h-8 cursor-pointer items-center gap-2 border-l-2 px-3 py-2 text-[13px] ${activeIndex === index ? "border-l-[var(--focus-ring)] bg-surface" : "border-l-transparent"}`}
        onPointerMove={() => setActiveIndex(index)}
        onClick={() => choose(index)}
      >
        <span className="min-w-0 flex-1 break-words">{option.label}</span>
        {option.value === value ? <Check className="h-4 w-4 shrink-0 text-focus-ring" aria-hidden="true" /> : null}
      </div>)}
    </div>, triggerRef.current?.closest("[role=dialog]") ?? document.body) : null}
  </>;
}
