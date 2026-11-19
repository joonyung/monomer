import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { WorkspaceRouteFixtureProvider } from "@/components/auth/TenantRouteContext";
import { ConnectionRecovery } from "@/components/catalog/ConnectionRecovery";
import type { WorkspaceRole } from "@/lib/auth/types";
import { useWorkspaceOwnersQuery } from "@/components/settings/workspaceSettingsQueries";

vi.mock("@/components/settings/workspaceSettingsQueries", () => ({ useWorkspaceOwnersQuery: vi.fn() }));

const ownersQuery = vi.mocked(useWorkspaceOwnersQuery);

beforeEach(() => ownersQuery.mockClear());

const WORKSPACE_ID = "83000000-0000-4000-8000-000000000001";
const ORGANIZATION_ID = "83000000-0000-4000-8000-000000000002";
const CONNECTOR = { id: "83000000-0000-4000-8000-000000000010", type: "SNOWFLAKE" as const };
const RETURN_TO = `/workspaces/${WORKSPACE_ID}/catalog?connector=${CONNECTOR.id}&choose=1`;

function render(role: WorkspaceRole, failureCode: string | null, owners: { userId: string; displayName: string }[] | null = null): string {
  ownersQuery.mockReturnValue({ data: owners ?? undefined } as ReturnType<typeof useWorkspaceOwnersQuery>);
  const queryClient = new QueryClient();
  return renderToStaticMarkup(
    <WorkspaceRouteFixtureProvider workspace={{ id: WORKSPACE_ID, organizationId: ORGANIZATION_ID, publicPages: false, reportingTimeZone: "UTC", name: "Analytics" }} access={{ granted: true, organizationId: ORGANIZATION_ID, role }}>
      <QueryClientProvider client={queryClient}>
        <ConnectionRecovery failureCode={failureCode} safeMessage="JDBC driver said no" connector={CONNECTOR} consequence="Nothing was saved to the catalog." returnTo={RETURN_TO} onRetry={() => undefined} retrying={false} links={null} density="panel" />
      </QueryClientProvider>
    </WorkspaceRouteFixtureProvider>,
  );
}

describe("ConnectionRecovery", () => {
  it("explains a rejected sign-in and lets an Owner fix it and come back", () => {
    const markup = render("OWNER", "AUTHENTICATION_FAILED");
    expect(markup).toContain("Monomer couldn&#x27;t sign in to Snowflake.");
    expect(markup).toContain("The warehouse rejected the username or key. Nothing was saved to the catalog.");
    expect(markup).toContain("Replace credentials");
    expect(markup).toContain("Edit settings");
    expect(markup).toContain("Try again");
    expect(markup).toContain(`mode=rotate&amp;returnTo=${encodeURIComponent(RETURN_TO)}`);
    expect(markup).toContain(`mode=revise&amp;returnTo=${encodeURIComponent(RETURN_TO)}`);
    expect(markup).toContain("After you fix it, you come back here.");
    expect(markup).not.toContain("Ask a workspace owner");
  });

  it("tells everyone else who can fix the connector", () => {
    const markup = render("EDITOR", "CONNECTION_FAILED");
    expect(markup).toContain("Monomer couldn&#x27;t reach Snowflake.");
    expect(markup).toContain("Ask a workspace owner to fix this connector.");
    expect(markup).not.toContain("Replace credentials");
    expect(markup).not.toContain("After you fix it");
  });

  it.each(["BIGQUERY_EXECUTION_FAILED", "WAREHOUSE_QUERY_FAILED", null])("keeps the safe message and neutral repair routes for an unclassified failure (%s)", (failureCode) => {
    const markup = render("OWNER", failureCode);
    expect(markup).toContain("Monomer couldn&#x27;t read this from the warehouse.");
    expect(markup).toContain("JDBC driver said no");
    expect(markup).toContain("Replace credentials");
    expect(markup).toContain("Edit settings");
    expect(markup).toContain(`mode=rotate&amp;returnTo=${encodeURIComponent(RETURN_TO)}`);
    expect(markup).toContain(`mode=revise&amp;returnTo=${encodeURIComponent(RETURN_TO)}`);
    expect(markup).not.toContain("The warehouse rejected the username or key.");
    expect(markup).not.toContain("Check the host, port, and network settings.");
    expect(markup).toContain("Try again");
    expect(ownersQuery).toHaveBeenCalledWith(WORKSPACE_ID, false);
  });

  it.each(["BIGQUERY_EXECUTION_FAILED", "WAREHOUSE_QUERY_FAILED", null])("names the Owner for an unclassified metadata failure (%s)", (failureCode) => {
    const markup = render("EXPLORER", failureCode, [{ userId: "owner", displayName: "Mina Park" }]);
    expect(markup).toContain("JDBC driver said no");
    expect(markup).toContain("Ask a workspace owner (Mina Park).");
    expect(markup).toContain("Try again");
    expect(markup).not.toContain("Replace credentials");
    expect(markup).not.toContain("Edit settings");
    expect(ownersQuery).toHaveBeenCalledWith(WORKSPACE_ID, true);
  });

  it("retains access-denied instructions without suggesting a settings or credential diagnosis", () => {
    const markup = render("OWNER", "PERMISSION_DENIED");
    expect(markup).toContain("Grant read access to the connector&#x27;s account, then try again.");
    expect(markup).not.toContain("Replace credentials");
    expect(markup).not.toContain("Edit settings");
  });
});
