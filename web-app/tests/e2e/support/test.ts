import { test as base, expect, type BrowserContext, type ConsoleMessage } from "@playwright/test";

// Every spec imports `test` and `expect` from here. The auto fixture fails a test on any browser
// console error or warning, including failed resource loads, and on any uncaught page error.
// A test that deliberately provokes a console error declares it with `consoleErrors.allow`; each
// declaration carries its reason and must match at least one message during that test.

type AllowedConsoleError =
  | { status: number; url: RegExp; reason: string }
  | { message: RegExp; url?: RegExp; reason: string };

export interface ConsoleErrors {
  allow(entry: AllowedConsoleError): void;
  watch(context: BrowserContext): void;
}

function matches(entry: AllowedConsoleError, message: ConsoleMessage): boolean {
  const url = message.location().url;
  if ("status" in entry) {
    return message.text().startsWith(`Failed to load resource: the server responded with a status of ${entry.status} (`) && entry.url.test(url);
  }
  return entry.message.test(message.text()) && (entry.url?.test(url) ?? true);
}

export const test = base.extend<{ consoleErrors: ConsoleErrors }>({
  consoleErrors: [async ({ context }, use) => {
    const allowed: Array<{ entry: AllowedConsoleError; used: boolean }> = [];
    const unexpected: string[] = [];
    const watch = (watched: BrowserContext) => {
      watched.on("console", (message) => {
        if (message.type() !== "error" && message.type() !== "warning") return;
        const allowance = allowed.find(({ entry }) => matches(entry, message));
        if (allowance) allowance.used = true;
        else unexpected.push(`console.${message.type()}: ${message.text()} (${message.location().url})`);
      });
      watched.on("weberror", (webError) => unexpected.push(`uncaught: ${webError.error().stack ?? webError.error().message}`));
    };
    watch(context);
    await use({ allow: (entry) => { allowed.push({ entry, used: false }); }, watch });
    expect(unexpected, "Unexpected browser console errors").toEqual([]);
    expect(allowed.filter(({ used }) => !used).map(({ entry }) => entry.reason), "Console error allowances that never matched").toEqual([]);
  }, { auto: true }],
});

export { expect };
