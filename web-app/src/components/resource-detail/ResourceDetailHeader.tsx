import type { ReactNode } from "react";
import { ArrowLeft } from "lucide-react";
import { Badge } from "@/components/ui/Badge";
import { Link } from "@/lib/navigation";
import { DefinitionRows } from "./DetailPanel";

export interface ResourceDetailFact {
  label: string;
  value: ReactNode;
}

interface ResourceDetailHeaderProps {
  backHref: string;
  backLabel: string;
  title: string;
  technicalName: string | null;
  classification: string | null;
  description: string | null;
  supportingDescription?: string | null;
  identityFacts?: ResourceDetailFact[];
  facts: ResourceDetailFact[];
  actions: ReactNode;
}

export function ResourceDetailHeader({
  backHref,
  backLabel,
  title,
  technicalName,
  classification,
  description,
  supportingDescription,
  identityFacts = [],
  facts,
  actions,
}: ResourceDetailHeaderProps) {
  return (
    <header className="border-b border-border pb-6">
      <Link
        href={backHref}
        className="type-control mb-4 inline-flex items-center gap-1.5 text-foreground-secondary transition-colors hover:text-foreground focus:outline-none focus-visible:underline"
      >
        <ArrowLeft className="h-3.5 w-3.5" aria-hidden="true" />
        {backLabel}
      </Link>

      <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
            <h1 className="type-display text-heading">{title}</h1>
            {classification && <Badge variant="info">{classification}</Badge>}
          </div>
          {technicalName && (
            <div className="type-mono mt-1.5 text-foreground-secondary">{technicalName}</div>
          )}
          {description && (
            <p className="type-body mt-3 max-w-3xl text-foreground">{description}</p>
          )}
          {supportingDescription && <p className="type-body mt-2 max-w-3xl text-foreground-secondary">{supportingDescription}</p>}
        </div>
        <div className="flex flex-none flex-wrap items-center gap-2">{actions}</div>
      </div>

      {identityFacts.length > 0 && <div className="mt-5"><DefinitionRows rows={identityFacts.map(({ label, value }) => ({ term: label, value }))} /></div>}

      {facts.length > 0 && (
        <dl className="type-meta mt-5 grid grid-cols-2 gap-x-4 gap-y-3 sm:flex sm:flex-wrap sm:items-baseline sm:gap-x-0">
          {facts.map((fact, index) => (
            <div key={fact.label} className={`min-w-0 space-y-1 sm:flex sm:items-baseline sm:gap-2 sm:space-y-0 ${index > 0 ? "sm:ml-4 sm:border-l sm:border-border sm:pl-4" : ""}`}>
              <dt className="text-foreground-secondary">{fact.label}</dt>
              <dd className="type-body text-foreground">{fact.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </header>
  );
}
