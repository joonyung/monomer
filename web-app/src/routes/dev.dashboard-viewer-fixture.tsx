import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import Page from "@/pages/dev/dashboard-viewer-fixture/page";

const dashboardFixtureSearchSchema = z.object({
  scene: z.enum(["layout-sizing", "content-height", "parameters", "loading", "refreshing", "error", "first-failure", "empty", "ready", "outside", "session-history", "nested-stacks", "atomic-variables", "table-presentation", "table-pivot", "input-layout"]).optional().catch(undefined),
  // The Reporting time zone the fixture's time inputs read in; unset reads them in UTC.
  zone: z.string().optional().catch(undefined),
});

export const Route = createFileRoute("/dev/dashboard-viewer-fixture")({
  validateSearch: dashboardFixtureSearchSchema,
  component: Page,
});
