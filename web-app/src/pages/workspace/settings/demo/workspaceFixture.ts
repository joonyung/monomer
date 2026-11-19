// In-memory Workspace settings backend for /dev/workspace-settings-demo and the role fixture. It
// answers the auth GraphQL operations the production Workspace settings send, so the real General,
// Members, and Public access surfaces render and mutate with no Control service.
import type {
  AuthUser,
  OrganizationMember,
  OrganizationMembership,
  OrganizationRole,
  WorkspaceRole,
} from "@/lib/auth/types";

export type WorkspaceFixtureViewer = "owner" | "admin" | "editor" | "explorer";

export const FIXTURE_ORGANIZATION = {
  id: "00000000-0000-4000-8000-000000000512",
  displayName: "Northstar Labs",
  plan: "FREE",
  createdAt: "2026-08-20T12:00:00Z",
};
export const FIXTURE_WORKSPACE_ID = "00000000-0000-4000-8000-000000000511";
export const FIXTURE_VIEWER: AuthUser = { id: "00000000-0000-4000-8000-000000000513", email: "jordan@northstar.test", displayName: "Jordan Lee" };

const VIEWER_ROLES: Record<WorkspaceFixtureViewer, { workspace: WorkspaceRole; organization: OrganizationRole }> = {
  owner: { workspace: "OWNER", organization: "MEMBER" },
  admin: { workspace: "EDITOR", organization: "ADMIN" },
  editor: { workspace: "EDITOR", organization: "MEMBER" },
  explorer: { workspace: "EXPLORER", organization: "MEMBER" },
};

export function fixtureMembership(viewer: WorkspaceFixtureViewer): OrganizationMembership {
  return { organization: FIXTURE_ORGANIZATION, role: VIEWER_ROLES[viewer].organization };
}

interface FixturePerson {
  user: AuthUser;
  organizationRole: OrganizationRole;
}

const PEOPLE: FixturePerson[] = [
  { user: FIXTURE_VIEWER, organizationRole: "MEMBER" },
  { user: { id: "user-morgan", email: "morgan@northstar.test", displayName: "Morgan Chen" }, organizationRole: "ADMIN" },
  { user: { id: "user-riley", email: "riley@northstar.test", displayName: "Riley Ortiz" }, organizationRole: "MEMBER" },
  { user: { id: "user-sam", email: "sam@northstar.test", displayName: "Sam Kim" }, organizationRole: "MEMBER" },
  { user: { id: "user-devon", email: "devon@northstar.test", displayName: "Devon Park" }, organizationRole: "MEMBER" },
  { user: { id: "user-mara", email: "mara@northstar.test", displayName: "Mara Chen" }, organizationRole: "MEMBER" },
  { user: { id: "user-priya", email: "priya@northstar.test", displayName: "Priya Nair" }, organizationRole: "MEMBER" },
];

function operationName(body: string): string {
  return body.match(/(?:query|mutation)\s+([A-Za-z0-9_]+)/)?.[1] ?? "";
}

function variablesOf(body: string): Record<string, unknown> {
  try {
    return (JSON.parse(body).variables ?? {}) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export function installWorkspaceFixtureFetch(viewer: WorkspaceFixtureViewer): () => void {
  const realFetch = window.fetch.bind(window);
  const people = PEOPLE.map((person) => person.user.id === FIXTURE_VIEWER.id
    ? { ...person, organizationRole: VIEWER_ROLES[viewer].organization }
    : person);
  let workspace = { id: FIXTURE_WORKSPACE_ID, organizationId: FIXTURE_ORGANIZATION.id, name: "Revenue Analytics", publicPages: false, reportingTimeZone: "UTC", createdAt: "2026-09-01T12:00:00Z" };
  const workspaceView = () => ({ id: workspace.id, organizationId: workspace.organizationId, name: workspace.name, publicPages: workspace.publicPages, reportingTimeZone: workspace.reportingTimeZone });
  let roles: Record<string, WorkspaceRole> = {
    [FIXTURE_VIEWER.id]: VIEWER_ROLES[viewer].workspace,
    "user-morgan": "OWNER",
    "user-riley": "EXPLORER",
    "user-sam": "EDITOR",
    "user-devon": "VIEWER",
  };
  const viewerRole = () => roles[FIXTURE_VIEWER.id] ?? null;
  const viewerIsAdmin = () => people.find((person) => person.user.id === FIXTURE_VIEWER.id)?.organizationRole === "ADMIN";
  const roster = () => people.filter((person) => roles[person.user.id]);
  const organizationMembers = (): OrganizationMember[] => people.map((person) => ({
    user: person.user,
    role: person.organizationRole,
    joinedAt: "2026-09-01T12:00:00Z",
    workspaces: roles[person.user.id]
      ? [{
          id: workspace.id,
          name: workspace.name,
          role: roles[person.user.id],
          memberCount: Object.keys(roles).length,
          ownerCount: Object.values(roles).filter((role) => role === "OWNER").length,
        }]
      : [],
  }));

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    const body = typeof init?.body === "string" ? init.body : "";
    if (!url.includes("/v2/graphql") || !body) return realFetch(input, init);
    const operation = operationName(body);
    const variables = variablesOf(body);
    const json = (data: unknown) => new Response(JSON.stringify({ data }), { status: 200, headers: { "Content-Type": "application/json" } });
    const forbidden = () => new Response(
      JSON.stringify({ errors: [{ message: "Forbidden", extensions: { classification: "FORBIDDEN" } }] }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
    const owner = viewerRole() === "OWNER";
    const manager = owner || viewerIsAdmin();

    switch (operation) {
      case "Me":
        return json({ me: { user: FIXTURE_VIEWER, organizationMemberships: [fixtureMembership(viewer)], loginIdentities: [] } });
      case "AccessibleWorkspaces":
        return json({ accessibleWorkspaces: viewerRole() ? [{ ...workspaceView(), role: viewerRole() }] : [] });
      case "WorkspaceSummaries":
        return json({ accessibleWorkspaces: viewerRole() ? [{ id: workspace.id, createdAt: workspace.createdAt, memberCount: Object.keys(roles).length }] : [] });
      case "WorkspaceAccess":
        return json({ workspaceAccess: { granted: viewerRole() !== null, organizationId: viewerRole() ? workspace.organizationId : null, role: viewerRole() } });
      case "WorkspaceOwners":
        return json({ workspaceAccess: { owners: roster().filter((person) => roles[person.user.id] === "OWNER").map((person) => ({ userId: person.user.id, displayName: person.user.displayName })) } });
      case "WorkspaceAuthorizationAssignments":
        if (!owner) return forbidden();
        return json({
          workspaceAuthorizationAssignments: {
            authorizationRevision: "12",
            members: roster().map((person) => ({ principalId: person.user.id, displayName: person.user.displayName, user: person.user, role: roles[person.user.id] })),
          },
        });
      case "WorkspaceMemberCandidates":
        if (!owner) return forbidden();
        return json({ workspaceMemberCandidates: { users: organizationMembers().map(({ user, role, joinedAt }) => ({ user, role, joinedAt })) } });
      case "OrganizationMembers":
        if (!viewerIsAdmin()) return forbidden();
        return json({ organizationMembers: organizationMembers() });
      case "AddWorkspaceMember":
        if (!owner) return forbidden();
        roles = { ...roles, [String(variables.principalId)]: variables.role as WorkspaceRole };
        return json({ addWorkspaceMember: { workspaceId: workspace.id, principalId: variables.principalId } });
      case "SetWorkspaceMemberRole":
        if (!manager) return forbidden();
        roles = { ...roles, [String(variables.userId)]: variables.role as WorkspaceRole };
        return json({ setWorkspaceMemberRole: true });
      case "RemoveWorkspaceMember": {
        if (!manager) return forbidden();
        const next = { ...roles };
        delete next[String(variables.principalId)];
        roles = next;
        return json({ removeWorkspaceMember: true });
      }
      case "RenameWorkspace":
        if (!manager) return forbidden();
        workspace = { ...workspace, name: String(variables.displayName) };
        return json({ renameWorkspace: workspaceView() });
      case "SetWorkspacePublicPages":
        if (!owner) return forbidden();
        workspace = { ...workspace, publicPages: variables.publicPages === true };
        return json({ setWorkspacePublicPages: workspaceView() });
      case "SetWorkspaceReportingTimeZone":
        if (!manager) return forbidden();
        workspace = { ...workspace, reportingTimeZone: String(variables.timeZone) };
        return json({ setWorkspaceReportingTimeZone: workspaceView() });
      default:
        return new Response(JSON.stringify({ errors: [{ message: `Unhandled fixture GraphQL operation: ${operation}` }] }), { status: 400, headers: { "Content-Type": "application/json" } });
    }
  };
  return () => {
    window.fetch = realFetch;
  };
}
