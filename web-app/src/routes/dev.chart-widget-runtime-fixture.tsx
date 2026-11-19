import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/dev/chart-widget-runtime-fixture/page";

export const Route = createFileRoute("/dev/chart-widget-runtime-fixture")({
  component: Page,
});
