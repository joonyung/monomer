import { createFileRoute, redirect } from "@tanstack/react-router";
import { webPaths } from "@/lib/web-paths";

export const Route = createFileRoute("/workspaces/$workspaceId/metrics/")({
  beforeLoad: ({ params }) => {
    throw redirect({ href: webPaths.metricCollection(params.workspaceId) });
  },
});
