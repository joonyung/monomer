import { describe, expect, it } from "vitest";
import { resolveWorkspaceRouteGateOutcome } from "./TenantRouteContext";

const readyInput = {
  validWorkspaceId: true,
  status: "authenticated" as const,
  accessPending: false,
  workspacesPending: false,
  accessError: false,
  workspacesError: false,
  grantedWorkspaceResolved: true,
};

describe("workspace route gate", () => {
  it("does not release the workspace subtree before validation and access resolution", () => {
    expect(resolveWorkspaceRouteGateOutcome({ ...readyInput, validWorkspaceId: false })).toBe("invalid");
    expect(resolveWorkspaceRouteGateOutcome({ ...readyInput, accessPending: true })).toBe("loading");
    expect(resolveWorkspaceRouteGateOutcome({ ...readyInput, workspacesPending: true })).toBe("loading");
  });

  it("fails closed for query errors, denied access, and missing inventory", () => {
    expect(resolveWorkspaceRouteGateOutcome({ ...readyInput, accessError: true })).toBe("error");
    expect(resolveWorkspaceRouteGateOutcome({ ...readyInput, workspacesError: true })).toBe("error");
    expect(resolveWorkspaceRouteGateOutcome({ ...readyInput, grantedWorkspaceResolved: false })).toBe("unavailable");
  });

  it("releases the workspace subtree only after both checks grant the URL workspace", () => {
    expect(resolveWorkspaceRouteGateOutcome(readyInput)).toBe("ready");
  });
});
