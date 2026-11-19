import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  consumePostLoginRedirect,
  sanitizePostLoginRedirect,
  storePostLoginRedirect,
  _internal,
} from "./post-login-redirect";
import { createMemoryStorage } from "@/test/memory-storage";

describe("OAuth return state", () => {
  const storage = createMemoryStorage();

  beforeEach(() => {
    storage.clear();
    vi.stubGlobal("window", { sessionStorage: storage });
  });
  afterEach(() => vi.unstubAllGlobals());

  it("keeps a canonical deep link including search and hash", () => {
    const target = "/workspaces/00000000-0000-4000-8000-000000000001/explore?mode=semantic#result";
    expect(sanitizePostLoginRedirect(target)).toBe(target);
  });

  it("rejects external and protocol-relative redirects", () => {
    expect(sanitizePostLoginRedirect("https://attacker.example/path")).toBe("/");
    expect(sanitizePostLoginRedirect("//attacker.example/path")).toBe("/");
  });

  it("keys each return target by opaque oauth_state and consumes only the matching entry", () => {
    const firstState = storePostLoginRedirect("/workspaces/first/catalog");
    const secondState = storePostLoginRedirect("/workspaces/second/metrics");
    expect(firstState).not.toBe(secondState);
    expect(storage.getItem(`${_internal.OAUTH_RETURN_PREFIX}${firstState}`)).toBe("/workspaces/first/catalog");
    expect(consumePostLoginRedirect(secondState)).toBe("/workspaces/second/metrics");
    expect(consumePostLoginRedirect(firstState)).toBe("/workspaces/first/catalog");
    expect(consumePostLoginRedirect(firstState)).toBeNull();
  });

  it("rejects callbacks without a matching browser-session state", () => {
    expect(consumePostLoginRedirect(null)).toBeNull();
    expect(consumePostLoginRedirect("unknown-state")).toBeNull();
  });
});
