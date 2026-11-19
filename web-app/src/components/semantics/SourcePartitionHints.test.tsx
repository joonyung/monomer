import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { SemanticModelResponse, SourcePartitionInfo } from "@/api/semantic-model";
import { PartitionFilterHint, SourcePartitionHints } from "./SourcePartitionHints";
import { sourcePartitionFilter } from "./source-partition";

vi.mock("@/lib/navigation", () => ({ Link: ({ href, children, className }: { href: string; children: ReactNode; className?: string }) => <a href={href} className={className}>{children}</a> }));

const source: SourcePartitionInfo = { semanticModelName: "orders", connectorId: "bq", relation: { catalog: "project", schema: "analytics", name: "orders" }, partitionKeys: ["order_date"], requiresPartitionFilter: true, dimensions: ["ordered_at"], timeRangeApplies: true };
const model: SemanticModelResponse = { id: "m1", name: "orders", label: "Northstar orders", sourceRelations: [], dimensions: [{ name: "ordered_at", label: "Order date", expression: "order_date", type: "time", granularity: "day", isPartition: false }], entities: [], measures: [], createdAt: "", updatedAt: "" };

describe("source partition guidance", () => {
  it("renders a nonblocking repair button with a product label", () => {
    const markup = renderToStaticMarkup(<PartitionFilterHint filter={sourcePartitionFilter(source)} models={[model]} workspaceId="w1" canEdit={false} onRepair={() => undefined} />);
    expect(markup).toContain("Northstar orders reads a table that needs a filter on order_date.");
    expect(markup).toContain(">Set time range</button>");
    expect(markup).not.toContain('disabled=""');
    expect(markup).not.toContain('role="alert"');
  });

  it("gives editors a model repair route and other roles an editor request", () => {
    const filter = sourcePartitionFilter({ ...source, dimensions: [] });
    const render = (canEdit: boolean) => renderToStaticMarkup(<PartitionFilterHint filter={filter} models={[model]} workspaceId="w1" canEdit={canEdit} onRepair={() => undefined} />);
    expect(render(true)).toContain('href="/workspaces/w1/semantic-models/m1?edit=true"');
    expect(render(true)).not.toContain("Set time range");
    expect(render(false)).toContain("Ask an editor to add it.");
    expect(render(false)).not.toContain("Open model");
  });

  it("shows saved read facts only for observed filter-required sources", () => {
    const render = (sources: SourcePartitionInfo[]) => renderToStaticMarkup(<SourcePartitionHints sources={sources} models={[model]} connectors={[{ id: "bq", name: "Northstar BigQuery" }]} />);
    expect(render([source])).toContain("Northstar orders · Northstar BigQuery · Partitioned by order_date · needs a time range");
    expect(render([{ ...source, requiresPartitionFilter: null }])).toBe("");
    expect(render([{ ...source, requiresPartitionFilter: false }])).toBe("");
  });
});
