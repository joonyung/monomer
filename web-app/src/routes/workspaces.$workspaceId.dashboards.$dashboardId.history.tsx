import { createFileRoute } from "@tanstack/react-router";
import DashboardHistoryPage from "@/pages/dashboard/history/page";

export const Route = createFileRoute("/workspaces/$workspaceId/dashboards/$dashboardId/history")({ component: DashboardHistoryPage });
