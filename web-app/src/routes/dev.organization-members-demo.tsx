import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/settings/members/demo/page";
import { organizationMembersDemoSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/dev/organization-members-demo")({
  validateSearch: organizationMembersDemoSearchSchema,
  component: Page,
});
