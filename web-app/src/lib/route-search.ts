import { z } from "zod";

function searchEnum<const Values extends readonly [string, ...string[]]>(values: Values) {
  return z.preprocess(
    (value) => value === undefined ? undefined : String(value),
    z.enum(values),
  );
}

// TanStack parses search values as JSON, so a schema named 2024 arrives as a number.
const searchName = z.preprocess(
  (value) => value === undefined || typeof value === "string" ? value : String(value),
  z.string().min(1),
);

export const catalogSearchSchema = z.object({
  connector: z.string().uuid().optional().catch(undefined),
  catalog: searchName.optional().catch(undefined),
  schema: searchName.optional().catch(undefined),
  table: searchName.optional().catch(undefined),
  // Kept as the number 1 so the router writes it back as `choose=1`; a string would be re-encoded as `"1"`.
  choose: z.preprocess((value) => value === 1 || value === "1" ? 1 : undefined, z.literal(1).optional()).catch(undefined),
  ingest: z.string().uuid().optional().catch(undefined),
  refresh: z.string().uuid().optional().catch(undefined),
  // An Overview list-refresh link names the refreshed level itself, apart from the catalog being browsed.
  refreshCatalog: searchName.optional().catch(undefined),
  refreshSchema: searchName.optional().catch(undefined),
});

export type CatalogSearch = z.infer<typeof catalogSearchSchema>;

export const tableSourceSearchSchema = z.object({
  connector: z.string().uuid().optional().catch(undefined),
  catalog: searchName.optional().catch(undefined),
  schema: searchName.optional().catch(undefined),
  table: searchName.optional().catch(undefined),
});

// A measure's "Create metric" opens the editor as a Simple metric over that model and measure.
export const newMetricSearchSchema = z.object({
  model: searchName.optional().catch(undefined),
  measure: searchName.optional().catch(undefined),
});

export const connectorsSearchSchema = z.object({
  connector: z.string().uuid().optional().catch(undefined),
  mode: searchEnum(["revise", "rotate"]).optional().catch(undefined),
  returnTo: z.string().min(1).optional().catch(undefined),
});

export const workspaceActivitySearchSchema = z.object({
  scope: searchEnum(["all", "mine", "agents"]).catch("all"),
});

export const loginSearchSchema = z.object({
  redirect: z.string().optional().catch(undefined),
});

export const oauthCallbackSearchSchema = z.object({
  invitation_continuation: z.string().optional(),
  error: z.string().optional().catch(undefined),
  error_description: z.string().optional().catch(undefined),
  new_user: searchEnum(["true", "false"]).optional().catch(undefined),
  auth_provider: z.string().optional().catch(undefined),
  oauth_state: z.string().min(1).optional().catch(undefined),
});

export const semanticsSearchSchema = z.object({
  view: searchEnum(["metrics", "models", "setup"]).catch("metrics"),
  connectorId: z.string().min(1).optional().catch(undefined),
  q: z.string().catch(""),
  modelPage: z.coerce.number().int().nonnegative().catch(0),
  modelFilter: z.string().catch(""),
  kindFilter: searchEnum(["", "simple", "ratio", "derived", "cumulative", "conversion"]).catch(""),
  statusFilter: searchEnum(["", "broken", "connector"]).catch(""),
  sort: searchEnum(["name", "updated"]).catch("name"),
});

export const semanticsDemoSearchSchema = semanticsSearchSchema.extend({
  scene: searchEnum(["ready", "empty", "loading", "error"]).catch("ready"),
});

export const pagesDemoSearchSchema = z.object({
  surface: searchEnum([
    "root", "folder", "markdown", "notebook", "html",
    "empty-editor", "empty-member", "depth", "dense", "loading", "error", "not-found",
  ]).catch("folder"),
  controls: searchEnum(["0", "1"]).catch("1"),
});

export const pagesDemoDetailSearchSchema = z.object({
  controls: searchEnum(["0", "1"]).catch("1"),
});

export const workspaceMembersSearchSchema = z.object({
  member: z.string().min(1).optional().catch(undefined),
});

export const workspaceSettingsDemoSearchSchema = workspaceMembersSearchSchema.extend({
  viewer: searchEnum(["owner", "admin", "editor", "explorer"]).catch("owner"),
  surface: searchEnum(["general", "members", "public-access"]).catch("general"),
});

// Organization settings open detail sheets from the URL so a sheet can be linked to and Back closes it.
export const organizationMembersSearchSchema = z.object({
  member: z.string().min(1).optional().catch(undefined),
});

export const organizationWorkspacesSearchSchema = z.object({
  workspace: z.string().min(1).optional().catch(undefined),
});

export const organizationSettingsDemoSearchSchema = organizationWorkspacesSearchSchema.extend({
  surface: searchEnum(["workspaces", "general"]).catch("workspaces"),
  state: searchEnum(["ready", "empty", "error", "loading", "overflow", "unlimited"]).catch("ready"),
});

export const organizationMembersDemoSearchSchema = organizationMembersSearchSchema;
