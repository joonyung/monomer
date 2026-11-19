import React from "react";

interface BadgeProps {
  children: React.ReactNode;
  variant?: "default" | "success" | "warning" | "error" | "info" | "secondary";
  size?: "sm" | "md";
  className?: string;
}

export function Badge({
  children,
  variant = "default",
  size = "md",
  className = "",
}: BadgeProps) {
  const variants = {
    default:
      "bg-background text-foreground border-[color:var(--border-light)]",
    secondary:
      "bg-surface text-foreground-secondary border-border",
    success:
      "bg-success-bg text-success-text border-success-border",
    warning:
      "bg-background text-warning border-warning",
    error:
      "bg-error-bg text-error border-error-border",
    info:
      "bg-surface text-foreground border-border",
  };

  const sizes = {
    sm: "px-1.5 py-[1px] text-[10px] font-normal",
    md: "px-1.5 py-[1px] text-[11px] font-normal",
  };

  return (
    <span
      className={`inline-flex items-center border rounded-[4px] ${variants[variant]} ${sizes[size]} ${className}`.trim()}
    >
      {children}
    </span>
  );
}
