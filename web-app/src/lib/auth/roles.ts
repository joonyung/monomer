import type { WorkspaceAccess, WorkspaceRole } from "./types";

export const WORKSPACE_ROLES: WorkspaceRole[] = ["VIEWER", "EXPLORER", "EDITOR", "OWNER"];
export const ROLE_DESCRIPTIONS: Record<WorkspaceRole, string> = {
  VIEWER: "Read Pages and explore their published results.",
  EXPLORER: "Run queries as this workspace’s connection account and create Dashboards and Pages.",
  EDITOR: "Also edit shared Semantic definitions and Catalog selection.",
  OWNER: "Also manage Connectors, members, roles, and workspace settings.",
};
export function roleLabel(role: WorkspaceRole): string {
  return role[0] + role.slice(1).toLowerCase();
}
export function hasWorkspaceRole(access: WorkspaceAccess | null, minimumRole: WorkspaceRole): boolean {
  return Boolean(access?.granted && access.role && WORKSPACE_ROLES.indexOf(access.role) >= WORKSPACE_ROLES.indexOf(minimumRole));
}
