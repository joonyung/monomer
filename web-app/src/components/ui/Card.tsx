import React from "react";

interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  padding?: "none" | "sm" | "md" | "lg";
  hover?: boolean;
}

export function Card({
  children,
  className = "",
  padding = "md",
  hover = false,
  ...props
}: CardProps) {
  const paddingStyles = {
    none: "",
    sm: "p-3",
    md: "p-4",
    lg: "p-6",
  };

  const hoverStyles = hover
    ? "transition-shadow duration-200 hover:shadow-raised"
    : "";

  const clickableStyles = props.onClick ? "cursor-pointer" : "";

  return (
    <div
      className={`bg-background border border-border rounded-[6px] shadow-ambient ${paddingStyles[padding]} ${hoverStyles} ${clickableStyles} ${className}`}
      {...props}
    >
      {children}
    </div>
  );
}
