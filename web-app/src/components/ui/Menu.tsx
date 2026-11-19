import type { ComponentPropsWithoutRef, ReactNode, Ref } from "react";
import * as RadixMenu from "@radix-ui/react-dropdown-menu";
import { Check, type LucideIcon } from "lucide-react";

// Radix owns roles, roving arrow-key focus, typeahead, Escape, outside dismissal, and focus return
// to the trigger. Menus render on the Overlay level with the 250ms entrance.
export const Menu = RadixMenu.Root;
export const MenuTrigger = RadixMenu.Trigger;

const MENU_CONTENT_STYLES =
  "z-50 overflow-hidden rounded-[6px] border border-border bg-background py-1 shadow-overlay animate-fade-in-up outline-none focus-visible:!outline-none";

// Keyboard focus shows the compact-list signal: the neutral surface plus a 2px Focus Sandstone leading marker.
const MENU_ITEM_STYLES =
  "relative flex w-full cursor-default select-none items-center gap-2 px-3 py-1.5 text-left type-control text-foreground outline-none transition-colors duration-150 data-[highlighted]:bg-surface-hover data-[disabled]:text-foreground-secondary focus-visible:!outline-none focus-visible:before:absolute focus-visible:before:inset-y-1 focus-visible:before:left-0 focus-visible:before:w-[2px] focus-visible:before:bg-[var(--focus-ring)] [&>svg]:h-3.5 [&>svg]:w-3.5 [&>svg]:shrink-0 [&>svg]:text-foreground-secondary";

type MenuContentProps = Omit<ComponentPropsWithoutRef<typeof RadixMenu.Content>, "className"> & {
  className: string;
  children: ReactNode;
  // A menu opened inside a modal dialog renders into that dialog, whose scroll lock admits wheel
  // and touch scrolling only within its own subtree.
  portalContainer?: HTMLElement | null;
};

export function MenuContent({ className, children, sideOffset = 4, collisionPadding = 8, portalContainer, ...props }: MenuContentProps) {
  return (
    <RadixMenu.Portal container={portalContainer}>
      <RadixMenu.Content
        sideOffset={sideOffset}
        collisionPadding={collisionPadding}
        className={`${MENU_CONTENT_STYLES} ${className}`}
        style={{ fontFeatureSettings: '"ss01"' }}
        {...props}
      >
        {children}
      </RadixMenu.Content>
    </RadixMenu.Portal>
  );
}

type MenuItemProps = Omit<ComponentPropsWithoutRef<typeof RadixMenu.Item>, "className"> & {
  icon?: LucideIcon;
  className?: string;
  ref?: Ref<HTMLDivElement>;
};

export function MenuItem({ icon: Icon, className = "", children, asChild, ...props }: MenuItemProps) {
  return (
    <RadixMenu.Item className={`${MENU_ITEM_STYLES} ${className}`} asChild={asChild} {...props}>
      {asChild ? children : <>{Icon ? <Icon aria-hidden="true" /> : null}{children}</>}
    </RadixMenu.Item>
  );
}

export const MenuRadioGroup = RadixMenu.RadioGroup;

type MenuRadioItemProps = Omit<ComponentPropsWithoutRef<typeof RadixMenu.RadioItem>, "className"> & {
  icon?: LucideIcon;
  // Secondary text after the label, such as the person's role in the choice.
  meta?: ReactNode;
};

// The current choice keeps a neutral surface and an ink check; sandstone stays on navigation markers.
export function MenuRadioItem({ icon: Icon, meta, children, ...props }: MenuRadioItemProps) {
  return (
    <RadixMenu.RadioItem className={`${MENU_ITEM_STYLES} data-[state=checked]:bg-surface/60`} {...props}>
      {Icon ? <Icon aria-hidden="true" /> : null}
      <span className="min-w-0 flex-1 truncate">{children}</span>
      {meta ? <span className="shrink-0 type-status text-foreground-secondary">{meta}</span> : null}
      <span className="flex w-3.5 shrink-0">
        <RadixMenu.ItemIndicator className="flex">
          <Check className="h-3.5 w-3.5 text-foreground" aria-hidden="true" />
        </RadixMenu.ItemIndicator>
      </span>
    </RadixMenu.RadioItem>
  );
}

export const MenuGroup = RadixMenu.Group;

export function MenuLabel({ className = "", ...props }: Omit<ComponentPropsWithoutRef<typeof RadixMenu.Label>, "className"> & { className?: string }) {
  return <RadixMenu.Label className={`px-3 py-1.5 ${className}`} {...props} />;
}

export function MenuSeparator() {
  return <RadixMenu.Separator className="my-1 h-px shrink-0 bg-border" />;
}
