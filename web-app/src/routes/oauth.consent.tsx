import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import AgentConsentPage from "@/pages/oauth/consent";
const consentSearch = z.object({ request: z.string().max(256).catch("") });
export const Route = createFileRoute("/oauth/consent")({
  validateSearch: consentSearch,
  component: ConsentRoute,
});
function ConsentRoute() {
  const { request } = Route.useSearch();
  return <AgentConsentPage key={request} request={request} />;
}
