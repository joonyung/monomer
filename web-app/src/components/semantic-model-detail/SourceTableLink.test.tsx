import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { SourceTableDisclosure } from "./SourceTableLink";

vi.mock("@/lib/navigation", () => ({ Link: ({ href, children, className }: { href: string; children: ReactNode; className?: string }) => <a href={href} className={className}>{children}</a> }));

const connectors = [{ id: "c1", name: "Warehouse" }, { id: "c2", name: "Analytics replica" }, { id: "c3", name: "Compute warehouse" }];
const sources = [
  { connectorId: "c1", schema: "analytics", relation: "orders" },
  { connectorId: "c2", catalog: "replica", schema: "analytics", relation: "orders_copy" },
  { connectorId: "c3", schema: "analytics", relation: "orders" },
];

describe("Source table disclosure", () => {
  it("keeps one source as a Catalog link without an advanced disclosure", () => {
    const markup = renderToStaticMarkup(<SourceTableDisclosure workspaceId="workspace" sources={sources.slice(0, 1)} connectors={connectors} />);
    expect(markup).toContain("Warehouse · analytics.orders</a>");
    expect(markup).toContain("connector=c1");
    expect(markup).not.toContain("Also on");
    expect(markup).not.toContain("<button");
  });

  it("sorts displayed connectors without changing source order and hides the remaining sources", () => {
    const markup = renderToStaticMarkup(<SourceTableDisclosure workspaceId="workspace" sources={sources.slice(0, 2)} connectors={connectors} />);
    expect(markup.indexOf("Analytics replica · replica.analytics.orders_copy")).toBeLessThan(markup.indexOf("Warehouse · analytics.orders"));
    expect(markup).toContain("Also on 1 more connector</button>");
    expect(markup).toContain('aria-expanded="false"');
    expect(markup).toMatch(/aria-controls="([^"]+)".*<div id="\1" hidden=""/);
    expect(sources.map((source) => source.connectorId)).toEqual(["c1", "c2", "c3"]);
  });

  it("uses the plural connector label for three sources", () => {
    const markup = renderToStaticMarkup(<SourceTableDisclosure workspaceId="workspace" sources={sources} connectors={connectors} />);
    expect(markup).toContain("Also on 2 more connectors</button>");
    expect(markup.indexOf("Analytics replica")).toBeLessThan(markup.indexOf("Compute warehouse"));
    expect(markup.indexOf("Compute warehouse")).toBeLessThan(markup.indexOf("Warehouse ·"));
  });
});
