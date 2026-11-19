import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { WorkspaceAccessContextProvider } from "@/components/auth/WorkspaceAccessProvider";
import { WorkspaceRoleRoute } from "@/components/auth/WorkspaceRoleRoute";
import type { WorkspaceAccess } from "@/lib/auth/types";

vi.mock("@/components/layout/MainLayout", () => ({ MainLayout: ({ children }: { children: ReactNode }) => <main>{children}</main> }));

function renderGuard(access: WorkspaceAccess): string {
  return renderToStaticMarkup(
    <WorkspaceAccessContextProvider workspaceId="workspace-1" access={access}>
      <WorkspaceRoleRoute minimumRole="EDITOR"><div>Semantic model editor</div></WorkspaceRoleRoute>
    </WorkspaceAccessContextProvider>,
  );
}

describe("Workspace role route gate", () => {
  it("renders the editor for an assigned semantic author", () => {
    const markup = renderGuard({ granted: true, organizationId: "organization-1", role: "EDITOR" as const, });
    expect(markup).toContain("Semantic model editor");
  });

  it("renders a clear denial without the assignment", () => {
    const markup = renderGuard({ granted: true, organizationId: "organization-1", role: "VIEWER" as const, });
    expect(markup).toContain("Requires Editor role");
    expect(markup).toContain("Current role: Viewer");
    expect(markup).not.toContain("Semantic model editor");
  });
});
