import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/workspace/settings/public-access/page";

export const Route = createFileRoute("/workspaces/$workspaceId/settings/public-access")({ component: Page });
