import { useEffect, useState } from "react";
import { CatalogSelectionDrawer } from "@/components/catalog/CatalogSelectionDrawer";
import { WorkspaceRouteFixtureProvider } from "@/components/auth/TenantRouteContext";
import { useAuthStore } from "@/lib/auth/store";
import { notFound } from "@/lib/navigation";
import type { CatalogSelection } from "@/lib/types/catalog";
import type { Connector } from "@/lib/types/connector";

const WORKSPACE_ID = "83000000-0000-7000-8000-000000000001";
const ORGANIZATION_ID = "83000000-0000-7000-8000-000000000002";
const PRINCIPAL_ID = "83000000-0000-7000-8000-000000000003";
const CONNECTOR_ID = "83000000-0000-7000-8000-000000000004";

const MOCK_SELECTION: CatalogSelection = {
  selectedSchemas: [
    { schemaName: "marketing", relations: { kind: "SELECTED_RELATIONS", relationNames: ["campaigns", "campaign_daily"] } },
  ],
};

const MOCK_CONNECTOR: Connector = {
  id: CONNECTOR_ID,
  workspaceId: WORKSPACE_ID,
  name: "Finance warehouse",
  type: "SNOWFLAKE",
  properties: { account: "northstar", warehouse: "REPORTING", database: "analytics" },
  createdAtEpochMs: 1,
  updatedAtEpochMs: 1,
};

export default function CatalogSelectionDemoPage() {
  const [open, setOpen] = useState(true);
  const [ready, setReady] = useState(false);
  if (!import.meta.env.DEV) notFound();

  useEffect(() => {
    useAuthStore.setState({
      status: "authenticated",
      user: { id: PRINCIPAL_ID, email: "catalog@monomer.test", displayName: "Catalog Operator" },
      organizationMemberships: [],
      accessToken: "catalog-selection-fixture",
      accessTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      error: null,
    });
    setReady(true);
  }, []);

  if (!ready) return null;
  return (
    <WorkspaceRouteFixtureProvider
      workspace={{ id: WORKSPACE_ID, organizationId: ORGANIZATION_ID, publicPages: false, reportingTimeZone: "UTC", name: "Analytics" }}
      access={{ granted: true, organizationId: ORGANIZATION_ID, role: "OWNER" as const, }}
    >
      <div className="min-h-screen bg-surface">
        <CatalogSelectionDrawer
          canEdit
          canRefresh
          open={open}
          connector={MOCK_CONNECTOR}
          catalogName="analytics"
          catalogStep={false}
          selection={MOCK_SELECTION}
          loading={false}
          saving={false}
          error={null}
          returnTo={`/workspaces/${WORKSPACE_ID}/catalog?connector=${CONNECTOR_ID}&catalog=analytics&choose=1`}
          onClose={() => setOpen(false)}
          onSave={async () => undefined}
          onCatalogsSaved={() => undefined}
        />
      </div>
    </WorkspaceRouteFixtureProvider>
  );
}
