import { useQuery } from "@tanstack/react-query";
import { catalogWorkspaceAPI } from "@/api/catalog";
import { dashboardAPI } from "@/api/dashboard";
import { exploreAPI } from "@/api/explore";
import { metricAPI } from "@/api/metric";
import { semanticModelAPI } from "@/api/semantic-model";
import { useConnectors } from "@/hooks/useConnectors";
import { queryKeys } from "@/lib/query/keys";
import { useAgentConnections } from "@/pages/user-settings/use-agent-connections";

/** The owner reads that Needs attention and Get started compute from. Nothing is stored for Overview. */
export function useOverviewSources(workspaceId: string, principalId: string) {
  const connectors = useConnectors();
  const summaries = useQuery({
    queryKey: queryKeys.catalog.connectorSummaries(workspaceId, principalId),
    queryFn: ({ signal }) => catalogWorkspaceAPI.listConnectorSummaries(workspaceId, signal),
    retry: false,
  });
  const dashboards = useQuery({
    queryKey: queryKeys.dashboards.list(workspaceId, principalId),
    queryFn: ({ signal }) => dashboardAPI.list(workspaceId, signal),
    retry: false,
  });
  const myRuns = useQuery({
    queryKey: queryKeys.explore.recentRuns(workspaceId, principalId),
    queryFn: () => exploreAPI.listRecentRuns({ workspaceId }),
    retry: false,
  });
  const models = useQuery({
    queryKey: queryKeys.semanticModels.all(workspaceId, principalId),
    queryFn: () => semanticModelAPI.list(workspaceId),
    retry: false,
  });
  const metrics = useQuery({
    queryKey: queryKeys.metrics.all(workspaceId, principalId),
    queryFn: () => metricAPI.list(workspaceId),
    retry: false,
  });
  // The Get started agent step is personal: the viewer's own connections say whether they have used one yet.
  const agentConnections = useAgentConnections();
  return { connectors, summaries, dashboards, myRuns, models, metrics, agentConnections };
}

export type OverviewSources = ReturnType<typeof useOverviewSources>;
