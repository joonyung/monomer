import { createFileRoute } from "@tanstack/react-router";
import SecurityPage from "@/pages/user-settings/security";

export const Route = createFileRoute("/settings/security")({ component: SecurityPage });
