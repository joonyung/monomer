import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/semantic-models/new/page";
import { WorkspaceRoleRoute } from "@/components/auth/WorkspaceRoleRoute";
import { tableSourceSearchSchema } from "@/lib/route-search";

function NewSemanticModelRoute() {
  return (
    <WorkspaceRoleRoute
      minimumRole="EDITOR"
    >
      <Page source={Route.useSearch()} />
    </WorkspaceRoleRoute>
  );
}

export const Route = createFileRoute("/workspaces/$workspaceId/semantic-models/new")({
  validateSearch: tableSourceSearchSchema,
  component: NewSemanticModelRoute,
});
