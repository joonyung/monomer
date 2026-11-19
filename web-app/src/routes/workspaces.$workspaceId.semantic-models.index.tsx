import { createFileRoute, redirect } from "@tanstack/react-router";
import { webPaths } from "@/lib/web-paths";

export const Route = createFileRoute("/workspaces/$workspaceId/semantic-models/")({
  beforeLoad: ({ params }) => {
    throw redirect({ href: webPaths.semanticModelCollection(params.workspaceId) });
  },
});
