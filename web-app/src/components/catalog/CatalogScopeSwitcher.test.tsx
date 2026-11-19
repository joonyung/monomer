import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { CatalogScopeSwitcher } from "@/components/catalog/CatalogScopeSwitcher";
import type { Connector } from "@/lib/types/connector";

function connector(id: string, name: string, type: Connector["type"]): Connector {
  return { id, workspaceId: "workspace-1", name, type, properties: { host: "fixture.internal", port: 5432, database: "warehouse" }, createdAtEpochMs: 1, updatedAtEpochMs: 1 };
}

const connectors = [connector("snowflake", "Finance warehouse", "SNOWFLAKE"), connector("postgres", "Product database", "POSTGRESQL")];

describe("CatalogScopeSwitcher", () => {
  it("switches a connector without a catalog level through one connector picker", () => {
    const markup = renderToStaticMarkup(
      <CatalogScopeSwitcher connectors={connectors} selection={{ connectorId: "postgres", catalogName: null }} catalogs={null} onSelect={() => undefined} onManageCatalogs={() => undefined} />,
    );
    expect(markup).toContain('aria-label="Connector"');
    expect(markup).toContain("Product database");
    expect(markup).not.toContain('aria-label="Catalog"');
    expect(markup).not.toContain("Manage catalogs");
    expect(markup).not.toContain("var(--accent-marker)");
  });

  it("adds the catalog picker and catalog management for a three-level connector", () => {
    const markup = renderToStaticMarkup(
      <CatalogScopeSwitcher connectors={connectors} selection={{ connectorId: "snowflake", catalogName: "analytics" }} catalogs={{ status: "loaded", catalogNames: ["analytics", "raw"] }} onSelect={() => undefined} onManageCatalogs={() => undefined} />,
    );
    expect(markup).toContain('aria-label="Catalog"');
    expect(markup).toContain("analytics");
    expect(markup).toContain("Manage catalogs");
  });

  it("names an empty catalog membership instead of showing a blank picker", () => {
    const markup = renderToStaticMarkup(
      <CatalogScopeSwitcher connectors={connectors} selection={{ connectorId: "snowflake", catalogName: null }} catalogs={{ status: "loaded", catalogNames: [] }} onSelect={() => undefined} onManageCatalogs={null} />,
    );
    expect(markup).toContain("No catalogs selected");
    expect(markup).not.toContain("Manage catalogs");
  });
});
