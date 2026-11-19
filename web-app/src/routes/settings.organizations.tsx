import { createFileRoute } from "@tanstack/react-router";
import OrganizationsPage from "@/pages/user-settings/organizations";

export const Route = createFileRoute("/settings/organizations")({ component: OrganizationsPage });
