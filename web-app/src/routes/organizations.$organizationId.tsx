import { createFileRoute } from "@tanstack/react-router";
import { OrganizationRouteGate } from "@/components/auth/TenantRouteContext";

export const Route = createFileRoute("/organizations/$organizationId")({ component: OrganizationRouteGate });
