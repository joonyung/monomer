import type { ReactNode } from "react";
import { Card } from "@/components/ui/Card";

export function EditorSection({
  title,
  description,
  action,
  framed = true,
  children,
}: {
  title: string;
  // Only for what the controls cannot say themselves, such as a consequence of changing them.
  description: string | null;
  action: ReactNode;
  // An empty optional section reads as one line instead of an empty card.
  framed?: boolean;
  children: ReactNode;
}) {
  return (
    <section>
      <div className="mb-1 flex items-start justify-between gap-4">
        <h2 className="type-item text-heading">{title}</h2>
        {action}
      </div>
      {description ? <p className="type-meta mb-3 max-w-3xl text-foreground-secondary">{description}</p> : <div className="mb-2" />}
      {framed ? <Card padding="lg">{children}</Card> : children}
    </section>
  );
}
