import { useQuery } from "@tanstack/react-query";
import { fetchAgentConnections } from "@/lib/auth/agent-oauth";
import { useAuthStore } from "@/lib/auth/store";

export const agentConnectionsKey = (epoch: number) => ["auth", epoch, "agent-connections"] as const;

export function useAgentConnections() {
  const status = useAuthStore((state) => state.status);
  const epoch = useAuthStore((state) => state.authEpoch);
  return useQuery({
    queryKey: agentConnectionsKey(epoch),
    queryFn: ({ signal }) => fetchAgentConnections(signal),
    enabled: status === "authenticated",
    retry: false,
  });
}
