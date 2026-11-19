import type { ReactNode } from "react";
import { Building2, ShieldCheck, SquareTerminal, UserRound } from "lucide-react";
import { SettingsShell } from "@/components/settings/SettingsShell";
import type { SettingsNavigationModel } from "@/components/settings/settingsNavigation";
import { Button } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import { useAuthStore } from "@/lib/auth/store";
import type { AuthUser } from "@/lib/auth/types";
import { webPaths } from "@/lib/web-paths";
import { useAgentConnections } from "./use-agent-connections";
import { useSettingsMe } from "./use-settings-me";
import { groupConnections, partitionConnections, type UserSettingsSection } from "./userSettingsModel";

export const USER_SETTINGS_NAVIGATION_LABEL = "Your settings";

export function userSettingsNavigation(
  user: AuthUser,
  section: UserSettingsSection,
  organizationCount: number | undefined,
  activeAgentCount: number | undefined,
): SettingsNavigationModel {
  return {
    label: USER_SETTINGS_NAVIGATION_LABEL,
    groupLabel: "Your settings",
    testIdPrefix: "user-settings",
    identity: { kind: "user", name: user.displayName, meta: user.email },
    activeId: section,
    items: [
      { id: "profile", label: "Profile", href: webPaths.userProfile, icon: UserRound },
      { id: "organizations", label: "Organizations", href: webPaths.userOrganizations, icon: Building2, count: organizationCount },
      { id: "agents", label: "Agents", href: webPaths.userAgents, icon: SquareTerminal, count: activeAgentCount },
      { id: "security", label: "Security", href: webPaths.userSecurity, icon: ShieldCheck },
    ],
  };
}

// Personal settings sit outside any Organization or workspace. Each page renders inside this shell
// once the signed-in user's profile has loaded for the current session.
export function UserSettingsShell({ section, title, children }: { section: UserSettingsSection; title: string; children: ReactNode }) {
  const user = useAuthStore((state) => state.user);
  const epoch = useAuthStore((state) => state.authEpoch);
  const organizationCount = useAuthStore((state) => state.organizationMemberships.length);
  const me = useSettingsMe();
  const connections = useAgentConnections();
  if (!user) return null;
  // The count matches the rows on Agents: one per app and target.
  const activeAgentCount = connections.data ? groupConnections(partitionConnections(connections.data, Date.now()).active).length : undefined;
  const navigation = userSettingsNavigation(user, section, organizationCount, activeAgentCount);
  return (
    <SettingsShell navigation={navigation} kicker="Your settings" title={title}>
      {me.isError ? (
        <StateBlock
          kind="error"
          layout="inline"
          title="Couldn't load your profile."
          action={<Button size="sm" variant="secondary" onClick={() => void me.refetch()}>Try again</Button>}
        />
      ) : !me.data ? (
        <StateBlock kind="loading" layout="inline" title="Loading your profile…" />
      ) : (
        <div key={epoch}>{children}</div>
      )}
    </SettingsShell>
  );
}
