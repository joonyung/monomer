import type { AnchorHTMLAttributes, ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { OrganizationMembership } from "@/lib/auth/types";

let hasWorkspaceContext = true;
let pathname = "/workspaces/workspace-1/catalog";
let organizationMemberships: OrganizationMembership[] = [];
let inSettings = false;

vi.mock("@/lib/auth/store", () => ({
  useAuthStore: (selector: (state: unknown) => unknown) => selector({ status: "authenticated", user: null, organizationMemberships }),
}));

vi.mock("@/lib/navigation", () => ({
  Link: ({ href, ...props }: AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => (
    <a {...props} href={href} />
  ),
  usePathname: () => pathname,
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
  }),
}));

vi.mock("@/hooks/use-workspace", () => ({
  useWorkspaceId: () => "workspace-1",
}));

vi.mock("@/components/auth/TenantRouteContext", () => ({
  useOptionalWorkspaceRouteContext: () => hasWorkspaceContext ? { workspaceId: "workspace-1", organizationId: "organization-1", workspace: { id: "workspace-1", organizationId: "organization-1", name: "Workspace" }, access: { granted: true, role: "VIEWER" as const, } } : null,
  useOptionalOrganizationRouteContext: () => null,
  useAccessibleWorkspacesQuery: () => ({ data: [], isPending: false }),
  WorkspaceRouteFixtureProvider: ({ children }: { children: ReactNode }) => children,
}));

vi.mock("@/components/settings/settingsNavigation", () => ({
  useSettingsNavigation: () => inSettings ? { label: "Workspace settings", identity: { name: "Workspace" } } : null,
}));

vi.mock("@/components/layout/SettingsNavigation", () => ({
  SettingsBackLink: () => null,
  SettingsNavigation: () => <nav aria-label="Workspace settings" />,
  CollapsedSettingsNavigation: () => null,
}));

vi.mock("@/components/auth/WorkspaceAccessProvider", () => ({
  useWorkspaceAccess: () => ({ hasRole: () => false }),
}));

vi.mock("@/components/pages/PagesDirectoryProvider", () => ({
  PagesDirectoryProvider: ({
    children,
    lifecycle,
  }: {
    children: ReactNode;
    lifecycle: "live" | "one-shot";
  }) => (
    <section data-testid="pages-directory-provider" data-lifecycle={lifecycle}>
      {children}
    </section>
  ),
  useOptionalPagesDirectory: () => null,
}));

import { MainLayout as MainLayoutUnderTest } from "@/components/layout/MainLayout";

function MainLayout({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={new QueryClient()}>
      <MainLayoutUnderTest>{children}</MainLayoutUnderTest>
    </QueryClientProvider>
  );
}

describe("MainLayout Pages directory scope", () => {
  beforeEach(() => {
    pathname = "/workspaces/workspace-1/catalog";
    hasWorkspaceContext = true;
    organizationMemberships = [];
    inSettings = false;
  });

  it("keeps one stable Pages directory scope in the Workspace shell", () => {
    const markup = renderToStaticMarkup(
      <MainLayout><div>Catalog content</div></MainLayout>,
    );

    expect(markup).toContain("Catalog content");
    expect(markup.match(/pages-directory-provider/g)).toHaveLength(1);
    expect(markup).toContain('data-lifecycle="inactive"');
    expect(markup).toMatch(/data-testid="mobile-workspace-name"[^>]*>Workspace</);
  });

  it("does not mount Workspace-only state for Organization administration", () => {
    hasWorkspaceContext = false;
    pathname = "/organizations/example/settings";
    const markup = renderToStaticMarkup(<MainLayout><div>Organization settings</div></MainLayout>);
    expect(markup).toContain("Organization settings");
    expect(markup).not.toContain("pages-directory-provider");
  });

  it("mounts one live Pages directory in the Pages shell", () => {
    pathname = "/dev/pages-demo/page-1";
    const markup = renderToStaticMarkup(
      <MainLayout><div>Page content</div></MainLayout>,
    );

    expect(markup.match(/pages-directory-provider/g)).toHaveLength(1);
    expect(markup).toContain('data-lifecycle="live"');
  });

  it("names only the Workspace for members on Pages routes and leaves the public masthead to anonymous readers", () => {
    organizationMemberships = [{ organization: { id: "organization-1", displayName: "Research organization", plan: "FREE", createdAt: "2026-10-02T00:00:00Z" }, role: "MEMBER" }];
    for (const pathnameSuffix of ["pages", "pages/document-1", "catalog"]) {
      pathname = `/workspaces/workspace-1/${pathnameSuffix}`;
      const markup = renderToStaticMarkup(<MainLayout><div>Page content</div></MainLayout>);
      expect(markup).toMatch(/data-testid="mobile-workspace-name"[^>]*>Workspace</);
      expect(markup).not.toContain("Built with Monomer");
      expect(markup).not.toContain("Research organization · Workspace");
      expect(markup.includes('data-testid="pages-context-bar"')).toBe(pathnameSuffix !== "catalog");
      expect(markup.includes('data-testid="mobile-pages-label"')).toBe(pathnameSuffix !== "catalog");
    }
  });

  it("offers Search in the mobile top bar only inside a workspace and outside settings", () => {
    expect(renderToStaticMarkup(<MainLayout><div>Catalog content</div></MainLayout>)).toContain('aria-label="Search"');
    hasWorkspaceContext = false;
    pathname = "/organizations/example/settings";
    expect(renderToStaticMarkup(<MainLayout><div>Organization settings</div></MainLayout>)).not.toContain('aria-label="Search"');
    hasWorkspaceContext = true;
    inSettings = true;
    pathname = "/workspaces/workspace-1/settings";
    expect(renderToStaticMarkup(<MainLayout><div>Workspace settings</div></MainLayout>)).not.toContain('aria-label="Search"');
  });
});
