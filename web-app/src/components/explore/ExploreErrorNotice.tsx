import { forwardRef } from "react";
import type { ExploreErrorPresentation } from "@/api/explore-errors";

// tabIndex -1 lets a caller move focus to a rejection that renders away from the action that caused it.
export const ExploreErrorNotice = forwardRef<HTMLDivElement, {
  error: ExploreErrorPresentation;
  className?: string;
}>(function ExploreErrorNotice({ error, className = "" }, ref) {
  return (
    <div ref={ref} tabIndex={-1} className={`border-b border-error/20 bg-error/5 px-5 py-3 outline-none sm:px-8 ${className}`} role="alert">
      <p className="type-control text-error">{error.detail}</p>
      {error.retryable ? <p className="type-status mt-1 text-foreground-secondary">You can try this request again.</p> : null}
      {error.code || error.traceId ? (
        <p className="type-mono mt-1 text-foreground-secondary">
          {[error.code, error.traceId ? `Reference ${error.traceId}` : null].filter(Boolean).join(" · ")}
        </p>
      ) : null}
    </div>
  );
});
