import type { ReactNode } from "react";
import { Globe, Lock, Settings2, UsersRound } from "lucide-react";
import { StateBlock } from "@/components/ui/StateBlock";
import { webPaths } from "@/lib/web-paths";
import { ReportingTimeZoneField } from "./ReportingTimeZoneField";
import { SettingsShell } from "./SettingsShell";
import type { SettingsNavigationModel } from "./settingsNavigation";
import {
  useWorkspaceOwnersQuery,
  useWorkspaceSettingsContext,
  useWorkspaceSummaryQuery,
  type WorkspaceSettingsContext,
} from "./workspaceSettingsQueries";
import { ownersLine, publicAccessStatus, type WorkspaceSettingsSection } from "./workspaceSettingsModel";

export const WORKSPACE_SETTINGS_NAVIGATION_LABEL = "Workspace settings";

export function workspaceSettingsNavigation(
  context: Pick<WorkspaceSettingsContext, "workspaceId" | "workspaceName" | "organizationName" | "publicPages">,
  section: WorkspaceSettingsSection,
  memberCount: number | undefined,
): SettingsNavigationModel {
  return {
    label: WORKSPACE_SETTINGS_NAVIGATION_LABEL,
    groupLabel: "Workspace settings",
    testIdPrefix: "workspace-settings",
    identity: { kind: "workspace", name: context.workspaceName, meta: `Workspace · ${context.organizationName}` },
    activeId: section,
    items: [
      { id: "general", label: "General", href: webPaths.workspaceSettings(context.workspaceId), icon: Settings2 },
      { id: "members", label: "Members", href: webPaths.workspaceMembers(context.workspaceId), icon: UsersRound, count: memberCount },
      {
        id: "public-access",
        label: "Public access",
        href: webPaths.workspacePublicAccess(context.workspaceId),
        icon: Globe,
        status: publicAccessStatus(context.publicPages),
      },
    ],
  };
}

// Workspace settings pages open for Owners and Organization Admins. Anyone else who reaches them
// sees who can make the change instead of the controls, and the Reporting time zone every member reads.
export function WorkspaceSettingsShell({
  section,
  title,
  actions,
  children,
}: {
  section: WorkspaceSettingsSection;
  title: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const context = useWorkspaceSettingsContext();
  const summary = useWorkspaceSummaryQuery(context.workspaceId);
  const navigation = workspaceSettingsNavigation(context, section, summary.data?.memberCount);
  const locked = !context.capabilities.canManage;
  return (
    <SettingsShell navigation={navigation} kicker={context.workspaceName} title={title} actions={locked ? undefined : actions}>
      {locked ? <WorkspaceSettingsLocked workspaceId={context.workspaceId} reportingTimeZone={context.reportingTimeZone} /> : children}
    </SettingsShell>
  );
}

export function WorkspaceSettingsLocked({ workspaceId, reportingTimeZone }: { workspaceId: string; reportingTimeZone: string }) {
  const owners = useWorkspaceOwnersQuery(workspaceId, true);
  return (
    <>
      <div className="border-y border-border">
        <StateBlock
          kind="empty"
          layout="inline"
          icon={Lock}
          title="Only workspace owners can change these settings."
          description={owners.data ? ownersLine(owners.data) ?? undefined : undefined}
        />
      </div>
      <dl className="mt-6 border-y border-border">
        <ReportingTimeZoneField timeZone={reportingTimeZone} />
      </dl>
    </>
  );
}
