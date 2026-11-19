import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAuthStore } from "@/lib/auth/store";
import { sampleWorkspaceAPI } from "./sample-workspace";

describe("sample Workspace API", () => {
  beforeEach(() => { vi.restoreAllMocks(); useAuthStore.setState({ status: "authenticated", accessToken: "sample-token" }); });
  it("treats an absent Workspace as a read result without creating one", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 404 }));
    await expect(sampleWorkspaceAPI.read("organization/id")).resolves.toBeNull();
    expect(String(fetch.mock.calls[0][0])).toContain("/v2/organizations/organization%2Fid/sample-workspace");
    expect(fetch.mock.calls[0][1]?.method).toBeUndefined();
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("starts or resumes once and retains the server marker", async () => {
    const sample = { workspaceId: "workspace", organizationId: "organization", displayName: "Acme Cloud (sample)", sample: { dataset: "acme-cloud@1", state: "PROVISIONING", initiator: "user" } };
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(Response.json(sample));
    await expect(sampleWorkspaceAPI.start("organization")).resolves.toEqual(sample);
    expect(fetch.mock.calls[0][1]?.method).toBe("POST");
    expect(new Headers(fetch.mock.calls[0][1]?.headers).get("Authorization")).toBe("Bearer sample-token");
  });
  it("keeps authorization errors and failed mutations visible without automatically retrying", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 403 }));
    await expect(sampleWorkspaceAPI.read("organization")).rejects.toMatchObject({ status: 403 });
    fetch.mockResolvedValue(new Response(null, { status: 503 }));
    await expect(sampleWorkspaceAPI.start("organization")).rejects.toMatchObject({ status: 503 });
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});
