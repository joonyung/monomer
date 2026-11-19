import { ApiError } from "@/lib/api/client";
import type { ControlProblem } from "@/api/control/types";
import { createBrowserControlClient } from "@/api/control/client";
import type { components } from "@/api/control/generated";

const client = createBrowserControlClient();

export type WorkspaceSearchResult = components["schemas"]["WorkspaceSearchResult"];
export type WorkspaceSearchDashboardHit = components["schemas"]["WorkspaceSearchDashboardHit"];
export type WorkspaceSearchMetricHit = components["schemas"]["WorkspaceSearchMetricHit"];
export type WorkspaceSearchSemanticModelHit = components["schemas"]["WorkspaceSearchSemanticModelHit"];
export type WorkspaceSearchTableHit = components["schemas"]["WorkspaceSearchTableHit"];

export const workspaceSearchAPI = {
  // Explorer or higher. Each kind returns at most `limit` current resources whose names match.
  async search(workspaceId: string, query: string, limit: number, signal?: AbortSignal): Promise<WorkspaceSearchResult> {
    const response = await client.request("searchWorkspaceResources", {
      path: { workspaceId },
      query: { query, limit },
    }, signal);
    if (response.status !== 200 || response.contentType !== "application/json") {
      const problem = response.contentType === "application/problem+json" ? response.data as ControlProblem : null;
      throw new ApiError(problem?.detail ?? problem?.title ?? `Search failed (${response.status}).`, response.status, "", problem);
    }
    return response.data as WorkspaceSearchResult;
  },
};
