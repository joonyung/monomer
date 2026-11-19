// Deterministic fixture for Organization settings General and Workspaces, including the Workspace
// sheet (?workspace=) and the New Workspace dialog. It renders the production surfaces against the
// in-memory fixture backend. Not linked from navigation; reach via /dev/organization-settings-demo.
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { notFound } from "@/lib/navigation";
import { GeneralSettings } from "@/components/settings/GeneralSettings";
import { WorkspacesSettings } from "@/components/settings/WorkspacesSettings";
import { useAuthStore } from "@/lib/auth/store";
import { OrganizationRouteFixtureProvider } from "@/components/auth/TenantRouteContext";
import { FIXTURE_ORGANIZATION, FIXTURE_VIEWER, fixtureMembership, installOrganizationFixtureFetch } from "./organizationFixture";

const ROUTE = "/dev/organization-settings-demo";

export default function SettingsDemoPage() {
  if (!import.meta.env.DEV) notFound();
  const { surface, state, workspace } = useSearch({ from: ROUTE });
  const navigate = useNavigate({ from: ROUTE });
  const [seeded, setSeeded] = useState(false);
  const membership = useAuthStore((store) => store.organizationMemberships[0]);

  useEffect(() => {
    const restoreFetch = installOrganizationFixtureFetch(state, true);
    useAuthStore.setState({
      status: "authenticated",
      user: FIXTURE_VIEWER,
      organizationMemberships: [fixtureMembership(FIXTURE_ORGANIZATION.displayName)],
      lastAccessibleWorkspaceId: "ws-organization-revenue",
      accessToken: "fixture-token",
      accessTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      error: null,
    });
    setSeeded(true);
    return restoreFetch;
  }, [state]);

  if (!seeded || !membership) return null;

  return (
    <OrganizationRouteFixtureProvider membership={membership}>
      {surface === "general" ? (
        <GeneralSettings />
      ) : (
        <WorkspacesSettings
          selectedWorkspaceId={workspace ?? null}
          onSelectWorkspace={(workspaceId) => void navigate({ search: (current) => ({ ...current, workspace: workspaceId ?? undefined }), replace: workspaceId === null })}
        />
      )}
    </OrganizationRouteFixtureProvider>
  );
}
