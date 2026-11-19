import { beforeEach, expect, it, vi } from "vitest";
import { useAuthStore } from "@/lib/auth/store";
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { publicPagesAPI, publicPagesKey } from "./public-pages";

beforeEach(() => {
  vi.restoreAllMocks();
  useAuthStore.setState({ status: "authenticated", accessToken: "reader-token" });
});
const reads = {
  access: () => publicPagesAPI.access("workspace"),
  tree: () => publicPagesAPI.tree("workspace"),
  content: () => publicPagesAPI.content("workspace", "page"),
  entry: () => publicPagesAPI.entry("workspace", "page"),
  sessions: () => publicPagesAPI.sessions("workspace", "page", null),
  runs: () => publicPagesAPI.runs("workspace", "page", "session", null),
  run: () => publicPagesAPI.run("workspace", "page", "session", "run"),
  result: () => publicPagesAPI.result("workspace", "page", "session", "run"),
  resolve: () => publicPagesAPI.resolve("workspace", "page", "session", {}, "request"),
};
const isRefresh = (input: unknown) => String(input).endsWith("/auth/refresh");
const authorizationOf = (init: RequestInit | undefined) => new Headers(init?.headers).get("Authorization");

for (const [name, request] of Object.entries(reads)) {
  it(`${name} refreshes an expired signed-in reader and retries as the same reader`, async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (isRefresh(input)) return new Response(JSON.stringify({ access_token: "refreshed-token", expires_at: "2099-01-01T00:00:00Z" }), { status: 200 });
      return authorizationOf(init) === "Bearer refreshed-token" ? new Response("{}", { status: 200 }) : new Response("{}", { status: 401 });
    });
    await expect(request()).resolves.toBeDefined();
    const reads = fetch.mock.calls.filter(([input]) => !isRefresh(input));
    expect(reads.map(([, init]) => authorizationOf(init))).toEqual(["Bearer reader-token", "Bearer refreshed-token"]);
    expect(fetch.mock.calls.filter(([input]) => isRefresh(input))).toHaveLength(1);
    expect(useAuthStore.getState().accessToken).toBe("refreshed-token");
  });

  it(`${name} keeps a bearer rejected after a live refresh as a denial, never an anonymous retry`, async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => isRefresh(input)
      ? new Response(JSON.stringify({ access_token: "refreshed-token", expires_at: "2099-01-01T00:00:00Z" }), { status: 200 })
      : new Response("{}", { status: 401 }));
    await expect(request()).rejects.toMatchObject({ status: 401 });
    const reads = fetch.mock.calls.filter(([input]) => !isRefresh(input));
    expect(reads.every(([, init]) => authorizationOf(init)?.startsWith("Bearer "))).toBe(true);
    expect(reads[0]?.[1]?.credentials).toBe("include");
    expect(useAuthStore.getState().status).toBe("authenticated");
  });

  it(`${name} never refreshes for an anonymous reader`, async () => {
    useAuthStore.setState({ status: "unauthenticated", accessToken: null });
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 401 }));
    await expect(request()).rejects.toMatchObject({ status: 401 });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(isRefresh(fetch.mock.calls[0]?.[0])).toBe(false);
  });
}

it("keeps the parent entry mounted when a denied Session discards its old and replacement results", async () => {
  const client = new QueryClient();
  const pageId = "page";
  const sessionId = "session";
  const rootKey = publicPagesKey("workspace");
  const sessionKey = [...rootKey, pageId, sessionId];
  const entryKey = [...rootKey, pageId, "entry", "visit", sessionId];
  const entryRead = vi.fn(async () => ({ sessionId }));
  const options = { queryKey: entryKey, queryFn: entryRead, staleTime: Infinity, gcTime: 0 };
  await client.fetchQuery(options);
  const entry = new QueryObserver(client, options);
  const unsubscribe = entry.subscribe(() => {});
  const originalEntry = client.getQueryCache().find({ queryKey: entryKey });
  client.setQueryData([...sessionKey, "result", "old-run"], { rows: [42_500] });
  client.setQueryData([...sessionKey, "result", "denied-run"], { rows: [] });
  client.setQueryData([...sessionKey, "run", "old-run"], { state: "SUCCEEDED" });

  client.removeQueries({ queryKey: sessionKey });
  entry.setOptions(options);
  await Promise.resolve();
  expect(client.getQueryCache().find({ queryKey: entryKey })).toBe(originalEntry);
  expect(entry.getCurrentResult().isSuccess).toBe(true);
  expect(entryRead).toHaveBeenCalledTimes(1);
  expect(client.getQueryCache().findAll({ queryKey: sessionKey })).toHaveLength(0);

  // Removing the parent entry resets its observer on the next render and remounts the reader.
  client.removeQueries({ queryKey: rootKey });
  entry.setOptions(options);
  expect(entry.getCurrentResult().isPending).toBe(true);
  expect(client.getQueryCache().find({ queryKey: entryKey })).not.toBe(originalEntry);
  await vi.waitFor(() => expect(entryRead).toHaveBeenCalledTimes(2));
  unsubscribe();
  client.clear();
});
