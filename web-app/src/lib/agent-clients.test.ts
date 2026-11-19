import { expect, it } from "vitest";
import { AGENT_MCP_URL, CURSOR_CONFIGURATION, CURSOR_INSTALL_URL, agentClientGuides, verifiedClientNames } from "./agent-clients";
import { CONTROL_API_URL } from "./auth/config";
import { publicDocsUrls, publicSiteHome } from "./public-docs";
import { resolvePublicSiteOrigin } from "./public-site-origin";

it("gives this environment's MCP address and the clients in the docs' order", () => {
  expect(AGENT_MCP_URL).toBe(`${CONTROL_API_URL}/mcp`);
  expect(agentClientGuides.map((client) => client.name)).toEqual(["Claude", "Claude Code", "Codex", "Cursor", "Other MCP client"]);
  expect(agentClientGuides.map((client) => client.verified)).toEqual([true, true, true, false, null]);
  expect(verifiedClientNames()).toBe("Claude, Claude Code, or Codex");
  for (const client of agentClientGuides) {
    expect(client.steps.length).toBeGreaterThanOrEqual(2);
    expect(client.steps.length).toBeLessThanOrEqual(3);
  }
  expect(agentClientGuides.find((client) => client.id === "claude-code")?.steps[0].command).toContain(AGENT_MCP_URL);
  expect(agentClientGuides.find((client) => client.id === "codex")?.steps[1].command)
    .toBe(`codex mcp add monomer --url ${AGENT_MCP_URL} && codex mcp login monomer --oauth-client-registration cimd`);
});

it("copies Cursor's file configuration and installs only the environment's transport configuration", () => {
  const serverConfiguration = { url: AGENT_MCP_URL };
  expect(JSON.parse(CURSOR_CONFIGURATION)).toEqual({ mcpServers: { monomer: serverConfiguration } });
  const install = new URL(CURSOR_INSTALL_URL);
  expect(install.protocol).toBe("cursor:");
  expect(install.hostname).toBe("anysphere.cursor-deeplink");
  expect(install.pathname).toBe("/mcp/install");
  expect(install.searchParams.get("name")).toBe("monomer");
  expect(JSON.parse(atob(install.searchParams.get("config")!))).toEqual(serverConfiguration);
  expect(agentClientGuides.find((client) => client.id === "cursor")?.verified).toBe(false);
});

it("links to the public docs of the configured environment", () => {
  expect(agentClientGuides[0].guideUrl).toBe("https://monomer.cc/en/docs/agents/claude/");
  expect(publicDocsUrls.connectRestClient).toBe("https://monomer.cc/en/docs/agents/rest/");
  expect(publicSiteHome).toEqual({ url: "https://monomer.cc/", label: "monomer.cc" });
  expect(resolvePublicSiteOrigin("https://dev.monomer.cc")).toBe("https://dev.monomer.cc");
  expect(resolvePublicSiteOrigin(undefined)).toBe("https://monomer.cc");
  expect(() => resolvePublicSiteOrigin("http://dev.monomer.cc")).toThrow("HTTPS origin");
  expect(() => resolvePublicSiteOrigin("https://monomer.cc/en/docs")).toThrow("HTTPS origin");
});
