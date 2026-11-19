import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/dev/design-system-fixture/page";

export const Route = createFileRoute("/dev/design-system-fixture")({
  component: Page,
});
