import { createFileRoute } from "@tanstack/react-router";
import { PagesRouteLayout } from "@/components/pages/PagesRouteLayout";

export const Route = createFileRoute("/workspaces/$workspaceId/pages")({ component: PagesRouteLayout });
