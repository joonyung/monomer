import { beforeEach, describe, expect, it, vi } from "vitest";
import { addWorkspaceMember, setWorkspaceMemberRole, fetchWorkspaceAccess, fetchWorkspaceAuthorizationAssignments, setWorkspaceReportingTimeZone, UnsupportedReportingTimeZoneError, MemberMutationError } from "./graphql";
import { useAuthStore } from "./store";

describe("Workspace role GraphQL contract", () => {
  beforeEach(() => { vi.restoreAllMocks(); useAuthStore.setState({ accessToken: "token", status: "authenticated" }); });
  it("reads one role without retired role or data ACL fields", async () => {
    const access = { granted: true, organizationId: "organization", role: "EDITOR" };
    const request = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ data: { workspaceAccess: access } })));
    await expect(fetchWorkspaceAccess("workspace")).resolves.toEqual(access);
    const body = JSON.parse(String(request.mock.calls[0][1]?.body));
    expect(body.query).toContain("granted organizationId role");
    expect(body.query).not.toMatch(/directRole|effectiveRole|authoringCapability|resourceAccess|owner/);
  });
  it("requires an explicit role for direct add and replacement", async () => {
    const request = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ data: { addWorkspaceMember: {}, setWorkspaceMemberRole: true } })));
    await addWorkspaceMember("workspace", "user", "EXPLORER");
    await setWorkspaceMemberRole("workspace", "user", "VIEWER");
    const bodies = request.mock.calls.map(call => JSON.parse(String(call[1]?.body)));
    expect(bodies[0].variables).toEqual({ workspaceId: "workspace", principalId: "user", role: "EXPLORER" });
    expect(bodies[0].query).toContain("$role: WorkspaceRole!");
    expect(bodies[1].variables).toEqual({ workspaceId: "workspace", userId: "user", role: "VIEWER" });
  });
  it("loads direct members in Owner inventory", async () => {
    const assignments = { authorizationRevision: "7", members: [] };
    const request = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ data: { workspaceAuthorizationAssignments: assignments } })));
    await expect(fetchWorkspaceAuthorizationAssignments("workspace")).resolves.toEqual(assignments);
    const body = JSON.parse(String(request.mock.calls[0][1]?.body));
    expect(body.query).toContain("authorizationRevision");
    expect(body.query).toContain("members { principalId displayName user { id email displayName } role }");
    expect(body.query).not.toMatch(/principals|secondaryLabel|Capability|resourceAccess|relationAccess|directRole|effectiveRole|principalKind/);
  });
});

describe("Workspace Reporting time zone GraphQL contract", () => {
  beforeEach(() => { vi.restoreAllMocks(); useAuthStore.setState({ accessToken: "token", status: "authenticated" }); });
  it("sets the zone and reads it back on the Workspace", async () => {
    const workspace = { id: "workspace", organizationId: "organization", name: "Revenue", publicPages: false, reportingTimeZone: "Asia/Seoul" };
    const request = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ data: { setWorkspaceReportingTimeZone: workspace } })));
    await expect(setWorkspaceReportingTimeZone("workspace", "Asia/Seoul")).resolves.toEqual(workspace);
    const body = JSON.parse(String(request.mock.calls[0][1]?.body));
    expect(body.variables).toEqual({ workspaceId: "workspace", timeZone: "Asia/Seoul" });
    expect(body.query).toContain("setWorkspaceReportingTimeZone(workspaceId: $workspaceId, timeZone: $timeZone) { id organizationId name publicPages reportingTimeZone sample { dataset state initiator } }");
  });
  it("names an unsupported zone and a missing permission separately", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response(JSON.stringify({ errors: [{ message: "Choose a time zone from the supported list.", extensions: { classification: "BAD_REQUEST", code: "UNSUPPORTED_REPORTING_TIME_ZONE" } }] })));
    await expect(setWorkspaceReportingTimeZone("workspace", "US/Eastern")).rejects.toBeInstanceOf(UnsupportedReportingTimeZoneError);
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response(JSON.stringify({ errors: [{ message: "Forbidden", extensions: { classification: "FORBIDDEN" } }] })));
    const forbidden = setWorkspaceReportingTimeZone("workspace", "Asia/Seoul");
    await expect(forbidden).rejects.toBeInstanceOf(MemberMutationError);
    await expect(forbidden).rejects.toThrow("Only workspace owners and Organization Admins can change the Reporting time zone.");
  });
});
