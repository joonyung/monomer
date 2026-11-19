import { Outlet } from "@tanstack/react-router";
import { describe, expect, it } from "vitest";
import { OrganizationIndexRedirect, OrganizationRouteGate, WorkspaceRouteGate } from "@/components/auth/TenantRouteContext";
import { PagesDemoRouteLayout } from "@/components/pages/PagesDemoRouteLayout";
import { PagesRouteLayout } from "@/components/pages/PagesRouteLayout";
import { pagesDemoSearchSchema } from "@/lib/route-search";
import WorkspacesPage from "@/pages/workspaces/page";
import { router } from "@/router";

const branchContracts = [
  {
    parentId: "/settings",
    indexId: "/settings/",
    childIds: ["/settings/profile", "/settings/organizations", "/settings/agents", "/settings/security"],
    parentComponent: Outlet,
  },
  {
    parentId: "/dev/pages-demo",
    indexId: "/dev/pages-demo/",
    childIds: ["/dev/pages-demo/$pageId"],
    parentComponent: PagesDemoRouteLayout,
  },
  {
    parentId: "/organizations/$organizationId/settings",
    indexId: "/organizations/$organizationId/settings/",
    childIds: [
      "/organizations/$organizationId/settings/members",
      "/organizations/$organizationId/settings/workspaces",
    ],
    parentComponent: Outlet,
  },
  {
    parentId: "/workspaces/$workspaceId/settings",
    indexId: "/workspaces/$workspaceId/settings/",
    childIds: [
      "/workspaces/$workspaceId/settings/members",
      "/workspaces/$workspaceId/settings/public-access",
    ],
    parentComponent: Outlet,
  },
  {
    parentId: "/workspaces/$workspaceId/metrics",
    indexId: "/workspaces/$workspaceId/metrics/",
    childIds: [
      "/workspaces/$workspaceId/metrics/$id",
      "/workspaces/$workspaceId/metrics/new",
    ],
    parentComponent: Outlet,
  },
  {
    parentId: "/workspaces/$workspaceId/metrics/$id",
    indexId: "/workspaces/$workspaceId/metrics/$id/",
    childIds: ["/workspaces/$workspaceId/metrics/$id/edit"],
    parentComponent: Outlet,
  },
  {
    parentId: "/workspaces/$workspaceId/pages",
    indexId: "/workspaces/$workspaceId/pages/",
    childIds: ["/workspaces/$workspaceId/pages/$pageId"],
    parentComponent: PagesRouteLayout,
  },
  {
    parentId: "/workspaces/$workspaceId/semantic-models",
    indexId: "/workspaces/$workspaceId/semantic-models/",
    childIds: [
      "/workspaces/$workspaceId/semantic-models/$id",
      "/workspaces/$workspaceId/semantic-models/new",
    ],
    parentComponent: Outlet,
  },
  {
    parentId: "/workspaces/$workspaceId/semantic-models/$id",
    indexId: "/workspaces/$workspaceId/semantic-models/$id/",
    childIds: ["/workspaces/$workspaceId/semantic-models/$id/edit"],
    parentComponent: Outlet,
  },
] as const;

describe("TanStack Router tenant contract", () => {
  const paths = Object.keys(router.routesByPath);

  it("exposes canonical workspace and organization route families", () => {
    expect(paths).toContain("/workspaces/$workspaceId/catalog");
    expect(paths).not.toContain("/workspaces/$workspaceId/dashboards/$id");
    expect(paths.some((path) => path.startsWith("/workspaces/$workspaceId/agent"))).toBe(false);
    expect(paths).not.toContain("/workspaces/$workspaceId/settings/data-access");
    expect(paths).toContain("/organizations/$organizationId/settings/workspaces");
    expect(paths).not.toContain("/organizations/$organizationId/settings/workspaces/$workspaceId");
  });

  it("has no unscoped tenant resource routes", () => {
    const forbidden = ["/catalog", "/connectors", "/dashboard", "/explore", "/lineage", "/metrics", "/pages", "/semantic-models", "/settings/members", "/settings/workspaces", "/workspace/settings"];
    for (const path of forbidden) expect(paths).not.toContain(path);
  });

  it("keeps User settings independent of Organization and Workspace selection", () => {
    expect(router.routesById["/settings"].parentRoute.id).toBe("__root__");
    expect(paths).toContain("/settings/profile");
    expect(paths).toContain("/settings/agents");
    expect(paths).not.toContain("/settings/connections");
    expect(paths).not.toContain("/settings/apps");
  });

  it("keeps the workspace chooser separate from workspace-scoped routes", () => {
    const workspacesLayout = router.routesById["/workspaces"];
    const workspaceChooser = router.routesById["/workspaces/"];
    const workspaceRouteGate = router.routesById["/workspaces/$workspaceId"];
    const workspaceCatalog = router.routesById["/workspaces/$workspaceId/catalog"];

    expect(workspacesLayout.options.component).toBe(Outlet);
    expect(workspaceChooser.options.component).toBe(WorkspacesPage);
    expect(workspaceRouteGate.options.component).toBe(WorkspaceRouteGate);
    expect(workspaceChooser.parentRoute).toBe(workspacesLayout);
    expect(workspaceRouteGate.parentRoute).toBe(workspacesLayout);
    expect(workspaceCatalog.parentRoute).toBe(workspaceRouteGate);
  });

  it("separates every branch layout from its index page and child routes", () => {
    for (const branchContract of branchContracts) {
      const parentRoute = router.routesById[branchContract.parentId];

      expect(parentRoute.options.component, branchContract.parentId).toBe(branchContract.parentComponent);
      if ("indexId" in branchContract) {
        const indexRoute = router.routesById[branchContract.indexId];
        expect(indexRoute.options.component, branchContract.indexId).not.toBe(Outlet);
        expect(indexRoute.parentRoute, branchContract.indexId).toBe(parentRoute);
      }
      for (const childId of branchContract.childIds) {
        expect(router.routesById[childId].parentRoute, childId).toBe(parentRoute);
      }
    }
  });

  it("covers every non-root route that owns child routes", () => {
    const genuineLayoutBranchIds = [
      "/organizations/$organizationId",
      "/dev",
      "/workspaces",
      "/workspaces/$workspaceId",
      "/workspaces/$workspaceId/dashboards",
      "/workspaces/$workspaceId/dashboards/$dashboardId",
    ];
    const actualBranchIds = Object.values(router.routesById)
      .filter((route) => route.id !== "__root__" && Array.isArray(route.children) && route.children.length > 0)
      .map((route) => route.id)
      .sort();
    const expectedBranchIds = [
      ...genuineLayoutBranchIds,
      ...branchContracts.map(({ parentId }) => parentId),
    ].sort();

    expect(actualBranchIds).toEqual(expectedBranchIds);
    expect(router.routesById["/dev"].options.component).toBe(Outlet);
    expect(router.routesById["/workspaces"].options.component).toBe(Outlet);
    expect(router.routesById["/organizations/$organizationId"].options.component).toBe(OrganizationRouteGate);
    expect(router.routesById["/organizations/$organizationId/"].options.component).toBe(OrganizationIndexRedirect);
    expect(router.routesById["/workspaces/$workspaceId"].options.component).toBe(WorkspaceRouteGate);
  });

  it("keeps page-local search schemas on index routes", () => {
    const pagesDemoLayout = router.routesById["/dev/pages-demo"];
    const pagesDemoIndex = router.routesById["/dev/pages-demo/"];

    expect(pagesDemoLayout.options.validateSearch).toBeUndefined();
    expect(pagesDemoIndex.options.validateSearch).toBe(pagesDemoSearchSchema);
  });

  it("keeps every deterministic fixture under dev", () => {
    const fixturePaths = paths.filter((path) => path.includes("fixture") || path.includes("demo"));
    expect(fixturePaths.length).toBeGreaterThan(0);
    expect(fixturePaths.every((path) => path.startsWith("/dev/"))).toBe(true);
  });
});
