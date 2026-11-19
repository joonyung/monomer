import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import Page from "@/pages/dev/role-settings-fixture/page";

export const Route = createFileRoute("/dev/role-settings-fixture")({
  validateSearch: z.object({
    viewer: z.enum(["explorer", "owner", "editor"]).catch("owner"),
    surface: z.enum(["settings", "semantics", "metric-detail"]).catch("settings"),
  }),
  component: Page,
});
