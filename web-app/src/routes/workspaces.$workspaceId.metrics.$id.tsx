import { createFileRoute, Outlet } from "@tanstack/react-router";

export const Route = createFileRoute("/workspaces/$workspaceId/metrics/$id")({ component: Outlet });
