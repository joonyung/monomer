export function resolveControlApiUrl(
  configuredUrl: string | undefined,
  localEnvironment: boolean,
): string {
  const invalidOrigin = () => new Error(
    "VITE_MONOMER_CONTROL_API_URL must be an explicit HTTPS origin without credentials, path, query, or fragment; HTTP loopback is allowed only with APP_ENV=local.",
  );
  if (!configuredUrl || configuredUrl !== configuredUrl.trim()) throw invalidOrigin();

  let url: URL;
  try {
    url = new URL(configuredUrl);
  } catch {
    throw invalidOrigin();
  }
  const loopback = url.hostname === "localhost"
    || url.hostname === "[::1]"
    || /^127(?:\.\d{1,3}){3}$/.test(url.hostname);
  const allowedProtocol = url.protocol === "https:"
    || (localEnvironment && loopback && url.protocol === "http:");
  if (!allowedProtocol || !/^https?:\/\/[^/?#\\\s]+\/?$/.test(configuredUrl)
    || configuredUrl.includes("@") || url.pathname !== "/" || url.search || url.hash) {
    throw invalidOrigin();
  }
  return url.origin;
}
