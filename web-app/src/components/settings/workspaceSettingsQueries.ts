import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import {
  addWorkspaceMember,
  fetchOrganizationMembers,
  fetchWorkspaceAuthorizationAssignments,
  fetchWorkspaceMemberCandidates,
  fetchWorkspaceOwners,
  fetchWorkspaceSummary,
  removeWorkspaceMember,
  setWorkspaceMemberRole,
} from "@/lib/auth/graphql";
import { useAuthStore } from "@/lib/auth/store";
import type { OrganizationMember, OrganizationRole, WorkspaceRole } from "@/lib/auth/types";
import { queryKeys } from "@/lib/query/keys";
import { organizationMembersQueryKey } from "./organizationQueries";
import {
  addableOrganizationMembers,
  workspaceMembersFromAssignments,
  workspaceMembersFromOrganization,
  workspaceSettingsCapabilities,
  type WorkspaceSettingsCapabilities,
  type WorkspaceSettingsMember,
} from "./workspaceSettingsModel";

export interface WorkspaceSettingsContext {
  workspaceId: string;
  workspaceName: string;
  publicPages: boolean;
  reportingTimeZone: string;
  organizationId: string;
  organizationName: string;
  organizationRole: OrganizationRole | null;
  capabilities: WorkspaceSettingsCapabilities;
}

export function useWorkspaceSettingsContext(): WorkspaceSettingsContext {
  const { workspace, access, organizationId } = useWorkspaceRouteContext();
  const membership = useAuthStore((state) => state.organizationMemberships.find((candidate) => candidate.organization.id === organizationId));
  const organizationRole = membership?.role ?? null;
  return {
    workspaceId: workspace.id,
    workspaceName: workspace.name,
    publicPages: workspace.publicPages,
    reportingTimeZone: workspace.reportingTimeZone,
    organizationId,
    organizationName: membership?.organization.displayName ?? "Organization",
    organizationRole,
    capabilities: workspaceSettingsCapabilities(access.role, organizationRole),
  };
}

function useWorkspaceSettingsKey(workspaceId: string) {
  const principalId = useAuthStore((state) => state.user?.id ?? "");
  return queryKeys.workspaceSettings.all(workspaceId, principalId);
}

export function useWorkspaceSummaryQuery(workspaceId: string) {
  const key = useWorkspaceSettingsKey(workspaceId);
  return useQuery({
    queryKey: [...key, "summary"],
    queryFn: ({ signal }) => fetchWorkspaceSummary(workspaceId, signal),
  });
}

export function useWorkspaceOwnersQuery(workspaceId: string, enabled: boolean) {
  const key = useWorkspaceSettingsKey(workspaceId);
  return useQuery({
    queryKey: [...key, "owners"],
    queryFn: ({ signal }) => fetchWorkspaceOwners(workspaceId, signal),
    enabled,
  });
}

export interface WorkspaceMembersSource {
  members: WorkspaceSettingsMember[] | undefined;
  // Organization members who aren't in the workspace yet; undefined until loaded.
  candidates: OrganizationMember[] | undefined;
  isPending: boolean;
  isError: boolean;
  candidatesError: boolean;
  refetch: () => void;
}

// Owners read the workspace roster and its candidates. Organization Admins who aren't Owners read
// the same people from Organization members, which carry each person's workspace roles.
export function useWorkspaceMembersSource(context: WorkspaceSettingsContext): WorkspaceMembersSource {
  const key = useWorkspaceSettingsKey(context.workspaceId);
  const principalId = useAuthStore((state) => state.user?.id ?? "");
  const roster = context.capabilities.readsWorkspaceRoster;
  const assignments = useQuery({
    queryKey: queryKeys.workspaceSettings.authorizationAssignments(context.workspaceId, principalId),
    queryFn: ({ signal }) => fetchWorkspaceAuthorizationAssignments(context.workspaceId, signal),
    enabled: roster,
  });
  const rosterCandidates = useQuery({
    queryKey: [...key, "candidates"],
    queryFn: ({ signal }) => fetchWorkspaceMemberCandidates(context.workspaceId, signal),
    enabled: roster,
  });
  const organizationMembers = useQuery({
    queryKey: organizationMembersQueryKey(principalId, context.organizationId),
    queryFn: ({ signal }) => fetchOrganizationMembers(context.organizationId, signal),
    enabled: !roster && context.capabilities.canManage,
  });
  if (roster) {
    const people = rosterCandidates.data?.users;
    const members = assignments.data ? workspaceMembersFromAssignments(assignments.data, people) : undefined;
    return {
      members,
      candidates: members && people ? addableOrganizationMembers(people, members) : undefined,
      isPending: assignments.isPending,
      isError: assignments.isError,
      candidatesError: rosterCandidates.isError,
      refetch: () => { void assignments.refetch(); void rosterCandidates.refetch(); },
    };
  }
  const members = organizationMembers.data ? workspaceMembersFromOrganization(organizationMembers.data, context.workspaceId) : undefined;
  return {
    members,
    candidates: members && organizationMembers.data ? addableOrganizationMembers(organizationMembers.data, members) : undefined,
    isPending: organizationMembers.isPending,
    isError: organizationMembers.isError,
    candidatesError: organizationMembers.isError,
    refetch: () => { void organizationMembers.refetch(); },
  };
}

// A member, role, name, public access, or Reporting time zone change can alter the viewer's own access, the workspace
// list, Organization settings, and Pages admission, so every one of those reads refreshes together.
export function useInvalidateWorkspaceSettings(workspaceId: string) {
  const queryClient = useQueryClient();
  const principalId = useAuthStore((state) => state.user?.id ?? "");
  return () => Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.workspaceSettings.all(workspaceId, principalId) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.workspaceContext.detail(workspaceId, principalId) }),
    queryClient.invalidateQueries({ queryKey: ["auth", principalId] }),
    queryClient.invalidateQueries({ queryKey: ["pages-access", workspaceId] }),
  ]);
}

export function workspaceMemberMutations(context: WorkspaceSettingsContext) {
  return {
    // Owners add through the workspace roster; Admins set a role, which adds an Organization member.
    add: (userId: string, role: WorkspaceRole) => context.capabilities.readsWorkspaceRoster
      ? addWorkspaceMember(context.workspaceId, userId, role)
      : setWorkspaceMemberRole(context.workspaceId, userId, role),
    setRole: (userId: string, role: WorkspaceRole) => setWorkspaceMemberRole(context.workspaceId, userId, role),
    remove: (userId: string) => removeWorkspaceMember(context.workspaceId, userId),
  };
}
