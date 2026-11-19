import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import Page from "@/pages/dev/dashboard-builder-fixture/page";

const dashboardBuilderFixtureSearchSchema = z.object({
  scene: z.enum(["ready", "last-good", "sse", "controls", "parameter-coherence", "changes"]).optional().catch(undefined),
});

export const Route = createFileRoute("/dev/dashboard-builder-fixture")({
  validateSearch: dashboardBuilderFixtureSearchSchema,
  component: Page,
});
