import { hasWorkspaceRole } from "@/lib/auth/roles";
import { createContext, useContext, useMemo, type ReactNode } from "react";
import type {
  WorkspaceRole,
  WorkspaceAccess,
} from "@/lib/auth/types";

export interface WorkspaceAccessContextValue {
  workspaceId: string | null;
  access: WorkspaceAccess | null;
  hasRole: (minimumRole: WorkspaceRole) => boolean;
}

const WorkspaceAccessContext = createContext<WorkspaceAccessContextValue | null>(null);

export function WorkspaceAccessContextProvider({
  workspaceId,
  access,
  children,
}: {
  workspaceId: string;
  access: WorkspaceAccess;
  children: ReactNode;
}) {
  const allow = useMemo(
    () => (minimumRole: WorkspaceRole) => hasWorkspaceRole(access, minimumRole),
    [access],
  );
  const value = useMemo<WorkspaceAccessContextValue>(() => ({
    workspaceId,
    access,
    hasRole: allow,
  }), [access, allow, workspaceId]);
  return (
    <WorkspaceAccessContext.Provider value={value}>
      {children}
    </WorkspaceAccessContext.Provider>
  );
}

export function useWorkspaceAccess(): WorkspaceAccessContextValue {
  const context = useContext(WorkspaceAccessContext);
  if (!context) throw new Error("useWorkspaceAccess must be used within a Workspace route.");
  return context;
}
