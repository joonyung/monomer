import { describe, expect, it } from "vitest";
import {
  clientIdentityLabel,
  connectionAppName,
  endedConnectionLabel,
  expiresSoon,
  expiryLabel,
  groupConnections,
  lastUsedLabel,
  lastUsedPhrase,
  partitionConnections,
  revokeKeepsNote,
  sortLoginIdentities,
} from "./userSettingsModel";

const grant = {
  id: "grant", clientId: "codex", clientName: "Codex" as string | null, resource: "https://api.monomer.cc/mcp",
  issuedAt: "2026-09-01T00:00:00Z", expiresAt: "2026-10-01T00:00:00Z", revokedAt: null as string | null,
  lastUsedAt: null as string | null,
};
const now = Date.parse("2026-09-07T00:00:00Z");

describe("User settings model", () => {
  it("lists sign-in methods in the order they were linked", () => {
    expect(sortLoginIdentities([
      { provider: "Google", linkedAt: "2026-09-02T00:00:00Z" },
      { provider: "Local development", linkedAt: "2026-09-01T00:00:00Z" },
    ]).map((identity) => identity.provider)).toEqual(["Local development", "Google"]);
  });

  it("keeps active grants apart from revoked and expired ones", () => {
    const expired = { ...grant, id: "expired", expiresAt: "2026-09-06T00:00:00Z" };
    const revoked = { ...grant, id: "revoked", revokedAt: "2026-09-05T00:00:00Z" };
    const { active, ended } = partitionConnections([grant, expired, revoked], now);
    expect(active.map((connection) => connection.id)).toEqual(["grant"]);
    expect(ended.map((connection) => connection.id)).toEqual(["expired", "revoked"]);
    expect(endedConnectionLabel(expired)).toBe("Expired Sep 6, 2026");
    expect(endedConnectionLabel(revoked)).toBe("Revoked Sep 5, 2026");
  });

  it("falls back to the client ID when an app has no usable name", () => {
    expect(connectionAppName(grant)).toBe("Codex");
    expect(connectionAppName({ clientId: "custom-client", clientName: "  " })).toBe("custom-client");
    expect(connectionAppName({ clientId: "custom-client", clientName: null })).toBe("custom-client");
  });

  it("groups an app's connections to one target and summarizes their use and expiry", () => {
    const mcp = "https://api.monomer.cc/mcp";
    const rest = "https://api.monomer.cc/v2/workspaces";
    const groups = groupConnections([
      { ...grant, id: "claude-old", clientId: "claude", clientName: "Claude", resource: mcp, issuedAt: "2026-09-02T00:00:00Z", expiresAt: "2026-10-02T00:00:00Z", lastUsedAt: "2026-09-06T20:00:00Z" },
      { ...grant, id: "claude-new", clientId: "claude", clientName: "Claude", resource: mcp, issuedAt: "2026-09-05T00:00:00Z", expiresAt: "2026-10-05T00:00:00Z", lastUsedAt: "2026-09-06T23:00:00Z" },
      { ...grant, id: "claude-rest", clientId: "claude", clientName: "Claude", resource: rest },
      { ...grant, id: "codex", resource: mcp, lastUsedAt: null },
    ]);
    expect(groups.map((group) => group.connections.map((connection) => connection.id))).toEqual([
      ["claude-new", "claude-old"], ["claude-rest"], ["codex"],
    ]);
    expect(groups[0]).toMatchObject({ appName: "Claude", resourceKind: "mcp", lastUsedAt: "2026-09-06T23:00:00Z", expiresAt: "2026-10-02T00:00:00Z" });
    expect(groups[1].resourceKind).toBe("rest");
    expect(groups[2].lastUsedAt).toBeNull();
  });

  it("says when a connection was last used at the hourly precision it is recorded with", () => {
    expect(lastUsedLabel(null, now)).toBe("Not used yet");
    expect(lastUsedLabel("2026-09-06T23:30:00Z", now)).toBe("Within the last hour");
    expect(lastUsedLabel("2026-09-06T21:00:00Z", now)).toBe("3 hours ago");
    expect(lastUsedLabel("2026-09-05T20:00:00Z", now)).toBe("Yesterday");
    expect(lastUsedLabel("2026-09-03T00:00:00Z", now)).toBe("4 days ago");
    expect(lastUsedLabel("2026-08-20T00:00:00Z", now)).toBe("Aug 20, 2026");
    expect(lastUsedPhrase(null, now)).toBe("Not used yet");
    expect(lastUsedPhrase("2026-09-05T20:00:00Z", now)).toBe("Last used yesterday");
    expect(lastUsedPhrase("2026-08-20T00:00:00Z", now)).toBe("Last used Aug 20, 2026");
  });

  it("warns within three days of expiry", () => {
    expect(expiresSoon("2026-09-10T00:00:00Z", now)).toBe(true);
    expect(expiryLabel("2026-09-10T00:00:00Z", now)).toBe("In 3 days");
    expect(expiryLabel("2026-09-08T01:00:00Z", now)).toBe("In 1 day");
    expect(expiryLabel("2026-09-07T05:00:00Z", now)).toBe("In less than a day");
    expect(expiresSoon("2026-09-10T00:00:01Z", now)).toBe(false);
    expect(expiryLabel("2026-10-01T00:00:00Z", now)).toBe("Oct 1, 2026");
  });

  it("names an app's metadata host instead of its full client ID URL", () => {
    expect(clientIdentityLabel("https://claude.ai/oauth/mcp-oauth-client-metadata")).toBe("claude.ai");
    expect(clientIdentityLabel("monomer-native")).toBe("monomer-native");
    expect(clientIdentityLabel("http://127.0.0.1:5000/client.json")).toBe("http://127.0.0.1:5000/client.json");
  });

  it("words what revoking keeps for the connection's target", () => {
    expect(revokeKeepsNote("mcp")).toContain("MCP server settings");
    expect(revokeKeepsNote("rest")).not.toContain("MCP");
    expect(revokeKeepsNote("other")).not.toContain("MCP");
  });
});
