import type { components } from "@/api/control/generated";

export interface AuthUser {
  id: string;
  email: string;
  displayName: string;
}

export type OrganizationRole = "ADMIN" | "MEMBER" | string;

export interface Organization {
  id: string;
  displayName: string;
  plan: string;
  createdAt: string;
}

export interface OrganizationMembership {
  organization: Organization;
  role: OrganizationRole;
}

export type SampleWorkspaceMarker = components["schemas"]["WorkspaceSampleMarker"];

export interface Workspace {
  sample?: SampleWorkspaceMarker | null;
  publicPages: boolean;
  // IANA time zone that groups days and filters times in new Semantic results, such as UTC or Asia/Seoul.
  reportingTimeZone: string;
  id: string;
  organizationId: string;
  name: string;
}

// A workspace the signed-in user can open, with the role their direct membership grants.
export interface AccessibleWorkspace extends Workspace {
  role: WorkspaceRole;
}

export interface ManagedWorkspace extends Workspace {
  createdAt: string;
  memberCount: number;
}

export type WorkspaceRole = "VIEWER" | "EXPLORER" | "EDITOR" | "OWNER";

export interface WorkspaceAuthorizationAssignments {
  authorizationRevision: string;
  members: WorkspaceMember[];
}

export interface WorkspaceAccess {
  granted: boolean;
  organizationId: string | null;
  role: WorkspaceRole | null;
}

export interface OrganizationMemberWorkspace {
  sample?: SampleWorkspaceMarker | null;
  id: string;
  name: string;
  role: WorkspaceRole;
  memberCount: number;
  ownerCount: number;
}

export interface OrganizationMember {
  workspaces: OrganizationMemberWorkspace[];
  user: AuthUser;
  role: OrganizationRole;
  joinedAt: string;
}

export interface OrganizationResourceCounts {
  members: number;
  workspaces: number;
  pendingInvitations: number;
}

export interface OrganizationUsage {
  // Null on plans without resource limits.
  limits: OrganizationResourceCounts | null;
  usage: OrganizationResourceCounts;
}

export interface WorkspaceMember {
  principalId: string;
  displayName: string;
  user: AuthUser | null;
  role: WorkspaceRole;
}

export interface WorkspaceMemberCandidates {
  users: OrganizationMember[];
}

export interface LoginIdentity {
  provider: string;
  linkedAt: string;
}

export interface MeResult {
  user: AuthUser;
  organizationMemberships: OrganizationMembership[];
  loginIdentities: LoginIdentity[];
}

export interface WorkspaceOwner {
  userId: string;
  displayName: string;
}

export interface WorkspaceSummary {
  id: string;
  createdAt: string;
  memberCount: number;
}
