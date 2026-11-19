import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createWorkspace,
  fetchWorkspaceOwnerCandidates,
} from "@/lib/auth/graphql";
import { useAuthStore } from "@/lib/auth/store";

afterEach(() => vi.restoreAllMocks());
describe("Organization Workspace governance contracts", () => {
  it("includes the explicitly selected initial Owner in Workspace creation", async () => {
    useAuthStore.setState({
      accessToken: "browser-token",
      status: "authenticated",
    });
    const request = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response(
          JSON.stringify({
            data: {
              createWorkspace: {
                id: "workspace",
                organizationId: "organization",
                name: "Analytics",
              },
            },
          }),
          { status: 200 },
        ),
      );
    await createWorkspace("organization", "Analytics", "other-user");
    expect(
      JSON.parse(String(request.mock.calls[0][1]?.body)).variables,
    ).toEqual({
      organizationId: "organization",
      displayName: "Analytics",
      initialOwnerUserId: "other-user",
    });
  });
  it("preserves server-authorized self and Organization-only candidates", async () => {
    const candidates = [
      { id: "actor", email: "actor@example.com", displayName: "Admin" },
      {
        id: "organization-only",
        email: "member@example.com",
        displayName: "Member",
      },
    ];
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({ data: { workspaceOwnerCandidates: candidates } }),
        { status: 200 },
      ),
    );
    await expect(
      fetchWorkspaceOwnerCandidates("organization", "workspace"),
    ).resolves.toEqual(candidates);
  });
});
