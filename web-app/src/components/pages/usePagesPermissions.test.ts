import { describe, expect, it } from "vitest";
import { canEditPages } from "@/components/pages/usePagesPermissions";
import type { WorkspaceAccess } from "@/lib/auth/types";

describe("Pages entry-point authorization", () => {
  it("keeps the entry point available for a subtree-scoped editor", () => {
    const access: WorkspaceAccess = {
      granted: true,
      organizationId: "organization-1",
      role: "EXPLORER" as const, };

    expect(canEditPages(access)).toBe(true);
  });

  it("does not infer Pages edit permission from read access", () => {
    const access: WorkspaceAccess = {
      granted: true,
      organizationId: "organization-1",
      role: "VIEWER" as const, };

    expect(canEditPages(access)).toBe(false);
  });
});
