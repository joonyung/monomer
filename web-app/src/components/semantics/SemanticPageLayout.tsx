import type { ReactNode } from "react";
import { AlertCircle, ArrowLeft } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Link } from "@/lib/navigation";

/**
 * Model and metric pages read and edit wide column tables beside a rail, so they use the workbench width instead of
 * the 1080px document width. Reading and editing share it so entering edit keeps every column still.
 */
export const SEMANTIC_PAGE_WIDTH = "mx-auto w-full max-w-[1440px] px-6 md:px-8";
export const SEMANTIC_PAGE_COLUMNS = "grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_340px]";

export function SemanticEditorFrame({ backHref, backLabel, title, saving, saveError, onSave, onCancel, aside, children }: {
  backHref: string;
  backLabel: string;
  title: string;
  saving: boolean;
  saveError: string | null;
  onSave: () => void;
  onCancel?: () => void;
  aside: ReactNode;
  children: ReactNode;
}) {
  return <div className="h-full overflow-y-auto bg-background">
    <div className="sticky top-0 z-10 border-b border-border bg-background/95 backdrop-blur">
      <div className={`${SEMANTIC_PAGE_WIDTH} flex items-center justify-between gap-4 py-3`}>
        <div className="min-w-0">
          <Link href={backHref} className="type-control inline-flex items-center gap-1.5 text-foreground-secondary transition-colors hover:text-foreground focus:outline-none focus-visible:underline">
            <ArrowLeft className="h-3.5 w-3.5" aria-hidden="true" />
            {backLabel}
          </Link>
          <h1 className="type-display mt-1 truncate text-heading">{title}</h1>
        </div>
        <div className="flex flex-none items-center gap-2">
          {onCancel && <Button variant="ghost" size="sm" disabled={saving} onClick={onCancel}>Cancel</Button>}
          <Button variant="primary" size="sm" pending={saving} onClick={onSave}>Save</Button>
        </div>
      </div>
      {saveError && <div className="border-t border-error/30 bg-error/5" role="alert">
        <div className={`${SEMANTIC_PAGE_WIDTH} flex items-start gap-3 py-2.5`}>
          <AlertCircle className="mt-0.5 h-4 w-4 flex-none text-error" aria-hidden="true" />
          <p className="type-body text-error">{saveError}</p>
        </div>
      </div>}
    </div>
    <div className={`${SEMANTIC_PAGE_WIDTH} ${SEMANTIC_PAGE_COLUMNS} py-6`}>
      <div className="min-w-0 space-y-8">{children}</div>
      <aside className="min-w-0 lg:sticky lg:top-24 lg:max-h-[calc(100vh-7rem)] lg:overflow-y-auto">{aside}</aside>
    </div>
  </div>;
}
