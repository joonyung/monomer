import { createFileRoute } from "@tanstack/react-router";
import SemanticDefinitionHistoryPage from "@/pages/semantic-history/page";

export const Route = createFileRoute("/workspaces/$workspaceId/metrics/$id/history")({ component: () => <SemanticDefinitionHistoryPage kind="metrics" /> });
