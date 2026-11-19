import { createBrowserControlClient } from "@/api/control/client";
import type { components } from "@/api/control/generated";

export type ActivityItem = components["schemas"]["OverviewActivityItem"];
export type ActivityPage = components["schemas"]["OverviewActivityPage"];
const client = createBrowserControlClient();

export const overviewAPI = {
  async read(workspaceId: string, options: {
    section: "live" | "recent";
    actor: string;
    surface?: "WEB" | "AGENT";
    limit: number;
    cursor?: string;
    watch?: string;
  }, signal: AbortSignal): Promise<ActivityPage> {
    const response = await client.request("listWorkspaceOverviewActivity", {
      path: { workspaceId }, query: options,
    }, signal);
    if (response.status !== 200 || response.contentType !== "application/json") {
      throw new Error("Activity could not be loaded.");
    }
    return response.data;
  },
};
