import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/settings/workspaces/page";
import { organizationWorkspacesSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/organizations/$organizationId/settings/workspaces")({
  validateSearch: organizationWorkspacesSearchSchema,
  component: Page,
});
