import { createFileRoute, Outlet } from "@tanstack/react-router";

export const Route = createFileRoute("/organizations/$organizationId/settings")({ component: Outlet });
