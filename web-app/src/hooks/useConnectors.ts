import { useQuery, useQueryClient } from "@tanstack/react-query";
import { connectorsAPI } from "@/api/connectors";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { queryKeys } from "@/lib/query/keys";
import type { Connector } from "@/lib/types/connector";

interface UseConnectorsOptions {
  workspaceId?: string;
}

interface UseConnectorsResult {
  connectors: Connector[];
  loading: boolean;
  retrying: boolean;
  error: string | null;
  retry: () => void;
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

export function useConnectors(options: UseConnectorsOptions = {}): UseConnectorsResult {
  const activeWorkspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const workspaceId = options.workspaceId ?? activeWorkspaceId;
  const queryClient = useQueryClient();
  const connectorListQueryKey = queryKeys.connectors.list(workspaceId ?? "", principalId ?? "");

  const connectorsQuery = useQuery({
    queryKey: connectorListQueryKey,
    queryFn: () => connectorsAPI.list({ workspaceId: workspaceId as string }),
    enabled: Boolean(workspaceId && principalId),
  });

  return {
    connectors: workspaceId && principalId ? connectorsQuery.data ?? [] : [],
    loading: Boolean(workspaceId && principalId) && connectorsQuery.isPending,
    retrying: connectorsQuery.isFetching,
    error: connectorsQuery.error ? errorMessage(connectorsQuery.error, "Failed to fetch connectors") : null,
    retry: () => {
      void queryClient.invalidateQueries({ queryKey: connectorListQueryKey, exact: true });
    },
  };
}
