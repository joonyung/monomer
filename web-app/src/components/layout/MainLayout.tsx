import React, { useCallback, useEffect, useRef, useState } from "react";
import { usePathname } from "@/lib/navigation";
import { Sidebar } from "./Sidebar";
import { CommandPalette } from "./CommandPalette";
import { MobileSidebar } from "./MobileSidebar";
import {
  applicationShellMode,
  type ApplicationShellMode,
  type ShellContextTransition,
} from "./shellMode";
import {
  PagesDirectoryProvider,
  type PagesDirectoryFixture,
} from "@/components/pages/PagesDirectoryProvider";
import {
  WorkspaceRouteFixtureProvider,
  useOptionalWorkspaceRouteContext,
} from "@/components/auth/TenantRouteContext";
import { useSettingsNavigation } from "@/components/settings/settingsNavigation";
import type { Workspace, WorkspaceAccess } from "@/lib/auth/types";
import { MonomerLogo } from "@/components/brand/MonomerLogo";
import { UserMenuButton } from "@/components/auth/UserMenu";
import { PagesContextBar } from "@/components/pages/PagesContextBar";
import { IconButton } from "@/components/ui/IconButton";
import { useAuthStore } from "@/lib/auth/store";
import { Search } from "lucide-react";
import { SampleWorkspaceBanner } from "@/components/workspaces/SampleWorkspaceBanner";

interface MainLayoutProps {
  children: React.ReactNode;
  pagesFixture?: PagesDirectoryFixture;
  workspaceFixture?: { workspace: Workspace; access: WorkspaceAccess };
}

// Reads the context inside the frame so fixture-provided workspaces are named too. Settings name
// their own subject, such as the Organization, because some of them sit outside any workspace.
function MobileWorkspaceName() {
  const workspaceContext = useOptionalWorkspaceRouteContext();
  const settingsNavigation = useSettingsNavigation();
  const name = settingsNavigation?.identity.name ?? workspaceContext?.workspace.name;
  if (!name) return null;
  return (
    <span
      className="min-w-0 truncate border-l border-border pl-2 type-body text-heading"
      data-testid="mobile-workspace-name"
      title={name}
    >
      {name}
    </span>
  );
}

function MemberPagesContextBar() {
  const workspaceContext = useOptionalWorkspaceRouteContext();
  return workspaceContext ? <PagesContextBar workspaceName={workspaceContext.workspace.name} /> : null;
}

// Search and the user menu on the right of the mobile top bar. Search needs a workspace to look in
// and stays out of settings, like the sidebar entry.
function MobileTopBarActions({ onOpenSearch }: { onOpenSearch: () => void }) {
  const workspaceContext = useOptionalWorkspaceRouteContext();
  const settingsNavigation = useSettingsNavigation();
  const user = useAuthStore((state) => state.user);
  return (
    <div className="ml-auto flex flex-shrink-0 items-center">
      {workspaceContext && !settingsNavigation ? <IconButton label="Search" icon={Search} size="md" onClick={onOpenSearch} /> : null}
      {user ? <UserMenuButton user={user} testIdPrefix="mobile-user-menu" switchWorkspace={false} /> : null}
    </div>
  );
}

export function MainLayout({ children, pagesFixture, workspaceFixture }: MainLayoutProps) {
  const [paletteOpen, setPaletteOpen] = useState(false);
  const openPalette = useCallback(() => setPaletteOpen(true), []);
  const pathname = usePathname();
  const workspaceContext = useOptionalWorkspaceRouteContext();
  const routeShellMode = applicationShellMode(pathname);
  const [shellMode, setShellMode] = useState(routeShellMode);
  const [shellTransition, setShellTransition] = useState<ShellContextTransition>(null);
  const previousPathname = useRef(pathname);

  const changeShellMode = useCallback((nextMode: ApplicationShellMode) => {
    if (nextMode === shellMode) return;
    setShellTransition(nextMode === "pages" ? "forward" : "backward");
    setShellMode(nextMode);
  }, [shellMode]);

  // Pages is a navigation context, not a route. Keep an explicit context
  // choice while the canvas URL stays put, but let a real route transition
  // establish the context for the destination (and for a deep link/full reload).
  useEffect(() => {
    if (previousPathname.current === pathname) return;
    previousPathname.current = pathname;
    setShellTransition(null);
    setShellMode(routeShellMode);
  }, [pathname, routeShellMode]);

  useEffect(() => {
    if (!shellTransition) return;
    const timeout = window.setTimeout(() => setShellTransition(null), 160);
    return () => window.clearTimeout(timeout);
  }, [shellTransition]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const isMod = e.metaKey || e.ctrlKey;
      if (isMod && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const frame = (
    <div className="flex h-screen bg-background">
      <Sidebar
        mode={shellMode}
        transition={shellTransition}
        onModeChange={changeShellMode}
        onOpenSearch={openPalette}
      />
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        {routeShellMode === "pages" ? <MemberPagesContextBar /> : null}
        <div className="flex h-12 flex-shrink-0 items-center gap-2 border-b border-border bg-background px-2 lg:hidden">
          <MobileSidebar
            mode={shellMode}
            transition={shellTransition}
            onModeChange={changeShellMode}
            onOpenSearch={openPalette}
          />
          <MonomerLogo compact className="h-[18px] w-auto flex-shrink-0" />
          <MobileWorkspaceName />
          {/* The top bar names the screen, so it follows the URL, not the drawer's sidebar context. */}
          {routeShellMode === "pages" ? (
            <span className="flex-shrink-0 border-l border-border pl-2 type-body text-foreground-secondary" data-testid="mobile-pages-label">
              Pages
            </span>
          ) : null}
          <MobileTopBarActions onOpenSearch={openPalette} />
        </div>
        <SampleWorkspaceBanner />
        <main className="min-h-0 flex-1 overflow-hidden">{children}</main>
      </div>
      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
      />
    </div>
  );

  const routeWorkspaceFixture = workspaceFixture ?? (pagesFixture
    ? {
        workspace: {
          id: pagesFixture.snapshot.workspaceId,
          organizationId: pagesFixture.workspaceAccess.organizationId ?? "pages-demo-organization",
          publicPages: false, reportingTimeZone: "UTC", name: "Pages workspace",
        },
        access: pagesFixture.workspaceAccess,
      }
    : undefined);
  // Keep the provider boundary stable while the sidebar context changes. It is
  // inactive outside Pages so the canvas and mobile drawer stay mounted without
  // loading a directory that is not visible.
  const contextualFrame = routeWorkspaceFixture || workspaceContext ? (
    <PagesDirectoryProvider
      fixture={pagesFixture}
      lifecycle={shellMode === "pages" ? "live" : "inactive"}
    >
      {frame}
    </PagesDirectoryProvider>
  ) : frame;

  return routeWorkspaceFixture ? (
    <WorkspaceRouteFixtureProvider
      workspace={routeWorkspaceFixture.workspace}
      access={routeWorkspaceFixture.access}
    >
      {contextualFrame}
    </WorkspaceRouteFixtureProvider>
  ) : contextualFrame;
}
