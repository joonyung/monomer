import { CreateOrganizationModal } from "@/components/settings/CreateOrganizationModal";
import { useEffect, useState, useRef, type Ref } from "react";
import { Link } from "@/lib/navigation";
import { useRouter, usePathname } from "@/lib/navigation";
import {
  Home,
  Database,
  Layers,
  LayoutDashboard,
  Compass,
  ChevronLeft,
  ChevronRight,
  ChevronsUpDown,
  CircleHelp,
  Plus,
  Search,
  Settings,
  Library,
  Plug,
  History,
  LayoutGrid,
} from "lucide-react";
import { useAuthStore } from "@/lib/auth/store";
import { UserAvatar, UserMenuEntries } from "@/components/auth/UserMenu";
import { roleLabel } from "@/lib/auth/roles";
import { helpDocsUrlForPath } from "@/lib/public-docs";
import { Input } from "@/components/ui/Input";
import {
  WORKSPACE_SEARCH_THRESHOLD,
  filterWorkspaceGroups,
  groupWorkspacesByOrganization,
  type OrganizationWorkspaceGroup,
} from "@/components/workspaces/workspaceDirectory";
import {
  useAccessibleWorkspacesQuery,
  useOptionalOrganizationRouteContext,
  useOptionalWorkspaceRouteContext,
} from "@/components/auth/TenantRouteContext";
import { webPaths, workspaceSwitchPath } from "@/lib/web-paths";
import { PagesNavigation } from "@/components/pages/PagesNavigation";
import {
  shellNavigationLabel,
  type ApplicationShellMode,
  type ShellContextTransition,
} from "@/components/layout/shellMode";
import { useSidebarUiStore } from "@/components/layout/sidebarUiState";
import styles from "./Sidebar.module.css";
import { SidebarTooltip } from "./SidebarTooltip";
import {
  CollapsedSettingsNavigation,
  SettingsBackLink,
  SettingsNavigation,
} from "./SettingsNavigation";
import { useSettingsNavigation } from "@/components/settings/settingsNavigation";
import { canOpenWorkspaceSettings } from "@/components/settings/workspaceSettingsModel";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import {
  Menu,
  MenuContent,
  MenuGroup,
  MenuItem,
  MenuLabel,
  MenuRadioGroup,
  MenuRadioItem,
  MenuSeparator,
  MenuTrigger,
} from "@/components/ui/Menu";

interface LinkNavItem {
  name: string;
  href: string;
  icon: React.ComponentType<{ className?: string }>;
  // Pages switches only the sidebar to the Pages directory; the canvas and URL stay. Its href
  // still marks it as the current destination on Pages URLs.
  switchesToPagesContext?: boolean;
  // Opens outside the application, such as the public Help docs.
  external?: boolean;
  // Route subtrees this destination owns. Empty means the destination owns only
  // itself — the Workspace index owns no subtree even though every other
  // Workspace route is nested below its path.
  ownedPathPrefixes: string[];
}

interface NavGroup {
  label?: string;
  items: LinkNavItem[];
}

function matchedPathSpecificity(item: LinkNavItem, pathname: string): number {
  const exactMatchLength = pathname === item.href ? item.href.length : 0;
  return item.ownedPathPrefixes.reduce((longestMatch, pathPrefix) => {
    const owns = pathname === pathPrefix || pathname.startsWith(`${pathPrefix}/`);
    return owns ? Math.max(longestMatch, pathPrefix.length) : longestMatch;
  }, exactMatchLength);
}

// Semantics owns several resource subtrees, so one pathname can match several
// destinations. The most specific
// match — the longest owned prefix, or an exact hit on the destination itself —
// is the one the viewer is actually on. A nested route that no destination
// owns, such as an unsupported nested detail, leaves the whole nav inactive rather than
// falling back to the Workspace index.
function activeNavigationHref(navGroups: NavGroup[], pathname: string): string | null {
  return navGroups
    .flatMap((group) => group.items)
    .reduce<{ href: string | null; specificity: number }>(
      (bestMatch, item) => {
        const specificity = matchedPathSpecificity(item, pathname);
        return specificity > bestMatch.specificity ? { href: item.href, specificity } : bestMatch;
      },
      { href: null, specificity: 0 },
    ).href;
}

function workspaceNavigationGroups(workspaceId: string): NavGroup[] {
  return [
    {
      items: [
        { name: "Overview", href: webPaths.workspace(workspaceId), icon: Home, ownedPathPrefixes: [] },
        {
          name: "Pages",
          href: webPaths.pages(workspaceId),
          icon: Library,
          switchesToPagesContext: true,
          ownedPathPrefixes: [webPaths.pages(workspaceId)],
        },
        {
          name: "Activity",
          href: webPaths.activity(workspaceId, null),
          icon: History,
          ownedPathPrefixes: [webPaths.activity(workspaceId, null)],
        },
      ],
    },
    {
      label: "Sources",
      items: [
        {
          name: "Connectors",
          href: webPaths.connectors(workspaceId),
          icon: Plug,
          ownedPathPrefixes: [webPaths.connectors(workspaceId)],
        },
        {
          name: "Catalog",
          href: webPaths.catalog(workspaceId),
          icon: Database,
          ownedPathPrefixes: [webPaths.catalog(workspaceId)],
        },
      ],
    },
    {
      label: "Model",
      items: [
        {
          name: "Semantics",
          href: webPaths.semantics(workspaceId),
          icon: Layers,
          ownedPathPrefixes: [
            webPaths.semantics(workspaceId),
            webPaths.lineage(workspaceId),
            `${webPaths.workspace(workspaceId)}/semantic-models`,
            `${webPaths.workspace(workspaceId)}/metrics`,
          ],
        },
      ],
    },
    {
      label: "Analyze",
      items: [
        {
          name: "Explore",
          href: webPaths.explore(workspaceId),
          icon: Compass,
          ownedPathPrefixes: [webPaths.explore(workspaceId)],
        },
        {
          name: "Dashboards",
          href: webPaths.dashboards(workspaceId),
          icon: LayoutDashboard,
          ownedPathPrefixes: [webPaths.dashboards(workspaceId)],
        },
      ],
    },
  ];
}

// The stack above the user: Workspace settings for those who manage it, and the public guide for the current screen.
function workspaceUtilityItems(workspaceId: string, canManageSettings: boolean, pathname: string): LinkNavItem[] {
  return [
    ...(canManageSettings
      ? [{
          name: "Settings",
          href: webPaths.workspaceSettings(workspaceId),
          icon: Settings,
          ownedPathPrefixes: [webPaths.workspaceSettings(workspaceId)],
        }]
      : []),
    { name: "Help", href: helpDocsUrlForPath(pathname), icon: CircleHelp, external: true, ownedPathPrefixes: [] },
  ];
}

// The palette shortcut as this platform writes it.
export function searchShortcutLabel(): string {
  if (typeof navigator === "undefined") return "⌘K";
  return /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent) ? "⌘K" : "Ctrl K";
}

function SidebarBrandMark() {
  return (
    <img
      src="/brand/monomer-mark-small.svg"
      alt="Monomer"
      className="h-auto w-9"
      draggable={false}
    />
  );
}

export function SidebarMasthead({
  collapsed,
  onToggleCollapsed,
  workspaceSwitcher,
}: {
  collapsed: boolean;
  onToggleCollapsed?: () => void;
  workspaceSwitcher: React.ReactNode;
}) {
  const expandable = collapsed && onToggleCollapsed;
  const collapsible = !collapsed && onToggleCollapsed;

  if (expandable) {
    return (
      <div className="flex h-14 flex-shrink-0 items-center justify-center border-b border-border">
        <button
          type="button"
          onClick={onToggleCollapsed}
          aria-label="Open sidebar"
          aria-describedby="sidebar-open-tooltip"
          className="group relative flex h-10 w-10 items-center justify-center rounded-[4px] focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring focus-visible:outline-offset-2"
        >
          <span className="flex opacity-100 transition-opacity duration-150 group-hover:opacity-0 group-focus-visible:opacity-0 motion-reduce:transition-none">
            <SidebarBrandMark />
          </span>
          <ChevronRight className="absolute h-4 w-4 text-foreground opacity-0 transition-opacity duration-150 group-hover:opacity-100 group-focus-visible:opacity-100 motion-reduce:transition-none" aria-hidden="true" />
          <SidebarTooltip id="sidebar-open-tooltip" label="Open sidebar" />
        </button>
      </div>
    );
  }

  return (
    <div className="relative flex h-14 flex-shrink-0 items-center gap-1 border-b border-border px-3">
      {collapsible ? (
        <button
          type="button"
          onClick={onToggleCollapsed}
          aria-label="Collapse sidebar"
          aria-describedby="sidebar-collapse-tooltip"
          className="group relative flex h-10 w-11 flex-shrink-0 items-center justify-center rounded-[4px] focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring focus-visible:outline-offset-2"
        >
          <span className="flex opacity-100 transition-opacity duration-150 group-hover:opacity-0 group-focus-visible:opacity-0 motion-reduce:transition-none">
            <SidebarBrandMark />
          </span>
          <ChevronLeft className="absolute h-4 w-4 text-foreground opacity-0 transition-opacity duration-150 group-hover:opacity-100 group-focus-visible:opacity-100 motion-reduce:transition-none" aria-hidden="true" />
          <SidebarTooltip id="sidebar-collapse-tooltip" label="Collapse sidebar" />
        </button>
      ) : (
        <span className="flex h-10 w-11 flex-shrink-0 items-center justify-center">
          <SidebarBrandMark />
        </span>
      )}
      {workspaceSwitcher}
    </div>
  );
}

export function Sidebar({
  mode,
  transition,
  onModeChange,
  onOpenSearch,
}: {
  mode: ApplicationShellMode;
  transition: ShellContextTransition;
  onModeChange: (mode: ApplicationShellMode) => void;
  onOpenSearch: () => void;
}) {
  const collapsed = useSidebarUiStore((state) => state.collapsed);
  const toggleCollapsed = useSidebarUiStore((state) => state.toggleCollapsed);

  return (
    <aside
      className={`${collapsed ? "w-14" : "w-64"} relative z-20 hidden flex-shrink-0 flex-col border-r border-border bg-background transition-[width] duration-200 lg:flex`}
      style={{ fontFeatureSettings: '"ss01"' }}
      data-testid="desktop-sidebar"
      data-collapsed={collapsed ? "true" : "false"}
    >
      <SidebarContent
        mode={mode}
        transition={transition}
        onModeChange={onModeChange}
        onWorkspaceContextChange={() => undefined}
        onOpenSearch={onOpenSearch}
        collapsed={collapsed}
        onToggleCollapsed={toggleCollapsed}
      />
    </aside>
  );
}

export function SidebarContent({
  mode,
  transition,
  onModeChange,
  onWorkspaceContextChange,
  onOpenSearch,
  collapsed = false,
  onToggleCollapsed,
  menuPortalContainer,
}: {
  mode: ApplicationShellMode;
  transition: ShellContextTransition;
  onModeChange: (mode: ApplicationShellMode) => void;
  onWorkspaceContextChange: () => void;
  onOpenSearch: () => void;
  collapsed?: boolean;
  onToggleCollapsed?: () => void;
  // The mobile drawer, which the workspace switcher's menu renders into.
  menuPortalContainer?: HTMLElement | null;
}) {
  const pathname = usePathname();
  const workspaceContext = useOptionalWorkspaceRouteContext();
  const organizationMemberships = useAuthStore((state) => state.organizationMemberships);
  const settingsNavigation = useSettingsNavigation();
  const navGroups = workspaceContext ? workspaceNavigationGroups(workspaceContext.workspaceId) : [];
  const utilityItems = workspaceContext
    ? workspaceUtilityItems(
        workspaceContext.workspaceId,
        canOpenWorkspaceSettings(
          workspaceContext.access.role,
          organizationMemberships.find((membership) => membership.organization.id === workspaceContext.organizationId)?.role ?? null,
        ),
        pathname,
      )
    : [];

  if (settingsNavigation) {
    return (
      <>
        <SidebarMasthead
          collapsed={collapsed}
          onToggleCollapsed={onToggleCollapsed}
          workspaceSwitcher={collapsed ? null : <SettingsBackLink navigation={settingsNavigation} />}
        />
        {collapsed
          ? <CollapsedSettingsNavigation navigation={settingsNavigation} />
          : <SettingsNavigation navigation={settingsNavigation} />}
        <SidebarUser collapsed={collapsed} switchWorkspace={false} />
      </>
    );
  }

  return (
    <>
      <SidebarMasthead
        collapsed={collapsed}
        onToggleCollapsed={onToggleCollapsed}
        workspaceSwitcher={collapsed ? null : (
          <WorkspaceSwitcher
            onWorkspaceContextChange={onWorkspaceContextChange}
            menuPortalContainer={menuPortalContainer}
          />
        )}
      />
      <SidebarNavigationRegion
        mode={mode}
        transition={transition}
        onModeChange={onModeChange}
        pathname={pathname}
        workspaceNavGroups={navGroups}
        pagesNavigation={<PagesNavigation />}
        collapsed={collapsed}
        onExpand={onToggleCollapsed}
      />
      {workspaceContext ? (
        <SidebarSearchEntry collapsed={collapsed} onOpenSearch={onOpenSearch} />
      ) : null}
      {utilityItems.length > 0 ? (
        <SidebarUtilityStack
          items={utilityItems}
          activeHref={activeNavigationHref([{ items: utilityItems }], pathname)}
          collapsed={collapsed}
        />
      ) : null}
      <SidebarUser collapsed={collapsed} switchWorkspace={false} />
    </>
  );
}

// The visible way into the command palette. ⌘K/Ctrl+K opens the same palette from anywhere.
function SidebarSearchEntry({ collapsed, onOpenSearch }: { collapsed: boolean; onOpenSearch: () => void }) {
  const shortcut = searchShortcutLabel();
  if (collapsed) {
    return (
      <div className="flex flex-shrink-0 justify-center pt-3">
        <button
          type="button"
          onClick={onOpenSearch}
          aria-label="Search"
          aria-keyshortcuts="Meta+K Control+K"
          aria-describedby="sidebar-search-tooltip"
          className="group relative flex h-9 w-9 items-center justify-center rounded-[4px] text-foreground-secondary transition-colors hover:bg-surface/60 hover:text-foreground focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring focus-visible:outline-offset-2"
        >
          <Search className="h-4 w-4" aria-hidden="true" />
          <SidebarTooltip id="sidebar-search-tooltip" label={`Search ${shortcut}`} />
        </button>
      </div>
    );
  }
  return (
    <div className="flex-shrink-0 px-3 pt-2">
      <button
        type="button"
        onClick={onOpenSearch}
        aria-keyshortcuts="Meta+K Control+K"
        data-testid="sidebar-search"
        className="group flex w-full items-center gap-2.5 rounded-sm px-2 py-1.5 text-left text-foreground-secondary transition-colors hover:bg-surface/60 hover:text-foreground focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring focus-visible:outline-offset-2"
      >
        <Search className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
        <span className="flex-1 type-control">Search</span>
        <kbd className="font-sans type-status text-foreground-secondary" aria-hidden="true">{shortcut}</kbd>
      </button>
    </div>
  );
}

function SidebarUtilityStack({
  items,
  activeHref,
  collapsed,
}: {
  items: LinkNavItem[];
  activeHref: string | null;
  collapsed: boolean;
}) {
  if (collapsed) {
    return (
      <nav aria-label="Workspace utilities" className="flex flex-shrink-0 flex-col items-center gap-1 py-2">
        {items.map((item) => (
          <CollapsedNavItem key={item.href} item={item} active={item.href === activeHref} />
        ))}
      </nav>
    );
  }
  return (
    <nav aria-label="Workspace utilities" className="flex-shrink-0 px-3 py-2">
      <ul className="space-y-px">
        {items.map((item) => (
          <NavItemRow key={item.href} item={item} isActive={item.href === activeHref} />
        ))}
      </ul>
    </nav>
  );
}

interface SidebarNavigationRegionProps {
  mode: ApplicationShellMode;
  transition?: ShellContextTransition;
  onModeChange?: (mode: ApplicationShellMode) => void;
  pathname: string;
  workspaceNavGroups: NavGroup[];
  pagesNavigation: React.ReactNode;
  // Sits beside the back control in the Pages context header, such as the editor's new-folder control.
  collapsed?: boolean;
  onExpand?: () => void;
}

export function SidebarNavigationRegion({
  mode,
  transition = null,
  onModeChange = () => undefined,
  pathname,
  workspaceNavGroups,
  pagesNavigation,
  collapsed = false,
  onExpand = () => undefined,
}: SidebarNavigationRegionProps) {
  // The control that switched the context unmounts with it, so focus moves to the control that
  // switches back instead of falling to the document.
  const contextControlRef = useRef<HTMLButtonElement>(null);
  const focusContextControlAfterSwitch = useRef(false);
  useEffect(() => {
    if (!focusContextControlAfterSwitch.current) return;
    focusContextControlAfterSwitch.current = false;
    contextControlRef.current?.focus();
  }, [mode, collapsed]);

  const switchContext = (nextMode: ApplicationShellMode) => {
    focusContextControlAfterSwitch.current = true;
    onModeChange(nextMode);
  };
  // The collapsed Pages context holds only its back control, not the directory, so opening the
  // directory from the rail also expands the sidebar.
  const openPagesContext = () => {
    if (collapsed) onExpand();
    switchContext("pages");
  };

  if (collapsed) {
    return mode === "pages" ? (
      <CollapsedPagesNavigation
        contextControlRef={contextControlRef}
        onBack={() => switchContext("workspace")}
      />
    ) : (
      <CollapsedWorkspaceNavigation
        navGroups={workspaceNavGroups}
        activeHref={activeNavigationHref(workspaceNavGroups, pathname)}
        contextControlRef={contextControlRef}
        onOpenPagesContext={openPagesContext}
      />
    );
  }

  if (mode === "pages") {
    return (
      <div
        key="pages-navigation"
        className={`flex min-h-0 flex-1 flex-col ${transition === "forward" ? styles.forward : ""}`}
        data-testid="sidebar-navigation-context"
        data-transition={transition ?? "none"}
      >
        <div className="flex h-14 flex-shrink-0 items-center border-b border-border">
          <button
            ref={contextControlRef}
            type="button"
            onClick={() => switchContext("workspace")}
            aria-label="Back to workspace navigation"
            className="group relative flex h-full min-w-0 flex-1 items-center gap-2 px-4 text-left transition-colors hover:bg-surface/60 focus-visible:bg-surface focus-visible:outline-none"
          >
            <span aria-hidden="true" className="absolute left-0 top-1/2 h-5 w-[2px] -translate-y-1/2 bg-[var(--focus-ring)] opacity-0 group-focus-visible:opacity-100" />
            <Library className="h-4 w-4 text-foreground" aria-hidden="true" />
            <span className="flex-1 type-item text-foreground">Pages</span>
            <ChevronLeft className="h-4 w-4 text-foreground" aria-hidden="true" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto py-3">
          <nav aria-label={shellNavigationLabel(mode)}>
            <ul className="space-y-px px-3">
              {pagesNavigation}
            </ul>
          </nav>
        </div>
      </div>
    );
  }

  const activeHref = activeNavigationHref(workspaceNavGroups, pathname);

  return (
    <div
      key="workspace-navigation"
      className={`flex-1 overflow-y-auto py-3 ${transition === "backward" ? styles.backward : ""}`}
      data-testid="sidebar-navigation-context"
      data-transition={transition ?? "none"}
    >
      <nav className="space-y-5" aria-label={shellNavigationLabel(mode)}>
        {workspaceNavGroups.map((group, idx) => (
          <NavGroupBlock
            key={group.label ?? `group-${idx}`}
            group={group}
            activeHref={activeHref}
            contextControlRef={contextControlRef}
            onOpenPagesContext={openPagesContext}
          />
        ))}
      </nav>
    </div>
  );
}

function CollapsedPagesNavigation({
  contextControlRef,
  onBack,
}: {
  contextControlRef: Ref<HTMLButtonElement>;
  onBack: () => void;
}) {
  const tooltipId = "sidebar-nav-tooltip-back-to-overview";
  return (
    <nav className="flex min-h-0 flex-1 flex-col items-center overflow-visible py-3" aria-label={shellNavigationLabel("pages")}>
      <button
        ref={contextControlRef}
        type="button"
        onClick={onBack}
        aria-label="Back to workspace navigation"
        aria-describedby={tooltipId}
        className="group relative flex h-9 w-9 items-center justify-center rounded-[4px] text-foreground-secondary transition-colors hover:bg-surface/60 hover:text-foreground focus-visible:bg-surface focus-visible:outline-none"
      >
        <Library className="h-4 w-4 text-foreground" aria-hidden="true" />
        <span
          aria-hidden="true"
          className="absolute left-0 top-1/2 h-4 w-[2px] -translate-y-1/2 rounded-r-sm bg-accent-marker group-focus-visible:bg-[var(--focus-ring)]"
        />
        <SidebarTooltip id={tooltipId} label="Back to workspace navigation" />
      </button>
    </nav>
  );
}

interface PagesContextControlProps {
  contextControlRef?: Ref<HTMLButtonElement>;
  onOpenPagesContext?: () => void;
}

function CollapsedWorkspaceNavigation({
  navGroups,
  activeHref,
  contextControlRef,
  onOpenPagesContext,
}: {
  navGroups: NavGroup[];
  activeHref: string | null;
} & PagesContextControlProps) {
  return (
    <nav className="flex min-h-0 flex-1 flex-col items-center gap-1 overflow-visible py-3" aria-label="Workspace navigation">
      {navGroups.flatMap((group) => group.items).map((item) => (
        <CollapsedNavItem
          key={item.href}
          item={item}
          active={item.href === activeHref}
          contextControlRef={contextControlRef}
          onOpenPagesContext={onOpenPagesContext}
        />
      ))}
    </nav>
  );
}

function CollapsedNavItem({
  item,
  active,
  contextControlRef,
  onOpenPagesContext,
}: {
  item: LinkNavItem;
  active: boolean;
} & PagesContextControlProps) {
  const Icon = item.icon;
  const tooltipId = `sidebar-nav-tooltip-${item.name.toLowerCase().replaceAll(" ", "-")}`;
  const className = `group relative flex h-9 w-9 items-center justify-center rounded-[4px] transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring focus-visible:outline-offset-2 ${
    active
      ? "bg-surface text-foreground"
      : "text-foreground-secondary hover:bg-surface/60 hover:text-foreground"
  }`;
  const content = (
    <>
      {active ? (
        <span
          aria-hidden="true"
          className="absolute left-0 top-1/2 h-4 w-[2px] -translate-y-1/2 rounded-r-sm bg-accent-marker"
        />
      ) : null}
      <Icon className="h-4 w-4" aria-hidden="true" />
      <SidebarTooltip id={tooltipId} label={item.name} />
    </>
  );
  if (item.external) {
    return (
      <a href={item.href} target="_blank" rel="noreferrer" aria-label={item.name} aria-describedby={tooltipId} className={className}>
        {content}
      </a>
    );
  }
  if (item.switchesToPagesContext && onOpenPagesContext) {
    return (
      <button
        ref={contextControlRef}
        type="button"
        onClick={onOpenPagesContext}
        aria-label={item.name}
        aria-current={active ? "page" : undefined}
        aria-describedby={tooltipId}
        className={className}
      >
        {content}
      </button>
    );
  }
  return (
    <Link
      href={item.href}
      aria-label={item.name}
      aria-current={active ? "page" : undefined}
      aria-describedby={tooltipId}
      className={className}
    >
      {content}
    </Link>
  );
}

interface NavGroupBlockProps extends PagesContextControlProps {
  group: NavGroup;
  activeHref: string | null;
}

function NavGroupBlock({ group, activeHref, contextControlRef, onOpenPagesContext }: NavGroupBlockProps) {
  return (
    <div className="px-3">
      {group.label && (
        <div
          className="mb-1.5 px-2 type-group text-label"
        >
          {group.label}
        </div>
      )}
      <ul className="space-y-px">
        {group.items.map((item) => (
          <NavItemRow
            key={item.href}
            item={item}
            isActive={item.href === activeHref}
            contextControlRef={contextControlRef}
            onOpenPagesContext={onOpenPagesContext}
          />
        ))}
      </ul>
    </div>
  );
}

function NavItemRow({
  item,
  isActive,
  contextControlRef,
  onOpenPagesContext,
}: {
  item: LinkNavItem;
  isActive: boolean;
} & PagesContextControlProps) {
  const Icon = item.icon;
  const className = `group relative flex items-center gap-2.5 rounded-sm px-2 py-1.5 transition-colors ${
    isActive
      ? "bg-surface text-foreground"
      : "text-foreground-secondary hover:bg-surface/60 hover:text-foreground"
  }`;
  const content = (
    <>
      {isActive && (
        <span
          aria-hidden="true"
          className="absolute left-0 top-1/2 -translate-y-1/2 h-4 w-[2px] rounded-r-sm"
          style={{ backgroundColor: "var(--accent-marker)" }}
        />
      )}
      <Icon
        className="h-4 w-4 flex-shrink-0"
        aria-hidden="true"
      />
      <span className="type-control">{item.name}</span>
    </>
  );

  if (item.switchesToPagesContext && onOpenPagesContext) {
    return (
      <li>
        <button
          ref={contextControlRef}
          type="button"
          onClick={onOpenPagesContext}
          aria-current={isActive ? "page" : undefined}
          className={`${className} w-full text-left`}
        >
          {content}
          <ChevronRight className="ml-auto h-4 w-4 flex-shrink-0 text-foreground-secondary" aria-hidden="true" />
        </button>
      </li>
    );
  }

  return (
    <li>
      {item.external ? (
        <a href={item.href} target="_blank" rel="noreferrer" className={className}>{content}</a>
      ) : (
        <Link
          href={item.href}
          aria-current={isActive ? "page" : undefined}
          className={className}
        >
          {content}
        </Link>
      )}
    </li>
  );
}

function WorkspaceSwitcher({
  onWorkspaceContextChange,
  menuPortalContainer,
}: {
  onWorkspaceContextChange: () => void;
  menuPortalContainer: HTMLElement | null | undefined;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const organizationMemberships = useAuthStore((state) => state.organizationMemberships);
  const workspaceContext = useOptionalWorkspaceRouteContext();
  const organizationContext = useOptionalOrganizationRouteContext();
  const scrollbar = useTransientScrollbar();
  const workspacesQuery = useAccessibleWorkspacesQuery();
  const workspaces = workspacesQuery.data ?? [];
  const [createOpen, setCreateOpen] = useState(false);
  const [search, setSearch] = useState("");
  // The create dialog opens after the menu has returned focus to its trigger, so closing the
  // dialog lands on the trigger instead of a menu item that no longer exists.
  const createRequested = useRef(false);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const currentWorkspace = workspaceContext?.workspace ?? null;
  const currentOrganizationId = workspaceContext?.organizationId ?? organizationContext?.organizationId ?? null;
  const currentMembership = organizationMemberships.find((membership) => membership.organization.id === currentOrganizationId)
    ?? organizationContext?.membership
    ?? null;
  const currentRole = workspaceContext?.access.role ?? null;
  const groups = filterWorkspaceGroups(groupWorkspacesByOrganization(organizationMemberships, workspaces), search);
  const showSearch = workspaces.length > WORKSPACE_SEARCH_THRESHOLD;

  const renderOrganizationBlock = ({ membership, workspaces: organizationWorkspaces }: OrganizationWorkspaceGroup) => {
    const labelId = `workspace-switcher-organization-${membership.organization.id}`;
    return (
      <MenuGroup key={membership.organization.id} aria-labelledby={labelId} className="py-0.5">
        <MenuLabel
          id={labelId}
          data-testid={`organization-option-${membership.organization.id}`}
          className="block truncate type-meta text-foreground-secondary"
        >
          {membership.organization.displayName}
        </MenuLabel>
        <MenuRadioGroup value={currentWorkspace?.id ?? ""}>
          {organizationWorkspaces.map((workspace) => (
            <MenuRadioItem
              key={workspace.id}
              value={workspace.id}
              meta={roleLabel(workspace.role)}
              data-testid={`workspace-option-${workspace.id}`}
              onSelect={() => {
                router.replace(workspaceSwitchPath(pathname, workspace.id));
                onWorkspaceContextChange();
              }}
            >
              {workspace.name}
            </MenuRadioItem>
          ))}
        </MenuRadioGroup>
        {organizationWorkspaces.length === 0 ? (
          <div className="px-3 py-1.5 type-status text-foreground-secondary">No workspace access yet</div>
        ) : null}
      </MenuGroup>
    );
  };

  const triggerName = currentWorkspace?.name ?? currentMembership?.organization.displayName ?? "Select a workspace";
  const triggerMeta = currentWorkspace && currentMembership && currentRole
    ? `${currentMembership.organization.displayName} · ${roleLabel(currentRole)}`
    : null;

  return (
    <div className="relative flex min-w-0 flex-1">
      {createOpen && <CreateOrganizationModal onClose={() => setCreateOpen(false)} />}
      <Menu modal={false} onOpenChange={(open) => { if (!open) setSearch(""); }}>
        <MenuTrigger asChild>
          <button
            ref={triggerRef}
            type="button"
            data-testid="workspace-switcher"
            aria-label="Switch workspace"
            title={triggerMeta ? `${triggerName} — ${triggerMeta}` : triggerName}
            className="flex min-w-0 flex-1 items-center gap-2 rounded-[4px] px-1 py-1 text-left transition-colors hover:bg-surface/60 focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring focus-visible:outline-offset-2"
          >
            <span className="min-w-0 flex-1">
              <span className="block truncate type-control text-heading">{triggerName}</span>
              {triggerMeta ? (
                <span className="block truncate type-status text-foreground-secondary" data-testid="workspace-switcher-meta">{triggerMeta}</span>
              ) : null}
            </span>
            <ChevronsUpDown className="h-3.5 w-3.5 flex-shrink-0 text-foreground-secondary" aria-hidden="true" />
          </button>
        </MenuTrigger>
        <MenuContent
          aria-label="Workspaces"
          align="start"
          sideOffset={8}
          portalContainer={menuPortalContainer}
          // The menu fits the room below its trigger; the list gives up height first so the actions stay visible.
          className="flex max-h-[var(--radix-dropdown-menu-content-available-height)] w-[260px] flex-col"
          onCloseAutoFocus={(event) => {
            if (!createRequested.current) return;
            createRequested.current = false;
            event.preventDefault();
            triggerRef.current?.focus();
            setCreateOpen(true);
          }}
        >
          {showSearch ? (
            <div className="px-2 pb-1 pt-0.5">
              <Input
                type="search"
                aria-label="Search workspaces"
                placeholder="Search workspaces"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                // The menu's typeahead and arrow keys would otherwise take the typing.
                onKeyDown={(event) => {
                  if (event.key !== "Escape" && event.key !== "ArrowDown") event.stopPropagation();
                }}
              />
            </div>
          ) : null}
          <div
            data-testid="workspace-switcher-list"
            onScroll={scrollbar.onScroll}
            className={`max-h-[360px] min-h-0 overflow-y-auto overscroll-contain ${scrollbar.className}`}
          >
            {groups.length === 0 && search.trim() ? (
              <div className="px-3 py-2 type-status text-foreground-secondary">No workspaces match.</div>
            ) : groups.map(renderOrganizationBlock)}
          </div>
          <MenuSeparator />
          <MenuItem asChild>
            <Link href={webPaths.workspaces} data-testid="workspace-switcher-all" onClick={() => onWorkspaceContextChange()}>
              <LayoutGrid aria-hidden="true" />
              All workspaces
            </Link>
          </MenuItem>
          {currentMembership?.role === "ADMIN" ? (
            <MenuItem asChild>
              <Link
                href={webPaths.organizationSettings(currentMembership.organization.id)}
                data-testid={`organization-settings-${currentMembership.organization.id}`}
                onClick={() => onWorkspaceContextChange()}
              >
                <Settings aria-hidden="true" />
                Organization settings
              </Link>
            </MenuItem>
          ) : null}
          <MenuItem icon={Plus} onSelect={() => { createRequested.current = true; }}>
            New Organization
          </MenuItem>
        </MenuContent>
      </Menu>
    </div>
  );
}

export function SidebarUser({ collapsed, switchWorkspace }: { collapsed: boolean; switchWorkspace: boolean }) {
  const user = useAuthStore((s) => s.user);

  if (!user) {
    if (collapsed) return <div className="h-14 flex-shrink-0 border-t border-border" />;
    return (
      <div className="border-t border-border px-4 py-3">
        <div className="type-status text-foreground-secondary">
          Not signed in
        </div>
      </div>
    );
  }

  return (
    <div className="relative border-t border-border">
      <Menu modal={false}>
        <MenuTrigger asChild>
          <button
            type="button"
            data-testid="sidebar-user"
            aria-label={`Open user menu for ${user.displayName}`}
            aria-describedby={collapsed ? "sidebar-user-tooltip" : undefined}
            className={`group relative flex w-full items-center text-left transition hover:bg-surface/60 ${
              collapsed ? "h-14 justify-center" : "gap-2.5 px-3 py-3"
            }`}
          >
            <UserAvatar user={user} />
            {!collapsed ? (
              <>
                <span className="min-w-0 flex-1">
                  <span
                    className="block truncate type-control text-foreground"
                    data-testid="sidebar-user-display-name"
                  >
                    {user.displayName}
                  </span>
                  <span className="block truncate type-status text-foreground-secondary">
                    {user.email}
                  </span>
                </span>
                <ChevronsUpDown className="h-3.5 w-3.5 text-foreground-secondary" aria-hidden="true" />
              </>
            ) : (
              <SidebarTooltip id="sidebar-user-tooltip" label={user.displayName} />
            )}
          </button>
        </MenuTrigger>
        <MenuContent
          aria-label="User menu"
          side={collapsed ? "right" : "top"}
          align={collapsed ? "end" : "start"}
          alignOffset={8}
          sideOffset={collapsed ? 8 : 4}
          className={collapsed ? "w-[228px]" : "w-[calc(var(--radix-dropdown-menu-trigger-width)-16px)]"}
        >
          <UserMenuEntries user={user} testIdPrefix="sidebar-user" switchWorkspace={switchWorkspace} />
        </MenuContent>
      </Menu>
    </div>
  );
}

export const _internal = {
  workspaceNavigationGroups,
  workspaceUtilityItems,
  activeNavigationHref,
};
