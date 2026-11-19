import type { ReactNode } from "react";
import { CircleAlert, Inbox, LoaderCircle, type LucideIcon } from "lucide-react";

export type StateBlockKind = "empty" | "loading" | "error";

interface StateBlockProps {
  kind: StateBlockKind;
  title: ReactNode;
  description?: ReactNode;
  // Empty states name what is missing with their own icon; loading and error icons are fixed.
  icon?: LucideIcon;
  action?: ReactNode;
  // A page state fills its region; an inline state sits inside a panel or list.
  layout: "page" | "inline";
  // Set when the state replaces a page or section heading, so heading navigation still finds it.
  headingLevel?: 1 | 2 | 3;
}

export function StateBlock({ kind, title, description, icon, action, layout, headingLevel }: StateBlockProps) {
  const Icon = kind === "loading" ? LoaderCircle : kind === "error" ? CircleAlert : icon ?? Inbox;
  const iconClass = kind === "loading"
    ? "animate-spin text-foreground-secondary motion-reduce:animate-none"
    : kind === "error" ? "text-error" : "text-foreground-secondary";
  const frame = layout === "page"
    ? "flex min-h-[240px] flex-1 flex-col items-center justify-center px-6 py-12 text-center"
    : "flex flex-col items-center justify-center px-5 py-8 text-center";
  return (
    <div className={frame} role={kind === "error" ? "alert" : kind === "loading" ? "status" : undefined}>
      <Icon className={`${layout === "page" ? "h-6 w-6" : "h-5 w-5"} ${iconClass}`} aria-hidden="true" />
      <p className={`${layout === "page" ? "type-item mt-3" : "type-control mt-2.5"} text-heading`}>
        {headingLevel ? <span role="heading" aria-level={headingLevel}>{title}</span> : title}
      </p>
      {description ? <p className="type-meta mt-1.5 max-w-sm text-foreground-secondary">{description}</p> : null}
      {action ? <div className="mt-4 flex flex-wrap items-center justify-center gap-2">{action}</div> : null}
    </div>
  );
}
