import type { AgentApp, RecordedActor } from "@/lib/types/provenance";
export const RUN_PRESENTATION_STATES = [
  "RUNNING",
  "SUCCEEDED",
  "FAILED",
  "CANCELLED",
] as const;

export type RunPresentationState = (typeof RUN_PRESENTATION_STATES)[number];

export type RunPresentationCell = string | boolean | null;

export interface RunPresentationColumn {
  id: string;
  label: string;
  description?: string;
  /** Numeric columns align to the end so digits line up; other columns align to the start. */
  numeric?: boolean;
}

export interface RunPresentationResult {
  columns: readonly RunPresentationColumn[];
  rows: readonly (readonly RunPresentationCell[])[];
  returnedRowCount: number;
  totalRowCount?: number | null;
  byteSize?: string | null;
}

export interface RunPresentationMetadata {
  label: string;
  value: string;
}

/**
 * Owner-neutral facts needed to present a run. Adapters translate owner data
 * into this shape before it reaches the shared rail or detail shell.
 */
export interface RunPresentation {
  id: string;
  title: string;
  subtitle: string;
  state: RunPresentationState;
  createdAt: string;
  updatedAt: string;
  resultAvailable: boolean;
  failureMessage: string | null;
  isCurrentTarget: boolean;
  metadata?: readonly RunPresentationMetadata[];
  request?: string | null;
  result?: RunPresentationResult | null;
  elapsedSeconds?: number;
  /** The person whose Web session or agent connection started the run; null when none was recorded. */
  startedBy?: RecordedActor | null;
  /** Request path that admitted the run; AGENT renders the agent marker after the person. */
  credentialSurface?: "WEB" | "AGENT" | null;
  /** The agent app behind an AGENT run, named by the marker; null reads "via agent". */
  agentApp?: AgentApp | null;
}
