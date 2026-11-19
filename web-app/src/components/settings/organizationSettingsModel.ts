import type { InvitationWorkspaceAccess, OrganizationInvitation } from "@/lib/auth/invitations";
import { roleLabel } from "@/lib/auth/roles";
import type {
  OrganizationMember,
  OrganizationMemberWorkspace,
  OrganizationResourceCounts,
} from "@/lib/auth/types";

export type OrganizationSettingsSection = "general" | "members" | "workspaces";
export type MemberFilter = "all" | "admins";

const DAY_MILLISECONDS = 86_400_000;
const USAGE_WARNING_RATIO = 0.8;

export function formatSettingsDate(value: string): string {
  return new Date(value).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

export function nameInitials(name: string): string {
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part[0])
    .slice(0, 2)
    .join("")
    .toUpperCase();
  return initials || "?";
}

export function firstName(name: string): string {
  return name.trim().split(/\s+/)[0] || name;
}

export function planLabel(plan: string): string {
  return `${plan.charAt(0).toUpperCase()}${plan.slice(1).toLowerCase()} plan`;
}

export interface UsageCounterDefinition {
  key: keyof OrganizationResourceCounts;
  label: string;
  singular: string;
  plural: string;
}

export const USAGE_COUNTERS: UsageCounterDefinition[] = [
  { key: "members", label: "Members", singular: "member", plural: "members" },
  { key: "workspaces", label: "Workspaces", singular: "workspace", plural: "workspaces" },
  { key: "pendingInvitations", label: "Pending invitations", singular: "invitation", plural: "invitations" },
];

// A counter warns once it reaches 80% of its limit, naming what is left rather than a percentage.
export function usageWarning(
  used: number,
  limit: number | null,
  counter: Pick<UsageCounterDefinition, "singular" | "plural">,
): string | null {
  if (limit === null || limit <= 0 || used / limit < USAGE_WARNING_RATIO) return null;
  const remaining = Math.max(0, limit - used);
  if (remaining === 0) return "Limit reached";
  return `${remaining} ${remaining === 1 ? counter.singular : counter.plural} left`;
}

export function usageRatio(used: number, limit: number): number {
  return limit <= 0 ? 1 : Math.min(1, Math.max(0, used / limit));
}

export function memberFilterCounts(members: OrganizationMember[]): Record<MemberFilter, number> {
  return {
    all: members.length,
    admins: members.filter((member) => member.role === "ADMIN").length,
  };
}

export function filterMembers(
  members: OrganizationMember[],
  search: string,
  filter: MemberFilter,
): OrganizationMember[] {
  const needle = search.trim().toLowerCase();
  return members.filter((member) => {
    if (filter === "admins" && member.role !== "ADMIN") return false;
    return `${member.user.displayName} ${member.user.email}`.toLowerCase().includes(needle);
  });
}

export function workspaceAccessSummary(
  workspaces: Pick<OrganizationMemberWorkspace, "name">[],
  visibleCount: number,
): { names: string; overflow: number } | null {
  if (workspaces.length === 0) return null;
  const names = [...workspaces].map((workspace) => workspace.name).sort((left, right) => left.localeCompare(right));
  return {
    names: names.slice(0, visibleCount).join(", "),
    overflow: Math.max(0, names.length - visibleCount),
  };
}

export function joinNames(names: string[]): string {
  if (names.length <= 1) return names.join("");
  if (names.length === 2) return `${names[0]} and ${names[1]}`;
  return `${names.slice(0, -1).join(", ")}, and ${names[names.length - 1]}`;
}

export function invitationExpiryLabel(
  invitation: Pick<OrganizationInvitation, "expiresAt" | "status">,
  now: Date,
): string {
  const remaining = new Date(invitation.expiresAt).getTime() - now.getTime();
  if (invitation.status === "EXPIRED" || remaining <= 0) return "Expired";
  const days = Math.floor(remaining / DAY_MILLISECONDS);
  if (days === 0) return "Expires today";
  return `Expires in ${days} ${days === 1 ? "day" : "days"}`;
}

export function invitationDeliveryFailed(invitation: Pick<OrganizationInvitation, "lastSentAt">): boolean {
  return invitation.lastSentAt === null;
}

export function isLastAdmin(member: OrganizationMember, members: OrganizationMember[]): boolean {
  return (
    member.role === "ADMIN" &&
    members.filter((candidate) => candidate.role === "ADMIN").length === 1
  );
}

export function soleOwnedWorkspaces(member: OrganizationMember): OrganizationMemberWorkspace[] {
  return member.workspaces.filter((workspace) => workspace.role === "OWNER" && workspace.ownerCount === 1);
}

export interface MemberRemovalBlock {
  reason: string;
  soleOwnerWorkspaces: OrganizationMemberWorkspace[];
}

// Removal cannot target yourself, the last Admin, or the only Owner of a workspace.
export function memberRemovalBlock(
  member: OrganizationMember,
  members: OrganizationMember[],
  isSelf: boolean,
): MemberRemovalBlock | null {
  if (isSelf) {
    return { reason: "You can't remove yourself. Ask another Admin.", soleOwnerWorkspaces: [] };
  }
  if (isLastAdmin(member, members)) {
    return {
      reason: `${firstName(member.user.displayName)} is the only Admin. Make another member an Admin first.`,
      soleOwnerWorkspaces: [],
    };
  }
  const owned = soleOwnedWorkspaces(member);
  if (owned.length > 0) {
    return {
      reason: `${firstName(member.user.displayName)} is the only owner of ${joinNames(owned.map((workspace) => workspace.name))}. Make someone else an owner first.`,
      soleOwnerWorkspaces: owned,
    };
  }
  return null;
}

export function organizationRoleBlock(
  member: OrganizationMember,
  members: OrganizationMember[],
  isSelf: boolean,
): string | null {
  if (isSelf) return "You can't change your own role. Ask another Admin.";
  if (isLastAdmin(member, members)) {
    return `${firstName(member.user.displayName)} is the only Admin. Make another member an Admin first.`;
  }
  return null;
}

// Removal ends the person's Organization access. Their app connections are user-scoped, so they
// remain but can no longer reach this Organization.
export function memberAccessConsequences(member: OrganizationMember, organizationName: string): string[] {
  const name = firstName(member.user.displayName);
  const workspaceNames = [...member.workspaces].map((workspace) => workspace.name).sort((left, right) => left.localeCompare(right));
  return [
    ...(workspaceNames.length > 0 ? [`Loses access to ${joinNames(workspaceNames)}`] : []),
    `Apps ${name} connected can no longer reach ${organizationName}.`,
  ];
}

export function workspaceOwners(
  workspaceId: string,
  members: OrganizationMember[],
): OrganizationMember[] {
  return members.filter((member) =>
    member.workspaces.some((workspace) => workspace.id === workspaceId && workspace.role === "OWNER"),
  );
}

// "Workspace access: Revenue Analytics · Explorer, Product · Viewer" for an invitation's attached access.
export function invitationWorkspaceAccessLabel(access: readonly Pick<InvitationWorkspaceAccess, "workspaceDisplayName" | "role">[]): string {
  return `Workspace access: ${access.map((grant) => `${grant.workspaceDisplayName} · ${roleLabel(grant.role)}`).join(", ")}`;
}
