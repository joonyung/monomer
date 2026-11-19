import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { CatalogConnectorListError } from "@/components/catalog/CatalogConnectorListError";

describe("CatalogConnectorListError", () => {
  it("keeps a connector-list failure distinct from an empty catalog", () => {
    const markup = renderToStaticMarkup(
      <CatalogConnectorListError
        error="Connector list unavailable"
        retrying={false}
        onRetry={() => {}}
      />,
    );

    expect(markup).toContain('role="alert"');
    expect(markup).toContain("Connectors could not be loaded");
    expect(markup).toContain("Connector list unavailable");
    expect(markup).toContain("Retry connector list");
    expect(markup).not.toContain("No connector is available for this catalog");
  });

  it("disables the retry control while the connector list is being retried", () => {
    const markup = renderToStaticMarkup(
      <CatalogConnectorListError
        error="Connector list unavailable"
        retrying
        onRetry={() => {}}
      />,
    );

    expect(markup).toMatch(/<button[^>]*disabled[^>]*>.*Retry connector list/s);
  });
});
