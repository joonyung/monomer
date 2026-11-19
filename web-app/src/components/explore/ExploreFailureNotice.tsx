import { forwardRef } from "react";
import { ArrowRight, CircleAlert } from "lucide-react";
import type { FailureAction } from "@/pages/explore/semantic-problems";
import { buttonClassName } from "@/components/ui/Button";
import { Link } from "@/lib/navigation";

export interface ExploreFailureNoticeContent {
  title: string;
  explanation: string | null;
  actions: FailureAction[];
  reference: string | null;
}

// tabIndex -1 lets a caller move focus to a rejection that renders away from the action that caused it.
export const ExploreFailureNotice = forwardRef<HTMLDivElement, { failure: ExploreFailureNoticeContent; className?: string }>(
  function ExploreFailureNotice({ failure, className = "" }, ref) {
    return (
      <div ref={ref} tabIndex={-1} role="alert" className={`flex gap-3 outline-none ${className}`}>
        <CircleAlert className="mt-0.5 h-4 w-4 shrink-0 text-error" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <p className="type-control break-words text-foreground">{failure.title}</p>
          {failure.explanation ? <p className="type-meta mt-1 break-words text-foreground-secondary">{failure.explanation}</p> : null}
          {failure.actions.length > 0 ? (
            <div className="-ml-3 mt-2 flex flex-wrap gap-1">
              {failure.actions.map((action) => (
                action.href ? <Link key={action.href + action.label} href={action.href} className={buttonClassName("ghost", "sm", "")}>{action.label}<ArrowRight aria-hidden="true" /></Link> : <button key={action.label} type="button" onClick={action.onClick} className={buttonClassName("ghost", "sm", "")}>{action.label}<ArrowRight aria-hidden="true" /></button>
              ))}
            </div>
          ) : null}
          {failure.reference ? <p className="type-mono mt-3 break-all text-foreground-secondary">{failure.reference}</p> : null}
        </div>
      </div>
    );
  },
);
