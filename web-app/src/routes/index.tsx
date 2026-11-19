import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/landing/page";

export const Route = createFileRoute("/")({
  component: Page,
});
