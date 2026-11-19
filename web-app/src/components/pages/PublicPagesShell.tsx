import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { useEffect, useState, type ReactNode } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { Library, Menu, X } from "lucide-react";
import type { PublicTree } from "@/api/public-pages";
import { PagesNavigationView } from "./PagesNavigation";
import { PagesContextBar } from "./PagesContextBar";
import { SidebarMasthead, SidebarUser } from "@/components/layout/Sidebar";
import { UserMenuButton } from "@/components/auth/UserMenu";
import { useAuthStore } from "@/lib/auth/store";
import { publicSiteHome } from "@/lib/public-docs";
import { useSidebarUiStore } from "@/components/layout/sidebarUiState";
import { DIALOG_OVERLAY_CLASS } from "@/components/ui/Dialog";
import { IconButton } from "@/components/ui/IconButton";

// Signed-in readers, Viewers included, get the user menu with Settings, Switch workspace, and Sign
// out. Public readers see where the Pages come from; only anonymous ones see "Built with Monomer".
export function PublicPagesShell({ workspaceId, pageId, tree, source, readerMode, onRetry, children }: {
  workspaceId: string; pageId?: string; tree: PublicTree; source: string; readerMode: "VIEWER" | "PUBLIC"; onRetry: () => void; children: ReactNode;
}) {
  const user = useAuthStore(state => state.status === "authenticated" ? state.user : null);
  const publicReader = readerMode === "PUBLIC";
  const anonymousReader = publicReader && !user;
  const shellName = publicReader ? source : tree.workspaceName;
  const scrollbar = useTransientScrollbar();
  const collapsed = useSidebarUiStore(state => state.collapsed);
  const toggleCollapsed = useSidebarUiStore(state => state.toggleCollapsed);
  const [mobileOpen, setMobileOpen] = useState(false);
  useEffect(() => setMobileOpen(false), [pageId]);
  const navigation = <nav aria-label="Pages navigation" onScroll={scrollbar.onScroll} className={`min-h-0 flex-1 overflow-y-auto py-3 ${scrollbar.className}`}><ul className="space-y-px px-3"><PagesNavigationView workspaceId={workspaceId} snapshot={tree} status="ready" error={null} canEdit={false} filterable pathname={`/workspaces/${workspaceId}/pages${pageId ? `/${pageId}` : ""}`} onRetry={onRetry} /></ul></nav>;
  const label = <div className="flex h-14 shrink-0 items-center gap-2 border-b border-border px-4"><Library className="h-4 w-4" aria-hidden="true" /><span className="type-item">Pages</span></div>;
  const builtWith = <a href={publicSiteHome.url} className="shrink-0 type-meta text-foreground-secondary underline-offset-4 hover:text-foreground hover:underline">Built with Monomer</a>;
  const footer = anonymousReader ? <div className="px-4 py-4">{builtWith}</div> : null;
  return <div className="flex h-dvh bg-background" style={{ fontFeatureSettings: '"ss01"' }}>
    <aside aria-label="Pages sidebar" className={`${collapsed ? "w-14" : "w-64"} hidden shrink-0 flex-col border-r border-border bg-background transition-[width] duration-200 motion-reduce:transition-none lg:flex`}>
      <SidebarMasthead collapsed={collapsed} onToggleCollapsed={toggleCollapsed} workspaceSwitcher={<span className="min-w-0 truncate type-item" title={shellName}>{shellName}</span>} />
      {collapsed ? <><button aria-label="Open Pages navigation" onClick={toggleCollapsed} className="grid h-14 place-items-center hover:bg-surface"><Library className="h-4 w-4" aria-hidden="true" /></button><div className="flex-1" /></> : <>{label}{navigation}</>}
      {user ? <SidebarUser collapsed={collapsed} switchWorkspace /> : null}
    </aside>
    <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
      {publicReader
        ? <div className="hidden h-12 shrink-0 items-center justify-between gap-4 border-b border-border px-6 lg:flex"><span className="min-w-0 truncate type-control text-label" title={source}>{source}</span>{anonymousReader ? builtWith : null}</div>
        : <PagesContextBar workspaceName={tree.workspaceName} />}
      <div className="flex h-12 shrink-0 items-center gap-2 border-b border-border px-2 lg:hidden">
        <IconButton label="Browse Pages" icon={Menu} size="md" onClick={() => setMobileOpen(true)} />
        <span className="min-w-0 truncate type-body" title={shellName} data-testid="reader-shell-name">{shellName}</span>
        {user ? <div className="ml-auto flex-shrink-0"><UserMenuButton user={user} testIdPrefix="reader-user-menu" switchWorkspace /></div> : null}
      </div>
      <div className="flex min-h-0 flex-1 flex-col">{children}</div>
    </div>
    <Dialog.Root open={mobileOpen} onOpenChange={setMobileOpen}><Dialog.Portal><Dialog.Overlay className={DIALOG_OVERLAY_CLASS} /><Dialog.Content aria-describedby={undefined} className="fixed inset-y-0 left-0 z-50 flex w-[min(320px,90vw)] flex-col border-r border-border bg-background shadow-modal">
      <div className="flex h-14 shrink-0 items-center justify-between border-b border-border px-4"><Dialog.Title className="flex items-center gap-2 type-item"><Library className="h-4 w-4" aria-hidden="true" />Pages</Dialog.Title><Dialog.Close asChild><IconButton label="Close Pages navigation" icon={X} /></Dialog.Close></div>{navigation}{footer}
    </Dialog.Content></Dialog.Portal></Dialog.Root>
  </div>;
}
