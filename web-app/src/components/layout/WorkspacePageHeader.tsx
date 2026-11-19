import type { ReactNode } from "react";

interface WorkspacePageHeaderProps {
  section?: "Workspace" | "Sources" | "Model" | "Analyze" | "Pages" | "Draft review";
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
  wrapTitle?: boolean;
}

export function WorkspacePageHeader({
  section,
  title,
  description,
  actions,
  className = "",
  wrapTitle = false,
}: WorkspacePageHeaderProps) {
  return (
    <header
      className={`relative flex flex-shrink-0 flex-wrap items-end justify-between gap-4 border-b border-border bg-background px-5 py-5 sm:px-8 ${description ? "min-h-[116px]" : section ? "min-h-[100px]" : "min-h-[88px]"} ${className}`}
      data-testid="workspace-page-header"
    >
      <div className="relative min-w-0 flex-1">
        {section ? (
          <p className="type-group text-foreground-secondary">
            {section}
          </p>
        ) : null}
        <h1 className={`${section ? "mt-1" : ""} ${wrapTitle ? "break-words sm:truncate" : "truncate"} type-display text-heading`} title={title}>
          {title}
        </h1>
        {description ? (
          <div className="mt-1.5 max-w-3xl type-body text-foreground-secondary">
            {description}
          </div>
        ) : null}
      </div>
      {actions ? (
        <div className="flex w-full flex-none flex-wrap items-center gap-2 sm:w-auto sm:justify-end">
          {actions}
        </div>
      ) : null}
    </header>
  );
}
