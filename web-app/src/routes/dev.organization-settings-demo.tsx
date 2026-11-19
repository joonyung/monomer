import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/settings/demo/page";
import { organizationSettingsDemoSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/dev/organization-settings-demo")({
  validateSearch: organizationSettingsDemoSearchSchema,
  component: Page,
});
