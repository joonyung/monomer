import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/workspace/settings/page";

export const Route = createFileRoute("/workspaces/$workspaceId/settings/")({ component: Page });
