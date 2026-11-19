import { afterEach, describe, expect, it, vi } from "vitest";
import {
  invitationRequest,
  invitationDeliveryMessage,
  createOrganizationInvitation,
} from "./invitations";
import { useAuthStore } from "./store";

afterEach(() => vi.restoreAllMocks());
describe("Browser invitation transport", () => {
  it("exchanges proof only in a POST body without bearer and consumes continuation with Browser provenance", async () => {
    useAuthStore.setState({
      status: "authenticated",
      accessToken: "browser-session-token",
      accessTokenExpiresAt: "2099-01-01T00:00:00Z",
    });
    const request = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response(JSON.stringify({ outcome: "AVAILABLE" }), { status: 200 }),
      );
    await invitationRequest("exchange", "raw-proof");
    expect(String(request.mock.calls[0][0])).not.toContain("raw-proof");
    expect(request.mock.calls[0][1]).toMatchObject({
      method: "POST",
      credentials: "include",
      referrerPolicy: "no-referrer",
      body: JSON.stringify({ token: "raw-proof" }),
      headers: { "Content-Type": "application/json" },
    });
    expect(request.mock.calls[0][1]?.headers).not.toHaveProperty(
      "Authorization",
    );
    request.mockResolvedValue(
      new Response(JSON.stringify({ outcome: "ACCEPTED" }), { status: 200 }),
    );
    await invitationRequest("accept", "opaque-continuation");
    expect(request.mock.calls[1][1]).toMatchObject({
      body: JSON.stringify({ continuationId: "opaque-continuation" }),
      headers: { Authorization: "Bearer browser-session-token" },
    });
  });
  it.each(["resolve", "accept", "decline"] as const)(
    "refreshes an expired bearer before invitation %s even when the endpoint would respond 200",
    async (action) => {
      useAuthStore.setState({
        status: "authenticated",
        accessToken: "expired-browser-token",
        accessTokenExpiresAt: "2020-01-01T00:00:00Z",
      });
      const request = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify({
              access_token: "refreshed-browser-token",
              expires_at: "2099-01-01T00:00:00Z",
            }),
            { status: 200 },
          ),
        )
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ outcome: "AVAILABLE" }), {
            status: 200,
          }),
        );
      await expect(
        invitationRequest(action, "opaque-continuation"),
      ).resolves.toEqual({ outcome: "AVAILABLE" });
      expect(String(request.mock.calls[0][0])).toMatch(/\/auth\/refresh$/);
      expect(request.mock.calls[0][1]).toMatchObject({
        method: "POST",
        credentials: "include",
      });
      expect(String(request.mock.calls[1][0])).toContain(
        `/auth/invitations/${action}`,
      );
      expect(request.mock.calls[1][1]).toMatchObject({
        headers: { Authorization: "Bearer refreshed-browser-token" },
      });
    },
  );
  it("fails closed without sending an expired bearer when refresh has ended", async () => {
    useAuthStore.setState({
      status: "authenticated",
      accessToken: "expired-browser-token",
      accessTokenExpiresAt: "2020-01-01T00:00:00Z",
    });
    const request = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ outcome: "AUTHENTICATION_REQUIRED" }), {
          status: 200,
        }),
      );
    await expect(
      invitationRequest("accept", "opaque-continuation"),
    ).resolves.toEqual({ outcome: "AUTHENTICATION_REQUIRED" });
    expect(request).toHaveBeenCalledTimes(2);
    expect(request.mock.calls[1][1]?.headers).not.toHaveProperty(
      "Authorization",
    );
  });
  it("sends the email with optional Workspace access and renders typed delivery failures without treating them as membership", async () => {
    const result = {
      outcome: "DELIVERY_FAILED" as const,
      invitation: null,
      retryAfter: null,
    };
    const request = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          data: { createOrganizationMembershipInvitation: result },
        }),
        { status: 200 },
      ),
    );
    await expect(
      createOrganizationInvitation("organization-1", "Recipient@Example.com", [{ workspaceId: "workspace-1", role: "EXPLORER" }]),
    ).resolves.toEqual(result);
    const body = JSON.parse(String(request.mock.calls[0][1]?.body));
    expect(body.variables).toEqual({
      organizationId: "organization-1",
      email: "Recipient@Example.com",
      workspaceAccess: [{ workspaceId: "workspace-1", role: "EXPLORER" }],
    });
    expect(body.query).toContain("workspaceAccess: $workspaceAccess");
    expect(invitationDeliveryMessage(result)).toContain(
      "Invitation saved · email not delivered",
    );
    expect(
      invitationDeliveryMessage({
        outcome: "RATE_LIMITED",
        invitation: null,
        retryAfter: 47,
      }),
    ).toContain("47 seconds");
  });
  it("never exposes arbitrary error responses that might contain sensitive request data", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("raw-proof echoed by proxy", { status: 500 }),
    );
    await expect(invitationRequest("exchange", "raw-proof")).rejects.toThrow(
      "The invitation could not be checked. Please try again.",
    );
  });
});

describe("invitation landing copy", () => {
  it("states expiry relative to now", async () => {
    const { invitationExpiresIn } = await import("./invitations");
    const now = new Date("2026-10-02T00:00:00Z");
    expect(invitationExpiresIn("2026-10-07T00:00:00Z", now)).toBe("in 5 days");
    expect(invitationExpiresIn("2026-10-02T05:00:00Z", now)).toBe("in 5 hours");
    expect(invitationExpiresIn("2026-10-02T00:20:00Z", now)).toBe("in 20 minutes");
    expect(invitationExpiresIn("2026-10-01T23:59:00Z", now)).toBe("in 1 minute");
  });
  it("names who can add workspace access", async () => {
    const { workspaceAccessHelper } = await import("./invitations");
    expect(workspaceAccessHelper("Mina Park")).toBe("Mina Park or a Workspace Owner can add you to a workspace.");
    expect(workspaceAccessHelper(null)).toBe("An Admin or a Workspace Owner can add you to a workspace.");
  });
});
