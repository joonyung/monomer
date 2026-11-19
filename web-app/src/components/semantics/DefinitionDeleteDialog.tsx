import { lineageAPI } from "@/api/lineage";
import { AffectsList } from "@/components/semantics/AffectsList";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { definitionDependentMetrics, metricAPI } from "@/api/metric";
import { semanticDefinitionAPI, type SemanticDefinitionKind } from "@/api/semantic-definition-history";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Input } from "@/components/ui/Input";
import { ApiError } from "@/lib/api/client";
import { Link } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { refreshSemanticDefinitionCaches } from "@/lib/query/semantic-definition-cache";
import { webPaths } from "@/lib/web-paths";

export function DefinitionDeleteDialog({ workspaceId, principalId, kind, definition, onCancel, onDeleted }: {
  workspaceId: string; principalId: string; kind: SemanticDefinitionKind;
  definition: { id: string; name: string; label?: string };
  onCancel: () => void; onDeleted: () => void | Promise<void>;
}) {
  const [confirmation, setConfirmation] = useState("");
  const [failedAttempt, setFailedAttempt] = useState(false);
  const client = useQueryClient();
  const metrics = useQuery({ queryKey: queryKeys.metrics.all(workspaceId, principalId), queryFn: () => metricAPI.list(workspaceId) });
  const finishDeletion = async () => {
    await onDeleted();
    await refreshSemanticDefinitionCaches(client, workspaceId, principalId, kind, definition.id, true);
  };
  const deletion = useMutation({
    mutationFn: () => semanticDefinitionAPI.delete(workspaceId, kind, definition.id),
    onSuccess: finishDeletion,
    onError: () => setFailedAttempt(true),
  });
  const lineage = useQuery({ queryKey: queryKeys.lineage.focus(workspaceId, principalId, kind === "metrics" ? "METRIC" : "SEMANTIC_MODEL", definition.id), queryFn: ({signal}) => lineageAPI.getFocusCone(workspaceId, {kind: kind === "metrics" ? "METRIC" : "SEMANTIC_MODEL", id: definition.id}, signal), retry: false });
  const affected = definitionDependentMetrics(kind, definition.name, metrics.data ?? []);
  const confirmed = confirmation === definition.name;
  const alreadyDeleted = failedAttempt && deletion.error instanceof ApiError && deletion.error.status === 404;
  const noun = kind === "metrics" ? "metric" : "model";
  return <Dialog open onOpenChange={(open) => { if (!open) onCancel(); }} size="md" role="alertdialog"
    busy={deletion.isPending} closeLabel={`Close delete ${noun}`} testId="definition-delete-dialog"
    title={`Delete ${definition.label || definition.name}?`} description="You can restore it later from its history."
    footer={<><Button variant="secondary" disabled={deletion.isPending} onClick={onCancel}>Cancel</Button>
      {alreadyDeleted ? <Button onClick={() => void finishDeletion()}>Back to Semantics</Button> : <Button type="submit" form="definition-delete-form" variant="danger" pending={deletion.isPending} disabled={!confirmed}>Delete {noun}</Button>}</>}>
    <form id="definition-delete-form" className="space-y-5" onSubmit={(event) => { event.preventDefault(); if (confirmed && !deletion.isPending) deletion.mutate(); }}>
      <ul className="list-disc space-y-2 pl-5 type-body text-foreground">
        <li>{metrics.isPending ? "Checking which metrics use it…" : metrics.isError ? "Metrics that use it couldn’t be checked. They won’t run until they’re fixed." : affected.length === 0 ? "No metrics use it." : `${affected.length} ${affected.length === 1 ? "metric uses it and won’t run until it’s" : "metrics use it and won’t run until they’re"} fixed:`}
          {affected.length > 0 && <ul aria-label="Metrics that use this definition" className="mt-1.5 space-y-1">{affected.map((metric) => <li key={metric.id}><Link href={webPaths.metric(workspaceId, metric.id)} className="text-foreground underline underline-offset-4">{metric.label || metric.name}</Link></li>)}</ul>}
        </li>
        <li>Dashboards and pages that use it stop updating.</li>
        <li>Explore runs and their results stay.</li>
        <li>Creating a {noun} named “{definition.name}” again makes everything that uses the name use the new one. The new definition has its own history.</li>
      </ul>
      <section aria-label="Affects if deleted"><h3 className="type-group mb-2 text-heading">Affects if deleted</h3>{lineage.data ? <AffectsList workspaceId={workspaceId} graph={lineage.data} metricDefinitions={metrics.data} /> : <p className="type-status text-foreground-secondary">{lineage.isError ? "What this affects could not be loaded." : "Loading what this affects…"}</p>}</section>
      <Input id="definition-delete-confirmation" label={`Type “${definition.name}” to confirm`} value={confirmation} disabled={deletion.isPending || alreadyDeleted} autoComplete="off" spellCheck={false} onChange={(event) => setConfirmation(event.target.value)} />
      {deletion.error && <p role="alert" className="type-body text-error">{alreadyDeleted ? "This definition is already deleted. Its history is still available." : deletion.error.message}</p>}
    </form>
  </Dialog>;
}
