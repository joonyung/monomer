import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/pages/demo/page";
import { pagesDemoSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/dev/pages-demo/")({
  validateSearch: pagesDemoSearchSchema,
  component: Page,
});
