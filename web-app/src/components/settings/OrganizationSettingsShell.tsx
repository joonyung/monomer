import type { ReactNode } from "react";
import { useManagedWorkspacesQuery, useOrganizationRouteContext } from "@/components/auth/TenantRouteContext";
import { SettingsShell } from "./SettingsShell";
import { useOrganizationMembersQuery } from "./organizationQueries";
import { organizationSettingsNavigation } from "./organizationSettingsNavigation";
import type { OrganizationSettingsSection } from "./organizationSettingsModel";

export function OrganizationSettingsShell({
  section,
  title,
  actions,
  children,
}: {
  section: OrganizationSettingsSection;
  title: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const { organization, organizationId } = useOrganizationRouteContext();
  const members = useOrganizationMembersQuery(organizationId).data;
  const workspaces = useManagedWorkspacesQuery(organizationId).data;
  const navigation = organizationSettingsNavigation({
    organization,
    section,
    memberCount: members?.length,
    workspaceCount: workspaces?.length,
  });
  return (
    <SettingsShell navigation={navigation} kicker={organization.displayName} title={title} actions={actions}>
      {children}
    </SettingsShell>
  );
}
