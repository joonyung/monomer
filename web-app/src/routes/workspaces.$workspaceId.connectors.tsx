import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/connectors/page";
import { connectorsSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/workspaces/$workspaceId/connectors")({
  validateSearch: connectorsSearchSchema,
  component: ConnectorsRoute,
});

function ConnectorsRoute() {
  return <Page target={Route.useSearch()} />;
}
