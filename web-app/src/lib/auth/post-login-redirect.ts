const DEFAULT_POST_LOGIN_REDIRECT = "/";
const APP_ORIGIN = "https://app.monomer.invalid";
const CONTROL_CHARACTER_PATTERN = new RegExp("[\\u0000-\\u001f\\u007f]");
const SCRIPT_SCHEME_PATTERN = /^\/(?:javascript|data|vbscript):/i;
const OAUTH_RETURN_PREFIX = "auth:oauth-return:";

function isAppRelativePath(value: string): boolean {
  return !(
    !value.startsWith("/") ||
    value.startsWith("//") ||
    value.includes("\\") ||
    CONTROL_CHARACTER_PATTERN.test(value) ||
    SCRIPT_SCHEME_PATTERN.test(value)
  );
}

function normalizeAppRelativePath(value: string): string | null {
  if (!isAppRelativePath(value)) return null;
  try {
    const parsed = new URL(value, APP_ORIGIN);
    if (parsed.origin !== APP_ORIGIN) return null;
    const normalizedValue = `${parsed.pathname}${parsed.search}${parsed.hash}`;
    return isAppRelativePath(normalizedValue) ? normalizedValue : null;
  } catch {
    return null;
  }
}

export function sanitizePostLoginRedirect(value: string | null): string {
  if (!value || value !== value.trim()) return DEFAULT_POST_LOGIN_REDIRECT;
  let decodedValue = value;
  let normalizedValue: string | null = null;
  for (let decodeDepth = 0; decodeDepth < 4; decodeDepth += 1) {
    const normalizedDecodedValue = normalizeAppRelativePath(decodedValue);
    if (!normalizedDecodedValue) return DEFAULT_POST_LOGIN_REDIRECT;
    normalizedValue ??= normalizedDecodedValue;
    let nextDecodedValue: string;
    try {
      nextDecodedValue = decodeURIComponent(decodedValue);
    } catch {
      return DEFAULT_POST_LOGIN_REDIRECT;
    }
    if (nextDecodedValue === decodedValue) return normalizedValue;
    decodedValue = nextDecodedValue;
  }
  return DEFAULT_POST_LOGIN_REDIRECT;
}

function createOpaqueState(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}

export function storePostLoginRedirect(value: string): string {
  const oauthState = createOpaqueState();
  try {
    window.sessionStorage.setItem(
      `${OAUTH_RETURN_PREFIX}${oauthState}`,
      sanitizePostLoginRedirect(value),
    );
  } catch {
    return oauthState;
  }
  return oauthState;
}

export function consumePostLoginRedirect(oauthState: string | null): string | null {
  if (!oauthState) return null;
  const storageKey = `${OAUTH_RETURN_PREFIX}${oauthState}`;
  let value: string | null = null;
  try {
    value = window.sessionStorage.getItem(storageKey);
    window.sessionStorage.removeItem(storageKey);
  } catch {
    return null;
  }
  if (value === null) return null;
  return sanitizePostLoginRedirect(value);
}

export const _internal = { OAUTH_RETURN_PREFIX };
