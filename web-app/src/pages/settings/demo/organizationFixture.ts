// In-memory Organization settings backend for the /dev Organization settings and Members demos.
// It answers the auth GraphQL operations the production components send, so the real General,
// Members, and Workspaces surfaces render and mutate with no Control service.
import type { OrganizationInvitation } from "@/lib/auth/invitations";
import type {
  AuthUser,
  ManagedWorkspace,
  OrganizationMember,
  OrganizationMembership,
  WorkspaceRole,
} from "@/lib/auth/types";

export type OrganizationFixtureState = "ready" | "empty" | "error" | "loading" | "overflow" | "unlimited";

export const FIXTURE_ORGANIZATION = {
  id: "organization-acme",
  plan: "FREE",
  createdAt: "2026-09-01T12:00:00Z",
  displayName: "Acme Analytics",
};

export const FIXTURE_VIEWER: AuthUser = { id: "user-jordan", email: "jordan@acme.test", displayName: "Jordan Lee" };

const DAY = 86_400_000;

interface FixtureMember {
  user: AuthUser;
  role: "ADMIN" | "MEMBER";
  joinedAt: string;
}

function initialMembers(): FixtureMember[] {
  return [
    { user: FIXTURE_VIEWER, role: "ADMIN", joinedAt: "2026-09-01T12:00:00Z" },
    { user: { id: "user-mara", email: "mara@acme.test", displayName: "Mara Chen" }, role: "ADMIN", joinedAt: "2026-09-04T12:00:00Z" },
    { user: { id: "user-devon", email: "devon@acme.test", displayName: "Devon Park" }, role: "MEMBER", joinedAt: "2026-09-12T12:00:00Z" },
    { user: { id: "user-priya", email: "priya@acme.test", displayName: "Priya Nair" }, role: "MEMBER", joinedAt: "2026-09-30T12:00:00Z" },
  ];
}

interface FixtureWorkspace {
  id: string;
  name: string;
  publicPages: boolean;
  reportingTimeZone: string;
  createdAt: string;
}

function initialWorkspaces(state: OrganizationFixtureState): FixtureWorkspace[] {
  if (state === "empty") return [];
  if (state === "overflow") {
    return Array.from({ length: 13 }, (_, index) => ({
      id: `ws-long-${index}`,
      name: `International Revenue Analytics and Customer Success Operations ${index + 1}`,
      publicPages: false, reportingTimeZone: "UTC",
      createdAt: "2026-09-01T12:00:00Z",
    }));
  }
  return [
    { id: "ws-organization-revenue", name: "Revenue Analytics", publicPages: false, reportingTimeZone: "UTC", createdAt: "2026-09-01T12:00:00Z" },
    { id: "ws-organization-finance", name: "Finance", publicPages: false, reportingTimeZone: "UTC", createdAt: "2026-09-03T12:00:00Z" },
    { id: "ws-organization-growth", name: "Growth", publicPages: true, reportingTimeZone: "UTC", createdAt: "2026-09-08T12:00:00Z" },
    { id: "ws-organization-ops", name: "Ops", publicPages: false, reportingTimeZone: "UTC", createdAt: "2026-09-20T12:00:00Z" },
  ];
}

function initialRoles(state: OrganizationFixtureState): Record<string, Record<string, WorkspaceRole>> {
  if (state === "overflow") {
    return Object.fromEntries(Array.from({ length: 13 }, (_, index) => [`ws-long-${index}`, { "user-jordan": "OWNER" as const }]));
  }
  return {
    "ws-organization-revenue": { "user-jordan": "OWNER", "user-devon": "EXPLORER" },
    "ws-organization-finance": { "user-mara": "OWNER", "user-jordan": "EDITOR" },
    "ws-organization-growth": { "user-mara": "OWNER", "user-devon": "EDITOR" },
    "ws-organization-ops": {},
  };
}

function initialInvitations(now: number): OrganizationInvitation[] {
  return [
    {
      id: "invitation-maria",
      organizationId: FIXTURE_ORGANIZATION.id,
      deliveryEmail: "maria@acme.test",
      invitedBy: FIXTURE_VIEWER,
      createdAt: new Date(now - 2 * DAY).toISOString(),
      expiresAt: new Date(now + 5.5 * DAY).toISOString(),
      lastDeliveryAttemptAt: new Date(now - 2 * DAY).toISOString(),
      lastSentAt: new Date(now - 2 * DAY).toISOString(),
      status: "PENDING",
      workspaceAccess: [],
    },
    {
      id: "invitation-contractor",
      organizationId: FIXTURE_ORGANIZATION.id,
      deliveryEmail: "devon@contractor.test",
      invitedBy: { id: "user-mara", email: "mara@acme.test", displayName: "Mara Chen" },
      createdAt: new Date(now - DAY).toISOString(),
      expiresAt: new Date(now + 6.5 * DAY).toISOString(),
      lastDeliveryAttemptAt: new Date(now - DAY).toISOString(),
      lastSentAt: null,
      status: "PENDING",
      workspaceAccess: [],
    },
  ];
}

export function fixtureMembership(displayName: string): OrganizationMembership {
  return { organization: { ...FIXTURE_ORGANIZATION, displayName }, role: "ADMIN" };
}

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

export function installOrganizationFixtureFetch(state: OrganizationFixtureState, invitationsEnabled: boolean): () => void {
  const realFetch = window.fetch.bind(window);
  let organizationName = FIXTURE_ORGANIZATION.displayName;
  let members = initialMembers();
  let workspaces = initialWorkspaces(state);
  const roles = initialRoles(state);
  let invitations = invitationsEnabled ? initialInvitations(Date.now()) : [];

  const organizationMembers = (): OrganizationMember[] => members.map((member) => ({
    user: member.user,
    role: member.role,
    joinedAt: member.joinedAt,
    workspaces: workspaces
      .filter((workspace) => roles[workspace.id]?.[member.user.id])
      .map((workspace) => {
        const workspaceRoles = Object.values(roles[workspace.id] ?? {});
        return {
          id: workspace.id,
          name: workspace.name,
          role: roles[workspace.id][member.user.id],
          memberCount: workspaceRoles.length,
          ownerCount: workspaceRoles.filter((role) => role === "OWNER").length,
        };
      }),
  }));
  const managedWorkspaces = (): ManagedWorkspace[] => workspaces.map((workspace) => ({
    ...workspace,
    organizationId: FIXTURE_ORGANIZATION.id,
    memberCount: Object.keys(roles[workspace.id] ?? {}).length,
  }));
  const dropWorkspaceRoles = (userId: string) => {
    for (const workspaceRoles of Object.values(roles)) delete workspaceRoles[userId];
  };

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    const body = typeof init?.body === "string" ? init.body : "";
    if (!url.includes("/v2/graphql") || !body) return realFetch(input, init);
    const operation = operationName(body);
    const variables = variablesOf(body);
    const json = (data: unknown) => new Response(JSON.stringify({ data }), { status: 200, headers: { "Content-Type": "application/json" } });
    const failure = (message: string, status: number) =>
      new Response(JSON.stringify({ errors: [{ message }] }), { status, headers: { "Content-Type": "application/json" } });

    switch (operation) {
      case "Me":
        return json({ me: { user: FIXTURE_VIEWER, organizationMemberships: [fixtureMembership(organizationName)] } });
      case "UpdateOrganizationDisplayName":
        organizationName = String(variables.displayName);
        return json({ updateOrganizationDisplayName: { id: FIXTURE_ORGANIZATION.id, displayName: organizationName } });
      case "OrganizationUsage":
        return json({
          organizationUsage: {
            limits: state === "unlimited" ? null : { members: 30, workspaces: 5, pendingInvitations: 30 },
            usage: {
              members: members.length,
              workspaces: workspaces.length,
              pendingInvitations: invitations.length,
            },
          },
        });
      case "ManagedWorkspaces":
        if (state === "loading") return new Promise<Response>(() => {});
        if (state === "error") return failure("Workspaces unavailable", 503);
        return json({ managedWorkspaces: managedWorkspaces() });
      case "AccessibleWorkspaces":
        return json({
          accessibleWorkspaces: managedWorkspaces()
            .filter((workspace) => roles[workspace.id]?.[FIXTURE_VIEWER.id])
            .map(({ id, organizationId, name, publicPages }) => ({ id, organizationId, name, publicPages, role: roles[id][FIXTURE_VIEWER.id] })),
        });
      case "OrganizationMembers":
        return json({ organizationMembers: organizationMembers() });
      case "WorkspaceOwnerCandidates":
        return json({
          workspaceOwnerCandidates: members
            .filter((member) => roles[String(variables.workspaceId)]?.[member.user.id] !== "OWNER")
            .map((member) => member.user),
        });
      case "SetWorkspaceMemberRole": {
        const workspaceId = String(variables.workspaceId);
        roles[workspaceId] = { ...roles[workspaceId], [String(variables.userId)]: variables.role as WorkspaceRole };
        return json({ setWorkspaceMemberRole: true });
      }
      case "RemoveWorkspaceMember": {
        const { [String(variables.principalId)]: _removed, ...remaining } = roles[String(variables.workspaceId)] ?? {};
        roles[String(variables.workspaceId)] = remaining;
        return json({ removeWorkspaceMember: true });
      }
      case "CreateWorkspace": {
        const workspace = { id: `ws-new-${workspaces.length}`, name: String(variables.displayName), publicPages: false, reportingTimeZone: "UTC", createdAt: "2026-10-01T12:00:00Z" };
        workspaces = [...workspaces, workspace];
        roles[workspace.id] = { [String(variables.initialOwnerUserId)]: "OWNER" };
        return json({ createWorkspace: { ...workspace, organizationId: FIXTURE_ORGANIZATION.id } });
      }
      case "RenameWorkspace":
        workspaces = workspaces.map((workspace) => workspace.id === variables.workspaceId ? { ...workspace, name: String(variables.displayName) } : workspace);
        return json({ renameWorkspace: managedWorkspaces().find((workspace) => workspace.id === variables.workspaceId) });
      case "UpdateOrganizationMemberRole":
        members = members.map((member) => member.user.id === variables.userId ? { ...member, role: variables.role === "ADMIN" ? "ADMIN" : "MEMBER" } : member);
        return json({ updateOrganizationMemberRole: { organizationId: FIXTURE_ORGANIZATION.id, userId: variables.userId, role: variables.role } });
      case "RemoveOrganizationMember":
        members = members.filter((member) => member.user.id !== variables.userId);
        dropWorkspaceRoles(String(variables.userId));
        return json({ removeOrganizationMember: true });
      case "OrganizationMembershipInvitations":
        return json({ organizationMembershipInvitations: invitations });
      case "createOrganizationMembershipInvitation": {
        const email = String(variables.email).trim();
        const requestedAccess = Array.isArray(variables.workspaceAccess) ? variables.workspaceAccess as { workspaceId: string; role: WorkspaceRole }[] : [];
        const member = members.find((candidate) => candidate.user.email.toLowerCase() === email.toLowerCase());
        if (member) {
          return json({ createOrganizationMembershipInvitation: { outcome: "ALREADY_MEMBER", invitation: null, retryAfter: null } });
        }
        const existing = invitations.find((invitation) => invitation.deliveryEmail.toLowerCase() === email.toLowerCase());
        const now = Date.now();
        const invitation: OrganizationInvitation = existing ?? {
          id: `invitation-${email}`,
          organizationId: FIXTURE_ORGANIZATION.id,
          deliveryEmail: email,
          invitedBy: FIXTURE_VIEWER,
          createdAt: new Date(now).toISOString(),
          expiresAt: new Date(now + 7 * DAY).toISOString(),
          lastDeliveryAttemptAt: new Date(now).toISOString(),
          lastSentAt: email.startsWith("fail@") ? null : new Date(now).toISOString(),
          status: "PENDING",
          workspaceAccess: requestedAccess.map((grant) => ({
            workspaceId: grant.workspaceId,
            workspaceDisplayName: workspaces.find((workspace) => workspace.id === grant.workspaceId)?.name ?? "Workspace",
            role: grant.role,
          })),
        };
        if (!existing) invitations = [...invitations, invitation];
        return json({ createOrganizationMembershipInvitation: { outcome: existing ? "INVITATION_PENDING" : invitation.lastSentAt ? "SENT" : "DELIVERY_FAILED", invitation, retryAfter: null } });
      }
      case "resendOrganizationMembershipInvitation":
        return json({ resendOrganizationMembershipInvitation: { outcome: "RATE_LIMITED", invitation: invitations.find((invitation) => invitation.id === variables.invitationId), retryAfter: 60 } });
      case "cancelOrganizationMembershipInvitation":
        invitations = invitations.filter((invitation) => invitation.id !== variables.invitationId);
        return json({ cancelOrganizationMembershipInvitation: { outcome: "CANCELLED", invitation: null, retryAfter: null } });
      default:
        return failure(`Unhandled fixture GraphQL operation: ${operation}`, 400);
    }
  };
  return () => {
    window.fetch = realFetch;
  };
}
