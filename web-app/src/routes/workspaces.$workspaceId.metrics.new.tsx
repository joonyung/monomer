import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/metrics/new/page";
import { WorkspaceRoleRoute } from "@/components/auth/WorkspaceRoleRoute";
import { newMetricSearchSchema } from "@/lib/route-search";

function NewMetricRoute() {
  return (
    <WorkspaceRoleRoute
      minimumRole="EDITOR"
    >
      <Page prefill={Route.useSearch()} />
    </WorkspaceRoleRoute>
  );
}

export const Route = createFileRoute("/workspaces/$workspaceId/metrics/new")({
  validateSearch: newMetricSearchSchema,
  component: NewMetricRoute,
});
