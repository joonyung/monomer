import { createFileRoute } from "@tanstack/react-router";
import { OrganizationIndexRedirect } from "@/components/auth/TenantRouteContext";

export const Route = createFileRoute("/organizations/$organizationId/")({ component: OrganizationIndexRedirect });
