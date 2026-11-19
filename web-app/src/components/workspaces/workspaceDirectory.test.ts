import { describe, expect, it } from "vitest";
import {
  filterWorkspaceGroups,
  groupWorkspacesByOrganization,
  noWorkspaceAccessMessage,
  organizationRoleLabel,
} from "./workspaceDirectory";
import type { AccessibleWorkspace, OrganizationMembership } from "@/lib/auth/types";

const membership = (id: string, displayName: string, role: string): OrganizationMembership => ({
  organization: { id, displayName, plan: "FREE", createdAt: "2026-09-01T00:00:00Z" },
  role,
});
const workspace = (id: string, organizationId: string, name: string): AccessibleWorkspace => ({
  id, organizationId, name, publicPages: false, reportingTimeZone: "UTC", role: "EXPLORER",
});

describe("workspace directory", () => {
  const memberships = [membership("org-a", "Northstar Labs", "ADMIN"), membership("org-b", "Monomer Team", "MEMBER")];
  const workspaces = [workspace("ws-1", "org-a", "Revenue Analytics"), workspace("ws-2", "org-a", "Product")];

  it("groups workspaces under every Organization, including ones with none", () => {
    const groups = groupWorkspacesByOrganization(memberships, workspaces);
    expect(groups.map((group) => [group.membership.organization.id, group.workspaces.map((item) => item.id)])).toEqual([
      ["org-a", ["ws-1", "ws-2"]],
      ["org-b", []],
    ]);
  });

  it("filters by workspace or Organization name and drops empty Organizations", () => {
    const groups = groupWorkspacesByOrganization(memberships, workspaces);
    expect(filterWorkspaceGroups(groups, "rev").flatMap((group) => group.workspaces.map((item) => item.id))).toEqual(["ws-1"]);
    expect(filterWorkspaceGroups(groups, "northstar").flatMap((group) => group.workspaces.map((item) => item.id))).toEqual(["ws-1", "ws-2"]);
    expect(filterWorkspaceGroups(groups, "  ")).toBe(groups);
    expect(filterWorkspaceGroups(groups, "zzz")).toEqual([]);
  });

  it("explains who can add me where I have no workspace", () => {
    expect(organizationRoleLabel("ADMIN")).toBe("Admin");
    expect(organizationRoleLabel("MEMBER")).toBe("Member");
    expect(noWorkspaceAccessMessage("MEMBER")).toBe("No workspace access yet. An Admin or a Workspace Owner can add you.");
    expect(noWorkspaceAccessMessage("ADMIN")).toContain("Organization settings");
  });
});
