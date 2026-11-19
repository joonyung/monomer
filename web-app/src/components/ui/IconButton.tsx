import type { ButtonHTMLAttributes, ReactNode, Ref } from "react";
import { LoaderCircle, type LucideIcon } from "lucide-react";

export type IconButtonSize = "md" | "sm";

interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "aria-label" | "children"> {
  // The accessible name and the hover tooltip; an icon-only control has no other text.
  label: string;
  icon: LucideIcon;
  size?: IconButtonSize;
  pending?: boolean;
  // Decorations positioned over the icon, such as a status dot; never a visible label.
  children?: ReactNode;
  ref?: Ref<HTMLButtonElement>;
}

const ICON_BUTTON_BASE_STYLES =
  "inline-grid shrink-0 place-items-center rounded-[4px] bg-transparent text-foreground-secondary transition-colors duration-150 [&>svg]:size-4 [&>svg]:shrink-0";

const ICON_BUTTON_INTERACTIVE_STYLES =
  "enabled:hover:bg-surface enabled:hover:text-foreground disabled:cursor-not-allowed disabled:text-gray-400";

const ICON_BUTTON_SIZE_STYLES: Record<IconButtonSize, string> = {
  md: "size-9",
  sm: "size-8",
};

export function iconButtonClassName(size: IconButtonSize, className: string): string {
  return `${ICON_BUTTON_BASE_STYLES} ${ICON_BUTTON_INTERACTIVE_STYLES} ${ICON_BUTTON_SIZE_STYLES[size]} ${className}`;
}

// A pending action keeps its resting color so the committed choice stays visible while it runs.
function pendingIconButtonClassName(size: IconButtonSize, className: string): string {
  return `${ICON_BUTTON_BASE_STYLES} ${ICON_BUTTON_SIZE_STYLES[size]} cursor-progress ${className}`;
}

export function IconButton({
  label,
  icon: Icon,
  size = "sm",
  pending = false,
  children,
  className = "",
  disabled,
  title,
  type = "button",
  ...props
}: IconButtonProps) {
  return (
    <button
      type={type}
      aria-label={label}
      title={title ?? label}
      className={pending ? pendingIconButtonClassName(size, className) : iconButtonClassName(size, className)}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      {...props}
    >
      {pending ? <LoaderCircle className="animate-spin motion-reduce:animate-none" aria-hidden="true" /> : <Icon aria-hidden="true" />}
      {children}
    </button>
  );
}
