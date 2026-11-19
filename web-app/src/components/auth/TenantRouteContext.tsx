import { isPagesPath } from "./AuthProvider";
import { PagesAccessGate } from "@/components/pages/PublicPagesExperience";
import { usePathname } from "@/lib/navigation";
import { createContext, useContext, useEffect, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Navigate, Outlet } from "@tanstack/react-router";
import { z } from "zod";
import { fetchAccessibleWorkspaces, fetchManagedWorkspaces } from "@/lib/auth/graphql";
import { fetchWorkspaceContext } from "@/api/workspace-context";
import type { Organization, OrganizationMembership, Workspace, WorkspaceAccess } from "@/lib/auth/types";
import { useAuthStore } from "@/lib/auth/store";
import { useParams } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { WorkspaceAccessContextProvider } from "@/components/auth/WorkspaceAccessProvider";
import { Button, buttonClassName } from "@/components/ui/Button";
import { EntryFrame, EntryStatus } from "@/components/auth/EntryFrame";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";

export interface WorkspaceRouteContextValue {
  workspaceId: string;
  workspace: Workspace;
  organizationId: string;
  access: WorkspaceAccess;
}

export interface OrganizationRouteContextValue {
  organizationId: string;
  organization: Organization;
  membership: OrganizationMembership;
}

const WorkspaceRouteContext = createContext<WorkspaceRouteContextValue | null>(null);
const OrganizationRouteContext = createContext<OrganizationRouteContextValue | null>(null);
const workspaceIdSchema = z.string().uuid();

export function resolveWorkspaceRouteGateOutcome(input: {
  validWorkspaceId: boolean;
  status: "idle" | "hydrating" | "authenticated" | "unauthenticated";
  accessPending: boolean;
  workspacesPending: boolean;
  accessError: boolean;
  workspacesError: boolean;
  grantedWorkspaceResolved: boolean;
}): "invalid" | "loading" | "error" | "unavailable" | "ready" {
  if (!input.validWorkspaceId) return "invalid";
  if (input.status === "idle" || input.status === "hydrating" || input.accessPending || input.workspacesPending) return "loading";
  if (input.accessError || input.workspacesError) return "error";
  return input.grantedWorkspaceResolved ? "ready" : "unavailable";
}

export function useAccessibleWorkspacesQuery() {
  const status = useAuthStore((state) => state.status);
  const principalId = useAuthStore((state) => state.user?.id ?? null);
  return useQuery({
    queryKey: queryKeys.auth.accessibleWorkspaces(principalId ?? ""),
    queryFn: ({ signal }) => fetchAccessibleWorkspaces(signal),
    enabled: status === "authenticated" && principalId !== null,
  });
}

export function useManagedWorkspacesQuery(organizationId: string | null) {
  const principalId = useAuthStore((state) => state.user?.id ?? null);
  return useQuery({
    queryKey: queryKeys.auth.managedWorkspaces(principalId ?? "", organizationId ?? ""),
    queryFn: ({ signal }) => fetchManagedWorkspaces(organizationId as string, signal),
    enabled: principalId !== null && organizationId !== null,
  });
}

function RouteStatus({
  kind,
  title,
  description,
  actions,
}: {
  kind: "info" | "error";
  title: string;
  description: string;
  actions?: ReactNode;
}) {
  return (
    <EntryFrame>
      <EntryStatus kind={kind} title={title} description={description} actions={actions} />
    </EntryFrame>
  );
}

function RouteLoading({ title }: { title: string }) {
  return (
    <EntryFrame>
      <EntryStatus kind="loading" title={title} />
    </EntryFrame>
  );
}

function ChooseWorkspaceLink({ variant }: { variant: "primary" | "ghost" }) {
  return (
    <Link href={webPaths.workspaces} className={buttonClassName(variant, "md", "")}>
      Choose workspace
    </Link>
  );
}

export function WorkspaceRouteGate() {
  const pathname = usePathname();
  const { workspaceId } = useParams<{ workspaceId: string }>();
  return isPagesPath(pathname) ? <PagesAccessGate workspaceId={workspaceId}><MemberWorkspaceRouteGate /></PagesAccessGate> : <MemberWorkspaceRouteGate />;
}

function MemberWorkspaceRouteGate() {
  const pathname = usePathname();
  const { workspaceId: rawWorkspaceId } = useParams<{ workspaceId: string }>();
  const parsedWorkspaceId = workspaceIdSchema.safeParse(rawWorkspaceId);
  const status = useAuthStore((state) => state.status);
  const principalId = useAuthStore((state) => state.user?.id ?? null);
  const setLandingPreference = useAuthStore((state) => state.setLandingPreference);
  const workspacesQuery = useAccessibleWorkspacesQuery();
  const accessQuery = useQuery({
    queryKey: queryKeys.workspaceContext.detail(rawWorkspaceId, principalId ?? ""),
    queryFn: ({ signal }) => fetchWorkspaceContext(rawWorkspaceId, signal),
    enabled: parsedWorkspaceId.success && status === "authenticated" && principalId !== null,
    retry: false,
    refetchInterval: 15_000,
  });
  const access = accessQuery.data ?? null;
  const workspace = workspacesQuery.data?.find((candidate) => candidate.id === rawWorkspaceId) ?? null;
  const outcome = resolveWorkspaceRouteGateOutcome({
    validWorkspaceId: parsedWorkspaceId.success,
    status,
    accessPending: accessQuery.isPending,
    workspacesPending: workspacesQuery.isPending,
    accessError: accessQuery.isError,
    workspacesError: workspacesQuery.isError,
    grantedWorkspaceResolved: Boolean(access && workspace && access.organizationId),
  });

  useEffect(() => {
    if (access && workspace) setLandingPreference(rawWorkspaceId);
  }, [access, rawWorkspaceId, setLandingPreference, workspace]);

  if (outcome === "invalid") {
    return <RouteStatus kind="info" title="Page not found" description="The workspace URL is not valid." actions={<ChooseWorkspaceLink variant="primary" />} />;
  }
  if (outcome === "loading") {
    return <RouteLoading title="Opening workspace…" />;
  }
  const retry = () => { void accessQuery.refetch(); void workspacesQuery.refetch(); };
  if (outcome === "error") {
    return (
      <RouteStatus
        kind="error"
        title="Workspace could not be loaded"
        description="The access check didn't complete. Try again."
        actions={<><Button onClick={retry}>Retry</Button><ChooseWorkspaceLink variant="ghost" /></>}
      />
    );
  }
  if (outcome === "unavailable" || !access || !workspace || !access.organizationId || !access.role) {
    return (
      <RouteStatus
        kind="info"
        title="You don't have access to this workspace"
        description="It may have been deleted, or a workspace owner removed your access. Ask a workspace owner to add you."
        actions={<><ChooseWorkspaceLink variant="primary" /><Button variant="ghost" onClick={retry}>Retry</Button></>}
      />
    );
  }
  if (access.role === "VIEWER") {
    if (pathname.replace(/\/$/, "") === webPaths.workspace(rawWorkspaceId).replace(/\/$/, "")) return <Navigate to="/workspaces/$workspaceId/pages" params={{ workspaceId: rawWorkspaceId }} replace />;
    return (
      <RouteStatus
        kind="info"
        title="Your workspace is in Pages"
        description="Your Viewer role gives you access to Pages and their published results."
        actions={<>
          <Link href={webPaths.pages(rawWorkspaceId)} className={buttonClassName("ghost", "md", "")}>Open Pages</Link>
          <ChooseWorkspaceLink variant="ghost" />
        </>}
      />
    );
  }
  const value = { workspaceId: rawWorkspaceId, workspace, organizationId: access.organizationId, access };
  return (
    <WorkspaceRouteContext.Provider value={value}>
      <WorkspaceAccessContextProvider workspaceId={rawWorkspaceId} access={access}>
        <Outlet />
      </WorkspaceAccessContextProvider>
    </WorkspaceRouteContext.Provider>
  );
}

export function OrganizationRouteGate() {
  const pathname = usePathname();
  const { organizationId } = useParams<{ organizationId: string }>();
  const status = useAuthStore((state) => state.status);
  const organizationMemberships = useAuthStore((state) => state.organizationMemberships);
  if (status === "idle" || status === "hydrating") {
    return <RouteLoading title="Opening Organization settings…" />;
  }
  const membership = organizationMemberships.find((candidate) => candidate.organization.id === organizationId) ?? null;
  if (!membership) {
    return <RouteStatus kind="info" title="Page not found" description="The Organization URL is not available." actions={<ChooseWorkspaceLink variant="primary" />} />;
  }
  if (membership.role !== "ADMIN") {
    // The bare Organization URL is where people land from links; a Member's place is their workspaces.
    if (pathname.replace(/\/$/, "") === `/organizations/${organizationId}`) return <Navigate to="/workspaces" replace />;
    return (
      <RouteStatus
        kind="info"
        title="Organization settings unavailable"
        description="Only Organization Admins can open Organization settings. Ask an Admin of this Organization for access."
        actions={<ChooseWorkspaceLink variant="primary" />}
      />
    );
  }
  const value = {
    organizationId: membership.organization.id,
    organization: membership.organization,
    membership,
  };
  return <OrganizationRouteContext.Provider value={value}><Outlet /></OrganizationRouteContext.Provider>;
}

// An Admin opening the Organization itself lands on its settings; the gate has already sent
// everyone else elsewhere.
export function OrganizationIndexRedirect() {
  const { organizationId } = useParams<{ organizationId: string }>();
  return <Navigate to="/organizations/$organizationId/settings" params={{ organizationId }} replace />;
}

export function WorkspaceRouteFixtureProvider({
  workspace,
  access,
  children,
}: {
  workspace: Workspace;
  access: WorkspaceAccess;
  children: ReactNode;
}) {
  const value = { workspaceId: workspace.id, workspace, organizationId: workspace.organizationId, access };
  return (
    <WorkspaceRouteContext.Provider value={value}>
      <WorkspaceAccessContextProvider workspaceId={workspace.id} access={access}>
        {children}
      </WorkspaceAccessContextProvider>
    </WorkspaceRouteContext.Provider>
  );
}

export function OrganizationRouteFixtureProvider({
  membership,
  children,
}: {
  membership: OrganizationMembership;
  children: ReactNode;
}) {
  const value = {
    organizationId: membership.organization.id,
    organization: membership.organization,
    membership,
  };
  return <OrganizationRouteContext.Provider value={value}>{children}</OrganizationRouteContext.Provider>;
}

export function useWorkspaceRouteContext(): WorkspaceRouteContextValue {
  const context = useContext(WorkspaceRouteContext);
  if (!context) throw new Error("useWorkspaceRouteContext must be used within a Workspace route.");
  return context;
}

export function useOptionalWorkspaceRouteContext(): WorkspaceRouteContextValue | null {
  return useContext(WorkspaceRouteContext);
}

export function useOrganizationRouteContext(): OrganizationRouteContextValue {
  const context = useContext(OrganizationRouteContext);
  if (!context) throw new Error("useOrganizationRouteContext must be used within an Organization route.");
  return context;
}

export function useOptionalOrganizationRouteContext(): OrganizationRouteContextValue | null {
  return useContext(OrganizationRouteContext);
}
