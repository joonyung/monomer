/** A recorded person. displayName is their current name, or null when the account has no readable name. */
export interface RecordedActor {
  id: string;
  displayName: string | null;
}

/** How a person did the work: through the Web, or through an agent connection acting as them. */
export type CredentialSurface = "WEB" | "AGENT";

/**
 * The agent app behind agent work. name is the client name the app presented when it was connected, shown only for
 * display; verifiedHost is the host of an https client id that Monomer verified, or null for a registered client.
 */
export interface AgentApp {
  clientId: string;
  name: string;
  verifiedHost: string | null;
}
