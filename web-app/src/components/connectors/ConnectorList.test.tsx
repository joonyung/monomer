import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ConnectorList, connectorCountLabel, type ConnectorSummaries } from "./ConnectorList";
import type { Connector } from "@/lib/types/connector";

function connector(overrides: Partial<Connector>): Connector {
  return {
    id: "connector-1",
    workspaceId: "workspace-1",
    name: "Northstar warehouse",
    type: "POSTGRESQL",
    properties: { host: "warehouse.internal", port: 5432, database: "warehouse" },
    createdAtEpochMs: Date.now() - 60_000,
    updatedAtEpochMs: Date.now() - 60_000,
    ...overrides,
  };
}

function render(connectors: Connector[], summaries: ConnectorSummaries, canChooseTables = true): string {
  return renderToStaticMarkup(
    <ConnectorList workspaceId="workspace-1" connectors={connectors} summaries={summaries} connectorLimit={10} canChooseTables={canChooseTables} onOpen={() => undefined} />,
  );
}

describe("ConnectorList", () => {
  it("labels the managed sample as read-only", () => {
    const markup = render([connector({ type: "DUCKDB", name: "Acme Cloud", sample: { dataset: "acme-cloud@1", label: "Acme Cloud" } })], { status: "loading" });
    expect(markup).toContain("Sample data · read-only · managed by Monomer");
    expect(markup).not.toContain("PostgreSQL");
  });
  it("opens connector details without exposing credential state or a repeated edit action", () => {
    const markup = render([connector({})], { status: "loading" });

    expect(markup).toContain('aria-label="View Northstar warehouse connector"');
    expect(markup).toContain('aria-label="Search connectors"');
    expect(markup).toContain("Plan usage: 1 of 10 connectors");
    expect(markup).toContain("PostgreSQL · warehouse");
    expect(markup).toContain("border-y border-border");
    expect(markup).not.toContain("secret set");
    expect(markup).not.toContain("Revise");
    expect(markup).not.toContain("Updated");
  });

  it("shows what each connector can serve and whether its last refresh worked", () => {
    const now = new Date().toISOString();
    const markup = render([
      connector({ id: "pg", name: "Production Postgres" }),
      connector({ id: "sf", name: "Snowflake — Finance", type: "SNOWFLAKE", properties: { account: "northstar", warehouse: "FINANCE_WH", database: "FINANCE" } }),
      connector({ id: "bq", name: "BigQuery events", type: "BIGQUERY", properties: { projectId: "northstar-prod", location: "US" } }),
    ], {
      status: "loaded",
      byConnectorId: new Map([
        ["pg", { connectorId: "pg", selectedCatalogCount: 0, selectedSchemaCount: 4, tableCount: 142, lastRefresh: { jobId: "j1", state: "COMPLETED", catalogName: null, requestedAt: now, finishedAt: now, errorCount: 0, entryFailureCode: null }, lastSuccessfulRefreshAt: now }],
        ["sf", { connectorId: "sf", selectedCatalogCount: 2, selectedSchemaCount: 3, tableCount: 38, lastRefresh: { jobId: "j2", state: "COMPLETED_WITH_ERRORS", catalogName: "FINANCE", requestedAt: now, finishedAt: now, errorCount: 1, entryFailureCode: "AUTHENTICATION_FAILED" }, lastSuccessfulRefreshAt: "2025-09-30T08:00:00Z" }],
      ]),
    });

    expect(markup).toContain("142 tables");
    expect(markup).toContain("4 schemas");
    expect(markup).toContain("38 tables");
    expect(markup).toContain("2 catalogs · 3 schemas");
    expect(markup).toContain("Failed · Sign-in rejected");
    expect(markup).toContain("last good Sep 30, 2025");
    expect(markup).toContain("No tables chosen");
    expect(markup).toContain("Choose tables");
    expect(markup).toContain("/workspaces/workspace-1/catalog?connector=bq&amp;choose=1");
  });

  it("hides the choose link from viewers who cannot change the selection", () => {
    const markup = render([connector({ id: "bq" })], { status: "loaded", byConnectorId: new Map() }, false);
    expect(markup).toContain("No tables chosen");
    expect(markup).not.toContain("Choose tables");
  });

  it("counts connectors against the plan limit and matching results", () => {
    expect(connectorCountLabel(3, null, 10)).toBe("Plan usage: 3 of 10 connectors");
    expect(connectorCountLabel(3, 1, 10)).toBe("1 matching · Plan usage: 3 of 10 connectors");
    expect(connectorCountLabel(1, null, null)).toBe("1 connector");
  });

  it("groups counts in English under a browser with another default locale", () => {
    const format = Number.prototype.toLocaleString;
    const localized = vi.spyOn(Number.prototype, "toLocaleString").mockImplementation(function (this: number, locales, options) {
      return format.call(this, locales ?? "de-DE", options);
    });
    try {
      expect(connectorCountLabel(1234, 1200, 2000)).toBe("1,200 matching · Plan usage: 1,234 of 2,000 connectors");
      expect(connectorCountLabel(1234, null, null)).toBe("1,234 connectors");
      const markup = render([connector({ id: "pg" })], {
        status: "loaded",
        byConnectorId: new Map([["pg", { connectorId: "pg", selectedCatalogCount: 0, selectedSchemaCount: 1, tableCount: 1234, lastRefresh: null, lastSuccessfulRefreshAt: null }]]),
      });
      expect(markup).toContain("1,234 tables");
    } finally {
      localized.mockRestore();
    }
  });
});
