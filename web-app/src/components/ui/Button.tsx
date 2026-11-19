import React from "react";
import { LoaderCircle } from "lucide-react";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "md" | "sm";

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  pending?: boolean;
  children: React.ReactNode;
}

const BUTTON_BASE_STYLES =
  "inline-flex shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-[4px] border text-[13px] font-normal leading-none transition-colors duration-150 disabled:cursor-not-allowed [&_svg]:size-4 [&_svg]:shrink-0";

const BUTTON_VARIANT_STYLES: Record<ButtonVariant, string> = {
  primary: "border-transparent bg-accent text-accent-foreground hover:bg-accent-hover",
  secondary: "border-border bg-transparent text-foreground hover:bg-surface",
  ghost: "border-transparent bg-transparent text-foreground hover:bg-surface",
  danger: "border-transparent bg-error text-accent-foreground hover:bg-error-hover",
};

const BUTTON_DISABLED_STYLES: Record<ButtonVariant, string> = {
  primary: "disabled:bg-surface-hover disabled:text-foreground-secondary",
  secondary: "disabled:border-transparent disabled:bg-surface-hover disabled:text-foreground-secondary",
  ghost: "disabled:bg-transparent disabled:text-foreground-secondary",
  danger: "disabled:bg-surface-hover disabled:text-foreground-secondary",
};

const BUTTON_SIZE_STYLES: Record<ButtonSize, string> = {
  md: "h-9 px-4",
  sm: "h-8 px-3",
};

// Lets a link carry button styling without nesting a <button> inside an <a>,
// which is invalid interactive markup and produces two tab stops per action.
export function buttonClassName(variant: ButtonVariant, size: ButtonSize, className: string): string {
  return `${BUTTON_BASE_STYLES} ${BUTTON_VARIANT_STYLES[variant]} ${BUTTON_DISABLED_STYLES[variant]} ${BUTTON_SIZE_STYLES[size]} ${className}`;
}

// A pending action keeps its variant color so the committed choice stays visible while it runs.
function pendingButtonClassName(variant: ButtonVariant, size: ButtonSize, className: string): string {
  return `${BUTTON_BASE_STYLES} ${BUTTON_VARIANT_STYLES[variant]} ${BUTTON_SIZE_STYLES[size]} cursor-progress disabled:cursor-progress ${className}`;
}

export function Button({
  variant = "primary",
  size = "md",
  pending = false,
  children,
  className = "",
  disabled,
  ...props
}: ButtonProps) {
  return (
    <button
      className={pending ? pendingButtonClassName(variant, size, className) : buttonClassName(variant, size, className)}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      {...props}
    >
      {pending && <LoaderCircle className="animate-spin motion-reduce:animate-none" aria-hidden="true" />}
      {children}
    </button>
  );
}
