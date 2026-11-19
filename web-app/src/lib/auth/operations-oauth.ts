import { z } from "zod";
import { sessionFetch } from "@/lib/api/client";
import { CONTROL_API_URL } from "./config";
import { useAuthStore } from "./store";

const operationsConsentSchema = z.object({
  request: z.string(), csrfToken: z.string(), clientName: z.string(),
  resource: z.string(), expiresAt: z.iso.datetime(),
});
export type OperationsConsent = z.infer<typeof operationsConsentSchema>;

async function consentRequest(path: string, init: RequestInit): Promise<unknown> {
  const epoch = useAuthStore.getState().authEpoch;
  const response = await sessionFetch(`${CONTROL_API_URL}/ops/oauth/${path}`, {
    ...init, credentials: "include", cache: "no-store", redirect: "error",
  });
  if (!response.ok) throw new Error(response.status === 403
    ? "You do not have operations access."
    : response.status === 401 ? "Your session expired. Sign in again."
      : "This authorization request is unavailable. Restart sign-in from Monomer Operations.");
  const data: unknown = await response.json();
  if (useAuthStore.getState().authEpoch !== epoch) throw new Error("Your sign-in session changed. Restart sign-in from Monomer Operations.");
  return data;
}
export async function fetchOperationsConsent(request: string, signal: AbortSignal) {
  return operationsConsentSchema.parse(await consentRequest(`consent?request=${encodeURIComponent(request)}`, { signal }));
}
export async function submitOperationsConsent(consent: OperationsConsent, approve: boolean) {
  const data = await consentRequest("consent", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ request: consent.request, csrfToken: consent.csrfToken, approve }),
  });
  const { redirectUri } = z.object({ redirectUri: z.string().url() }).parse(data);
  const callback = new URL(redirectUri);
  if (callback.protocol !== "http:" || callback.hostname !== "127.0.0.1" || !callback.port
    || callback.username || callback.password || callback.pathname !== "/oauth/callback" || callback.hash) {
    throw new Error("Could not verify the local callback address.");
  }
  return redirectUri;
}
