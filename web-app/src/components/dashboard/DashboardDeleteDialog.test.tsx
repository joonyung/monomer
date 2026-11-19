import { IsRestoringProvider, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import type { PagesTreeResponse } from "@/api/pages";
import { DashboardDeleteDialog, linkedDashboardPages } from "./DashboardDeleteDialog";
import { queryKeys } from "@/lib/query/keys";

vi.mock("@radix-ui/react-dialog", () => {
  const Primitive = ({ children, asChild: _asChild, ...props }: { children?: ReactNode } & Record<string, unknown>) => <div {...props}>{children}</div>;
  return { Root: Primitive, Portal: Primitive, Overlay: Primitive, Content: Primitive, Title: Primitive, Description: Primitive, Close: Primitive, Trigger: Primitive };
});
const tree: PagesTreeResponse = {
  workspaceId: "ws", revision: 1, updatedAt: "", updatedBy: { kind: "USER", id: "user" },
  pages: [
    { pageId: "folder", displayTitle: "Sales", kind: "FOLDER", dashboardId: null },
    { pageId: "linked", displayTitle: "Weekly review", kind: "DASHBOARD", dashboardId: "dashboard" },
    { pageId: "unplaced", displayTitle: "Unplaced review", kind: "DASHBOARD", dashboardId: "dashboard" },
    { pageId: "other", displayTitle: "Other dashboard", kind: "DASHBOARD", dashboardId: "other-dashboard" },
  ] as PagesTreeResponse["pages"],
  placements: [{ pageId: "folder", children: [{ pageId: "linked", children: [] }, { pageId: "other", children: [] }] }],
};
function render(options: { publicPages?: boolean; pagesError?: boolean; dashboardError?: boolean; pending?: boolean } = {}) {
  const client = new QueryClient();
  const dashboardKey = [...queryKeys.dashboards.detail("ws", "user", "dashboard"), "delete-confirmation"];
  const pagesKey = [...queryKeys.pages.directory("ws", "user"), "dashboard-delete", "dashboard"];
  if (!options.pending) {
    client.setQueryData(dashboardKey, { definition: { meta: { title: "Current revenue" }, schedules: { first: {}, second: {} } } });
    client.setQueryData(pagesKey, tree);
  }
  if (options.pagesError) client.getQueryCache().find({ queryKey: pagesKey })?.setState({ status: "error", error: new Error("Unavailable") });
  if (options.dashboardError) client.getQueryCache().find({ queryKey: dashboardKey })?.setState({ status: "error", error: new Error("Unavailable") });
  return renderToStaticMarkup(<QueryClientProvider client={client}><IsRestoringProvider value={true}><DashboardDeleteDialog open workspaceId="ws" principalId="user" dashboardId="dashboard" publicPages={options.publicPages ?? false} onCancel={() => undefined} onDeleted={() => undefined} /></IsRestoringProvider></QueryClientProvider>);
}
describe("Dashboard deletion confirmation", () => {
  it("shows every linked Page, using its placement path or unplaced title", () => {
    expect(linkedDashboardPages(tree, "dashboard")).toEqual([
      { pageId: "linked", path: "Sales / Weekly review" },
      { pageId: "unplaced", path: "Unplaced review" },
    ]);
    const markup = render({ publicPages: true });
    expect(markup).toContain("Delete “Current revenue”?");
    expect(markup).toContain("Its 2 schedules stop.");
    expect(markup).toContain("2 linked pages are deleted:");
    expect(markup).toContain("Sales / Weekly review");
    expect(markup).toContain('href="/workspaces/ws/pages/linked"');
    expect(markup).toContain("Unplaced review");
    expect(markup).toContain('href="/workspaces/ws/pages/unplaced"');
    expect(markup).not.toContain("Other dashboard");
    expect(markup).toContain("This workspace’s pages are public");
    expect(markup).toContain("Queries that are running now are cancelled");
    expect(markup).toContain("Pages and past results don’t come back.");
    expect(markup).toContain("Type “Current revenue” to confirm");
    expect(markup).toMatch(/<button[^>]*disabled=""[^>]*>Delete dashboard<\/button>/);
    expect(render()).not.toContain("people outside it lose access");
  });
  it("blocks failed dependency reads and supplies explicit retries", () => {
    const failedPages = render({ pagesError: true });
    expect(failedPages).toContain("Couldn’t load linked pages.");
    expect(failedPages).toContain("Retry linked pages");
    expect(failedPages).toMatch(/<button[^>]*disabled=""[^>]*>Delete dashboard<\/button>/);
    const failedDashboard = render({ dashboardError: true });
    expect(failedDashboard).toContain("Couldn’t load the current dashboard.");
    expect(failedDashboard).toContain("Retry dashboard");
    expect(render({ pending: true })).toContain("Checking linked pages…");
  });
});
