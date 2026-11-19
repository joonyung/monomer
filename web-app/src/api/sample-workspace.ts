import { ApiError, apiJson } from "@/lib/api/client";
import { CONTROL_API_URL } from "@/lib/auth/config";
import type { components } from "@/api/control/generated";

export type SampleWorkspaceResponse = components["schemas"]["SampleWorkspaceResponse"];

const sampleWorkspaceUrl = (organizationId: string) => `${CONTROL_API_URL}/v2/organizations/${encodeURIComponent(organizationId)}/sample-workspace`;

export const sampleWorkspaceAPI = {
  read: async (organizationId: string, signal?: AbortSignal): Promise<SampleWorkspaceResponse | null> => {
    try {
      return await apiJson<SampleWorkspaceResponse>(sampleWorkspaceUrl(organizationId), { signal });
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null;
      throw error;
    }
  },
  start: (organizationId: string): Promise<SampleWorkspaceResponse> => apiJson(sampleWorkspaceUrl(organizationId), { method: "POST" }),
};
