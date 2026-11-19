import { Building2, LayoutGrid, UsersRound } from "lucide-react";
import type { Organization } from "@/lib/auth/types";
import { webPaths } from "@/lib/web-paths";
import type { SettingsNavigationModel } from "./settingsNavigation";
import { planLabel, type OrganizationSettingsSection } from "./organizationSettingsModel";

export const ORGANIZATION_SETTINGS_NAVIGATION_LABEL = "Organization settings";

export function organizationSettingsNavigation({
  organization,
  section,
  memberCount,
  workspaceCount,
}: {
  organization: Organization;
  section: OrganizationSettingsSection;
  memberCount: number | undefined;
  workspaceCount: number | undefined;
}): SettingsNavigationModel {
  return {
    label: ORGANIZATION_SETTINGS_NAVIGATION_LABEL,
    groupLabel: "Organization",
    testIdPrefix: "organization-settings",
    identity: { kind: "organization", name: organization.displayName, meta: planLabel(organization.plan) },
    activeId: section,
    items: [
      { id: "general", label: "General", href: webPaths.organizationSettings(organization.id), icon: Building2 },
      { id: "members", label: "Members", href: webPaths.organizationMembers(organization.id), icon: UsersRound, count: memberCount },
      { id: "workspaces", label: "Workspaces", href: webPaths.organizationWorkspaces(organization.id), icon: LayoutGrid, count: workspaceCount },
    ],
  };
}
