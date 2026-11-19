import { apiFetch } from "@/lib/api/client";
import { semanticBffUrl } from "@/api/workspace-bff";
import type { components } from "@/api/control/generated";

export type SemanticDefinitionKind = "semantic-models" | "metrics";
export type DefinitionChange = components["schemas"]["MetricDefinitionVersion"] | components["schemas"]["SemanticModelDefinitionVersion"];
export type DefinitionHistory = Omit<components["schemas"]["MetricHistoryResponse"], "changes"> & { changes: DefinitionChange[] };

const definitionPath = (kind: SemanticDefinitionKind, id: string) => `/${kind}/${encodeURIComponent(id)}`;

export const semanticDefinitionAPI = {
  async delete(workspaceId: string, kind: SemanticDefinitionKind, id: string): Promise<void> {
    await apiFetch(semanticBffUrl(workspaceId, definitionPath(kind, id)), { method: "DELETE" });
  },
  async history(workspaceId: string, kind: SemanticDefinitionKind, id: string, cursor: string | undefined, signal?: AbortSignal): Promise<DefinitionHistory> {
    const query = new URLSearchParams({ limit: "20" });
    if (cursor) query.set("cursor", cursor);
    const response = await apiFetch(semanticBffUrl(workspaceId, `${definitionPath(kind, id)}/history?${query}`), { signal });
    return await response.json() as DefinitionHistory;
  },
  async restore(workspaceId: string, kind: SemanticDefinitionKind, id: string, changeId: string, idempotencyKey: string): Promise<void> {
    await apiFetch(semanticBffUrl(workspaceId, `${definitionPath(kind, id)}/history/${encodeURIComponent(changeId)}/restore`), {
      method: "POST", headers: { "Idempotency-Key": idempotencyKey },
    });
  },
};
