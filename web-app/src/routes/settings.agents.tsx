import { createFileRoute } from "@tanstack/react-router";
import AgentsPage from "@/pages/user-settings/agents";

export const Route = createFileRoute("/settings/agents")({ component: AgentsPage });
