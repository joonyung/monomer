import { ArrowLeft, ChevronLeft } from "lucide-react";
import { Link } from "@/lib/navigation";
import { useAuthStore } from "@/lib/auth/store";
import { webPaths } from "@/lib/web-paths";
import { useAccessibleWorkspacesQuery } from "@/components/auth/TenantRouteContext";
import { nameInitials } from "@/components/settings/organizationSettingsModel";
import type { SettingsNavigationItem, SettingsNavigationModel } from "@/components/settings/settingsNavigation";
import { SidebarTooltip } from "./SidebarTooltip";

// Settings leave the workspace shell, so the way back names the last workspace the person opened.
function useSettingsReturnTarget(): { href: string; label: string } {
  const lastWorkspaceId = useAuthStore((state) => state.lastAccessibleWorkspaceId);
  const workspaces = useAccessibleWorkspacesQuery().data ?? [];
  const lastWorkspace = workspaces.find((workspace) => workspace.id === lastWorkspaceId);
  return lastWorkspace
    ? { href: webPaths.workspace(lastWorkspace.id), label: `Back to ${lastWorkspace.name}` }
    : { href: webPaths.workspaces, label: "All workspaces" };
}

export function SettingsBackLink({ navigation }: { navigation: SettingsNavigationModel }) {
  const target = useSettingsReturnTarget();
  return (
    <Link
      href={target.href}
      data-testid={`${navigation.testIdPrefix}-back`}
      className="flex min-w-0 flex-1 items-center gap-1.5 rounded-[4px] px-1 py-2 type-control text-foreground-secondary transition-colors hover:text-foreground"
    >
      <ChevronLeft className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
      <span className="min-w-0 truncate">{target.label}</span>
    </Link>
  );
}

// Only a person gets a mark; Organizations and Workspaces are identified by name alone.
function IdentityMark({ navigation }: { navigation: SettingsNavigationModel }) {
  if (navigation.identity.kind !== "user") return null;
  return (
    <span aria-hidden="true" className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-accent type-status text-accent-foreground">
      {nameInitials(navigation.identity.name)}
    </span>
  );
}

function ItemTrailing({ item }: { item: SettingsNavigationItem }) {
  if (item.count !== undefined) {
    return <span className="type-status tabular-nums text-foreground-secondary">{item.count}</span>;
  }
  if (item.status !== undefined) {
    return <span className="type-status text-foreground-secondary">{item.status}</span>;
  }
  return null;
}

export function SettingsNavigation({ navigation }: { navigation: SettingsNavigationModel }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col" data-testid="sidebar-navigation-context">
      <div
        className="flex flex-shrink-0 items-center gap-3 border-b border-border px-4 py-4"
        data-testid={`${navigation.testIdPrefix}-identity`}
      >
        <IdentityMark navigation={navigation} />
        <span className="min-w-0">
          <span className="block truncate type-control text-heading">{navigation.identity.name}</span>
          <span className="mt-1 block truncate type-meta text-foreground-secondary">{navigation.identity.meta}</span>
        </span>
      </div>
      <nav aria-label={navigation.label} className="flex-1 overflow-y-auto py-4">
        <div className="px-3">
          <div className="mb-1.5 px-2 type-group text-label">{navigation.groupLabel}</div>
          <ul className="space-y-px">
            {navigation.items.map((item) => {
              const active = item.id === navigation.activeId;
              return (
                <li key={item.id}>
                  <Link
                    href={item.href}
                    aria-current={active ? "page" : undefined}
                    data-testid={`${navigation.testIdPrefix}-nav-${item.id}`}
                    className={`relative flex items-center gap-2.5 rounded-sm px-2 py-1.5 transition-colors ${
                      active ? "bg-surface text-foreground" : "text-foreground-secondary hover:bg-surface/60 hover:text-foreground"
                    }`}
                  >
                    {active ? (
                      <span aria-hidden="true" className="absolute left-0 top-1/2 h-4 w-[2px] -translate-y-1/2 rounded-r-sm bg-accent-marker" />
                    ) : null}
                    <span className="flex-1 type-control">{item.label}</span>
                    <ItemTrailing item={item} />
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      </nav>
    </div>
  );
}

export function CollapsedSettingsNavigation({ navigation }: { navigation: SettingsNavigationModel }) {
  const target = useSettingsReturnTarget();
  const railItem = "group relative flex h-9 w-9 items-center justify-center rounded-[4px] transition-colors";
  return (
    <nav className="flex min-h-0 flex-1 flex-col items-center gap-1 overflow-visible py-3" aria-label={navigation.label}>
      <Link
        href={target.href}
        aria-label={target.label}
        aria-describedby="sidebar-settings-back-tooltip"
        className={`${railItem} mb-2 text-foreground-secondary hover:bg-surface/60 hover:text-foreground`}
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        <SidebarTooltip id="sidebar-settings-back-tooltip" label={target.label} />
      </Link>
      {navigation.items.map((item) => {
        const Icon = item.icon;
        const active = item.id === navigation.activeId;
        const tooltipId = `sidebar-settings-tooltip-${item.id}`;
        return (
          <Link
            key={item.id}
            href={item.href}
            aria-label={item.label}
            aria-current={active ? "page" : undefined}
            aria-describedby={tooltipId}
            className={`${railItem} ${active ? "bg-surface text-foreground" : "text-foreground-secondary hover:bg-surface/60 hover:text-foreground"}`}
          >
            {active ? (
              <span aria-hidden="true" className="absolute left-0 top-1/2 h-4 w-[2px] -translate-y-1/2 rounded-r-sm bg-accent-marker" />
            ) : null}
            <Icon className="h-4 w-4" aria-hidden="true" />
            <SidebarTooltip id={tooltipId} label={item.label} />
          </Link>
        );
      })}
    </nav>
  );
}
