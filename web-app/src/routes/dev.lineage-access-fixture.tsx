import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import Page from "@/components/lineage/LineageAccessFixture";

const lineageFixtureSearchSchema = z.object({
  scene: z.enum(["empty", "ready", "peer", "overflow", "unavailable", "loading", "error"]).optional().catch(undefined),
  pickerState: z.enum(["ready", "loading", "error", "empty"]).optional().catch(undefined),
});
export const Route = createFileRoute("/dev/lineage-access-fixture")({
  validateSearch: lineageFixtureSearchSchema,
  component: Page,
});
