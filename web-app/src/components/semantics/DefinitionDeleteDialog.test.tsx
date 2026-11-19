import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { DefinitionDeleteDialog } from "@/components/semantics/DefinitionDeleteDialog";
import { parseMetricDsl } from "@/api/metric";
import { queryKeys } from "@/lib/query/keys";

vi.mock("@radix-ui/react-dialog", () => {
  const Primitive = ({ children, ...props }: { children?: ReactNode } & Record<string, unknown>) => <div {...props}>{children}</div>;
  return { Root: Primitive, Portal: Primitive, Overlay: Primitive, Content: Primitive, Title: Primitive, Description: Primitive, Close: Primitive, Trigger: Primitive };
});
vi.mock("@/lib/navigation", () => ({ Link: ({ href, children }: { href: string; children: ReactNode }) => <a href={href}>{children}</a> }));

it("previews filter-only and indirect metric impact and requires the definition name", () => {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity } } });
  const definitions = [
    { schemaVersion: 3, name: "revenue", type: "simple", semanticModelName: "orders", measure: "amount", filters: [{ dimension: { semanticModelName: "customers", name: "region" }, operator: "eq", values: ["US"] }] },
    { schemaVersion: 3, name: "growth", type: "cumulative", inputMetric: { metricName: "revenue" } },
  ];
  client.setQueryData(queryKeys.metrics.all("ws", "user"), definitions.map((definition) => parseMetricDsl(String(definition.name), definition, "", "")));
  const markup = renderToStaticMarkup(<QueryClientProvider client={client}><DefinitionDeleteDialog workspaceId="ws" principalId="user" kind="semantic-models" definition={{ id: "customer-id", name: "customers", label: "Customers" }} onCancel={() => undefined} onDeleted={() => undefined} /></QueryClientProvider>);
  expect(markup).toContain("You can restore it later from its history.");
  expect(markup).toContain("2 metrics use it");
  expect(markup).toContain('href="/workspaces/ws/metrics/revenue"');
  expect(markup).toContain('href="/workspaces/ws/metrics/growth"');
  expect(markup).toContain("Dashboards and pages that use it stop updating.");
  expect(markup).toContain("Explore runs and their results stay.");
  expect(markup).toContain("The new definition has its own history.");
  expect(markup).toContain("Type “customers” to confirm");
  expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>[\s\S]*?Delete model<\/button>/);
});

describe("confirmation impact loading", () => {
  it("does not claim no dependents before the metrics load", () => {
    const client = new QueryClient();
    const markup = renderToStaticMarkup(<QueryClientProvider client={client}><DefinitionDeleteDialog workspaceId="ws" principalId="user" kind="metrics" definition={{ id: "revenue-id", name: "revenue" }} onCancel={() => undefined} onDeleted={() => undefined} /></QueryClientProvider>);
    expect(markup).toContain("Checking which metrics use it…");
    expect(markup).not.toContain("No metrics use it.");
  });
});
