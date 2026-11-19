import { useNavigate, useParams, useSearch } from "@tanstack/react-router";
import { PageRoute } from "@/components/pages/PageRoute";

export default function WorkspacePage() {
  const { pageId } = useParams({ from: "/workspaces/$workspaceId/pages/$pageId" });
  const search = useSearch({ from: "/workspaces/$workspaceId/pages/$pageId" });
  const navigate = useNavigate({ from: "/workspaces/$workspaceId/pages/$pageId" });
  return <PageRoute pageId={pageId} search={search} onSearchChange={next => { void navigate({ to: ".", replace: true, search: next }); }} />;
}
