import { createFileRoute, Outlet } from "@tanstack/react-router";

export const Route = createFileRoute("/workspaces/$workspaceId/dashboards/$dashboardId")({
  component: Outlet,
});
