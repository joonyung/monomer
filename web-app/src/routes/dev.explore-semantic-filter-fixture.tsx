import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/dev/explore-semantic-filter-fixture/page";

export const Route = createFileRoute("/dev/explore-semantic-filter-fixture")({
  component: Page,
});
