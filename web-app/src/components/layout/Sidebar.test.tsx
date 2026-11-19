import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import {
  SidebarNavigationRegion,
  _internal,
} from "@/components/layout/Sidebar";

function renderSidebarNavigation(
  mode: "workspace" | "pages",
  workspacePath?: string,
  collapsed = false,
  transition: "forward" | "backward" | null = null,
): string {
  return renderToStaticMarkup(
    <SidebarNavigationRegion
      mode={mode}
      transition={transition}
      pathname={workspacePath ?? (mode === "pages" ? "/workspaces/workspace-1/pages/page-1" : "/workspaces/workspace-1/catalog")}
      workspaceNavGroups={_internal.workspaceNavigationGroups("workspace-1")}
      pagesNavigation={<li data-testid="page-tree-item">Quarterly plan</li>}
      collapsed={collapsed}
    />,
  );
}

describe("contextual sidebar navigation", () => {
  it("renders Pages as a sidebar context button directly below Overview, then Activity, Sources, Model, and Analyze", () => {
    const markup = renderSidebarNavigation("workspace");

    expect(markup).toContain('aria-label="Workspace navigation"');
    expect(markup).toMatch(/<button type="button"[^>]*>.*Pages.*<\/button>/);
    expect(markup).not.toContain('href="/workspaces/workspace-1/pages"');
    expect(markup.indexOf("Overview")).toBeLessThan(markup.indexOf("Pages"));
    expect(markup).not.toContain("Quarterly plan");
    expect(markup).toMatch(/<a[^>]*href="\/workspaces\/workspace-1\/activity"[^>]*>.*Activity.*<\/a>/);
    const order = ["Overview", "Pages", "Activity", "Sources", "Connectors", "Catalog", "Model", "Semantics", "Analyze", "Explore", "Dashboards"]
      .map((label) => markup.indexOf(`>${label}<`));
    expect(order.every((position) => position >= 0)).toBe(true);
    expect([...order].sort((left, right) => left - right)).toEqual(order);
    expect(markup).not.toContain(">Data<");
    expect(markup).not.toContain(">Surfaces<");
    expect(markup).not.toContain(">Settings<");
    expect(markup).not.toContain("Semantic Models");
    expect(markup).not.toContain(">Metrics<");
    expect(markup).toContain('data-transition="none"');
  });

  it("puts Settings only for managers and always Help in the stack above the user", () => {
    expect(_internal.workspaceUtilityItems("workspace-1", true, "/workspaces/workspace-1").map((item) => item.name)).toEqual(["Settings", "Help"]);
    const memberItems = _internal.workspaceUtilityItems("workspace-1", false, "/workspaces/workspace-1/catalog");
    expect(memberItems.map((item) => item.name)).toEqual(["Help"]);
    expect(memberItems[0]).toMatchObject({ external: true, href: "https://monomer.cc/en/docs/sources/catalog/" });
  });

  it("marks Pages active on Pages routes when the sidebar shows workspace navigation", () => {
    const markup = renderSidebarNavigation("workspace", "/workspaces/workspace-1/pages/page-1");
    expect(markup.match(/aria-current="page"/g)).toHaveLength(1);
    expect(markup).toMatch(/<button[^>]*aria-current="page"[^>]*>.*Pages.*<\/button>/);
  });

  it("adds directional motion only for an explicit context transition", () => {
    const ordinaryWorkspaceRender = renderSidebarNavigation("workspace");
    const explicitPagesTransition = renderSidebarNavigation("pages", undefined, false, "forward");
    const explicitWorkspaceTransition = renderSidebarNavigation("workspace", undefined, false, "backward");

    expect(ordinaryWorkspaceRender).toContain('data-transition="none"');
    expect(explicitPagesTransition).toContain('data-transition="forward"');
    expect(explicitWorkspaceTransition).toContain('data-transition="backward"');
  });

  it("renders only the Pages hierarchy in Pages navigation", () => {
    const markup = renderSidebarNavigation("pages");

    expect(markup).toContain('aria-label="Pages navigation"');
    expect(markup).toContain('aria-label="Back to workspace navigation"');
    expect(markup).toContain("Pages");
    expect(markup).toContain("Quarterly plan");
    expect(markup).not.toContain("All pages");
    expect(markup).not.toContain('href="/workspaces/workspace-1/pages"');
    expect(markup).not.toContain('aria-current="page"');
    expect(markup).not.toContain("Connectors");
    expect(markup).not.toContain("Dashboards");
    expect(markup).not.toContain("Settings");
  });

  it("keeps Semantics active on its collection and both resource detail routes", () => {
    for (const pathname of [
      "/workspaces/workspace-1/semantics",
      "/workspaces/workspace-1/semantic-models/model-1",
      "/workspaces/workspace-1/metrics/metric-1/edit",
    ]) {
      const markup = renderSidebarNavigation("workspace", pathname);
      expect(markup).toMatch(/<a[^>]*aria-current="page"[^>]*>.*Semantics.*<\/a>/);
    }
  });

  it("marks exactly one destination active, even though every route nests under Overview", () => {
    const navGroups = [
      ..._internal.workspaceNavigationGroups("workspace-1"),
      { items: _internal.workspaceUtilityItems("workspace-1", true, "/workspaces/workspace-1") },
    ];

    for (const [pathname, expectedHref] of [
      ["/workspaces/workspace-1", "/workspaces/workspace-1"],
      ["/workspaces/workspace-1/activity", "/workspaces/workspace-1/activity"],
      ["/workspaces/workspace-1/connectors", "/workspaces/workspace-1/connectors"],
      ["/workspaces/workspace-1/catalog", "/workspaces/workspace-1/catalog"],
      ["/workspaces/workspace-1/semantics", "/workspaces/workspace-1/semantics"],
      ["/workspaces/workspace-1/lineage", "/workspaces/workspace-1/semantics"],
      ["/workspaces/workspace-1/semantic-models/model-1", "/workspaces/workspace-1/semantics"],
      ["/workspaces/workspace-1/explore", "/workspaces/workspace-1/explore"],
      ["/workspaces/workspace-1/dashboards", "/workspaces/workspace-1/dashboards"],
      ["/workspaces/workspace-1/dashboards/dashboard-1", "/workspaces/workspace-1/dashboards"],
      ["/workspaces/workspace-1/settings", "/workspaces/workspace-1/settings"],
    ] as const) {
      expect(_internal.activeNavigationHref(navGroups, pathname)).toBe(expectedHref);
    }
  });

  it("leaves the nav inactive on a nested route no destination owns", () => {
    const navGroups = _internal.workspaceNavigationGroups("workspace-1");

    expect(_internal.activeNavigationHref(navGroups, "/workspaces/workspace-1/unsupported/item-1")).toBeNull();

    const markup = renderSidebarNavigation("workspace", "/workspaces/workspace-1/unsupported/item-1");
    expect(markup).not.toContain('aria-current="page"');
  });

  it("stops rendering Overview as active once the viewer navigates into a nested destination", () => {
    const overviewActive = renderSidebarNavigation("workspace", "/workspaces/workspace-1");
    expect(overviewActive).toMatch(/<a[^>]*aria-current="page"[^>]*>.*Overview.*<\/a>/);

    for (const pathname of [
      "/workspaces/workspace-1/semantics",
      "/workspaces/workspace-1/lineage",
    ]) {
      const markup = renderSidebarNavigation("workspace", pathname);
      expect(markup.match(/aria-current="page"/g)).toHaveLength(1);
      expect(markup).not.toMatch(/<a[^>]*aria-current="page"[^>]*>.*Overview.*<\/a>/);
    }
    expect(renderSidebarNavigation("workspace", "/workspaces/workspace-1/settings")).not.toContain('aria-current="page"');
  });

  it("reduces collapsed Workspace navigation to icon links and accessible tooltips", () => {
    const markup = renderSidebarNavigation(
      "workspace",
      "/workspaces/workspace-1/semantics",
      true,
    );

    expect(markup.match(/aria-current="page"/g)).toHaveLength(1);
    expect(markup).toContain('aria-label="Semantics"');
    expect(markup).toMatch(/<button type="button" aria-label="Pages"/);
    expect(markup).toContain('role="tooltip"');
    expect(markup).not.toContain(">Sources<");
    expect(markup).not.toContain(">Analyze<");
  });

  it("uses only the Pages context mark when Pages navigation is collapsed", () => {
    const markup = renderSidebarNavigation(
      "pages",
      "/workspaces/workspace-1/pages/page-1",
      true,
    );

    expect(markup).toContain('aria-label="Back to workspace navigation"');
    expect(markup).toContain('role="tooltip"');
    expect(markup).toContain("Back to workspace navigation");
    expect(markup).not.toContain("Quarterly plan");
    expect(markup).not.toContain("New top-level folder");
  });
});
