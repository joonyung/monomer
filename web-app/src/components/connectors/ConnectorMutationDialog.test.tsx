import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ConnectorMutationDialog } from "./ConnectorMutationDialog";
import type { Connector } from "@/lib/types/connector";

vi.mock("@/components/ui/Dialog", () => ({ Dialog: ({ title, children, headerActions, footer }: { title: ReactNode; children: ReactNode; headerActions?: ReactNode; footer?: ReactNode }) => <div role="dialog">{title}{headerActions}{children}{footer}</div> }));
vi.mock("@/lib/navigation", () => ({ Link: ({ href, children, ...props }: { href: string; children: ReactNode }) => <a href={href} {...props}>{children}</a> }));

describe("sample Connector details", () => {
  const connector: Connector = { id: "sample", workspaceId: "workspace", type: "DUCKDB", name: "Acme Cloud", sample: { dataset: "acme-cloud@1", label: "Acme Cloud" }, properties: { sampleDataset: "acme-cloud@1" }, createdAtEpochMs: 1, updatedAtEpochMs: 1 };
  it.each(["detail", "revise", "rotate"] as const)("keeps %s entry read-only even when the caller is an Owner", (mode) => {
    const markup = renderToStaticMarkup(<ConnectorMutationDialog open mode={mode} connector={connector} canEdit catalogHref="/catalog" submitting={false} error={null} onClose={() => undefined} onEdit={() => undefined} onRotate={() => undefined} onDelete={() => undefined} onCancel={() => undefined} onSubmit={() => undefined} />);
    expect(markup).toContain("Sample data · read-only · managed by Monomer");
    expect(markup).toContain("Browse in Catalog");
    for (const label of ["Edit settings", "Replace credentials", "Delete connector", "Save changes", "Display name", "Password"]) expect(markup).not.toContain(label);
  });
});
