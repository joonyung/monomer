import { createFileRoute, redirect } from "@tanstack/react-router";

export const Route = createFileRoute("/workspaces/$workspaceId/metrics/$id/edit")({
  beforeLoad: ({ params }) => { throw redirect({ to: "/workspaces/$workspaceId/metrics/$id", params, search: { edit: true }, replace: true }); },
});
