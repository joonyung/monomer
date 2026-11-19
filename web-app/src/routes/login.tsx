import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/login/page";
import { loginSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/login")({
  validateSearch: loginSearchSchema,
  component: Page,
});
