import { createFileRoute, Outlet } from "@tanstack/react-router";

export const Route = createFileRoute("/workspaces/$workspaceId/semantic-models/$id")({ component: Outlet });
