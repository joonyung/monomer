import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthStatus } from "@/lib/auth/store";
import type { OrganizationMembership, Workspace } from "@/lib/auth/types";
import type { WorkspaceRouteContextValue } from "@/components/auth/TenantRouteContext";
import { captureSourceLine } from "@/components/dashboard/capture/provenance";
import { PublicPagesShell } from "./PublicPagesShell";
import { usePageSource, useReaderPageSource } from "./usePageSource";

const fixture = vi.hoisted(() => ({
  status: "authenticated" as AuthStatus,
  user: { id: "reader-1" } as { id: string } | null,
  organizationMemberships: [] as OrganizationMembership[],
  workspaces: [] as Workspace[],
  inventorySuccess: true,
  inventoryEnabled: false,
  context: null as WorkspaceRouteContextValue | null,
}));

vi.mock("@/lib/auth/store", () => ({
  useAuthStore: (selector: (state: typeof fixture) => unknown) => selector(fixture),
}));
vi.mock("@/components/auth/TenantRouteContext", () => ({
  useOptionalWorkspaceRouteContext: () => fixture.context,
}));
vi.mock("@tanstack/react-query", () => ({
  useQuery: ({ enabled }: { enabled: boolean }) => {
    fixture.inventoryEnabled = enabled;
    return { isSuccess: fixture.inventorySuccess, data: fixture.workspaces };
  },
}));
vi.mock("@/components/layout/Sidebar", () => ({
  SidebarMasthead: ({ workspaceSwitcher }: { workspaceSwitcher: ReactNode }) => <aside>{workspaceSwitcher}</aside>,
  SidebarUser: ({ switchWorkspace }: { switchWorkspace: boolean }) => <nav data-sidebar-user={String(switchWorkspace)} />,
}));
vi.mock("@/components/auth/UserMenu", () => ({
  UserMenuButton: ({ switchWorkspace }: { switchWorkspace: boolean }) => <button data-top-bar-user={String(switchWorkspace)} />,
}));
vi.mock("./PagesNavigation", () => ({ PagesNavigationView: () => null }));

const workspace: Workspace = { id: "workspace-1", organizationId: "organization-1", name: "Old workspace name", publicPages: true, reportingTimeZone: "UTC" };
const membership: OrganizationMembership = {
  organization: { id: "organization-1", displayName: "Organization Research & Development", plan: "FREE", createdAt: "2026-10-02T00:00:00Z" },
  role: "MEMBER",
};
const workspaceName = "Current workspace name";

function Reader({ mode }: { mode: "VIEWER" | "PUBLIC" }) {
  const source = useReaderPageSource(workspace.id, workspaceName, mode);
  return <PublicPagesShell workspaceId={workspace.id} tree={{ revision: 1, workspaceName, pages: [], placements: [] }} source={source} readerMode={mode} onRetry={() => undefined}>
    <footer>{captureSourceLine(source)}</footer>
  </PublicPagesShell>;
}

function renderReader(mode: "VIEWER" | "PUBLIC" = "VIEWER") {
  return renderToStaticMarkup(<Reader mode={mode} />);
}

function MemberSource() {
  return <span>{usePageSource()}</span>;
}

describe("Pages reader source privacy", () => {
  beforeEach(() => {
    fixture.status = "authenticated";
    fixture.user = { id: "reader-1" };
    fixture.organizationMemberships = [membership];
    fixture.workspaces = [workspace];
    fixture.inventorySuccess = true;
    fixture.inventoryEnabled = false;
    fixture.context = { workspaceId: workspace.id, organizationId: workspace.organizationId, workspace: { ...workspace, name: workspaceName }, access: { granted: true, organizationId: workspace.organizationId, role: "OWNER" } };
  });

  it("keeps the admitted Organization in the Viewer's PNG source while the Viewer shell names only the Workspace", () => {
    const markup = renderReader();
    expect(markup).toContain("Organization Research &amp; Development · Current workspace name · Built with Monomer");
    expect(markup.match(/title="Current workspace name"/g)).toHaveLength(3);
    expect(markup).toContain('data-testid="pages-context-bar"');
    expect(markup).not.toContain('title="Organization Research');
    expect(markup).not.toContain(">Built with Monomer<");
    expect(markup).toContain('data-sidebar-user="true"');
    expect(markup).toContain('data-top-bar-user="true"');
    expect(markup).not.toContain(workspace.name);
    expect(fixture.inventoryEnabled).toBe(true);
  });

  it("shows public readers the source masthead, signed-in ones their user menu, and only anonymous ones Built with Monomer", () => {
    fixture.workspaces = [];
    const signedIn = renderReader("PUBLIC");
    expect(signedIn.match(/title="Current workspace name"/g)).toHaveLength(3);
    expect(signedIn).not.toContain(">Built with Monomer<");
    expect(signedIn).toContain('data-sidebar-user="true"');
    fixture.status = "unauthenticated";
    fixture.user = null;
    const anonymous = renderReader("PUBLIC");
    expect(anonymous).toContain(">Built with Monomer<");
    expect(anonymous).toContain('href="https://monomer.cc/"');
    expect(anonymous).not.toContain("data-sidebar-user");
    expect(anonymous).not.toContain("data-top-bar-user");
  });

  it("keeps authenticated PUBLIC and Organization-only visitors Workspace-only even with stale accessible inventory", () => {
    for (const workspaces of [[workspace], []]) {
      fixture.workspaces = workspaces;
      const markup = renderReader("PUBLIC");
      expect(markup).not.toContain("Organization Research");
      expect(markup).toContain("Current workspace name · Built with Monomer");
      expect(fixture.inventoryEnabled).toBe(false);
    }
  });

  it("removes Organization names when authentication changes, even with retained membership and inventory", () => {
    expect(renderReader()).toContain("Organization Research");
    for (const status of ["unauthenticated", "hydrating", "idle"] as const) {
      fixture.status = status;
      expect(renderReader()).not.toContain("Organization Research");
      expect(fixture.inventoryEnabled).toBe(false);
    }
    fixture.status = "authenticated";
    fixture.user = null;
    expect(renderReader()).not.toContain("Organization Research");
  });

  it("requires the target Workspace and its matching Organization membership", () => {
    fixture.workspaces = [{ ...workspace, id: "other-workspace" }];
    expect(renderReader()).not.toContain("Organization Research");
    fixture.workspaces = [{ ...workspace, organizationId: "other-organization" }];
    expect(renderReader()).not.toContain("Organization Research");
    fixture.workspaces = [workspace];
    fixture.organizationMemberships = [];
    expect(renderReader()).not.toContain("Organization Research");
  });

  it("keeps missing or failed inventory Workspace-only", () => {
    fixture.workspaces = [];
    expect(renderReader()).not.toContain("Organization Research");
    fixture.workspaces = [workspace];
    fixture.inventorySuccess = false;
    expect(renderReader()).not.toContain("Organization Research");
  });

  it("uses current admitted member route context for the member shell and its existing PNG source", () => {
    expect(renderToStaticMarkup(<MemberSource />)).toContain("Organization Research &amp; Development · Current workspace name");
    fixture.context!.access = { granted: false, organizationId: null, role: null };
    expect(renderToStaticMarkup(<MemberSource />)).toBe("<span>Current workspace name</span>");
    fixture.context!.access = { granted: true, organizationId: workspace.organizationId, role: "OWNER" };
    fixture.context!.organizationId = "other-organization";
    expect(renderToStaticMarkup(<MemberSource />)).toBe("<span>Current workspace name</span>");
    fixture.context!.organizationId = workspace.organizationId;
    fixture.status = "unauthenticated";
    expect(renderToStaticMarkup(<MemberSource />)).toBe("<span>Current workspace name</span>");
  });
});
