import timeZoneContract from "../../../../contracts/time-zones.json";
import { WORKSPACE_ROLES } from "@/lib/auth/roles";
import { timeZoneOffsetLabel } from "@/lib/time-zone";
import type {
  AuthUser,
  OrganizationMember,
  OrganizationMemberWorkspace,
  OrganizationRole,
  WorkspaceAuthorizationAssignments,
  WorkspaceOwner,
  WorkspaceRole,
} from "@/lib/auth/types";
import { firstName } from "./organizationSettingsModel";

export type WorkspaceSettingsSection = "general" | "members" | "public-access";
export type WorkspaceMemberFilter = "all" | "owners" | "editors" | "explorers" | "viewers";

export interface WorkspaceSettingsMember {
  user: AuthUser;
  role: WorkspaceRole;
  // Null when the roster source does not carry the person's Organization role.
  organizationRole: OrganizationRole | null;
}

export interface WorkspaceSettingsCapabilities {
  // Owners and Organization Admins manage the workspace name and members.
  canManage: boolean;
  // Publishing to the public is the data owner's decision, so only Owners change it.
  canEditPublicAccess: boolean;
  // Owners read the workspace roster; Admins who aren't Owners read it from Organization members.
  readsWorkspaceRoster: boolean;
}

export function canOpenWorkspaceSettings(role: WorkspaceRole | null, organizationRole: OrganizationRole | null): boolean {
  return role === "OWNER" || organizationRole === "ADMIN";
}

export function workspaceSettingsCapabilities(
  role: WorkspaceRole | null,
  organizationRole: OrganizationRole | null,
): WorkspaceSettingsCapabilities {
  return {
    canManage: canOpenWorkspaceSettings(role, organizationRole),
    canEditPublicAccess: role === "OWNER",
    readsWorkspaceRoster: role === "OWNER",
  };
}

const FILTER_ROLES: Record<Exclude<WorkspaceMemberFilter, "all">, WorkspaceRole> = {
  owners: "OWNER",
  editors: "EDITOR",
  explorers: "EXPLORER",
  viewers: "VIEWER",
};

export const WORKSPACE_MEMBER_FILTERS: { value: WorkspaceMemberFilter; label: string }[] = [
  { value: "all", label: "All" },
  { value: "owners", label: "Owners" },
  { value: "editors", label: "Editors" },
  { value: "explorers", label: "Explorers" },
  { value: "viewers", label: "Viewers" },
];

export function workspaceMemberFilterCounts(members: WorkspaceSettingsMember[]): Record<WorkspaceMemberFilter, number> {
  const count = (role: WorkspaceRole) => members.filter((member) => member.role === role).length;
  return {
    all: members.length,
    owners: count("OWNER"),
    editors: count("EDITOR"),
    explorers: count("EXPLORER"),
    viewers: count("VIEWER"),
  };
}

export function filterWorkspaceMembers(
  members: WorkspaceSettingsMember[],
  search: string,
  filter: WorkspaceMemberFilter,
): WorkspaceSettingsMember[] {
  const needle = search.trim().toLowerCase();
  return members.filter((member) => {
    if (filter !== "all" && member.role !== FILTER_ROLES[filter]) return false;
    return `${member.user.displayName} ${member.user.email}`.toLowerCase().includes(needle);
  });
}

export function workspaceMembersFromAssignments(
  assignments: WorkspaceAuthorizationAssignments,
  organizationMembers: OrganizationMember[] | undefined,
): WorkspaceSettingsMember[] {
  return assignments.members.map((member) => ({
    user: member.user ?? { id: member.principalId, email: "", displayName: member.displayName },
    role: member.role,
    organizationRole: organizationMembers?.find((candidate) => candidate.user.id === member.principalId)?.role ?? null,
  }));
}

export function workspaceMembersFromOrganization(
  organizationMembers: OrganizationMember[],
  workspaceId: string,
): WorkspaceSettingsMember[] {
  return organizationMembers.flatMap((member) => {
    const membership = member.workspaces.find((workspace) => workspace.id === workspaceId);
    return membership ? [{ user: member.user, role: membership.role, organizationRole: member.role }] : [];
  });
}

export function addableOrganizationMembers(
  organizationMembers: OrganizationMember[],
  members: WorkspaceSettingsMember[],
): OrganizationMember[] {
  return organizationMembers.filter((candidate) => !members.some((member) => member.user.id === candidate.user.id));
}

export function isSoleOwner(member: WorkspaceSettingsMember, members: WorkspaceSettingsMember[]): boolean {
  return member.role === "OWNER" && members.filter((candidate) => candidate.role === "OWNER").length === 1;
}

export function lastOwnerMessage(name: string, workspaceName: string): string {
  return `${firstName(name)} is the only owner of ${workspaceName}. Make someone else an owner first.`;
}

// Removing someone from one workspace cannot leave it without an owner. Admins leave a workspace from
// its own settings rather than from their Organization member sheet.
export function workspaceAccessRemovalBlock(
  memberName: string,
  workspace: Pick<OrganizationMemberWorkspace, "name" | "role" | "ownerCount">,
  isSelf: boolean,
): string | null {
  if (workspace.role === "OWNER" && workspace.ownerCount === 1) return lastOwnerMessage(memberName, workspace.name);
  if (isSelf) return `To leave ${workspace.name}, use its workspace settings.`;
  return null;
}

// Lowering your own Owner role takes away settings you are using, so it asks first. Other role
// changes apply at once because they can be changed back.
export function needsSelfDemotionConfirmation(member: WorkspaceSettingsMember, isSelf: boolean, role: WorkspaceRole): boolean {
  return isSelf && member.role === "OWNER" && role !== "OWNER";
}

export function selfDemotionMessage(otherOwnerName: string | null, organizationAdmin: boolean): string {
  const consequence = organizationAdmin
    ? "You'll lose access to Connectors and to Public access."
    : "You'll lose access to these settings and to Connectors.";
  return otherOwnerName ? `${consequence} Another owner (${otherOwnerName}) can change it back.` : consequence;
}

export function otherOwnerName(members: WorkspaceSettingsMember[], userId: string): string | null {
  return members.find((member) => member.role === "OWNER" && member.user.id !== userId)?.user.displayName ?? null;
}

export function publisherCount(members: Pick<WorkspaceSettingsMember, "role">[]): number {
  const explorer = WORKSPACE_ROLES.indexOf("EXPLORER");
  return members.filter((member) => WORKSPACE_ROLES.indexOf(member.role) >= explorer).length;
}

export function publisherWarning(count: number): string {
  return `While on, anyone with Explorer or higher can publish by adding to Pages. ${count} ${count === 1 ? "person" : "people"} can publish now. Visitors can't edit anything.`;
}

export function ownersLine(owners: WorkspaceOwner[]): string | null {
  if (owners.length === 0) return null;
  return `${owners.length === 1 ? "Owner" : "Owners"}: ${owners.map((owner) => owner.displayName).join(", ")}`;
}

export function publicAccessStatus(publicPages: boolean): string {
  return publicPages ? "On" : "Off";
}

// The zones Control accepts for a Reporting time zone; the server validates against the same list.
export const REPORTING_TIME_ZONES: readonly string[] = timeZoneContract.timeZones;

export interface ReportingTimeZoneOption {
  value: string;
  label: string;
}

/** "Asia/Seoul · GMT+9": the zone and its offset at `at`, or the zone alone when the browser lacks its data. */
export function reportingTimeZoneLabel(timeZone: string, at: Date): string {
  const offset = timeZoneOffsetLabel(timeZone, at);
  return offset ? `${timeZone} · ${offset}` : timeZone;
}

export function reportingTimeZoneOptions(at: Date): ReportingTimeZoneOption[] {
  return REPORTING_TIME_ZONES.map((value) => ({ value, label: reportingTimeZoneLabel(value, at) }));
}

/** Options whose zone or offset contains every typed word; "new york" finds America/New_York. */
export function filterReportingTimeZoneOptions(options: readonly ReportingTimeZoneOption[], search: string): ReportingTimeZoneOption[] {
  const words = search.trim().toLowerCase().split(/[\s_/]+/).filter(Boolean);
  if (words.length === 0) return [...options];
  return options.filter((option) => {
    const text = option.label.toLowerCase().replaceAll("_", " ");
    return words.every((word) => text.includes(word));
  });
}

export const REPORTING_TIME_ZONE_CHANGE_NOTICE = "New results group days and filter times in this zone. Existing results keep their original zone.";
