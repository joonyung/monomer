import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import Page from "@/pages/dashboard/builder/page";
import { WorkspaceRoleRoute } from "@/components/auth/WorkspaceRoleRoute";

function DashboardBuilderRoute() {
  return (
    <WorkspaceRoleRoute minimumRole="EXPLORER">
      <Page linkedRunId={Route.useSearch().run} />
    </WorkspaceRoleRoute>
  );
}

export const Route = createFileRoute("/workspaces/$workspaceId/dashboards/$dashboardId/builder")({
  validateSearch: z.object({ run: z.string().uuid().optional() }),
  component: DashboardBuilderRoute,
});
