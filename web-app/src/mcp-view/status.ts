import { formatRelativeTime } from "@/lib/utils/relative-time";
import type { LiveState, ViewStage } from "./controller";

export const SUPERSEDED_MESSAGE = "A newer view of this dashboard is open below.";

export interface StatusLineInput {
  targetKind: "DRAFT" | "SESSION" | null;
  stage: ViewStage;
  live: LiveState;
  runningQueries: number;
  updatedAt: number | null;
  now: number;
}

export function statusLine(input: StatusLineInput): string {
  const label = input.targetKind === "SESSION" ? "Session" : "Draft";
  switch (input.stage) {
    case "waiting": return "Loading…";
    case "out-of-date": return "Out of date";
    case "show-failed": return "Not shown";
    case "loading": return `${label} · Loading…`;
    case "error": return `${label} · Couldn't load`;
    case "ready": break;
  }
  if (input.live === "superseded") return `${label} · Not updating`;
  if (input.live === "paused") return `${label} · Paused`;
  if (input.live === "failed") return `${label} · Updates stopped`;
  if (input.runningQueries > 0) return `${label} · ${input.runningQueries} ${input.runningQueries === 1 ? "query" : "queries"} running`;
  if (input.targetKind === "SESSION") return "Session · Ready";
  return `Draft · Updated ${formatRelativeTime(input.updatedAt ?? input.now, input.now)}`;
}
