import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { connectorsAPI } from "@/api/connectors";
import { semanticModelAPI, type SemanticModelResponse } from "@/api/semantic-model";
import { ConnectorTypeMark } from "@/components/connectors/ConnectorTypeMark";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Input } from "@/components/ui/Input";
import { ApiError } from "@/lib/api/client";
import { catalogHref } from "@/lib/catalog/catalog-links";
import { Link } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import type { Connector } from "@/lib/types/connector";
import { webPaths } from "@/lib/web-paths";

export const CONNECTOR_REFRESH_ACTIVE = "CONNECTOR_REFRESH_ACTIVE";

/** Semantic models whose source tables read this connector. */
export function semanticModelsUsingConnector(models: SemanticModelResponse[], connectorId: string): SemanticModelResponse[] {
  return models.filter((model) => model.sourceRelations.some((source) => source.connectorId === connectorId));
}

export function semanticModelImpact(count: number): string {
  return count === 1
    ? "Used by 1 semantic model — it won’t run until it uses another connector."
    : `Used by ${count} semantic models — they won’t run until they use another connector.`;
}

interface ConnectorDeleteDialogProps {
  open: boolean;
  workspaceId: string;
  principalId: string;
  connector: Connector | null;
  onCancel: () => void;
  onDeleted: (connector: Connector) => void;
}

export function ConnectorDeleteDialog({ open, workspaceId, principalId, connector, onCancel, onDeleted }: ConnectorDeleteDialogProps) {
  const [confirmation, setConfirmation] = useState("");
  const modelsQuery = useQuery({
    queryKey: queryKeys.semanticModels.all(workspaceId, principalId),
    queryFn: () => semanticModelAPI.list(workspaceId),
    enabled: open && Boolean(workspaceId && principalId),
  });
  const deletion = useMutation({
    mutationFn: async (target: Connector) => {
      await connectorsAPI.delete({ workspaceId, connectorId: target.id });
      return target;
    },
    onSuccess: (target) => onDeleted(target),
  });
  const resetDeletion = deletion.reset;

  useEffect(() => {
    if (!open) return;
    setConfirmation("");
    resetDeletion();
  }, [open, connector?.id, resetDeletion]);

  if (!connector) return null;
  const usingModels = modelsQuery.data ? semanticModelsUsingConnector(modelsQuery.data, connector.id) : [];
  const confirmed = confirmation === connector.name;
  const refreshActive = deletion.error instanceof ApiError && deletion.error.problem?.code === CONNECTOR_REFRESH_ACTIVE;
  const deleteError = deletion.error && !refreshActive
    ? deletion.error instanceof Error ? deletion.error.message : "The connector was not deleted."
    : null;

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen) onCancel();
      }}
      size="md"
      role="alertdialog"
      busy={deletion.isPending}
      closeLabel="Close delete connector"
      testId="connector-delete-dialog"
      title={
        <span className="flex min-w-0 items-center gap-2">
          <ConnectorTypeMark type={connector.type} size="sm" />
          <span className="min-w-0 break-words">Delete {connector.name}?</span>
        </span>
      }
      description="This can’t be undone."
      footer={
        <div className="ml-auto flex gap-2">
          <Button type="button" variant="secondary" disabled={deletion.isPending} onClick={onCancel}>Keep connector</Button>
          <Button
            type="submit"
            form="connector-delete-form"
            variant="danger"
            pending={deletion.isPending}
            disabled={!confirmed}
          >
            Delete connector
          </Button>
        </div>
      }
    >
      <form
        id="connector-delete-form"
        className="space-y-5"
        onSubmit={(event) => {
          event.preventDefault();
          if (confirmed && !deletion.isPending) deletion.mutate(connector);
        }}
      >
        <ul className="list-disc space-y-2 pl-5 type-body text-foreground">
          <li>Its tables leave Catalog, and its saved credentials are destroyed.</li>
          <li>
            {modelsQuery.isPending
              ? "Checking which semantic models use it…"
              : modelsQuery.isError
                ? "Semantic models that use it couldn’t be checked. Models that read its tables won’t run until they use another connector."
                : usingModels.length === 0
                  ? "No semantic models use it."
                  : semanticModelImpact(usingModels.length)}
            {usingModels.length > 0 ? (
              <ul aria-label="Semantic models that use this connector" className="mt-1.5 space-y-1">
                {usingModels.map((model) => (
                  <li key={model.id}>
                    <Link href={webPaths.semanticModel(workspaceId, model.id)} className="text-foreground underline underline-offset-4">
                      {model.label || model.name}
                    </Link>
                  </li>
                ))}
              </ul>
            ) : null}
          </li>
          <li>Explore runs and refresh history stay, shown as “Deleted connector”.</li>
        </ul>
        <Input
          id="connector-delete-confirmation"
          label={`Type “${connector.name}” to confirm`}
          value={confirmation}
          disabled={deletion.isPending}
          autoComplete="off"
          spellCheck={false}
          onChange={(event) => setConfirmation(event.target.value)}
        />
        {refreshActive ? (
          <p role="alert" className="type-body text-error">
            A refresh is running. Cancel it in Catalog, then delete.{" "}
            <Link href={catalogHref(workspaceId, { connector: connector.id })} className="text-foreground underline underline-offset-4">
              Open Catalog
            </Link>
          </p>
        ) : null}
        {deleteError ? (
          <p role="alert" className="type-body text-error">{deleteError}</p>
        ) : null}
      </form>
    </Dialog>
  );
}
