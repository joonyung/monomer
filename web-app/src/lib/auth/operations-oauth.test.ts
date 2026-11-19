import { beforeEach, expect, it, vi } from "vitest";
import { sessionFetch } from "@/lib/api/client";
import { useAuthStore } from "./store";
import { fetchOperationsConsent, submitOperationsConsent, type OperationsConsent } from "./operations-oauth";
vi.mock("@/lib/api/client", () => ({ sessionFetch: vi.fn() }));
const consent: OperationsConsent = { request: "request", csrfToken: "csrf", clientName: "Monomer Operations", resource: "https://api.monomer.cc/ops/v1", expiresAt: "2099-01-01T00:00:00Z" };
beforeEach(() => vi.resetAllMocks());
it.each(["https://127.0.0.1:1234/oauth/callback", "http://localhost:1234/oauth/callback", "http://127.0.0.1.evil:1234/oauth/callback", "http://127.0.0.1:1234/other", "http://a:b@127.0.0.1:1234/oauth/callback", "http://127.0.0.1:1234/oauth/callback#token"])("rejects invalid callback %s", async (redirectUri) => {
  vi.mocked(sessionFetch).mockResolvedValue(new Response(JSON.stringify({ redirectUri })));
  await expect(submitOperationsConsent(consent, true)).rejects.toThrow("callback");
});
it("uses authenticated browser consent and allows only the local callback", async () => {
  const redirectUri = "http://127.0.0.1:45678/oauth/callback?code=fake&state=fake";
  vi.mocked(sessionFetch).mockResolvedValue(new Response(JSON.stringify({ redirectUri })));
  await expect(submitOperationsConsent(consent, true)).resolves.toBe(redirectUri);
  expect(sessionFetch).toHaveBeenCalledWith(expect.stringMatching(/\/ops\/oauth\/consent$/), expect.objectContaining({ method: "POST", credentials: "include", cache: "no-store", redirect: "error", body: JSON.stringify({ request: "request", csrfToken: "csrf", approve: true }) }));
});
it("does not use consent data after the browser identity changes", async () => {
  vi.mocked(sessionFetch).mockResolvedValue({ ok: true, json: async () => { useAuthStore.getState().logout(); return consent; } } as Response);
  await expect(fetchOperationsConsent("request", new AbortController().signal)).rejects.toThrow("session changed");
});
it("reports access denial without exposing response details", async () => {
  vi.mocked(sessionFetch).mockResolvedValue(new Response("private-details", { status: 403 }));
  await expect(fetchOperationsConsent("request", new AbortController().signal)).rejects.toThrow("You do not have operations access.");
});
