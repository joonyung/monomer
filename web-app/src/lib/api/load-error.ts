import { ControlResponseContractError } from "@/api/control/transport";

export function isLoadErrorRetryable(error: unknown): boolean {
  if (error instanceof ControlResponseContractError) return false;
  if (typeof error !== "object" || error === null) return true;
  const failure = error as { problem?: { retryable?: boolean } | null; apiError?: { problem?: { retryable?: boolean } | null } | null; status?: number };
  const retryable = failure.problem?.retryable ?? failure.apiError?.problem?.retryable;
  if (retryable !== undefined) return retryable;
  return failure.status === undefined || failure.status === 0 || failure.status === 429 || failure.status >= 500;
}

export function loadErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && !(error instanceof ControlResponseContractError) ? error.message : fallback;
}
