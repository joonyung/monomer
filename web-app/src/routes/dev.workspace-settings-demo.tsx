import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/workspace/settings/demo/page";
import { workspaceSettingsDemoSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/dev/workspace-settings-demo")({ validateSearch: workspaceSettingsDemoSearchSchema, component: Page });
