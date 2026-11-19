import { describe, expect, it } from "vitest";
import { webPaths, workspaceSwitchPath } from "./web-paths";

describe("canonical web paths", () => {
  it("builds workspace and organization routes from immutable identifiers", () => {
    expect(webPaths.catalog("workspace / one")).toBe("/workspaces/workspace%20%2F%20one/catalog");
    expect(webPaths.connectors("workspace / one")).toBe("/workspaces/workspace%20%2F%20one/connectors");
    expect(webPaths.lineage("workspace / one")).toBe("/workspaces/workspace%20%2F%20one/lineage");
    expect(webPaths.dashboard("workspace / one", "dashboard / one")).toBe("/workspaces/workspace%20%2F%20one/dashboards/dashboard%20%2F%20one");
    expect(webPaths.dashboardBuilder("workspace / one", "dashboard / one")).toBe("/workspaces/workspace%20%2F%20one/dashboards/dashboard%20%2F%20one/builder");
    expect(webPaths.organizationMembers("00000000-0000-4000-8000-000000000002")).toBe("/organizations/00000000-0000-4000-8000-000000000002/settings/members");
    expect(webPaths.organizationMemberSheet("organization", "user / one")).toBe("/organizations/organization/settings/members?member=user%20%2F%20one");
    expect(webPaths.organizationWorkspaceSheet("organization", "workspace / one")).toBe("/organizations/organization/settings/workspaces?workspace=workspace%20%2F%20one");
    expect(webPaths.workspaceMembers("workspace / one")).toBe("/workspaces/workspace%20%2F%20one/settings/members");
    expect(webPaths.workspacePublicAccess("workspace")).toBe("/workspaces/workspace/settings/public-access");
  });

  it("carries only a non-default Activity scope", () => {
    expect(webPaths.activity("workspace / one", null)).toBe("/workspaces/workspace%20%2F%20one/activity");
    expect(webPaths.activity("workspace", "all")).toBe("/workspaces/workspace/activity");
    expect(webPaths.activity("workspace", "mine")).toBe("/workspaces/workspace/activity?scope=mine");
    expect(webPaths.activity("workspace", "agents")).toBe("/workspaces/workspace/activity?scope=agents");
  });

  it("preserves stateless and collection surfaces during workspace switching", () => {
    expect(workspaceSwitchPath("/workspaces/old/catalog", "next")).toBe("/workspaces/next/catalog");
    expect(workspaceSwitchPath("/workspaces/old/lineage", "next")).toBe("/workspaces/next/lineage");
    expect(workspaceSwitchPath("/workspaces/old/connectors", "next")).toBe("/workspaces/next/connectors");
    expect(workspaceSwitchPath("/workspaces/old/semantics", "next")).toBe("/workspaces/next/semantics");
    expect(workspaceSwitchPath("/workspaces/old/dashboards", "next")).toBe("/workspaces/next/dashboards");
    expect(workspaceSwitchPath("/workspaces/old/activity", "next")).toBe("/workspaces/next/activity");
  });

  it("resets detail routes to the target workspace collection", () => {
    expect(workspaceSwitchPath("/workspaces/old/metrics/revenue/edit", "next")).toBe("/workspaces/next/semantics");
    expect(workspaceSwitchPath("/workspaces/old/semantic-models/orders/edit", "next")).toBe("/workspaces/next/semantics?view=models");
    expect(workspaceSwitchPath("/workspaces/old/dashboards/orders", "next")).toBe("/workspaces/next/dashboards");
  });
});
