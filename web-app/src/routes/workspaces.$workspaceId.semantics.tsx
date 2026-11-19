import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import Page from "@/pages/semantics/page";
import { semanticsSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/workspaces/$workspaceId/semantics")({
  validateSearch: semanticsSearchSchema,
  search: { middlewares: [stripSearchParams({ view: "metrics", q: "", modelPage: 0, modelFilter: "", kindFilter: "", statusFilter: "", sort: "name" })] },
  component: Page,
});
