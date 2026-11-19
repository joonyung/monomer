import { useAuthStore } from "@/lib/auth/store";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";

export function useWorkspaceId(): string {
  return useWorkspaceRouteContext().workspaceId;
}

export function usePrincipalId(): string | null {
  return useAuthStore((s) => s.user?.id ?? null);
}
