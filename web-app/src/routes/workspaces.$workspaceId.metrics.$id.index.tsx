import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import { z } from "zod";
import Page from "@/pages/metrics/[id]/page";

export const Route = createFileRoute("/workspaces/$workspaceId/metrics/$id/")({
  validateSearch: z.object({ edit: z.preprocess((value) => value === true || value === "true", z.boolean()).catch(false) }),
  search: { middlewares: [stripSearchParams({ edit: false })] },
  component: Page,
});
