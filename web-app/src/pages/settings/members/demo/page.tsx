// Deterministic fixture for Organization settings Members: pending invitations, the member table,
// and the member sheet (?member=). It renders the production surface against the in-memory fixture
// backend. Not linked from navigation; reach via /dev/organization-members-demo.
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { notFound } from "@/lib/navigation";
import { MembersSettings } from "@/components/settings/MembersSettings";
import { useAuthStore } from "@/lib/auth/store";
import { OrganizationRouteFixtureProvider } from "@/components/auth/TenantRouteContext";
import { FIXTURE_ORGANIZATION, FIXTURE_VIEWER, fixtureMembership, installOrganizationFixtureFetch } from "@/pages/settings/demo/organizationFixture";

const ROUTE = "/dev/organization-members-demo";

export default function SettingsMembersDemoPage() {
  if (!import.meta.env.DEV) notFound();
  const { member } = useSearch({ from: ROUTE });
  const navigate = useNavigate({ from: ROUTE });
  const [seeded, setSeeded] = useState(false);
  const membership = useAuthStore((store) => store.organizationMemberships[0]);

  useEffect(() => {
    const restoreFetch = installOrganizationFixtureFetch("ready", true);
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
  }, []);

  if (!seeded || !membership) return null;

  return (
    <OrganizationRouteFixtureProvider membership={membership}>
      <MembersSettings
        selectedMemberId={member ?? null}
        onSelectMember={(userId) => void navigate({ search: (current) => ({ ...current, member: userId ?? undefined }), replace: userId === null })}
      />
    </OrganizationRouteFixtureProvider>
  );
}
