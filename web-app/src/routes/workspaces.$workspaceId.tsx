import { createFileRoute } from "@tanstack/react-router";
import { WorkspaceRouteGate } from "@/components/auth/TenantRouteContext";

export const Route = createFileRoute("/workspaces/$workspaceId")({
  component: WorkspaceRouteGate,
});
