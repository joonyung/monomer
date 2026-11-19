import type { Page, Route } from "@playwright/test";
import type { AccessibleWorkspace, OrganizationMembership, AuthUser, WorkspaceAccess, WorkspaceRole } from "../../../src/lib/auth/types";
import type { ConsoleErrors } from "./test";

// Shared Control session fixtures. Specs keep their own REST routes. mockGraphql answers only the
// operations it is given, so a spec that needs more than the session reads passes them all to one
// mockGraphql call, and anything unexpected fails loudly.

export const E2E_ACCESS_TOKEN = "e2e-access-token";

export function json(route: Route, value: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(value) });
}

export function problem(route: Route, status: number, code: string, detail: string, title: string): Promise<void> {
  return route.fulfill({
    status,
    contentType: "application/problem+json",
    body: JSON.stringify({
      type: `https://monomer.cc/problems/${code.toLowerCase().replaceAll("_", "-")}`,
      title,
      status,
      detail,
      instance: new URL(route.request().url()).pathname,
      code,
      requestId: "e2e-request",
      traceId: "e2e-trace",
      retryable: false,
    }),
  });
}

export type GraphqlOperation = (route: Route, variables: Record<string, unknown>) => Promise<void>;

export async function mockGraphql(page: Page, operations: Record<string, GraphqlOperation>): Promise<void> {
  await page.route("**/v2/graphql", async (route) => {
    const body = route.request().postDataJSON() as { query: string; variables?: Record<string, unknown> };
    const operationName = /\b(?:query|mutation)\s+(\w+)/.exec(body.query)?.[1] ?? "anonymous";
    const operation = operations[operationName];
    if (!operation) {
      await json(route, { errors: [{ message: `Unexpected fixture GraphQL operation: ${operationName}` }] }, 400);
      return;
    }
    await operation(route, body.variables ?? {});
  });
}

export async function mockSessionRefresh(page: Page): Promise<void> {
  await page.route("**/auth/refresh", (route) =>
    json(route, { access_token: E2E_ACCESS_TOKEN, expires_at: "2099-01-01T00:00:00Z" }));
}

export async function mockSignedOut(page: Page, consoleErrors: ConsoleErrors): Promise<void> {
  consoleErrors.allow({ status: 401, url: /\/auth\/refresh$/, reason: "Control answers 401 to a refresh without a session, which signs the visitor out." });
  await page.route("**/auth/refresh", (route) =>
    problem(route, 401, "AUTHENTICATION_REQUIRED", "No refresh session is present.", "Unauthorized"));
}

function meResponse(user: AuthUser, organizationId: string) {
  return {
    me: {
      user,
      organizationMemberships: [{
        organization: { id: organizationId, displayName: user.displayName, plan: "FREE", createdAt: "2026-09-01T00:00:00Z" },
        role: "MEMBER",
      } satisfies OrganizationMembership],
    },
  };
}

export interface SignedInWorkspace {
  workspaceId: string;
  workspaceName: string;
  organizationId: string;
  role: WorkspaceRole;
  user: AuthUser;
  // Workspace owners named where a step needs an owner, such as on Overview; none by default.
  owners?: Array<{ userId: string; displayName: string }>;
}

export async function mockSignedInWorkspace(page: Page, session: SignedInWorkspace): Promise<void> {
  await mockSessionRefresh(page);
  await mockGraphql(page, {
    AccessibleWorkspaces: (route) => json(route, { data: { accessibleWorkspaces: [
      { id: session.workspaceId, organizationId: session.organizationId, name: session.workspaceName, publicPages: false, reportingTimeZone: "UTC", role: session.role } satisfies AccessibleWorkspace,
    ] } }),
    WorkspaceAccess: (route) => json(route, { data: { workspaceAccess: {
      granted: true, organizationId: session.organizationId, role: session.role, } satisfies WorkspaceAccess } }),
    Me: (route) => json(route, { data: meResponse(session.user, session.organizationId) }),
    WorkspaceOwners: (route) => json(route, { data: { workspaceAccess: { owners: session.owners ?? [] } } }),
  });
}
