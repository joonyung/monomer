import { createFileRoute, redirect } from "@tanstack/react-router";
import { webPaths } from "@/lib/web-paths";

export const Route = createFileRoute("/workspaces/$workspaceId/semantic-models/$id/edit")({
  beforeLoad: ({ params }) => {
    throw redirect({ href: `${webPaths.semanticModel(params.workspaceId, params.id)}?edit=true` });
  },
});
