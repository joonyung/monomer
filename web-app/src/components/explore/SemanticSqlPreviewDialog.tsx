import type { ReactNode } from "react";
import { Copy } from "lucide-react";
import { ExploreFailureNotice, type ExploreFailureNoticeContent } from "@/components/explore/ExploreFailureNotice";
import { SqlEditor } from "@/components/explore/SqlEditor";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";
import { StateBlock } from "@/components/ui/StateBlock";
import { useClipboardCopy } from "@/hooks/useClipboardCopy";
import type { SemanticSqlDialect } from "@/lib/types/explore";

interface SemanticSqlPreviewDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  trigger: ReactNode;
  loading: boolean;
  /** Why the SQL couldn't be rendered, worded like a rejected run. */
  failure: ExploreFailureNoticeContent | null;
  sql: string;
  connectorName: string;
  dialect: SemanticSqlDialect | null;
  onRetry: () => void;
}

const DIALECT_LABELS: Record<SemanticSqlDialect, string> = {
  POSTGRESQL: "PostgreSQL",
  BIGQUERY: "GoogleSQL",
  SNOWFLAKE: "Snowflake SQL",
  DATABRICKS: "Databricks SQL",
  REDSHIFT: "Redshift SQL",
  TRINO: "Trino SQL",
};

export function SemanticSqlPreviewDialog({
  open,
  onOpenChange,
  trigger,
  loading,
  failure,
  sql,
  connectorName,
  dialect,
  onRetry,
}: SemanticSqlPreviewDialogProps) {
  const sqlCopy = useClipboardCopy();
  return (
    <Dialog closeLabel="Close rendered SQL"
      open={open}
      onOpenChange={onOpenChange}
      trigger={trigger}
      size="xl"
      testId="rendered-sql-dialog"
      title="Rendered SQL"
      description={`${connectorName}${dialect ? ` · ${DIALECT_LABELS[dialect]}` : ""}`}
      headerActions={
        <span className="inline-flex items-center">
          <span role="status" className={`type-status text-foreground-secondary${sqlCopy.status === "copied" ? " mr-1" : ""}`}>{sqlCopy.status === "copied" ? "SQL copied" : ""}</span>
          <IconButton
            label="Copy rendered SQL"
            icon={Copy}
            disabled={loading || Boolean(failure) || !sql}
            onClick={() => { void sqlCopy.copy(sql); }}
          />
        </span>
      }
      bodyClassName="flex flex-col p-0"
    >
      <div className="flex min-h-0 flex-1 flex-col md:h-[min(670px,calc(100dvh-120px))] md:flex-none">
        {sqlCopy.status === "failed" ? (
          <p role="alert" className="type-meta shrink-0 border-b border-border px-5 py-2 text-error sm:px-6">Couldn’t copy the SQL. Please try again.</p>
        ) : null}
        <div className="relative min-h-0 flex-1">
          {loading ? (
            <div className="absolute inset-0 z-10 flex bg-background">
              <StateBlock kind="loading" layout="page" title="Rendering SQL" />
            </div>
          ) : null}
          {!loading && failure ? (
            <div className="absolute inset-0 flex flex-col items-start gap-4 overflow-y-auto px-5 py-6 sm:px-6">
              <ExploreFailureNotice failure={failure} />
              <Button type="button" variant="secondary" onClick={onRetry}>Retry</Button>
            </div>
          ) : null}
          {!loading && !failure && sql ? (
            <SqlEditor
              value={sql}
              onChange={() => undefined}
              onRun={() => undefined}
              runDisabled
              readOnly
              ariaLabel="Rendered SQL"
            />
          ) : null}
        </div>
      </div>
    </Dialog>
  );
}
