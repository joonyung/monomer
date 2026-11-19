import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/pages/page";

export const Route = createFileRoute("/workspaces/$workspaceId/pages/")({ component: Page });
