import { describe, expect, it } from "vitest";
import {
  organizationMembersSearchSchema,
  organizationSettingsDemoSearchSchema,
  organizationWorkspacesSearchSchema,
  pagesDemoSearchSchema,
  semanticsDemoSearchSchema,
  semanticsSearchSchema,
  workspaceActivitySearchSchema,
  workspaceMembersSearchSchema,
  workspaceSettingsDemoSearchSchema,
} from "@/lib/route-search";

describe("route search schemas", () => {
  it("validates product deep-link state", () => {
    expect(organizationMembersSearchSchema.parse({ member: "user-1" })).toEqual({ member: "user-1" });
    expect(organizationMembersSearchSchema.parse({ member: "" })).toEqual({});
    expect(organizationWorkspacesSearchSchema.parse({ workspace: "workspace-1" })).toEqual({ workspace: "workspace-1" });
    expect(organizationSettingsDemoSearchSchema.parse({})).toEqual({ surface: "workspaces", state: "ready" });
  });

  it("provides deterministic fixture fallbacks", () => {
    expect(semanticsDemoSearchSchema.parse({})).toEqual({
      view: "metrics",
      q: "",
      modelPage: 0,
      modelFilter: "", kindFilter: "", statusFilter: "", sort: "name",
      scene: "ready",
    });
  });

  it("validates Semantics areas and model-root pagination", () => {
    expect(semanticsSearchSchema.parse({
      view: "metrics",
      q: "revenue",
      modelPage: "2",
      modelFilter: "", kindFilter: "", statusFilter: "", sort: "name",
    })).toEqual({
      view: "metrics",
      q: "revenue",
      modelPage: 2,
      modelFilter: "", kindFilter: "", statusFilter: "", sort: "name",
    });
    expect(semanticsSearchSchema.parse({ view: "setup", connectorId: "connector-1" })).toEqual({
      view: "setup",
      connectorId: "connector-1",
      q: "",
      modelPage: 0,
      modelFilter: "", kindFilter: "", statusFilter: "", sort: "name",
    });
    expect(semanticsSearchSchema.parse({
      view: "unknown",
      modelPage: "-1",
      modelFilter: "", kindFilter: "", statusFilter: "", sort: "name",
    })).toEqual({
      view: "metrics",
      q: "",
      modelPage: 0,
      modelFilter: "", kindFilter: "", statusFilter: "", sort: "name",
    });
  });

  it("normalizes primitives decoded by the router search parser", () => {
    expect(pagesDemoSearchSchema.parse({ surface: "root", controls: 0 })).toEqual({
      surface: "root",
      controls: "0",
    });
  });

  it("keeps only the Activity scope and drops a malformed one", () => {
    const person = "019e8000-0000-7000-8000-000000000b03";
    const app = "https://claude.ai/oauth/mcp-oauth-client-metadata";
    expect(workspaceActivitySearchSchema.parse({})).toEqual({ scope: "all" });
    expect(workspaceActivitySearchSchema.parse({ scope: "agents", app, person })).toEqual({ scope: "agents" });
    expect(workspaceActivitySearchSchema.parse({ scope: "mine" })).toEqual({ scope: "mine" });
    expect(workspaceActivitySearchSchema.parse({ scope: "everyone" })).toEqual({ scope: "all" });
  });

  it("keeps only the member sheet in Workspace members search state", () => {
    expect(workspaceMembersSearchSchema.parse({ aclView: "preview" })).toEqual({});
    expect(workspaceMembersSearchSchema.parse({ member: "user-1" })).toEqual({ member: "user-1" });
    expect(workspaceSettingsDemoSearchSchema.parse({ viewer: "guest", surface: "danger" })).toEqual({ viewer: "owner", surface: "general" });
  });
});
