import type { Query, QueryClient } from "@tanstack/react-query";
import type { SemanticDefinitionKind } from "@/api/semantic-definition-history";
import { queryKeys } from "@/lib/query/keys";

export async function refreshSemanticDefinitionReads(client: QueryClient, workspaceId: string, principalId: string): Promise<void> {
  const readsDerivedFromDefinitions = ({ queryKey: key }: Query) =>
    (key[0] === "protected" && key[1] === workspaceId && key[2] === principalId &&
      ["search", "lineage", "semantic-check"].includes(String(key[3]))) ||
    (key[0] === "protected" && key[1] === workspaceId && key[2] === principalId && key[3] === "explore" && key[4] === "semantic-group-bys") ||
    (key[0] === "overview" && key[1] === workspaceId && key[2] === principalId);
  await Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.semanticModels.all(workspaceId, principalId), refetchType: "all" }),
    client.invalidateQueries({ queryKey: queryKeys.metrics.all(workspaceId, principalId), refetchType: "all" }),
    client.invalidateQueries({ queryKey: queryKeys.overview.counts(workspaceId, principalId), refetchType: "all" }),
    client.invalidateQueries({ predicate: readsDerivedFromDefinitions }),
  ]);
}

export async function refreshSemanticDefinitionCaches(client: QueryClient, workspaceId: string, principalId: string, kind: SemanticDefinitionKind, id: string, deleted: boolean): Promise<void> {
  if (deleted) client.removeQueries({ queryKey: kind === "metrics"
    ? queryKeys.metrics.detail(workspaceId, principalId, id)
    : queryKeys.semanticModels.detail(workspaceId, principalId, id), exact: true });
  await refreshSemanticDefinitionReads(client, workspaceId, principalId);
}
