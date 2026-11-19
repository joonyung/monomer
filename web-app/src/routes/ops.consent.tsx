import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import OperationsConsentPage from "@/pages/operations/consent";
export const Route = createFileRoute("/ops/consent")({
  validateSearch: z.object({ request: z.string().max(256).catch("") }),
  component: OperationsConsentRoute,
});
function OperationsConsentRoute() {
  const { request } = Route.useSearch();
  return <OperationsConsentPage key={request} request={request} />;
}
