import type { ReactNode } from "react";
import { AgentMarker } from "@/components/ui/AgentMarker";
import { agentAppIdentity } from "@/components/ui/agentAppIdentity";
import type { AgentApp, CredentialSurface, RecordedActor } from "@/lib/types/provenance";

/** The name a sample Workspace gives the definitions Monomer seeded (2026-10-16 sample follow-ups, D12). */
export const SAMPLE_SEED_AUTHOR = "Monomer";

/**
 * Whether a definition record was seeded by Monomer: a loaded Semantic Model, Metric, or Dashboard record in a sample
 * Workspace (sampleDefinition) that recorded neither a person nor a surface. Runs, drafts, Catalog jobs, and Pages never
 * pass sampleDefinition, so their null keeps its meaning.
 */
export function seededByMonomer(sampleDefinition: boolean, actor: RecordedActor | null, surface: CredentialSurface | null): boolean {
  return sampleDefinition && actor === null && surface === null;
}

/** The person in words: "you" for the viewer, their current name, or a fallback when the account has none. */
export function actorName(actor: RecordedActor, principalId: string | null): string {
  if (principalId !== null && actor.id === principalId) return "you";
  return actor.displayName?.trim() || "Unknown user";
}

/** The person standing alone, as a label or the start of a line: "You", "Sam Kim", "Unknown user". */
export function actorLabel(actor: RecordedActor, principalId: string | null): string {
  const name = actorName(actor, principalId);
  return name.charAt(0).toUpperCase() + name.slice(1);
}

/**
 * The provenance rule in words, for an accessible name: "started by Sam Kim via Claude (claude.ai)", "started by
 * you", "started via agent" when only the agent surface was recorded, or null when nothing was recorded.
 */
export function actorProvenanceLabel(
  verb: string,
  actor: RecordedActor | null,
  surface: CredentialSurface | null,
  agentApp: AgentApp | null,
  principalId: string | null,
): string | null {
  const via = surface === "AGENT" ? agentAppIdentity(agentApp).label : null;
  if (!actor) return via === null ? null : `${verb} ${via}`;
  const person = `${verb} by ${actorName(actor, principalId)}`;
  return via === null ? person : `${person} ${via}`;
}

/**
 * One provenance rule everywhere: "{verb} by {Person} [via {App}] · {time}". The text AgentMarker follows the person
 * whenever the owner recorded the AGENT surface. Without a recorded person the line reads "{verb} {time}", except that a
 * sample Workspace's seeded definition (sampleDefinition, see seededByMonomer) names Monomer. A null verb suits a
 * labelled fact ("Updated" → "Sam Kim via Claude · 2h ago").
 */
export function ActorProvenance({ verb, actor, surface, agentApp, principalId, time, sampleDefinition = false, className }: {
  verb: string | null;
  actor: RecordedActor | null;
  surface: CredentialSurface | null;
  agentApp: AgentApp | null;
  principalId: string | null;
  time: ReactNode;
  sampleDefinition?: boolean;
  className?: string;
}) {
  const seeded = seededByMonomer(sampleDefinition, actor, surface);
  if (!actor && !seeded) {
    return <span className={className}>{verb === null ? time : <>{verb} {time}</>}</span>;
  }
  const person = actor
    ? verb === null ? actorLabel(actor, principalId) : `${verb} by ${actorName(actor, principalId)}`
    : verb === null ? SAMPLE_SEED_AUTHOR : `${verb} by ${SAMPLE_SEED_AUTHOR}`;
  return (
    <span className={["inline-flex flex-wrap items-baseline gap-x-1.5", className].filter(Boolean).join(" ")}>
      <span>{person}</span>
      {surface === "AGENT" ? <AgentMarker app={agentApp} focusable /> : null}
      {time !== null && time !== undefined && time !== "" ? (
        <>
          <span aria-hidden="true">·</span>
          <span>{time}</span>
        </>
      ) : null}
    </span>
  );
}
