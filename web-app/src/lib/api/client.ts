import { readAuthSnapshot, useAuthStore } from "@/lib/auth/store";
import { CONTROL_API_URL } from "@/lib/auth/config";
import { parseControlProblem } from "@/api/control/problem";
import type { ControlProblem } from "@/api/control/types";
import {
  accessDeniedInputFromUnknown,
  SEMANTIC_AUTHORIZATION_REQUIRED_ERROR,
} from "@/lib/access-denied";

export interface ApiFetchOptions extends Omit<RequestInit, "body"> {
  body?: BodyInit | null;
  /** Skip Authorization header injection. */
  skipAuth?: boolean;
  /** Skip the 401 → refresh → retry loop. */
  skipRefresh?: boolean;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ControlProblem | null;
  readonly bodyText: string;

  constructor(
    message: string,
    status: number,
    bodyText: string,
    problem: ControlProblem | null = null,
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
    this.bodyText = bodyText;
  }
}

function applyAuthHeaders(
  headers: Headers,
  options: { skipAuth?: boolean }
): void {
  const snap = readAuthSnapshot();
  if (!options.skipAuth && snap.accessToken && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${snap.accessToken}`);
  }
}

function buildHeaders(init: ApiFetchOptions): Headers {
  const headers = new Headers(init.headers ?? undefined);
  applyAuthHeaders(headers, init);
  return headers;
}

const REFRESH_LOCK_NAME = "monomer-auth-refresh";
const REFRESH_TIMEOUT_MILLIS = 10_000;

// Module-level singleton refresh promise so concurrent callers in this tab share one
// refresh attempt. This is the SINGLE refresh entry point for the whole app — both the
// 401-retry path here and AuthProvider's session hydration funnel through it, so the
// refresh token (which the auth service rotates and reuse-detects on every call) is
// never presented twice concurrently. Two concurrent presentations would look like
// token theft to the server and revoke the entire token family, logging the user out.
let inflightRefresh: Promise<boolean> | null = null;

async function fetchRotatedToken(): Promise<boolean> {
  // Capture the session generation before the round-trip. If the session changes mid-flight —
  // a logout, or a fresh login (e.g. the OAuth callback) bumping authEpoch — discard the rotated
  // token instead of overwriting/persisting it over the new session.
  const epoch = useAuthStore.getState().authEpoch;
  const abortController = new AbortController();
  const timeout = setTimeout(() => abortController.abort(), REFRESH_TIMEOUT_MILLIS);
  try {
    const res = await fetch(`${CONTROL_API_URL}/auth/refresh`, {
      method: "POST",
      credentials: "include",
      signal: abortController.signal,
    });
    if (!res.ok) return false;
    const data = (await res.json()) as { access_token?: string; expires_at?: string };
    if (!data.access_token || !data.expires_at) return false;
    if (useAuthStore.getState().authEpoch !== epoch) return false;
    useAuthStore.getState().setAccessToken({
      accessToken: data.access_token,
      accessTokenExpiresAt: data.expires_at,
    });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timeout);
  }
}

// Serialize refresh across every tab in the browser via the Web Locks API. Refresh-token
// rotation requires each token be presented exactly once; with the cookie shared across
// tabs, two tabs refreshing at once would race. The lock makes the second tab wait until
// the first has rotated the cookie, so it then presents the already-rotated token.
async function fetchRotatedTokenSerialized(): Promise<boolean> {
  const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
  if (locks?.request) {
    return locks.request(REFRESH_LOCK_NAME, () => fetchRotatedToken());
  }
  return fetchRotatedToken();
}

export async function refreshAccessToken(): Promise<boolean> {
  if (inflightRefresh) return inflightRefresh;
  inflightRefresh = fetchRotatedTokenSerialized().finally(() => {
    inflightRefresh = null;
  });
  return inflightRefresh;
}

async function readErrorBody(res: Response): Promise<{
  problem: ControlProblem | null;
  text: string;
}> {
  const text = await res.text();
  if (!text) return { problem: null, text: "" };
  try {
    return { problem: parseControlProblem(JSON.parse(text)), text };
  } catch {
    return { problem: null, text };
  }
}

async function doFetch(input: RequestInfo | URL, init: ApiFetchOptions): Promise<Response> {
  const headers = buildHeaders(init);
  return fetch(input, {
    ...init,
    headers,
    credentials: init.credentials ?? "include",
  });
}

/**
 * Session-aware fetch primitive.
 *
 * - Injects `Authorization: Bearer <jwt>` from auth store.
 * - On 401: attempt one refresh + retry. If the refresh fails, the session has expired — UNLESS the session changed
 *   under us (concurrent logout/login), in which case this request is stale and must not tear
 *   down the now-current session.
 * - Returns the original response body for the caller's contract-specific parser.
 */
export async function sessionFetch(input: RequestInfo | URL, init: ApiFetchOptions = {}): Promise<Response> {
  // Pin the session generation at request dispatch. Any session transition after this point
  // (logout, or a fresh login) means a 401 from this request belongs to a now-superseded
  // session and must not tear down the current one.
  const requestEpoch = useAuthStore.getState().authEpoch;
  let res = await doFetch(input, init);
  if (res.status === 401 && !init.skipRefresh) {
    if (useAuthStore.getState().authEpoch !== requestEpoch) return res;
    const refreshed = await refreshAccessToken();
    if (useAuthStore.getState().authEpoch !== requestEpoch) return res;
    if (refreshed) {
      res = await doFetch(input, init);
    }
    if (res.status === 401) {
      const { text } = await readErrorBody(res.clone());
      const accessDenied = accessDeniedInputFromUnknown({ bodyText: text });
      const semanticAuthorizationRequired =
        accessDenied &&
        "error" in accessDenied &&
        accessDenied.error === SEMANTIC_AUTHORIZATION_REQUIRED_ERROR
          ? accessDenied
          : null;
      // A successful refresh means the browser session is still live. A following 401 is
      // an action denial (or a stale request), not session death — keep the session so
      // the caller can show a form error.
      const sessionIsDead = !refreshed;
      if (
        sessionIsDead &&
        !semanticAuthorizationRequired &&
        useAuthStore.getState().authEpoch === requestEpoch
      ) {
        useAuthStore.getState().expireSession();
      }
    }
  }
  return res;
}

/**
 * Authenticated JSON/API fetch wrapper.
 *
 * Adds typed application error parsing on top of `sessionFetch`. Generated Control
 * clients use `sessionFetch` directly so they can validate and parse their declared
 * response rails without losing the response body.
 */
export async function apiFetch(input: RequestInfo | URL, init: ApiFetchOptions = {}): Promise<Response> {
  const res = await sessionFetch(input, init);
  if (!res.ok) {
    const { problem, text } = await readErrorBody(res);
    const accessDenied = res.status === 401
      ? accessDeniedInputFromUnknown({ bodyText: text })
      : null;
    const semanticAuthorizationRequired =
      accessDenied &&
      "error" in accessDenied &&
      accessDenied.error === SEMANTIC_AUTHORIZATION_REQUIRED_ERROR
        ? accessDenied
        : null;
    throw new ApiError(
      semanticAuthorizationRequired?.message ?? problem?.detail ?? `Request failed with ${res.status}`,
      res.status,
      text,
      problem,
    );
  }
  return res;
}

export async function apiJson<T>(input: RequestInfo | URL, init: ApiFetchOptions = {}): Promise<T> {
  const res = await apiFetch(input, init);
  return (await res.json()) as T;
}

export interface EventStreamHandlers {
  onEvent: (type: string, data: string) => void;
}

/**
 * Parses one SSE frame (the text between blank-line separators) into its event
 * type and joined data payload, per the WHATWG event-stream spec. Lines may end
 * with CR, LF, or CRLF, so we split on `/\r\n|\r|\n/` to guarantee no field
 * value or event type ever retains a trailing `\r` regardless of how network
 * chunks split a CRLF. Returns null when the frame carries no `data:` line.
 */
export function parseSseFrame(frameText: string): { type: string; data: string } | null {
  let eventType = "message";
  const dataLines: string[] = [];
  for (const line of frameText.split(/\r\n|\r|\n/)) {
    if (line.length === 0 || line.startsWith(":")) continue;
    if (line.startsWith("event:")) {
      eventType = line.slice("event:".length).replace(/^ /, "");
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice("data:".length).replace(/^ /, ""));
    }
  }
  if (dataLines.length === 0) return null;
  return { type: eventType, data: dataLines.join("\n") };
}

/**
 * Reads a Server-Sent Events stream over `fetch`, so it can carry the same
 * `Authorization` header used by `apiFetch`; native `EventSource` cannot send custom headers.
 *
 * Resolves when the stream ends. Throws on non-2xx / missing body so the caller
 * can reconcile and reconnect. Aborting `signal` ends the loop cleanly.
 */
export async function openEventStream(
  url: string,
  handlers: EventStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const headers = new Headers({ Accept: "text/event-stream" });
  applyAuthHeaders(headers, {});
  let res: Response;
  try {
    res = await fetch(url, { headers, signal, credentials: "include", cache: "no-store" });
  } catch (err) {
    if (signal.aborted) return;
    throw err;
  }
  if (!res.ok || !res.body) {
    throw new Error(`Event stream request failed with ${res.status}`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  const flushFrames = (text: string) => {
    buffer += text;
    let match: RegExpExecArray | null;
    const separator = /\r\n\r\n|\r\r|\n\n/;
    while ((match = separator.exec(buffer)) !== null) {
      const frame = parseSseFrame(buffer.slice(0, match.index));
      buffer = buffer.slice(match.index + match[0].length);
      if (frame) handlers.onEvent(frame.type, frame.data);
    }
  };
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      flushFrames(decoder.decode(value, { stream: true }));
    }
    flushFrames(decoder.decode());
  } catch (err) {
    if (signal.aborted) return;
    throw err;
  }
}

// Re-exported for tests.
export const _internal = {
  buildHeaders,
  REFRESH_TIMEOUT_MILLIS,
  refreshAccessToken,
  resetInflightRefresh: () => {
    inflightRefresh = null;
  },
};
