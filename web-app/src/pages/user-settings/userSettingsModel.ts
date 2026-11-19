import { agentResourceKind, type AgentConnection, type AgentResourceKind } from "@/lib/auth/agent-oauth";
import type { LoginIdentity } from "@/lib/auth/types";

export type UserSettingsSection = "profile" | "organizations" | "agents" | "security";

const HOUR_MS = 60 * 60 * 1_000;
const DAY_MS = 24 * HOUR_MS;
/** A connection this close to its 30-day expiry warns the person to reconnect from the app. */
export const EXPIRY_WARNING_MS = 3 * DAY_MS;

export function sortLoginIdentities(identities: LoginIdentity[]): LoginIdentity[] {
  return [...identities].sort((left, right) => left.linkedAt.localeCompare(right.linkedAt));
}

export function isActiveConnection(connection: Pick<AgentConnection, "revokedAt" | "expiresAt">, now: number): boolean {
  return connection.revokedAt === null && Date.parse(connection.expiresAt) > now;
}

// Active grants come first and stay visible; revoked and expired grants collapse behind a toggle.
export function partitionConnections(connections: AgentConnection[], now: number): { active: AgentConnection[]; ended: AgentConnection[] } {
  return {
    active: connections.filter((connection) => isActiveConnection(connection, now)),
    ended: connections.filter((connection) => !isActiveConnection(connection, now)),
  };
}

/** An HTTPS client ID is the address Monomer fetched the app's metadata from; its host identifies the app. */
export function clientIdentityLabel(clientId: string): string {
  try {
    const url = new URL(clientId);
    return url.protocol === "https:" ? url.host : clientId;
  } catch {
    return clientId;
  }
}

export function connectionAppName(connection: Pick<AgentConnection, "clientName" | "clientId">): string {
  return connection.clientName?.trim() || connection.clientId;
}

/** Active connections of one app to one target (MCP or REST), shown as one row. */
export interface ConnectionGroup {
  key: string;
  appName: string;
  clientId: string;
  resource: string;
  resourceKind: AgentResourceKind;
  connections: AgentConnection[];
  lastUsedAt: string | null;
  expiresAt: string;
}

function latest(values: string[]): string | null {
  return values.reduce<string | null>((current, value) => (current === null || Date.parse(value) > Date.parse(current) ? value : current), null);
}

export function groupConnections(active: AgentConnection[]): ConnectionGroup[] {
  const groups = new Map<string, AgentConnection[]>();
  for (const connection of active) {
    const key = JSON.stringify([connection.clientId, connection.resource]);
    groups.set(key, [...(groups.get(key) ?? []), connection]);
  }
  return [...groups.entries()].map(([key, members]) => {
    const connections = [...members].sort((left, right) => Date.parse(right.issuedAt) - Date.parse(left.issuedAt));
    const expiries = connections.map((connection) => connection.expiresAt).sort((left, right) => Date.parse(left) - Date.parse(right));
    return {
      key,
      appName: connectionAppName(connections[0]),
      clientId: connections[0].clientId,
      resource: connections[0].resource,
      resourceKind: agentResourceKind(connections[0].resource),
      connections,
      lastUsedAt: latest(connections.flatMap((connection) => (connection.lastUsedAt ? [connection.lastUsedAt] : []))),
      expiresAt: expiries[0],
    };
  }).sort((left, right) => {
    const used = (right.lastUsedAt ? Date.parse(right.lastUsedAt) : 0) - (left.lastUsedAt ? Date.parse(left.lastUsedAt) : 0);
    return used !== 0 ? used : Date.parse(right.connections[0].issuedAt) - Date.parse(left.connections[0].issuedAt);
  });
}

export function settingsDateLabel(value: string): string {
  return new Intl.DateTimeFormat("en", { year: "numeric", month: "short", day: "numeric", timeZone: "UTC" }).format(new Date(value));
}

/** Use is recorded at most hourly, so anything inside the last hour reads as one span. */
export function lastUsedLabel(lastUsedAt: string | null, now: number): string {
  if (!lastUsedAt) return "Not used yet";
  const age = Math.max(0, now - Date.parse(lastUsedAt));
  if (age < HOUR_MS) return "Within the last hour";
  if (age < DAY_MS) {
    const hours = Math.floor(age / HOUR_MS);
    return `${hours} ${hours === 1 ? "hour" : "hours"} ago`;
  }
  if (age < 2 * DAY_MS) return "Yesterday";
  if (age < 7 * DAY_MS) return `${Math.floor(age / DAY_MS)} days ago`;
  return settingsDateLabel(lastUsedAt);
}

/** The same moment as a phrase for compact rows: "Last used 3 hours ago", "Not used yet". */
export function lastUsedPhrase(lastUsedAt: string | null, now: number): string {
  if (!lastUsedAt) return "Not used yet";
  const label = lastUsedLabel(lastUsedAt, now);
  return `Last used ${/^[A-Z][a-z]+ \d/.test(label) ? label : label.charAt(0).toLowerCase() + label.slice(1)}`;
}

export function expiresSoon(expiresAt: string, now: number): boolean {
  return Date.parse(expiresAt) - now <= EXPIRY_WARNING_MS;
}

/** "In 2 days" inside the warning window, otherwise the expiry date. */
export function expiryLabel(expiresAt: string, now: number): string {
  if (!expiresSoon(expiresAt, now)) return settingsDateLabel(expiresAt);
  const remaining = Date.parse(expiresAt) - now;
  if (remaining < DAY_MS) return "In less than a day";
  const days = Math.floor(remaining / DAY_MS);
  return `In ${days} ${days === 1 ? "day" : "days"}`;
}

export function endedConnectionLabel(connection: Pick<AgentConnection, "revokedAt" | "expiresAt">): string {
  return connection.revokedAt
    ? `Revoked ${settingsDateLabel(connection.revokedAt)}`
    : `Expired ${settingsDateLabel(connection.expiresAt)}`;
}

/** What revoking leaves in place, worded for what the connection reaches. */
export function revokeKeepsNote(kind: AgentResourceKind): string {
  if (kind === "mcp") return "The app’s MCP server settings and any data it already retrieved are not deleted.";
  return "The app’s own settings and any data it already retrieved are not deleted.";
}
