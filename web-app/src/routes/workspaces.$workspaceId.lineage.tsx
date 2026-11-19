import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import Page from "@/pages/lineage/page";

const lineageSearchSchema = z.object({
  kind: z.enum(["RELATION", "SEMANTIC_MODEL", "METRIC"]).optional().catch(undefined),
  id: z.string().uuid().optional().catch(undefined),
  mode: z.enum(["my-access", "manage-access"]).optional().catch("my-access"),
});
export const Route = createFileRoute("/workspaces/$workspaceId/lineage")({
  validateSearch: lineageSearchSchema,
  component: Page,
});
