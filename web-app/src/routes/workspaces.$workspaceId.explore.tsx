import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import { SEMANTIC_TIME_GRANULARITIES } from "@/lib/types/explore";
import Page from "@/pages/explore/page";

const optionalName = z.string().min(1).optional().catch(undefined);

const exploreSearchSchema = z.object({
  run: optionalName,
  metric: optionalName,
  dimensionModel: optionalName,
  dimension: optionalName,
  granularity: z.enum(SEMANTIC_TIME_GRANULARITIES).optional().catch(undefined),
  model: optionalName,
  connector: optionalName,
  catalog: optionalName,
  schema: optionalName,
  table: optionalName,
});

export type ExploreSearch = z.infer<typeof exploreSearchSchema>;

export const Route = createFileRoute("/workspaces/$workspaceId/explore")({
  validateSearch: exploreSearchSchema,
  component: Page,
});
