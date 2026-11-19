import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ResourceDetailHeader } from "./ResourceDetailHeader";

vi.mock("@/lib/navigation", () => ({ Link: (props: { href: string; children: React.ReactNode }) => <a {...props} /> }));

describe("resource detail header", () => {
  it("keeps definition and description together with responsive semantic facts", () => {
    const markup = renderToStaticMarkup(<ResourceDetailHeader backHref="/semantics" backLabel="Semantics" title="Gross margin" technicalName="gross_margin" classification="Aggregate metric" description="Sum of gross_margin from Orders" supportingDescription="Margin after costs." facts={[{label: "Created", value: "Yesterday"}, {label: "Updated", value: "Today"}]} actions={null} />);
    expect(markup).toContain("type-body mt-3");
    expect(markup).toContain("type-body mt-2");
    expect(markup.indexOf("Sum of gross_margin")).toBeLessThan(markup.indexOf("Margin after costs."));
    expect(markup.indexOf("Margin after costs.")).toBeLessThan(markup.indexOf("</header>"));
    expect(markup).toContain("grid-cols-2");
    expect(markup).toContain("sm:border-l");
    expect(markup).not.toContain(">·<");
    expect(markup).toContain("<dt");
    expect(markup).toContain("<dd");
    expect(markup).toContain('class="type-body text-foreground"');
    expect(markup).toContain('max-w-3xl text-foreground">Sum of gross_margin');
    expect(markup).toContain('max-w-3xl text-foreground-secondary">Margin after costs.');
  });

  it("separates model identity definitions from creation and update facts", () => {
    const markup = renderToStaticMarkup(<ResourceDetailHeader backHref="/semantics" backLabel="Semantics" title="Orders" technicalName="orders" classification="Model" description={null} identityFacts={[{ label: "Source table", value: "analytics.orders" }, { label: "Row key", value: "order_id" }, { label: "Counted by", value: "ordered_at" }, { label: "Joins", value: "Customers" }]} facts={[{ label: "Created", value: "Yesterday" }, { label: "Updated", value: "Today" }]} actions={null} />);
    const lists = markup.match(/<dl[\s\S]*?<\/dl>/g)!;
    expect(lists).toHaveLength(2);
    expect(lists[0]).toContain("grid-cols-[104px_minmax(0,1fr)]");
    expect(lists[0]).toContain("Source table");
    expect(lists[0]).toContain("Row key");
    expect(lists[0]).not.toContain("Created");
    expect(lists[1]).toContain("Created");
    expect(lists[1]).not.toContain("Source table");
  });
});
