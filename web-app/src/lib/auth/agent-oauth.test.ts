import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createMemoryStorage } from "@/test/memory-storage";
import { sessionFetch } from "@/lib/api/client";
import { useAuthStore } from "./store";
import {
  submitAgentConsent, fetchAgentConnections, agentResourceLabel, fetchAgentConsent, releaseAgentConsent,
  existingConnectionNote, consentWorkspaceScope, agentResourceKind, AgentConsentError, type AgentConsent,
} from "./agent-oauth";
vi.mock("@/lib/api/client", () => ({ sessionFetch: vi.fn() }));
const consent: AgentConsent = {
  request: "fake-request", csrfToken: "fake-csrf", clientName: "Fake client", clientId: "fake-client",
  clientOrigin: null, localClient: true, resource: "https://api.monomer.cc/mcp", expiresAt: "2099-01-01T00:00:00Z",
  existingConnectionCount: 0, latestConnectionIssuedAt: null,
};
const storage = createMemoryStorage();
beforeEach(() => {
  vi.resetAllMocks();
  storage.clear();
  vi.stubGlobal("window", { sessionStorage: storage });
});
afterEach(() => vi.unstubAllGlobals());
it.each(["javascript:alert(1)", "http://evil.example/callback", "https://user:password@client.example/callback"])("rejects unsafe browser callback %s", async (redirectUri) => {
  vi.mocked(sessionFetch).mockResolvedValue(new Response(JSON.stringify({ redirectUri })));
  await expect(submitAgentConsent(consent, false)).rejects.toThrow();
});
it("discards callback if the browser user changes while consent is submitted", async () => {
  vi.mocked(sessionFetch).mockImplementation(async () => {
    useAuthStore.getState().logout();
    return new Response(JSON.stringify({ redirectUri: "https://client.example/callback" }));
  });
  await expect(submitAgentConsent(consent, true)).rejects.toThrow("Your sign-in session changed");
});
it("sends consent with browser cookies, no cache and no redirect following", async () => {
  vi.mocked(sessionFetch).mockResolvedValue(new Response(JSON.stringify({ redirectUri: "http://127.0.0.1:45678/callback" })));
  await expect(submitAgentConsent(consent, false)).resolves.toBe("http://127.0.0.1:45678/callback");
  expect(sessionFetch).toHaveBeenCalledWith(expect.stringMatching(/\/oauth\/consent$/), expect.objectContaining({
    method: "POST", credentials: "include", cache: "no-store", redirect: "error",
    body: JSON.stringify({ request: "fake-request", csrfToken: "fake-csrf", approve: false }),
  }));
});

it("discards connection data if the session changes while the response body is read", async () => {
  vi.mocked(sessionFetch).mockResolvedValue({
    ok: true,
    json: async () => {
      useAuthStore.getState().logout();
      return { connections: [] };
    },
  } as Response);
  await expect(fetchAgentConnections(new AbortController().signal)).rejects.toThrow("Your sign-in session changed");
});
it("reads every connection page before publishing a complete list", async () => {
  const connection = { id: "first", clientId: "client", clientName: "Client", resource: "resource",
    issuedAt: "2026-10-01T00:00:00Z", expiresAt: "2026-10-31T00:00:00Z", revokedAt: null, lastUsedAt: null };
  const second = { ...connection, id: "second", revokedAt: "2026-10-02T00:00:00Z" };
  vi.mocked(sessionFetch)
    .mockResolvedValueOnce(new Response(JSON.stringify({ connections: [connection], nextCursor: "opaque+/cursor" })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ connections: [second], nextCursor: null })));
  const signal = new AbortController().signal;
  await expect(fetchAgentConnections(signal)).resolves.toEqual([connection, second]);
  expect(sessionFetch).toHaveBeenNthCalledWith(1, expect.stringMatching(/\/oauth\/connections\?limit=100$/), expect.objectContaining({ signal }));
  expect(sessionFetch).toHaveBeenNthCalledWith(2, expect.stringMatching(/\/oauth\/connections\?limit=100&cursor=opaque%2B%2Fcursor$/), expect.objectContaining({ signal }));
});
it("does not publish a partial connection list when a later page fails", async () => {
  vi.mocked(sessionFetch)
    .mockResolvedValueOnce(new Response(JSON.stringify({ connections: [], nextCursor: "next" })))
    .mockResolvedValueOnce(new Response(null, { status: 503 }));
  await expect(fetchAgentConnections(new AbortController().signal)).rejects.toThrow("Could not complete the request");
});
it("discards all connection pages if the session changes on a later page", async () => {
  vi.mocked(sessionFetch)
    .mockResolvedValueOnce(new Response(JSON.stringify({ connections: [], nextCursor: "next" })))
    .mockImplementationOnce(async () => {
      useAuthStore.getState().logout();
      return new Response(JSON.stringify({ connections: [], nextCursor: null }));
    });
  await expect(fetchAgentConnections(new AbortController().signal)).rejects.toThrow("Your sign-in session changed");
});
it("labels exact resource audiences and preserves unknown values", () => {
  expect(agentResourceLabel("https://api.monomer.cc/mcp")).toBe("MCP");
  expect(agentResourceLabel("https://api.monomer.cc/v2/workspaces")).toBe("REST API");
  expect(agentResourceLabel("https://untrusted.example/mcp")).toBe("https://untrusted.example/mcp");
  expect(agentResourceLabel("https://api.monomer.cc/mcp/extra")).toBe("https://api.monomer.cc/mcp/extra");
  expect(agentResourceKind("https://api.monomer.cc/v2/workspaces")).toBe("rest");
  expect(agentResourceKind("https://untrusted.example/mcp")).toBe("other");
});

it("explains a rejected consent request by cause", async () => {
  useAuthStore.setState({ user: { id: "user-a", email: "a@example.test", displayName: "A" } });
  vi.mocked(sessionFetch).mockResolvedValueOnce(new Response(JSON.stringify(consent)));
  await fetchAgentConsent("fake-request", new AbortController().signal);
  vi.mocked(sessionFetch).mockResolvedValueOnce(new Response(JSON.stringify({ error: "invalid_request" }), { status: 400 }));
  await expect(fetchAgentConsent("fake-request", new AbortController().signal)).rejects.toThrow("This request expired. Start again from the app.");
  useAuthStore.setState({ user: { id: "user-b", email: "b@example.test", displayName: "B" } });
  vi.mocked(sessionFetch).mockResolvedValueOnce(new Response(JSON.stringify({ error: "invalid_request" }), { status: 400 }));
  const changed = fetchAgentConsent("fake-request", new AbortController().signal);
  await expect(changed).rejects.toBeInstanceOf(AgentConsentError);
  await expect(changed).rejects.toThrow("You signed in as someone else. Start again from the app.");
  vi.mocked(sessionFetch).mockResolvedValueOnce(new Response(JSON.stringify({ error: "invalid_request" }), { status: 400 }));
  await expect(fetchAgentConsent("unknown-request", new AbortController().signal)).rejects.toThrow("This request expired.");
});
it("releases a consent request with its CSRF token so another account can review it", async () => {
  storage.setItem("monomer.agent-consent.reviewer:fake-request", "user-a");
  vi.mocked(sessionFetch).mockResolvedValue(new Response(null, { status: 204 }));
  await releaseAgentConsent(consent);
  expect(sessionFetch).toHaveBeenCalledWith(expect.stringMatching(/\/oauth\/consent\/release$/), expect.objectContaining({
    method: "POST", body: JSON.stringify({ request: "fake-request", csrfToken: "fake-csrf" }),
  }));
  expect(storage.getItem("monomer.agent-consent.reviewer:fake-request")).toBeNull();
});
it("describes existing connections and the Workspaces a connection reaches", () => {
  expect(existingConnectionNote("Claude", 0, null)).toBeNull();
  expect(existingConnectionNote("Claude", 1, "2026-09-28T12:00:00Z")).toContain("Claude is already connected to your account (since Sep 28).");
  expect(existingConnectionNote("Claude", 2, "2026-09-28T12:00:00Z")).toContain("2 connections");
  expect(existingConnectionNote("Claude", 1, "2026-09-28T12:00:00Z")).toContain("revoke any of them in Agents.");
  expect(consentWorkspaceScope(4)).toBe("in the 4 Workspaces you're a member of — and any you join later");
  expect(consentWorkspaceScope(1)).toBe("in the 1 Workspace you're a member of — and any you join later");
  expect(consentWorkspaceScope(0)).toBe("in Workspaces you join");
  expect(consentWorkspaceScope(null)).toBe("in the Workspaces you're a member of — and any you join later");
});
