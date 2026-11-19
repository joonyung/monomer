import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { WorkspaceRouteFixtureProvider } from "@/components/auth/TenantRouteContext";
import { MetricDetailActions } from "@/components/metric/MetricDetailActions";

vi.mock("@/lib/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

function renderActions(role: "VIEWER" | "EXPLORER" | "EDITOR" = "EDITOR"): string {
  return renderToStaticMarkup(
    <WorkspaceRouteFixtureProvider
      workspace={{ id: "workspace-1", organizationId: "organization-1", publicPages: false, reportingTimeZone: "UTC", name: "Fixture" }}
      access={{
        granted: true,
        organizationId: "organization-1",
        role, }}
    >
      <MetricDetailActions
        exploreHref="/workspaces/workspace-1/explore?metric=revenue"
        onEdit={() => undefined}
      />
    </WorkspaceRouteFixtureProvider>,
  );
}

describe("Metric detail actions", () => {
  it("offers Edit for every metric with collection edit permission", () => {
    const markup = renderActions();

    expect(markup).toContain(">Edit<");
  });

  it("links Explore with the metric preselected for explorers, without Edit", () => {
    const markup = renderActions("EXPLORER");

    expect(markup).toContain('href="/workspaces/workspace-1/explore?metric=revenue"');
    expect(markup).not.toContain(">Edit<");
    const viewer = renderActions("VIEWER");
    expect(viewer).not.toContain("explore?metric=");
  });
});
