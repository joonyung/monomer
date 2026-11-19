import type { AgentApp } from "@/lib/types/provenance";

/** The agent apps whose logo Monomer shows. */
export type AgentAppLogo = "claude" | "openai" | "cursor";

// Keyed by verified host only. An app's name is its own claim, so an app named "Claude" on another host never gets
// the Claude logo.
const LOGO_BY_VERIFIED_HOST: ReadonlyMap<string, AgentAppLogo> = new Map([
  ["claude.ai", "claude"],
  ["chatgpt.com", "openai"],
  ["cursor.com", "cursor"],
]);

export interface AgentAppIdentity {
  /** The app's logo when its verified host is a known app's host; otherwise null and the mark stays neutral. */
  logo: AgentAppLogo | null;
  /**
   * The app in a few words: the app's name for a known host or a client registered in Control, the verified host
   * for any other app, and "agent" when no app was recorded.
   */
  shortName: string;
  /**
   * The full meaning for accessible names and tooltips. It starts with the visible words so speech input matches:
   * "via Claude (claude.ai)" for a known app, "via agents.acme.dev (Acme Agent)" for any other host, "via Monomer CLI",
   * "via agent".
   */
  label: string;
}

/** How agent work identifies the app it came through. */
export function agentAppIdentity(app: AgentApp | null): AgentAppIdentity {
  if (!app) return { logo: null, shortName: "agent", label: "via agent" };
  const host = app.verifiedHost;
  if (!host) return { logo: null, shortName: app.name, label: `via ${app.name}` };
  const logo = LOGO_BY_VERIFIED_HOST.get(host.toLowerCase()) ?? null;
  return logo
    ? { logo, shortName: app.name, label: `via ${app.name} (${host})` }
    : { logo, shortName: host, label: `via ${host} (${app.name})` };
}
