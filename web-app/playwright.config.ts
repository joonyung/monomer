import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig } from "@playwright/test";

// Default: Playwright manages its own fresh dev server on a dedicated port so
// `npm run test:e2e` is self-contained — no manually started server, no stale
// HMR state, no collision with the main-stack Web app on 3100 or a slot app.
// Set PLAYWRIGHT_BASE_URL to target an already-running FE instead (slot
// pairing, live specs); no server is started then.
const externalBaseUrl = process.env.PLAYWRIGHT_BASE_URL;
// Each worktree slot gets its own managed server port (3005 + slot × 100), so suites in
// different worktrees never race for the same port; slot 0 (the main checkout) keeps 3005.
function worktreeSlot(): number {
  const instanceFile = fileURLToPath(new URL("../.monomer/instance.env", import.meta.url));
  if (!existsSync(instanceFile)) return 0;
  const slot = /^MONOMER_SLOT=(\d+)$/m.exec(readFileSync(instanceFile, "utf8"))?.[1];
  return slot ? Number(slot) : 0;
}
const managedServerPort = Number(process.env.PLAYWRIGHT_MANAGED_SERVER_PORT ?? String(3005 + worktreeSlot() * 100));
if (!Number.isInteger(managedServerPort) || managedServerPort < 1 || managedServerPort > 65_535) {
  throw new Error("PLAYWRIGHT_MANAGED_SERVER_PORT must be an integer from 1 to 65535.");
}
const managedServerEnvironment: Record<string, string> = {};
const configuredControlApiUrl =
  process.env.MONOMER_LIVE_CONTROL_API_URL?.trim()
  || process.env.VITE_MONOMER_CONTROL_API_URL?.trim();
if (configuredControlApiUrl) {
  managedServerEnvironment.VITE_MONOMER_CONTROL_API_URL = configuredControlApiUrl;
}

export default defineConfig({
  testDir: "./tests/e2e",
  // One Playwright run at a time on this machine; see tests/e2e/support/run-lock.ts.
  globalSetup: "./tests/e2e/support/run-lock.ts",
  fullyParallel: false,
  workers: process.env.CI === "true" ? 1 : "50%",
  retries: 0,
  use: {
    baseURL: externalBaseUrl ?? `http://localhost:${managedServerPort}`,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  webServer: externalBaseUrl
    ? undefined
    : {
        command: `npm run dev -- --port ${managedServerPort}`,
        url: `http://localhost:${managedServerPort}`,
        env: managedServerEnvironment,
        reuseExistingServer: false,
        timeout: 120_000,
      },
});
