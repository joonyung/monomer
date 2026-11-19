import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";

export function CatalogConnectorListError({
  error,
  retrying,
  onRetry,
}: {
  error: string;
  retrying: boolean;
  onRetry: () => void;
}) {
  return (
    <StateBlock
      kind="error"
      layout="page"
      title={<span role="heading" aria-level={2}>Connectors could not be loaded</span>}
      description={error}
      action={
        <Button
          type="button"
          variant="secondary"
          size="sm"
          pending={retrying}
          onClick={onRetry}
        >
          {!retrying && <RefreshCw aria-hidden="true" />}
          Retry connector list
        </Button>
      }
    />
  );
}
