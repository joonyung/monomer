import { CONTROL_API_URL } from "./config";
import { visibleWorkspaces } from "./sample-workspace";
import { graphqlRequest, GraphQLRequestError } from "@/lib/api/graphql";
import type {
  AccessibleWorkspace,
  OrganizationMember,
  OrganizationRole,
  OrganizationUsage,
  MeResult,
  Workspace,
  ManagedWorkspace,
  WorkspaceAccess,
  WorkspaceAuthorizationAssignments,
  WorkspaceMemberCandidates,
  WorkspaceOwner,
  WorkspaceRole,
  WorkspaceSummary,
} from "./types";

const AUTH_GRAPHQL_ENDPOINT = `${CONTROL_API_URL}/v2/graphql`;

const ACCESSIBLE_WORKSPACES_QUERY = `
  query AccessibleWorkspaces {
    accessibleWorkspaces {
      id
      organizationId
      name
      publicPages
      reportingTimeZone
      role
      sample { dataset state initiator }
    }
  }
`;

const MANAGED_WORKSPACES_QUERY = `
  query ManagedWorkspaces($organizationId: ID!) {
    managedWorkspaces(organizationId: $organizationId) {
      id
      organizationId
      name
      publicPages
      reportingTimeZone
      memberCount
      createdAt
      sample { dataset state initiator }
    }
  }
`;

const CREATE_WORKSPACE_MUTATION = `
  mutation CreateWorkspace($organizationId: ID!, $displayName: String!, $initialOwnerUserId: ID!) {
    createWorkspace(organizationId: $organizationId, displayName: $displayName, initialOwnerUserId: $initialOwnerUserId) {
      id
      organizationId
      name
      publicPages
      reportingTimeZone
      sample { dataset state initiator }
    }
  }
`;

const ME_QUERY = `
  query Me {
    me {
      user { id email displayName }
      organizationMemberships {
        organization { id displayName plan createdAt }
        role
      }
      loginIdentities { provider linkedAt }
    }
  }
`;

const WORKSPACE_ACCESS_QUERY = `
query WorkspaceAccess($workspaceId: ID!) {
 workspaceAccess(workspaceId: $workspaceId) {
   granted organizationId role
 }
}
`;

const WORKSPACE_OWNERS_QUERY = `
query WorkspaceOwners($workspaceId: ID!) {
 workspaceAccess(workspaceId: $workspaceId) {
   owners { userId displayName }
 }
}
`;

const WORKSPACE_SUMMARIES_QUERY = `
  query WorkspaceSummaries {
    accessibleWorkspaces {
      id
      createdAt
      memberCount
    }
  }
`;

const WORKSPACE_AUTHORIZATION_ASSIGNMENTS_QUERY = `
query WorkspaceAuthorizationAssignments($workspaceId: ID!) {
 workspaceAuthorizationAssignments(workspaceId: $workspaceId) {
   authorizationRevision
   members { principalId displayName user { id email displayName } role }
 }
}
`;

const ORGANIZATION_MEMBERS_QUERY = `
  query OrganizationMembers($organizationId: ID!) {
    organizationMembers(organizationId: $organizationId) {
      user { id email displayName }
      role
      workspaces { id name role memberCount ownerCount sample { dataset state initiator } }
      joinedAt
    }
  }
`;

const ORGANIZATION_USAGE_QUERY = `
  query OrganizationUsage($organizationId: ID!) {
    organizationUsage(organizationId: $organizationId) {
      limits { members workspaces pendingInvitations }
      usage { members workspaces pendingInvitations }
    }
  }
`;

const WORKSPACE_OWNER_CANDIDATES_QUERY = `
  query WorkspaceOwnerCandidates($organizationId: ID!, $workspaceId: ID!) {
    workspaceOwnerCandidates(organizationId: $organizationId, workspaceId: $workspaceId) {
      id
      email
      displayName
    }
  }
`;

const WORKSPACE_MEMBER_CANDIDATES_QUERY = `
  query WorkspaceMemberCandidates($workspaceId: ID!) {
    workspaceMemberCandidates(workspaceId: $workspaceId) {
      users {
        user { id email displayName }
        role
        joinedAt
      }
    }
  }
`;

const UPDATE_ORGANIZATION_MEMBER_ROLE_MUTATION = `
  mutation UpdateOrganizationMemberRole($organizationId: ID!, $userId: ID!, $role: OrganizationRole!) {
    updateOrganizationMemberRole(organizationId: $organizationId, userId: $userId, role: $role) {
      organizationId
      userId
      role
    }
  }
`;

const REMOVE_ORGANIZATION_MEMBER_MUTATION = `
  mutation RemoveOrganizationMember($organizationId: ID!, $userId: ID!) {
    removeOrganizationMember(organizationId: $organizationId, userId: $userId)
  }
`;

const ADD_WORKSPACE_MEMBER_MUTATION = `
  mutation AddWorkspaceMember($workspaceId: ID!, $principalId: ID!, $role: WorkspaceRole!) {
    addWorkspaceMember(workspaceId: $workspaceId, principalId: $principalId, role: $role) {
      workspaceId
      principalId
    }
  }
`;

const REMOVE_WORKSPACE_MEMBER_MUTATION = `
  mutation RemoveWorkspaceMember($workspaceId: ID!, $principalId: ID!) {
    removeWorkspaceMember(workspaceId: $workspaceId, principalId: $principalId)
  }
`;

const RENAME_WORKSPACE_MUTATION = `
  mutation RenameWorkspace($workspaceId: ID!, $displayName: String!) {
    renameWorkspace(workspaceId: $workspaceId, displayName: $displayName) {
      id
      organizationId
      name
      publicPages
      reportingTimeZone
      sample { dataset state initiator }
    }
  }
`;

export async function fetchMe(signal: AbortSignal | undefined, skipRefresh: boolean): Promise<MeResult> {
  const data = await graphqlRequest<{ me: MeResult }>({
    endpoint: AUTH_GRAPHQL_ENDPOINT,
    query: ME_QUERY,
    signal,
    skipRefresh,
  });
  return data.me;
}

export async function fetchWorkspaceAccess(
  workspaceId: string,
  signal?: AbortSignal,
): Promise<WorkspaceAccess> {
  const data = await graphqlRequest<{ workspaceAccess: WorkspaceAccess }>({
    endpoint: AUTH_GRAPHQL_ENDPOINT,
    query: WORKSPACE_ACCESS_QUERY,
    variables: { workspaceId },
    signal,
  });
  return data.workspaceAccess;
}

// Any granted member can read who owns a workspace, so a locked settings page can name whom to ask.
export async function fetchWorkspaceOwners(
  workspaceId: string,
  signal?: AbortSignal,
): Promise<WorkspaceOwner[]> {
  const data = await graphqlRequest<{ workspaceAccess: { owners: WorkspaceOwner[] } }>({
    endpoint: AUTH_GRAPHQL_ENDPOINT,
    query: WORKSPACE_OWNERS_QUERY,
    variables: { workspaceId },
    signal,
  });
  return data.workspaceAccess.owners;
}

export async function fetchWorkspaceSummary(
  workspaceId: string,
  signal?: AbortSignal,
): Promise<WorkspaceSummary | null> {
  const data = await graphqlRequest<{ accessibleWorkspaces: WorkspaceSummary[] }>({
    endpoint: AUTH_GRAPHQL_ENDPOINT,
    query: WORKSPACE_SUMMARIES_QUERY,
    signal,
  });
  return data.accessibleWorkspaces.find((workspace) => workspace.id === workspaceId) ?? null;
}

export async function fetchWorkspaceAuthorizationAssignments(
  workspaceId: string,
  signal?: AbortSignal,
): Promise<WorkspaceAuthorizationAssignments> {
  try {
    const data = await graphqlRequest<{
      workspaceAuthorizationAssignments: WorkspaceAuthorizationAssignments;
    }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: WORKSPACE_AUTHORIZATION_ASSIGNMENTS_QUERY,
      variables: { workspaceId },
      signal,
    });
    return data.workspaceAuthorizationAssignments;
  } catch (err) {
    throw toMemberMutationError(
      err,
      "Only a Workspace Owner can view Workspace authorization assignments.",
    );
  }
}

export async function fetchAccessibleWorkspaces(
  signal?: AbortSignal,
): Promise<AccessibleWorkspace[]> {
  const data = await graphqlRequest<{ accessibleWorkspaces: AccessibleWorkspace[] }>({
    endpoint: AUTH_GRAPHQL_ENDPOINT,
    query: ACCESSIBLE_WORKSPACES_QUERY,
    signal,
  });
  return visibleWorkspaces(data.accessibleWorkspaces);
}

export async function fetchManagedWorkspaces(
  organizationId: string,
  signal?: AbortSignal,
): Promise<ManagedWorkspace[]> {
  const data = await graphqlRequest<{ managedWorkspaces: ManagedWorkspace[] }>({
    endpoint: AUTH_GRAPHQL_ENDPOINT,
    query: MANAGED_WORKSPACES_QUERY,
    variables: { organizationId },
    signal,
  });
  return visibleWorkspaces(data.managedWorkspaces);
}

/**
 * Create a workspace in an organization.
 *
 * BE authorization: the caller must be an organization ADMIN; the BE returns
 * FORBIDDEN otherwise. A FORBIDDEN response is surfaced as a friendly
 * `CreateWorkspaceError` so the UI can show an actionable message instead of a
 * raw GraphQL error.
 */
export class CreateWorkspaceError extends Error {
  readonly forbidden: boolean;

  constructor(message: string, forbidden: boolean) {
    super(message);
    this.name = "CreateWorkspaceError";
    this.forbidden = forbidden;
  }
}

export async function createWorkspace(
  organizationId: string,
  displayName: string,
  initialOwnerUserId: string,
  signal?: AbortSignal,
): Promise<Workspace> {
  try {
    const data = await graphqlRequest<{ createWorkspace: Workspace }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: CREATE_WORKSPACE_MUTATION,
      variables: { organizationId, displayName, initialOwnerUserId },
      signal,
    });
    return data.createWorkspace;
  } catch (err) {
    if (err instanceof GraphQLRequestError) {
      const forbidden = err.errors.some(
        (e) => e.extensions?.classification === "FORBIDDEN",
      );
      throw new CreateWorkspaceError(
        forbidden
          ? "You need to be an Organization Admin to create a workspace."
          : err.message,
        forbidden,
      );
    }
    throw err;
  }
}

/**
 * Load the members of an organization.
 *
 * BE authorization: ADMIN-only. A FORBIDDEN response is surfaced as a friendly
 * `MemberMutationError` so the page can show "admin only" instead of a raw
 * GraphQL error.
 */
export async function fetchOrganizationMembers(
  organizationId: string,
  signal?: AbortSignal,
): Promise<OrganizationMember[]> {
  try {
    const data = await graphqlRequest<{ organizationMembers: OrganizationMember[] }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: ORGANIZATION_MEMBERS_QUERY,
      variables: { organizationId },
      signal,
    });
    return data.organizationMembers.map((member) => ({ ...member, workspaces: visibleWorkspaces(member.workspaces) }));
  } catch (err) {
    throw toMemberMutationError(
      err,
      "You need to be an Organization Admin to view members.",
    );
  }
}

export async function fetchOrganizationUsage(
  organizationId: string,
  signal?: AbortSignal,
): Promise<OrganizationUsage> {
  try {
    const data = await graphqlRequest<{ organizationUsage: OrganizationUsage }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: ORGANIZATION_USAGE_QUERY,
      variables: { organizationId },
      signal,
    });
    return data.organizationUsage;
  } catch (err) {
    throw toMemberMutationError(
      err,
      "You need to be an Organization Admin to view usage.",
    );
  }
}

export async function fetchWorkspaceOwnerCandidates(
  organizationId: string,
  workspaceId: string,
  signal?: AbortSignal,
): Promise<MeResult["user"][]> {
  try {
    const data = await graphqlRequest<{ workspaceOwnerCandidates: MeResult["user"][] }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: WORKSPACE_OWNER_CANDIDATES_QUERY,
      variables: { organizationId, workspaceId },
      signal,
    });
    return data.workspaceOwnerCandidates;
  } catch (err) {
    throw toMemberMutationError(
      err,
      "You need Organization governance access to view eligible Owner candidates.",
    );
  }
}

export async function fetchWorkspaceMemberCandidates(
  workspaceId: string,
  signal?: AbortSignal,
): Promise<WorkspaceMemberCandidates> {
  try {
    const data = await graphqlRequest<{
      workspaceMemberCandidates: WorkspaceMemberCandidates;
    }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: WORKSPACE_MEMBER_CANDIDATES_QUERY,
      variables: { workspaceId },
      signal,
    });
    return data.workspaceMemberCandidates;
  } catch (err) {
    throw toMemberMutationError(
      err,
      "Only a Workspace Owner can view member candidates.",
    );
  }
}

/**
 * Friendly error for the member-management surface. `forbidden` lets the UI
 * distinguish an authorization failure (show "admin only") from a transient
 * one (show "try again").
 */
export class MemberMutationError extends Error {
  readonly forbidden: boolean;

  constructor(message: string, forbidden: boolean) {
    super(message);
    this.name = "MemberMutationError";
    this.forbidden = forbidden;
  }
}

function toMemberMutationError(err: unknown, forbiddenMessage: string): Error {
  if (err instanceof GraphQLRequestError) {
    const forbidden = err.errors.some(
      (e) => e.extensions?.classification === "FORBIDDEN",
    );
    return new MemberMutationError(
      forbidden ? forbiddenMessage : err.message,
      forbidden,
    );
  }
  return err instanceof Error ? err : new Error(String(err));
}

export async function updateOrganizationMemberRole(
  organizationId: string,
  userId: string,
  role: OrganizationRole,
  signal?: AbortSignal,
): Promise<void> {
  try {
    await graphqlRequest<{ updateOrganizationMemberRole: unknown }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: UPDATE_ORGANIZATION_MEMBER_ROLE_MUTATION,
      variables: { organizationId, userId, role },
      signal,
    });
  } catch (err) {
    throw toMemberMutationError(
      err,
      "You need to be an Organization Admin to change Organization roles.",
    );
  }
}

export async function removeOrganizationMember(
  organizationId: string,
  userId: string,
  signal?: AbortSignal,
): Promise<void> {
  try {
    await graphqlRequest<{ removeOrganizationMember: boolean }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: REMOVE_ORGANIZATION_MEMBER_MUTATION,
      variables: { organizationId, userId },
      signal,
    });
  } catch (err) {
    throw toMemberMutationError(
      err,
      "You need to be an Organization Admin to remove members.",
    );
  }
}

export async function addWorkspaceMember(
  workspaceId: string,
  principalId: string,
  role: WorkspaceRole,
  signal?: AbortSignal,
): Promise<void> {
  try {
    await graphqlRequest<{ addWorkspaceMember: unknown }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: ADD_WORKSPACE_MEMBER_MUTATION,
      variables: { workspaceId, principalId, role },
      signal,
    });
  } catch (err) {
    throw toMemberMutationError(
      err,
      "Only a Workspace Owner can add Workspace members.",
    );
  }
}

export async function removeWorkspaceMember(
  workspaceId: string,
  principalId: string,
  signal?: AbortSignal,
): Promise<void> {
  try {
    await graphqlRequest<{ removeWorkspaceMember: boolean }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: REMOVE_WORKSPACE_MEMBER_MUTATION,
      variables: { workspaceId, principalId },
      signal,
    });
  } catch (err) {
    throw toMemberMutationError(
      err,
      "Only workspace owners and Organization Admins can remove members.",
    );
  }
}

export async function renameWorkspace(
  workspaceId: string,
  displayName: string,
  signal?: AbortSignal,
): Promise<Workspace> {
  try {
    const data = await graphqlRequest<{ renameWorkspace: Workspace }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: RENAME_WORKSPACE_MUTATION,
      variables: { workspaceId, displayName },
      signal,
    });
    return data.renameWorkspace;
  } catch (err) {
    throw toMemberMutationError(
      err,
      "Only workspace owners and Organization Admins can rename this workspace.",
    );
  }
}

export async function updateOrganizationDisplayName(organizationId: string, displayName: string): Promise<void> {
  await graphqlRequest({ endpoint: AUTH_GRAPHQL_ENDPOINT, query: `mutation UpdateOrganizationDisplayName($organizationId: ID!, $displayName: String!) { updateOrganizationDisplayName(organizationId: $organizationId, displayName: $displayName) { id displayName } }`, variables: { organizationId, displayName } });
}

export async function createOrganization(displayName: string): Promise<{ organizationId: string; defaultWorkspaceId: string }> {
  const data = await graphqlRequest<{ createOrganization: { organization: { id: string }; defaultWorkspace: { id: string } } }>({ endpoint: AUTH_GRAPHQL_ENDPOINT, query: `mutation CreateOrganization($displayName: String!) { createOrganization(displayName: $displayName) { organization { id } defaultWorkspace { id } } }`, variables: { displayName } });
  return { organizationId: data.createOrganization.organization.id, defaultWorkspaceId: data.createOrganization.defaultWorkspace.id };
}

export async function updateMyDisplayName(displayName: string): Promise<void> {
  await graphqlRequest({
    endpoint: AUTH_GRAPHQL_ENDPOINT,
    query: `mutation UpdateMyDisplayName($displayName: String!) {
      updateMyDisplayName(displayName: $displayName) { id email displayName }
    }`,
    variables: { displayName },
  });
}

export async function setWorkspacePublicPages(workspaceId: string, publicPages: boolean): Promise<Workspace> {
  const data = await graphqlRequest<{ setWorkspacePublicPages: Workspace }>({
    endpoint: AUTH_GRAPHQL_ENDPOINT,
    query: `mutation SetWorkspacePublicPages($workspaceId: ID!, $publicPages: Boolean!) { setWorkspacePublicPages(workspaceId: $workspaceId, publicPages: $publicPages) { id organizationId name publicPages reportingTimeZone sample { dataset state initiator } } }`,
    variables: { workspaceId, publicPages },
  });
  return data.setWorkspacePublicPages;
}

/** The chosen zone isn't in the Control's supported list, so nothing changed. */
export class UnsupportedReportingTimeZoneError extends Error {
  constructor() {
    super("Choose a time zone from the list. This one isn't supported.");
    this.name = "UnsupportedReportingTimeZoneError";
  }
}

export async function setWorkspaceReportingTimeZone(workspaceId: string, timeZone: string): Promise<Workspace> {
  try {
    const data = await graphqlRequest<{ setWorkspaceReportingTimeZone: Workspace }>({
      endpoint: AUTH_GRAPHQL_ENDPOINT,
      query: `mutation SetWorkspaceReportingTimeZone($workspaceId: ID!, $timeZone: String!) { setWorkspaceReportingTimeZone(workspaceId: $workspaceId, timeZone: $timeZone) { id organizationId name publicPages reportingTimeZone sample { dataset state initiator } } }`,
      variables: { workspaceId, timeZone },
    });
    return data.setWorkspaceReportingTimeZone;
  } catch (err) {
    if (err instanceof GraphQLRequestError && err.errors.some((e) => e.extensions?.code === "UNSUPPORTED_REPORTING_TIME_ZONE")) {
      throw new UnsupportedReportingTimeZoneError();
    }
    throw toMemberMutationError(err, "Only workspace owners and Organization Admins can change the Reporting time zone.");
  }
}

export async function setWorkspaceMemberRole(workspaceId: string, userId: string, role: WorkspaceRole): Promise<void> {
 await graphqlRequest({ endpoint: AUTH_GRAPHQL_ENDPOINT,
 query: `mutation SetWorkspaceMemberRole($workspaceId: ID!, $userId: ID!, $role: WorkspaceRole!) { setWorkspaceMemberRole(workspaceId: $workspaceId, userId: $userId, role: $role) }`,
 variables: { workspaceId, userId, role } });
}
