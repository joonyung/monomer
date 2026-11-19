import { z } from "zod";
import { sessionFetch } from "@/lib/api/client";
import { CONTROL_API_URL } from "./config";
import { useAuthStore } from "./store";

const consentSchema = z.object({
  request: z.string(), csrfToken: z.string(), clientName: z.string(), clientId: z.string(),
  clientOrigin: z.string().nullable(), localClient: z.boolean(), resource: z.string(),
  expiresAt: z.iso.datetime(),
  existingConnectionCount: z.number().int().nonnegative(), latestConnectionIssuedAt: z.iso.datetime().nullable(),
});
const connectionsSchema = z.object({ connections: z.array(z.object({
  id: z.string(), clientId: z.string(), clientName: z.string().nullable(),
  resource: z.string(), issuedAt: z.iso.datetime(), expiresAt: z.iso.datetime(), revokedAt: z.iso.datetime().nullable(),
  lastUsedAt: z.iso.datetime().nullable(),
})), nextCursor: z.string().min(1).nullable() });
export type AgentConsent = z.infer<typeof consentSchema>;
export type AgentConnection = z.infer<typeof connectionsSchema>["connections"][number];

// Why a consent request can't continue. Each cause has its own next step for the person.
export type AgentConsentFailure = "expired" | "session-changed" | "failed";
export const AGENT_CONSENT_FAILURE_MESSAGES: Record<AgentConsentFailure, string> = {
  expired: "This request expired. Start again from the app.",
  "session-changed": "You signed in as someone else. Start again from the app.",
  failed: "Couldn't check this request. Start again from the app.",
};
export class AgentConsentError extends Error {
  constructor(readonly failure: AgentConsentFailure) {
    super(AGENT_CONSENT_FAILURE_MESSAGES[failure]);
    this.name = "AgentConsentError";
  }
}
class OAuthRequestError extends Error {
  constructor(readonly status: number | "session-changed", message: string) {
    super(message);
  }
}

async function oauthRequest(path: string, init: RequestInit = {}) {
  const epoch = useAuthStore.getState().authEpoch;
  const response = await sessionFetch(`${CONTROL_API_URL}/oauth/${path}`, {
    ...init, credentials: "include", cache: "no-store", redirect: "error",
  });
  if (useAuthStore.getState().authEpoch !== epoch) throw new OAuthRequestError("session-changed", "Your sign-in session changed. Reopen this page.");
  if (!response.ok) throw new OAuthRequestError(response.status, response.status === 401
    ? "Your session expired. Sign in again."
    : "Could not complete the request. Restart authorization from the app.");
  return response;
}
async function oauthJson(path: string, init: RequestInit) {
  const epoch = useAuthStore.getState().authEpoch;
  const data: unknown = await (await oauthRequest(path, init)).json();
  if (useAuthStore.getState().authEpoch !== epoch) throw new OAuthRequestError("session-changed", "Your sign-in session changed. Reopen this page.");
  return data;
}

// This tab remembers which user first reviewed a request, so a later rejection after another
// account signed in reads as a session change rather than an expired request.
const CONSENT_REVIEWER_PREFIX = "monomer.agent-consent.reviewer:";
function readReviewer(request: string): string | null {
  try { return window.sessionStorage.getItem(CONSENT_REVIEWER_PREFIX + request); } catch { return null; }
}
function writeReviewer(request: string, userId: string | null): void {
  try {
    if (userId) window.sessionStorage.setItem(CONSENT_REVIEWER_PREFIX + request, userId);
    else window.sessionStorage.removeItem(CONSENT_REVIEWER_PREFIX + request);
  } catch {
    return;
  }
}

export function agentConsentFailure(cause: unknown, reviewer: string | null, currentUser: string | null): AgentConsentFailure {
  if (!(cause instanceof OAuthRequestError)) return "failed";
  if (cause.status === "session-changed") return "session-changed";
  if (cause.status === 400) return reviewer && currentUser && reviewer !== currentUser ? "session-changed" : "expired";
  return "failed";
}

export async function fetchAgentConsent(request: string, signal: AbortSignal) {
  const currentUser = useAuthStore.getState().user?.id ?? null;
  try {
    const consent = consentSchema.parse(await oauthJson(`consent?request=${encodeURIComponent(request)}`, { signal }));
    writeReviewer(request, currentUser);
    return consent;
  } catch (cause) {
    if (signal.aborted) throw cause;
    throw new AgentConsentError(agentConsentFailure(cause, readReviewer(request), currentUser));
  }
}

// "Not you? Switch account": hand the request back, then let Google show its account chooser and
// return to the same request.
export async function releaseAgentConsent(consent: AgentConsent) {
  await oauthRequest("consent/release", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ request: consent.request, csrfToken: consent.csrfToken }),
  });
  writeReviewer(consent.request, null);
}
export async function submitAgentConsent(consent: AgentConsent, approve: boolean) {
  const data = await oauthJson("consent", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ request: consent.request, csrfToken: consent.csrfToken, approve }),
  });
  const { redirectUri } = z.object({ redirectUri: z.string().url() }).parse(data);
  const callback = new URL(redirectUri);
  if (callback.username || callback.password || !(
    callback.protocol === "https:" || (callback.protocol === "http:" && ["127.0.0.1", "localhost", "[::1]"].includes(callback.hostname))
  )) throw new Error("Could not verify the app callback address.");
  return redirectUri;
}
export async function fetchAgentConnections(signal: AbortSignal) {
  const epoch = useAuthStore.getState().authEpoch;
  const connections: AgentConnection[] = [];
  let cursor: string | null = null;
  do {
    const params = new URLSearchParams({ limit: "100" });
    if (cursor) params.set("cursor", cursor);
    const page = connectionsSchema.parse(await oauthJson(`connections?${params}`, { signal }));
    if (useAuthStore.getState().authEpoch !== epoch) throw new OAuthRequestError("session-changed", "Your sign-in session changed. Reopen this page.");
    connections.push(...page.connections);
    cursor = page.nextCursor;
  } while (cursor);
  return connections;
}
export async function revokeAgentConnection(id: string) {
  await oauthRequest(`connections/${encodeURIComponent(id)}/revoke`, { method: "POST" });
}

export type AgentResourceKind = "mcp" | "rest" | "other";

export function agentResourceKind(resource: string): AgentResourceKind {
  if (["http://127.0.0.1:11077/mcp", "https://api.monomer.cc/mcp", `${CONTROL_API_URL}/mcp`].includes(resource)) return "mcp";
  if (["http://127.0.0.1:11077/v2/workspaces", "https://api.monomer.cc/v2/workspaces", `${CONTROL_API_URL}/v2/workspaces`].includes(resource)) return "rest";
  return "other";
}

export function agentResourceLabel(resource: string): string {
  const kind = agentResourceKind(resource);
  return kind === "mcp" ? "MCP" : kind === "rest" ? "REST API" : resource;
}

// "the 4 Workspaces you're a member of"; with none yet, only the Workspaces the person joins later.
// Without a count (the list couldn't load) the scope is still stated, just not counted.
export function consentWorkspaceScope(workspaceCount: number | null): string {
  if (workspaceCount === null) return "in the Workspaces you're a member of — and any you join later";
  if (workspaceCount === 0) return "in Workspaces you join";
  return `in the ${workspaceCount} ${workspaceCount === 1 ? "Workspace" : "Workspaces"} you're a member of — and any you join later`;
}

export function existingConnectionNote(clientName: string, count: number, latestIssuedAt: string | null): string | null {
  if (count === 0 || !latestIssuedAt) return null;
  const since = new Date(latestIssuedAt).toLocaleDateString("en", { month: "short", day: "numeric" });
  const already = count === 1
    ? `${clientName} is already connected to your account (since ${since}).`
    : `${clientName} already has ${count} connections to your account (the latest since ${since}).`;
  return `${already} Connecting again adds another connection; you can revoke any of them in Agents.`;
}
