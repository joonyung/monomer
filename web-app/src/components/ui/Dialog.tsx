import { useRef, type ReactNode } from "react";
import * as RadixDialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { IconButton } from "@/components/ui/IconButton";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { activeElementBeforeDialogOpens, dialogFocusReturnTarget } from "@/components/ui/dialogFocus";

export type DialogSize = "sm" | "md" | "lg" | "xl";

// One overlay for every modal surface, including drawers that keep their own content frame.
// The single modal backdrop tone; surfaces that position their own backdrop (the command palette) reuse it.
export const DIALOG_OVERLAY_TONE = "bg-foreground/20 backdrop-blur-[2px]";
export const DIALOG_OVERLAY_CLASS = `fixed inset-0 z-50 ${DIALOG_OVERLAY_TONE}`;

const DIALOG_WIDTH: Record<DialogSize, string> = {
  sm: "md:w-[min(480px,calc(100vw-24px))]",
  md: "md:w-[min(640px,calc(100vw-24px))]",
  lg: "md:w-[min(840px,calc(100vw-24px))]",
  xl: "md:w-[min(1040px,calc(100vw-24px))]",
};

interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  trigger?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  // Keeps the accessible description while hiding a sentence the visible layout already conveys.
  descriptionHidden?: boolean;
  size: DialogSize;
  // A confirmation that interrupts work announces as an alertdialog.
  role?: "dialog" | "alertdialog";
  // While busy the result is unknown, so close, Escape, and outside dismissal stay disabled.
  busy?: boolean;
  headerActions?: ReactNode;
  footer?: ReactNode;
  bodyClassName?: string;
  // Names the close control after the surface, e.g. "Close run detail".
  closeLabel?: string;
  testId?: string;
  overlayTestId?: string;
  children?: ReactNode;
}

export function Dialog({
  open,
  onOpenChange,
  trigger,
  title,
  description,
  descriptionHidden = false,
  size,
  role = "dialog",
  busy = false,
  headerActions,
  footer,
  bodyClassName = "px-6 py-5",
  closeLabel = "Close dialog",
  testId,
  overlayTestId,
  children,
}: DialogProps) {
  const scrollbar = useTransientScrollbar();
  // Radix restores focus only to its own Trigger; controlled dialogs return focus to the opener.
  // The opener is read while rendering the opening transition, before an autoFocus child steals focus.
  const openerRef = useRef<HTMLElement | null>(null);
  const wasOpenRef = useRef(false);
  if (open && !wasOpenRef.current && !trigger) {
    openerRef.current = activeElementBeforeDialogOpens(typeof document === "undefined" ? undefined : document);
  }
  wasOpenRef.current = open;
  // Radix links Dialog.Description itself; a dialog without one opts out explicitly.
  const descriptionProps = description ? {} : { "aria-describedby": undefined };
  return (
    <RadixDialog.Root open={open} onOpenChange={(next) => { if (next || !busy) onOpenChange(next); }}>
      {trigger ? <RadixDialog.Trigger asChild>{trigger}</RadixDialog.Trigger> : null}
      <RadixDialog.Portal>
        <RadixDialog.Overlay data-testid={overlayTestId} className={DIALOG_OVERLAY_CLASS} />
        <RadixDialog.Content
          {...descriptionProps}
          data-testid={testId}
          role={role}
          onCloseAutoFocus={(event) => {
            if (trigger) return;
            const target = dialogFocusReturnTarget(openerRef.current, document);
            openerRef.current = null;
            if (!target) return;
            event.preventDefault();
            target.focus();
          }}
          onEscapeKeyDown={(event) => { if (busy) event.preventDefault(); }}
          onPointerDownOutside={(event) => { if (busy) event.preventDefault(); }}
          onInteractOutside={(event) => { if (busy) event.preventDefault(); }}
          className={`fixed inset-0 z-50 flex flex-col bg-background outline-none md:inset-auto md:left-1/2 md:top-1/2 md:max-h-[min(840px,calc(100dvh-24px))] md:-translate-x-1/2 md:-translate-y-1/2 md:rounded-[8px] md:border md:border-border md:shadow-modal ${DIALOG_WIDTH[size]}`}
        >
          <header className="flex shrink-0 items-start gap-4 border-b border-border px-6 py-5">
            <div className="min-w-0 flex-1 break-words">
              <RadixDialog.Title className="type-section text-heading">{title}</RadixDialog.Title>
              {description ? (
                <RadixDialog.Description className={descriptionHidden ? "sr-only" : "type-body mt-1 text-foreground-secondary"}>{description}</RadixDialog.Description>
              ) : null}
            </div>
            {headerActions}
            <RadixDialog.Close asChild>
              <IconButton label={closeLabel} icon={X} disabled={busy} />
            </RadixDialog.Close>
          </header>
          {children ? (
            <div className={`min-h-0 flex-1 overflow-y-auto ${scrollbar.className} ${bodyClassName}`} onScroll={scrollbar.onScroll}>
              {children}
            </div>
          ) : null}
          {footer ? (
            <footer className="flex shrink-0 flex-wrap items-center justify-end gap-2 border-t border-border px-6 py-4">{footer}</footer>
          ) : null}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
