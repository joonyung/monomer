// A completed query run and its artifact are immutable and never re-read
// periodically. gcTime 0 keeps them only while a screen observes them, so page
// entry and Session switch read them again and a revoked result is denied there.
export function runPollInterval(query: { state: { data?: { state: string } } }): number | false {
  const state = query.state.data?.state;
  return !state || state === "RUNNING" ? 2_000 : false;
}

export function retryResultRead(failureCount: number, error: unknown): boolean {
  const status = typeof error === "object" && error !== null && "status" in error ? Number(error.status) : 0;
  return failureCount < 2 && (!status || status >= 500);
}

export const immutableResultOptions = {
  staleTime: Infinity,
  gcTime: 0,
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
  retry: retryResultRead,
} as const;
