import { describe, expect, it } from "vitest";
import { hasWorkspaceRole, WORKSPACE_ROLES } from "@/lib/auth/roles";
import type { WorkspaceAccess } from "@/lib/auth/types";

describe("Workspace role admission", () => {
  it("uses cumulative direct roles across every minimum role", () => {
    WORKSPACE_ROLES.forEach((role, index) => WORKSPACE_ROLES.forEach((minimum, required) => {
      const access: WorkspaceAccess = { granted: true, organizationId: "organization", role, };
      expect(hasWorkspaceRole(access, minimum)).toBe(index >= required);
    }));
  });
  it("never grants access to a nonmember", () => {
    expect(hasWorkspaceRole({ granted: false, organizationId: "organization", role: null }, "VIEWER")).toBe(false);
    expect(hasWorkspaceRole(null, "VIEWER")).toBe(false);
  });
});
