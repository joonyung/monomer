import { useSearch } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { WorkspaceRouteFixtureProvider } from "@/components/auth/TenantRouteContext";
import { useAuthStore } from "@/lib/auth/store";
import { notFound } from "@/lib/navigation";
import CatalogPage from "@/pages/catalog/page";

export const CATALOG_PRODUCTION_DEMO_WORKSPACE_ID = "83000000-0000-4000-8000-000000000001";
const ORGANIZATION_ID = "83000000-0000-4000-8000-000000000002";
const PRINCIPAL_ID = "83000000-0000-4000-8000-000000000003";

export default function CatalogProductionDemoPage() {
  if (!import.meta.env.DEV) notFound();
  const { role, ...target } = useSearch({ from: "/dev/catalog-production-demo" });
  const [ready, setReady] = useState(false);

  useEffect(() => {
    useAuthStore.setState({
      status: "authenticated",
      user: { id: PRINCIPAL_ID, email: "catalog@monomer.test", displayName: "Catalog Operator" },
      organizationMemberships: [],
      accessToken: "catalog-production-fixture",
      accessTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      error: null,
    });
    setReady(true);
  }, []);

  if (!ready) return null;
  return (
    <WorkspaceRouteFixtureProvider
      workspace={{ id: CATALOG_PRODUCTION_DEMO_WORKSPACE_ID, organizationId: ORGANIZATION_ID, publicPages: false, reportingTimeZone: "UTC", name: "Northstar Studio" }}
      access={{
        granted: true,
        organizationId: ORGANIZATION_ID,
        role, }}
    >
      <CatalogPage target={target} />
    </WorkspaceRouteFixtureProvider>
  );
}
