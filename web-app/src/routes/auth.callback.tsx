import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/auth/callback/page";
import { oauthCallbackSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/auth/callback")({
  validateSearch: oauthCallbackSearchSchema,
  component: Page,
});
