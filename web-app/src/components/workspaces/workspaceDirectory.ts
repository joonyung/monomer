import type { AccessibleWorkspace, OrganizationMembership } from "@/lib/auth/types";

// The Workspaces page and the workspace switcher offer a search field only past this many workspaces.
export const WORKSPACE_SEARCH_THRESHOLD = 8;

export interface OrganizationWorkspaceGroup {
  membership: OrganizationMembership;
  workspaces: AccessibleWorkspace[];
}

// Every Organization I belong to, in membership order, with the workspaces I can open in it.
export function groupWorkspacesByOrganization(
  memberships: OrganizationMembership[],
  workspaces: AccessibleWorkspace[],
): OrganizationWorkspaceGroup[] {
  return memberships.map((membership) => ({
    membership,
    workspaces: workspaces.filter((workspace) => workspace.organizationId === membership.organization.id),
  }));
}

// Keeps workspaces whose name, or whose Organization's name, contains the query. Organizations
// with nothing left drop out while a query is active.
export function filterWorkspaceGroups(groups: OrganizationWorkspaceGroup[], query: string): OrganizationWorkspaceGroup[] {
  const needle = query.trim().toLocaleLowerCase();
  if (!needle) return groups;
  return groups
    .map((group) => ({
      membership: group.membership,
      workspaces: group.membership.organization.displayName.toLocaleLowerCase().includes(needle)
        ? group.workspaces
        : group.workspaces.filter((workspace) => workspace.name.toLocaleLowerCase().includes(needle)),
    }))
    .filter((group) => group.workspaces.length > 0);
}

export function organizationRoleLabel(role: OrganizationMembership["role"]): string {
  return role === "ADMIN" ? "Admin" : "Member";
}

// Who can give access to an Organization where I can open no workspace. Members can't read the
// Admin roster, so the sentence names roles rather than people.
export function noWorkspaceAccessMessage(role: OrganizationMembership["role"]): string {
  return role === "ADMIN"
    ? "No workspace access yet. Add yourself to a workspace in Organization settings."
    : "No workspace access yet. An Admin or a Workspace Owner can add you.";
}
