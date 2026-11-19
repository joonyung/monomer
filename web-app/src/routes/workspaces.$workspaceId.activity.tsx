import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/activity/page";
import { workspaceActivitySearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/workspaces/$workspaceId/activity")({
  validateSearch: workspaceActivitySearchSchema,
  component: Page,
});
