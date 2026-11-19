import { afterEach, expect, it, vi } from "vitest";
import { overviewAPI } from "@/api/overview";

afterEach(() => vi.unstubAllGlobals());

it("does not report Viewer denial as a missing Workspace", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
    type: "about:blank", title: "Forbidden", status: 403, detail: "Explorer role is required.",
      code: "FORBIDDEN", instance: "/v2/workspaces/fixture", requestId: "request-1", traceId: "trace-1", retryable: false,
  }), { status: 403, headers: { "Content-Type": "application/problem+json" } })));
  await expect(overviewAPI.read("00000000-0000-7000-8000-000000000001",
    { section: "recent", actor: "all", limit: 10 }, new AbortController().signal))
    .rejects.toThrow("Activity could not be loaded.");
});
