import type { Page } from "@playwright/test";
import { E2E_ACCESS_TOKEN, json, mockGraphql, mockSessionRefresh } from "./support/session";
import { expect, test } from "./support/test";

const operator = { id: "019e8000-0000-7000-8000-0000000000d1", email: "operator@example.test", displayName: "Morgan Operator" };
const consent = {
  request: "ops-fixture-request",
  csrfToken: "ops-fixture-csrf",
  clientName: "Monomer local operations",
  resource: "https://api.monomer.cc/ops/v1",
  expiresAt: "2026-09-30T00:10:00Z",
};
const localCallback = "http://127.0.0.1:47123/oauth/callback";

// Control's /ops/oauth/consent read and decision for a signed-in browser session.
async function openConsent(page: Page, readStatus: 200 | 403) {
  await page.clock.install({ time: new Date("2026-09-30T00:00:00Z") });
  await mockSessionRefresh(page);
  await mockGraphql(page, {
    Me: (route) => json(route, { data: { me: { user: operator, organizationMemberships: [] } } }),
  });
  const decisions: unknown[] = [];
  await page.route("**/ops/oauth/consent**", async (route) => {
    expect(route.request().headers().authorization).toBe(`Bearer ${E2E_ACCESS_TOKEN}`);
    if (route.request().method() === "POST") {
      const decision = route.request().postDataJSON() as { approve: boolean };
      decisions.push(decision);
      return json(route, { redirectUri: `${localCallback}?${decision.approve ? "code=ops-fixture-code" : "error=access_denied"}&state=ops-fixture-state` });
    }
    expect(new URL(route.request().url()).searchParams.get("request")).toBe(consent.request);
    return readStatus === 200 ? json(route, consent) : json(route, { error: "access_denied" }, 403);
  });
  await page.route(`${localCallback}?*`, (route) => route.fulfill({ contentType: "text/html; charset=utf-8", body: "Returned to Monomer Operations." }));
  await page.goto(`/ops/consent?request=${consent.request}`);
  return { decisions };
}

test("names the operator, app, and production service before authorizing read-only access", async ({ page }) => {
  const fixture = await openConsent(page, 200);

  await expect(page.getByRole("heading", { name: "Authorize operations access" })).toBeVisible();
  const details = page.getByRole("definition");
  await expect(details).toHaveText([operator.displayName, operator.email, consent.clientName, consent.resource, "Production"]);
  await expect(page.getByText("This grants read-only service operations access to the local app on this computer.")).toBeVisible();
  await page.getByRole("button", { name: "Authorize read-only access" }).click();

  await expect(page.getByText("Returned to Monomer Operations.")).toBeVisible();
  expect(page.url()).toBe(`${localCallback}?code=ops-fixture-code&state=ops-fixture-state`);
  expect(fixture.decisions).toEqual([{ request: consent.request, csrfToken: consent.csrfToken, approve: true }]);
});

test("explains a denied operations request without offering a decision", async ({ page, consoleErrors }) => {
  consoleErrors.allow({ status: 403, url: /\/ops\/oauth\/consent\?/, reason: "Control denies consent to a user without operations access." });
  const fixture = await openConsent(page, 403);

  const alert = page.getByRole("alert");
  await expect(alert).toContainText("You do not have operations access.");
  await expect(alert).toContainText("Restart sign-in from your local Monomer Operations app.");
  await expect(page.getByRole("button", { name: "Authorize read-only access" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Cancel" })).toHaveCount(0);
  expect(fixture.decisions).toEqual([]);
});
