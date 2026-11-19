import { resolvePublicSiteOrigin } from "./public-site-origin";

// Public documentation lives on the public site, not the application origin. Each deployment links
// to the docs of its own environment.
const PUBLIC_SITE_ORIGIN = resolvePublicSiteOrigin(import.meta.env.VITE_MONOMER_PUBLIC_SITE_ORIGIN);

/** The public site home of this deployment's environment, labeled by its host. */
export const publicSiteHome = { url: `${PUBLIC_SITE_ORIGIN}/`, label: new URL(PUBLIC_SITE_ORIGIN).host } as const;

export function publicDocsUrl(path: string): string {
  return `${PUBLIC_SITE_ORIGIN}/en/docs/${path}/`;
}

export const publicDocsUrls = {
  help: `${PUBLIC_SITE_ORIGIN}/en/docs/`,
  connectAgent: publicDocsUrl("agents/connect"),
  connectRestClient: publicDocsUrl("agents/rest"),
  dashboards: publicDocsUrl("dashboards"),
  terms: publicDocsUrl("help/terms"),
  privacy: publicDocsUrl("help/privacy"),
} as const;

// Each app screen's guide, as a public docs source path.
export const SCREEN_DOCS_PATHS = {
  overview: "start/overview",
  workspaces: "start/workspaces",
  pages: "dashboards/pages",
  connectors: "sources/connectors",
  catalog: "sources/catalog",
  semantics: "model/semantic-models",
  metrics: "model/metrics",
  lineage: "model/lineage",
  explore: "analyze/query-metrics",
  dashboards: "dashboards/view",
  workspaceMembers: "administer/members",
  workspacePublicAccess: "dashboards/public-pages",
  workspaceSettings: "administer/members",
  organizationSettings: "administer/members",
  userSettings: "administer/user-settings",
  userAgents: "agents/connect",
} as const;

export type AppScreen = keyof typeof SCREEN_DOCS_PATHS;

export function screenDocsUrl(screen: AppScreen): string {
  return publicDocsUrl(SCREEN_DOCS_PATHS[screen]);
}

// The first segment under /workspaces/{id}/ names the screen; nested routes share their screen's guide.
const WORKSPACE_SCREENS: Record<string, AppScreen> = {
  activity: "overview",
  pages: "pages",
  connectors: "connectors",
  catalog: "catalog",
  semantics: "semantics",
  "semantic-models": "semantics",
  metrics: "metrics",
  lineage: "lineage",
  explore: "explore",
  dashboards: "dashboards",
};

/** The guide for the screen at an app path, or the docs home when no guide matches. */
export function helpDocsUrlForPath(pathname: string): string {
  const screen = appScreenForPath(pathname);
  return screen ? screenDocsUrl(screen) : publicDocsUrls.help;
}

function appScreenForPath(pathname: string): AppScreen | null {
  if (pathname === "/workspaces") return "workspaces";
  if (pathname === "/settings/agents") return "userAgents";
  if (pathname.startsWith("/settings/")) return "userSettings";
  if (/^\/organizations\/[^/]+\/settings(?:\/|$)/.test(pathname)) return "organizationSettings";
  const workspaceRoute = pathname.match(/^\/workspaces\/[^/]+(?:\/(.*))?$/);
  if (!workspaceRoute) return null;
  const segments = (workspaceRoute[1] ?? "").split("/").filter(Boolean);
  if (segments.length === 0) return "overview";
  if (segments[0] === "settings") {
    if (segments[1] === "members") return "workspaceMembers";
    if (segments[1] === "public-access") return "workspacePublicAccess";
    return "workspaceSettings";
  }
  return WORKSPACE_SCREENS[segments[0]] ?? null;
}
