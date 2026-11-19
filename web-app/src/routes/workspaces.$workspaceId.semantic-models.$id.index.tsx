import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import { z } from "zod";
import Page from "@/pages/semantic-models/[id]/page";

const modelPageSearchSchema = z.object({ edit: z.preprocess((value) => value === true || value === "true", z.boolean()).catch(false) });

export const Route = createFileRoute("/workspaces/$workspaceId/semantic-models/$id/")({
  validateSearch: modelPageSearchSchema,
  search: { middlewares: [stripSearchParams({ edit: false })] },
  component: ModelPageRoute,
});

function ModelPageRoute() {
  return <Page editing={Route.useSearch().edit} />;
}
