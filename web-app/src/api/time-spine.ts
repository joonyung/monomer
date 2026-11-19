import { apiFetch } from "@/lib/api/client";
import { CONTROL_API_URL } from "@/lib/auth/config";
import type {
  TimeSpineConfiguration,
  TimeSpineDefinition,
} from "@/lib/types/time-spine";

function collectionUrl(workspaceId: string): string {
  return `${CONTROL_API_URL}/v2/workspaces/${encodeURIComponent(workspaceId)}/semantic/time-spines`;
}

function configurationUrl(workspaceId: string, connectorId: string): string {
  return `${collectionUrl(workspaceId)}/${encodeURIComponent(connectorId)}`;
}

export const timeSpineAPI = {
  async list(workspaceId: string): Promise<TimeSpineConfiguration[]> {
    const response = await apiFetch(collectionUrl(workspaceId));
    return await response.json() as TimeSpineConfiguration[];
  },

  async replace(
    workspaceId: string,
    connectorId: string,
    definition: TimeSpineDefinition,
  ): Promise<TimeSpineConfiguration> {
    const response = await apiFetch(configurationUrl(workspaceId, connectorId), {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(definition),
    });
    return await response.json() as TimeSpineConfiguration;
  },
};
