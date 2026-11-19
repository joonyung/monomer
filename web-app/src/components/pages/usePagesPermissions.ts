import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { hasWorkspaceRole } from "@/lib/auth/roles";
import type { WorkspaceAccess } from "@/lib/auth/types";
export function canEditPages(access: WorkspaceAccess | null): boolean { return hasWorkspaceRole(access, "EXPLORER"); }
export function useCanEditPages(): boolean { return canEditPages(useWorkspaceAccess().access); }
