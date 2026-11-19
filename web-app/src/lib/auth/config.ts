export const CONTROL_API_URL = import.meta.env.VITE_MONOMER_CONTROL_API_URL;

export const LOCAL_LOGIN_ENABLED =
  import.meta.env.VITE_LOCAL_LOGIN_ENABLED === "true";

export const GOOGLE_OAUTH_ENABLED =
  import.meta.env.VITE_GOOGLE_OAUTH_ENABLED !== "false";

export const GOOGLE_OAUTH_START_URL = `${CONTROL_API_URL}/oauth2/authorization/google`;
export const LOCAL_LOGIN_URL = `${CONTROL_API_URL}/auth/local/login`;

export const TOKEN_REFRESH_THRESHOLD_MS = 60_000;

export function isTokenExpiringSoon(expiresAt: string | null, nowMs: number = Date.now()): boolean {
  if (!expiresAt) return true;
  const timestamp = Date.parse(expiresAt);
  if (Number.isNaN(timestamp)) return true;
  return timestamp - nowMs <= TOKEN_REFRESH_THRESHOLD_MS;
}
