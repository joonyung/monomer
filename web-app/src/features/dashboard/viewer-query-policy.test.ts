import { describe, expect, it, vi } from "vitest";
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { immutableResultOptions, runPollInterval, retryResultRead } from "./viewer-query-policy";

describe("immutable viewer results", () => {
  it("polls only unknown or running executions", () => {
    expect(runPollInterval({ state: {} })).toBe(2000);
    expect(runPollInterval({ state: { data: { state: "RUNNING" } } })).toBe(2000);
    for (const state of ["SUCCEEDED", "FAILED", "CANCELLED"]) {
      expect(runPollInterval({ state: { data: { state } } })).toBe(false);
    }
  });
  it("reuses a completed artifact while a screen still observes it", async () => {
    const client = new QueryClient();
    let reads = 0;
    const options = { ...immutableResultOptions, queryKey: ["result", "run-1"], queryFn: async () => { reads++; return { rows: [1] }; } };
    await client.fetchQuery(options);
    const observer = new QueryObserver(client, options);
    const unsubscribe = observer.subscribe(() => {});
    await Promise.resolve();
    expect(reads).toBe(1);
    unsubscribe(); client.clear();
  });
  it("drops a completed artifact once no screen observes it so the next entry reads it again", async () => {
    const client = new QueryClient();
    let reads = 0;
    const options = { ...immutableResultOptions, queryKey: ["result", "run-1"], queryFn: async () => { reads++; return { rows: [1] }; } };
    const first = new QueryObserver(client, options);
    const unsubscribeFirst = first.subscribe(() => {});
    await vi.waitFor(() => expect(first.getCurrentResult().isSuccess).toBe(true));
    unsubscribeFirst();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(client.getQueryCache().find({ queryKey: options.queryKey })).toBeUndefined();
    const second = new QueryObserver(client, options);
    const unsubscribeSecond = second.subscribe(() => {});
    await vi.waitFor(() => expect(reads).toBe(2));
    unsubscribeSecond(); client.clear();
  });
  it("bounds transient retries and never retries revoked access", () => {
    expect(retryResultRead(0, { status: 503 })).toBe(true);
    expect(retryResultRead(2, { status: 503 })).toBe(false);
    for (const status of [401, 403, 404]) expect(retryResultRead(0, { status })).toBe(false);
  });
});
