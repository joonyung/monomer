import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import Page from "@/pages/dashboard/viewer/page";

const dashboardSearchSchema = z.object({
  scheduleKey: z.string().regex(/^[A-Za-z_][A-Za-z0-9_]{0,127}$/).refine(value => value !== "__proto__").optional().catch(undefined),
  scheduledAt: z.iso.datetime().optional().catch(undefined),
  run: z.string().uuid().optional().catch(undefined),
  session: z.string().uuid().optional().catch(undefined),
});

export const Route = createFileRoute("/workspaces/$workspaceId/dashboards/$dashboardId/")({
  validateSearch: dashboardSearchSchema,
  component: Page,
});
