import { useOptionalWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { useAuthStore } from "@/lib/auth/store";
import { useQuery } from "@tanstack/react-query";
import { fetchAccessibleWorkspaces } from "@/lib/auth/graphql";
import { queryKeys } from "@/lib/query/keys";

export function usePageSource(): string | null {
  const context = useOptionalWorkspaceRouteContext();
  const status = useAuthStore(state => state.status);
  const memberships = useAuthStore(state => state.organizationMemberships);
  if (!context) return null;
  const organization = status === "authenticated" && context.access.granted && context.access.role
    && context.workspaceId === context.workspace.id && context.organizationId === context.workspace.organizationId
    ? memberships.find(membership => membership.organization.id === context.organizationId)?.organization.displayName
    : undefined;
  return organization ? `${organization} · ${context.workspace.name}` : context.workspace.name;
}

function useReaderWorkspace(workspaceId: string, readerMode: "VIEWER" | "PUBLIC") {
  const status = useAuthStore(state => state.status);
  const principalId = useAuthStore(state => state.user?.id ?? null);
  const workspaceMember = status === "authenticated" && principalId !== null && readerMode === "VIEWER";
  const workspaces = useQuery({
    queryKey: queryKeys.auth.accessibleWorkspaces(principalId ?? ""),
    queryFn: ({ signal }) => fetchAccessibleWorkspaces(signal),
    enabled: workspaceMember,
  });
  return workspaceMember && workspaces.isSuccess
    ? workspaces.data.find(candidate => candidate.id === workspaceId)
    : undefined;
}

export function useReaderPageSource(workspaceId: string, workspaceName: string, readerMode: "VIEWER" | "PUBLIC"): string {
  const memberships = useAuthStore(state => state.organizationMemberships);
  const workspace = useReaderWorkspace(workspaceId, readerMode);
  const organizationName = workspace
    ? memberships.find(membership => membership.organization.id === workspace.organizationId)?.organization.displayName
    : undefined;
  return organizationName ? `${organizationName} · ${workspaceName}` : workspaceName;
}

/** A Viewer member reads the Workspace Reporting time zone; a public reader doesn't, so its time inputs stay in UTC. */
export function useReaderReportingTimeZone(workspaceId: string, readerMode: "VIEWER" | "PUBLIC"): string | null {
  return useReaderWorkspace(workspaceId, readerMode)?.reportingTimeZone ?? null;
}
