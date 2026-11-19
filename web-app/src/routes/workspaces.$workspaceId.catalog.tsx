import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/catalog/page";
import { catalogSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/workspaces/$workspaceId/catalog")({
  validateSearch: catalogSearchSchema,
  component: CatalogRoute,
});

function CatalogRoute() {
  return <Page target={Route.useSearch()} />;
}
