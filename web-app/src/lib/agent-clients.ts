import { z } from "zod";
import sharedClientList from "../../../public-site/src/data/agent-clients.json";
import { CONTROL_API_URL } from "./auth/config";
import { publicDocsUrl, publicDocsUrls } from "./public-docs";

// The public docs own each client's setup steps and whether Monomer sign-in has been confirmed with
// it. This page shows short summaries of those steps and links to the full guide.
const sharedClientsSchema = z.object({
  clients: z.array(z.object({
    id: z.string(),
    name: z.string(),
    guide: z.string(),
    monomerSignIn: z.enum(["verified", "not-verified"]),
  })),
});
const sharedClients = sharedClientsSchema.parse(sharedClientList).clients;

/** This environment's MCP server address, which every MCP client is given. */
export const AGENT_MCP_URL = `${CONTROL_API_URL}/mcp`;
const cursorServerConfiguration = { url: AGENT_MCP_URL };
export const CURSOR_CONFIGURATION = JSON.stringify({ mcpServers: { monomer: cursorServerConfiguration } }, null, 2);
export const CURSOR_INSTALL_URL = `cursor://anysphere.cursor-deeplink/mcp/install?${new URLSearchParams({ name: "monomer", config: btoa(JSON.stringify(cursorServerConfiguration)) })}`;

/** One short step; a command is shown as code the person can copy into a terminal. */
export interface AgentClientStep {
  text: string;
  command: string | null;
}

export interface AgentClientGuide {
  id: string;
  name: string;
  /** Whether Monomer sign-in has been confirmed with this client; null for the generic MCP client tab. */
  verified: boolean | null;
  guideUrl: string;
  steps: AgentClientStep[];
}

const step = (text: string, command: string | null = null): AgentClientStep => ({ text, command });
const CHECK = step("Ask: “List my Monomer Workspaces.” The connection then appears under Your connections.");

const IN_APP_CLIENTS = ["claude", "claude-code", "codex", "cursor"] as const;
type InAppClientId = (typeof IN_APP_CLIENTS)[number];

const STEPS: Record<InAppClientId, AgentClientStep[]> = {
  claude: [
    step("In Claude, open Customize → Connectors → Add custom connector and paste the URL."),
    step("Choose Sign in now, then approve Monomer in the window that opens."),
    CHECK,
  ],
  "claude-code": [
    step("Add Monomer to your user configuration:", `claude mcp add --transport http --scope user monomer ${AGENT_MCP_URL}`),
    step("In Claude Code, enter /mcp, select Monomer, and approve Monomer in your browser."),
    CHECK,
  ],
  codex: [
    step("In Codex settings, add an MCP server named monomer with the URL, leaving the token and header fields empty. Choose Authenticate, then approve Monomer in your browser."),
    step("Using the Codex CLI? Run this, then approve Monomer in your browser:", `codex mcp add monomer --url ${AGENT_MCP_URL} && codex mcp login monomer --oauth-client-registration cimd`),
    CHECK,
  ],
  cursor: [
    step("Add monomer under mcpServers in ~/.cursor/mcp.json, preserving your other server entries."),
    step("Enable Monomer in Cursor’s MCP settings and start its sign-in if offered."),
  ],
};

function sharedClient(id: string) {
  const client = sharedClients.find((candidate) => candidate.id === id);
  if (!client) throw new Error(`The shared agent client list has no ${id}.`);
  return client;
}

export const agentClientGuides: AgentClientGuide[] = [
  ...IN_APP_CLIENTS.map((id) => {
    const client = sharedClient(id);
    return { id, name: client.name, verified: client.monomerSignIn === "verified", guideUrl: publicDocsUrl(client.guide), steps: STEPS[id] };
  }),
  {
    id: "other",
    name: "Other MCP client",
    verified: null,
    guideUrl: publicDocsUrls.connectAgent,
    steps: [
      step("Add a remote MCP server (Streamable HTTP) with the URL. Leave the token and header fields empty."),
      step("Start the client’s sign-in and approve Monomer in your browser. The client must support sign-in with a Client ID Metadata Document."),
    ],
  },
];

/** Clients confirmed with Monomer sign-in, in the order the docs list them, such as "Claude, Claude Code, or Codex". */
export function verifiedClientNames(): string {
  const names = sharedClients.filter((client) => client.monomerSignIn === "verified").map((client) => client.name);
  if (names.length <= 2) return names.join(" or ");
  return `${names.slice(0, -1).join(", ")}, or ${names[names.length - 1]}`;
}
