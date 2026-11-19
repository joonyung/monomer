import type { Page } from "@playwright/test";

const OAUTH_RETURN_PREFIX = "auth:oauth-return:";

/**
 * Production mints the opaque state in googleSignInUrl via storePostLoginRedirect, which writes the
 * return target to sessionStorage under this prefix. A spec that navigates straight to the callback
 * has to establish the same correlation, otherwise parseOAuthCallbackParams rejects the request
 * before it reads any other parameter.
 *
 * The seed goes in through an init script rather than a preliminary navigation: several specs arm
 * one-shot page.route mocks before reaching the callback, and an extra page load consumes them.
 */
export async function seedOAuthReturn(
  page: Page,
  oauthState: string,
  returnTarget: string,
): Promise<void> {
  await page.addInitScript(
    ([storageKey, value]) => {
      try {
        window.sessionStorage.setItem(storageKey, value);
      } catch {
        // A spec asserting storage failure behaviour may block sessionStorage; leave it alone.
      }
    },
    [`${OAUTH_RETURN_PREFIX}${oauthState}`, returnTarget] as const,
  );
}

export async function readOAuthReturn(page: Page, oauthState: string): Promise<string | null> {
  return page.evaluate(
    (storageKey) => window.sessionStorage.getItem(storageKey),
    `${OAUTH_RETURN_PREFIX}${oauthState}`,
  );
}

/**
 * Navigates to the OAuth callback the way the provider would, correlated with a return target.
 * `params` carries the provider-supplied parameters (new_user, auth_provider, error, ...).
 */
export async function completeOAuthCallback(
  page: Page,
  params: Record<string, string>,
  returnTarget: string,
): Promise<string> {
  const oauthState = `e2e-${Math.random().toString(16).slice(2)}${Date.now().toString(16)}`;
  await seedOAuthReturn(page, oauthState, returnTarget);
  const search = new URLSearchParams({ ...params, oauth_state: oauthState });
  await page.goto(`/auth/callback?${search.toString()}`);
  return oauthState;
}
