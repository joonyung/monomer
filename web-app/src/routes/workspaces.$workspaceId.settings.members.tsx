import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/workspace/settings/members/page";
import { workspaceMembersSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/workspaces/$workspaceId/settings/members")({
  validateSearch: workspaceMembersSearchSchema,
  component: Page,
});
