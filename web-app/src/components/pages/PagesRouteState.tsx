import { RefreshCw } from "lucide-react";
import { Link } from "@/lib/navigation";
import { Button, buttonClassName } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";

export function PagesLoadingState({ label = "Loading Pages…" }: { label?: string }) {
  return (
    <div className="flex h-full flex-col">
      <StateBlock kind="loading" layout="page" title={label} />
    </div>
  );
}

export function PagesErrorState({
  title,
  description,
  onRetry,
  backHref,
}: {
  title: string;
  description: string;
  onRetry?: () => void;
  backHref?: string;
}) {
  return (
    <div className="flex h-full flex-col">
      <StateBlock
        kind="error"
        layout="page"
        title={<span role="heading" aria-level={1}>{title}</span>}
        description={description}
        action={(<>
          {backHref ? <Link href={backHref} className={buttonClassName(onRetry ? "ghost" : "secondary", "sm", "")}>Back to Pages</Link> : null}
          {onRetry && (
          <Button variant="secondary" size="sm" onClick={onRetry}>
            <RefreshCw aria-hidden="true" />
            Retry
          </Button>
        )}</>)}
      />
    </div>
  );
}
