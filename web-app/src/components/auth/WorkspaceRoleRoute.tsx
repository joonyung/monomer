import type { ReactNode } from "react";
import { MainLayout } from "@/components/layout/MainLayout";
import { useWorkspaceAccess } from "./WorkspaceAccessProvider";
import { roleLabel } from "@/lib/auth/roles";
import type { WorkspaceRole } from "@/lib/auth/types";

export function WorkspaceRoleRoute({ minimumRole, children }: { minimumRole: WorkspaceRole; children: ReactNode }) {
  const access = useWorkspaceAccess();
  if (access.hasRole(minimumRole)) return <>{children}</>;
  return <MainLayout><section className="p-8" data-testid="workspace-role-route-denied">
    <h1 className="type-display">Requires {roleLabel(minimumRole)} role</h1>
    <p className="mt-2 type-body text-foreground-secondary">Current role: {access.access?.role ? roleLabel(access.access.role) : "No workspace role"}.</p>
    <p className="mt-2 type-body text-foreground-secondary">Ask a Workspace Owner to update your role.</p>
  </section></MainLayout>;
}
