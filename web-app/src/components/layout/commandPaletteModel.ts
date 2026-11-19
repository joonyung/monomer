import type { ComponentType } from "react";
import {
  Compass,
  Database,
  FileText,
  Folder,
  GitBranch,
  History,
  Home,
  Layers,
  LayoutDashboard,
  Library,
  Plug,
  Settings,
  Sigma,
  Table as TableIcon,
} from "lucide-react";
import type { WorkspaceSearchResult } from "@/api/workspace-search";
import type { PageResponse } from "@/api/pages";
import { qualifiedTableName, tableCatalogHref } from "@/lib/catalog/catalog-links";
import { webPaths } from "@/lib/web-paths";

export const RESOURCE_RESULT_LIMIT = 5;

export interface PaletteItem {
  id: string;
  label: string;
  hint: string;
  icon: ComponentType<{ className?: string }>;
  href: string;
}

export interface PaletteSection {
  id: "dashboards" | "pages" | "metrics" | "semantic-models" | "tables" | "go-to";
  title: string;
  items: PaletteItem[];
}

export function workspaceCommands(workspaceId: string, canOpenSettings: boolean): PaletteItem[] {
  return [
    { id: "overview", label: "Overview", hint: "Workspace overview", icon: Home, href: webPaths.workspace(workspaceId) },
    { id: "pages", label: "Pages", hint: "Dashboards and documents your team shares", icon: Library, href: webPaths.pages(workspaceId) },
    { id: "activity", label: "Activity", hint: "Who ran what, including agents", icon: History, href: webPaths.activity(workspaceId, null) },
    { id: "connectors", label: "Connectors", hint: "Register and revise data sources", icon: Plug, href: webPaths.connectors(workspaceId) },
    { id: "catalog", label: "Catalog", hint: "Browse source tables", icon: Database, href: webPaths.catalog(workspaceId) },
    { id: "semantics", label: "Semantics", hint: "Models and business metrics", icon: Layers, href: webPaths.semantics(workspaceId) },
    { id: "lineage", label: "Lineage", hint: "Explore resource relationships", icon: GitBranch, href: webPaths.lineage(workspaceId) },
    { id: "explore", label: "Explore", hint: "Run Metrics and SQL queries", icon: Compass, href: webPaths.explore(workspaceId) },
    { id: "dashboards", label: "Dashboards", hint: "Open and build dashboards", icon: LayoutDashboard, href: webPaths.dashboards(workspaceId) },
    ...(canOpenSettings ? [
      { id: "workspace-settings", label: "Settings", hint: "Workspace name, members, and public access", icon: Settings, href: webPaths.workspaceSettings(workspaceId) },
    ] : []),
  ];
}

// The search endpoint rejects longer queries; a longer paste searches its first 200 characters.
export const MAXIMUM_SEARCH_QUERY_LENGTH = 200;

export function resourceSearchQuery(input: string): string {
  return input.trim().slice(0, MAXIMUM_SEARCH_QUERY_LENGTH).trim();
}

// Resource search runs only for text the index can match: at least one letter or number.
export function isSearchableQuery(query: string): boolean {
  return /[\p{L}\p{N}]/u.test(query);
}

export function filterCommands(commands: PaletteItem[], query: string): PaletteItem[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return commands;
  return commands.filter((command) => command.label.toLowerCase().includes(needle) || command.hint.toLowerCase().includes(needle));
}

const METRIC_TYPE_LABELS: Record<WorkspaceSearchResult["metrics"][number]["type"], string> = {
  SIMPLE: "Aggregate",
  RATIO: "Ratio",
  DERIVED: "Formula",
  CUMULATIVE: "Running total",
  CONVERSION: "Conversion",
};

const PAGE_KIND_HINTS: Record<PageResponse["kind"], string> = {
  DASHBOARD: "Dashboard page",
  DOCUMENT: "Document",
  FOLDER: "Folder",
};

// Pages are not in the metadata index; the palette matches them by title in the permission-checked
// directory the Pages sidebar already reads.
export function matchingPages(pages: PageResponse[], query: string, limit: number): PageResponse[] {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return [];
  return pages.filter((page) => {
    const title = page.displayTitle.toLowerCase();
    return words.every((word) => title.includes(word));
  }).slice(0, limit);
}

export function resourceSections(
  workspaceId: string,
  results: WorkspaceSearchResult | null,
  pages: PageResponse[],
): PaletteSection[] {
  const sections: PaletteSection[] = [
    {
      id: "dashboards",
      title: "Dashboards",
      items: (results?.dashboards ?? []).map((dashboard) => ({
        id: `dashboard-${dashboard.dashboardId}`,
        label: dashboard.title?.trim() || "Untitled dashboard",
        hint: "Dashboard",
        icon: LayoutDashboard,
        href: webPaths.dashboard(workspaceId, dashboard.dashboardId),
      })),
    },
    {
      id: "pages",
      title: "Pages",
      items: pages.map((page) => ({
        id: `page-${page.pageId}`,
        label: page.displayTitle,
        hint: PAGE_KIND_HINTS[page.kind],
        icon: page.kind === "FOLDER" ? Folder : page.kind === "DASHBOARD" ? LayoutDashboard : FileText,
        href: webPaths.page(workspaceId, page.pageId),
      })),
    },
    {
      id: "metrics",
      title: "Metrics",
      items: (results?.metrics ?? []).map((metric) => ({
        id: `metric-${metric.metricId}`,
        label: metric.label?.trim() || metric.name,
        hint: [METRIC_TYPE_LABELS[metric.type], metric.label?.trim() ? metric.name : null].filter(Boolean).join(" · "),
        icon: Sigma,
        href: webPaths.metric(workspaceId, metric.metricId),
      })),
    },
    {
      id: "semantic-models",
      title: "Semantic models",
      items: (results?.semanticModels ?? []).map((model) => ({
        id: `semantic-model-${model.semanticModelId}`,
        label: model.label?.trim() || model.name,
        hint: model.label?.trim() ? `Semantic model · ${model.name}` : "Semantic model",
        icon: Layers,
        href: webPaths.semanticModel(workspaceId, model.semanticModelId),
      })),
    },
    {
      id: "tables",
      title: "Tables",
      items: (results?.tables ?? []).map((table) => ({
        id: `table-${table.connectorId}-${qualifiedTableName(table)}`,
        label: qualifiedTableName(table),
        hint: table.connectorName,
        icon: TableIcon,
        href: tableCatalogHref(workspaceId, table),
      })),
    },
  ];
  return sections.filter((section) => section.items.length > 0);
}
