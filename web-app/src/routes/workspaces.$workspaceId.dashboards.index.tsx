import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/dashboard/list/page";

export const Route = createFileRoute("/workspaces/$workspaceId/dashboards/")({ component: Page });
