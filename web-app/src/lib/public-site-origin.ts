// The public site serves documentation for this deployment's environment, such as the dev docs for dev.
export const DEFAULT_PUBLIC_SITE_ORIGIN = "https://monomer.cc";

export function resolvePublicSiteOrigin(configuredOrigin: string | undefined): string {
  const value = configuredOrigin?.trim() || DEFAULT_PUBLIC_SITE_ORIGIN;
  const invalidOrigin = () => new Error("VITE_MONOMER_PUBLIC_SITE_ORIGIN must be an HTTPS origin without credentials, path, query, or fragment.");
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw invalidOrigin();
  }
  if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/" || url.search || url.hash) throw invalidOrigin();
  return url.origin;
}
