import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/semantics/demo/page";
import { semanticsDemoSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/dev/semantics-demo")({
  validateSearch: semanticsDemoSearchSchema,
  component: Page,
});
