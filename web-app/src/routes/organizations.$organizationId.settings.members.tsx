import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/settings/members/page";
import { organizationMembersSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/organizations/$organizationId/settings/members")({
  validateSearch: organizationMembersSearchSchema,
  component: Page,
});
