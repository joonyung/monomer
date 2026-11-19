import type { ControlProblem } from "@/api/control/types";
import { ApiError } from "@/lib/api/client";

/** One field-level reason from Problem Details; Semantic query errors name broken definitions here. */
export interface ExploreViolation {
  field: string;
  code: string;
  message: string;
}

export interface ExploreErrorPresentation {
  code: string | null;
  detail: string;
  retryable: boolean;
  traceId: string | null;
  violations: ExploreViolation[];
  partitionFilters?: ControlProblem["partitionFilters"];
}

export function toExploreErrorPresentation(error: unknown): ExploreErrorPresentation | null {
  if (error == null) return null;
  if (error instanceof ApiError && error.problem) {
    return {
      code: error.problem.code,
      detail: error.problem.detail,
      retryable: error.problem.retryable,
      traceId: error.problem.traceId,
      ...(error.problem.partitionFilters ? { partitionFilters: error.problem.partitionFilters } : {}),
      violations: (error.problem.violations ?? []).map(({ field, code, message }) => ({ field, code, message })),
    };
  }
  if (error instanceof ApiError) {
    return {
      code: null,
      detail: `Request failed with ${error.status}.`,
      retryable: false,
      traceId: null,
      violations: [],
    };
  }
  return {
    code: null,
    detail: "The request could not be completed.",
    retryable: false,
    traceId: null,
    violations: [],
  };
}
