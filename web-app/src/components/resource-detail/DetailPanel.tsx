import type { ReactNode } from "react";

/**
 * A bordered section of a resource detail page. The border and header divider carry the structure, so the
 * sections of a page read as separate blocks without shadows or tinted fills.
 */
export function DetailPanel({ id, title, description, action, titleRole = "section", children, bodyClassName = "p-4" }: {
  id: string;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  titleRole?: "section" | "item";
  children: ReactNode;
  bodyClassName?: string;
}) {
  return <section aria-labelledby={id} className="min-w-0 rounded-[6px] border border-border bg-background">
    <header className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 border-b border-border-light px-4 py-3">
      <div className="min-w-0">
        <h2 id={id} className={`${titleRole === "section" ? "type-section" : "type-item"} text-heading`}>{title}</h2>
        {description && <p className="type-meta mt-1 text-foreground-secondary">{description}</p>}
      </div>
      {action}
    </header>
    <div className={bodyClassName}>{children}</div>
  </section>;
}

/** Label and value rows separated by light dividers, for stored definition values. */
export function DefinitionRows({ rows }: { rows: Array<{ term: string; value: ReactNode }> }) {
  return <dl className="divide-y divide-border-light">{rows.map(({ term, value }, index) => <div key={`${index}:${term}`} className="grid grid-cols-[104px_minmax(0,1fr)] items-baseline gap-4 py-2.5 first:pt-0 last:pb-0">
    <dt className="type-meta text-foreground-secondary">{term}</dt>
    <dd className="type-body min-w-0 text-foreground">{value}</dd>
  </div>)}</dl>;
}

/** A stored expression shown exactly as written. */
export function StoredExpression({ expression }: { expression: string }) {
  return <pre className="type-mono m-0 w-fit max-w-full whitespace-pre-wrap rounded-[4px] border border-border bg-surface px-3 py-2 text-foreground [overflow-wrap:anywhere]">{expression}</pre>;
}
