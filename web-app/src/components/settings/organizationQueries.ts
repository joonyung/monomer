import { useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchOrganizationMembers, fetchOrganizationUsage } from "@/lib/auth/graphql";
import { fetchOrganizationInvitations } from "@/lib/auth/invitations";
import { useAuthStore } from "@/lib/auth/store";

// Every Organization settings read shares the session-scoped ["auth", principal] prefix, so one
// invalidation after a mutation refreshes the sidebar counts, tables, sheets, and usage together.
function useOrganizationKey(organizationId: string) {
  const principalId = useAuthStore((state) => state.user?.id ?? "");
  return ["auth", principalId, organizationId] as const;
}

export function organizationMembersQueryKey(principalId: string, organizationId: string) {
  return ["auth", principalId, organizationId, "members"] as const;
}

export function useOrganizationMembersQuery(organizationId: string) {
  const principalId = useAuthStore((state) => state.user?.id ?? "");
  return useQuery({
    queryKey: organizationMembersQueryKey(principalId, organizationId),
    queryFn: ({ signal }) => fetchOrganizationMembers(organizationId, signal),
  });
}

export function useOrganizationInvitationsQuery(organizationId: string) {
  const key = useOrganizationKey(organizationId);
  return useQuery({
    queryKey: [...key, "invitations"],
    queryFn: ({ signal }) => fetchOrganizationInvitations(organizationId, signal),
  });
}

export function useOrganizationUsageQuery(organizationId: string) {
  const key = useOrganizationKey(organizationId);
  return useQuery({
    queryKey: [...key, "usage"],
    queryFn: ({ signal }) => fetchOrganizationUsage(organizationId, signal),
  });
}

export function useInvalidateOrganizationSettings() {
  const queryClient = useQueryClient();
  const principalId = useAuthStore((state) => state.user?.id ?? "");
  return () => queryClient.invalidateQueries({ queryKey: ["auth", principalId] });
}
