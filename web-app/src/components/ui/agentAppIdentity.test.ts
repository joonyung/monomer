import { describe, expect, it } from "vitest";
import { agentAppIdentity } from "./agentAppIdentity";

describe("agentAppIdentity", () => {
  it("chooses a known app's logo by its verified host and keeps no host in the short name", () => {
    expect(agentAppIdentity({ clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude", verifiedHost: "claude.ai" }))
      .toEqual({ logo: "claude", shortName: "Claude", label: "via Claude (claude.ai)" });
    expect(agentAppIdentity({ clientId: "https://chatgpt.com/codex/client.json", name: "Codex", verifiedHost: "chatgpt.com" }))
      .toEqual({ logo: "openai", shortName: "Codex", label: "via Codex (chatgpt.com)" });
    expect(agentAppIdentity({ clientId: "https://cursor.com/oauth/client.json", name: "Cursor", verifiedHost: "cursor.com" }))
      .toEqual({ logo: "cursor", shortName: "Cursor", label: "via Cursor (cursor.com)" });
  });

  it("never gives a known logo to an app that only claims a known name", () => {
    expect(agentAppIdentity({ clientId: "https://claude-helper.app/client.json", name: "Claude", verifiedHost: "claude-helper.app" }))
      .toEqual({ logo: null, shortName: "claude-helper.app", label: "via claude-helper.app (Claude)" });
    expect(agentAppIdentity({ clientId: "https://claude.ai.evil.dev/client.json", name: "Claude", verifiedHost: "claude.ai.evil.dev" }).logo).toBeNull();
    expect(agentAppIdentity({ clientId: "https://mcp.claude.ai/client.json", name: "Claude", verifiedHost: "mcp.claude.ai" }).logo).toBeNull();
  });

  it("names any other verified app by its host", () => {
    expect(agentAppIdentity({ clientId: "https://agents.acme.dev/client.json", name: "Acme Agent", verifiedHost: "agents.acme.dev" }))
      .toEqual({ logo: null, shortName: "agents.acme.dev", label: "via agents.acme.dev (Acme Agent)" });
  });

  it("names a client registered in Control by its name", () => {
    expect(agentAppIdentity({ clientId: "monomer-native", name: "Monomer CLI", verifiedHost: null }))
      .toEqual({ logo: null, shortName: "Monomer CLI", label: "via Monomer CLI" });
  });

  it("reads agent when no app was recorded", () => {
    expect(agentAppIdentity(null)).toEqual({ logo: null, shortName: "agent", label: "via agent" });
  });
});
