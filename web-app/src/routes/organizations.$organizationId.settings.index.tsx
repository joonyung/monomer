import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/settings/page";

export const Route = createFileRoute("/organizations/$organizationId/settings/")({ component: Page });
