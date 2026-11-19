import { useRef, type ReactNode } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { IconButton } from "@/components/ui/IconButton";
import { DIALOG_OVERLAY_CLASS } from "@/components/ui/Dialog";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { activeElementBeforeDialogOpens, dialogFocusReturnTarget } from "@/components/ui/dialogFocus";

interface SheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  // Identity beside the title, such as an avatar or workspace tile.
  leading?: ReactNode;
  // A confirmation that interrupts work announces as an alertdialog.
  role?: "dialog" | "alertdialog";
  // While busy the result is unknown, so close, Escape, and outside dismissal stay disabled.
  busy?: boolean;
  footer?: ReactNode;
  closeLabel: string;
  testId?: string;
  children?: ReactNode;
}

// A right-side detail sheet: 480px on desktop and full screen below 768px. Radix owns the focus
// trap and Escape; focus returns to the row that opened it, as with the Dialog primitive.
export function Sheet({
  open,
  onOpenChange,
  title,
  description,
  leading,
  role = "dialog",
  busy = false,
  footer,
  closeLabel,
  testId,
  children,
}: SheetProps) {
  const scrollbar = useTransientScrollbar();
  const openerRef = useRef<HTMLElement | null>(null);
  const titleRef = useRef<HTMLHeadingElement | null>(null);
  const wasOpenRef = useRef(false);
  if (open && !wasOpenRef.current) {
    openerRef.current = activeElementBeforeDialogOpens(typeof document === "undefined" ? undefined : document);
  }
  wasOpenRef.current = open;
  const descriptionProps = description ? {} : { "aria-describedby": undefined };
  return (
    <Dialog.Root open={open} onOpenChange={(next) => { if (next || !busy) onOpenChange(next); }}>
      <Dialog.Portal>
        <Dialog.Overlay className={DIALOG_OVERLAY_CLASS} />
        <Dialog.Content
          {...descriptionProps}
          data-testid={testId}
          role={role}
          // The sheet opens on its title so screen readers start at the identity and no control looks pre-selected.
          onOpenAutoFocus={(event) => {
            if (!titleRef.current) return;
            event.preventDefault();
            titleRef.current.focus();
          }}
          onCloseAutoFocus={(event) => {
            const target = dialogFocusReturnTarget(openerRef.current, document);
            openerRef.current = null;
            if (!target) return;
            event.preventDefault();
            target.focus();
          }}
          onEscapeKeyDown={(event) => { if (busy) event.preventDefault(); }}
          onPointerDownOutside={(event) => { if (busy) event.preventDefault(); }}
          onInteractOutside={(event) => { if (busy) event.preventDefault(); }}
          className="fixed inset-0 z-50 flex flex-col bg-background outline-none md:inset-y-0 md:left-auto md:right-0 md:w-[min(480px,100vw)] md:border-l md:border-border md:shadow-modal"
          style={{ fontFeatureSettings: '"ss01"' }}
        >
          <header className="flex shrink-0 items-start gap-4 border-b border-border px-6 py-5">
            {leading ? <div className="shrink-0">{leading}</div> : null}
            <div className="min-w-0 flex-1 break-words">
              <Dialog.Title ref={titleRef} tabIndex={-1} className="type-section text-heading outline-none focus-visible:!outline-none">{title}</Dialog.Title>
              {description ? (
                <Dialog.Description className="mt-1 type-meta text-foreground-secondary">{description}</Dialog.Description>
              ) : null}
            </div>
            <Dialog.Close asChild>
              <IconButton label={closeLabel} icon={X} disabled={busy} />
            </Dialog.Close>
          </header>
          <div className={`min-h-0 flex-1 overflow-y-auto px-6 py-6 ${scrollbar.className}`} onScroll={scrollbar.onScroll}>
            {children}
          </div>
          {footer ? (
            <footer className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-t border-border px-6 py-4">{footer}</footer>
          ) : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
