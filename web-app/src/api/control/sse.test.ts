import { describe, expect, it } from "vitest";
import { controlOperationDefinitions } from "./generated-operations";
import { ControlSseParseError, openControlSse, parseControlSseFrame } from "./sse";

describe("Control SSE transport boundary", () => {
  it("parses event, id, and joined data fields", () => {
    expect(parseControlSseFrame("id: 4\nevent: UPDATED\ndata: {\"revision\":\"4\"}\ndata: {\"tail\":true}"))
      .toEqual({
        id: "4",
        event: "UPDATED",
        data: "{\"revision\":\"4\"}\n{\"tail\":true}",
      });
  });

  it("closes the open stream when a frame carries malformed data", async () => {
    let cancelReason: unknown = null;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode("event: UPDATED\ndata: {not json\n\n"));
      },
      cancel(reason) {
        cancelReason = reason;
      },
    });
    const events: unknown[] = [];

    const opened = openControlSse({
      fetchImplementation: () => Promise.resolve(new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } })),
      handlers: { onEvent: (event) => events.push(event) },
      operation: controlOperationDefinitions.watchDashboardSession,
      request: { path: { workspaceId: "workspace-1", sessionId: "session-1" } },
      signal: new AbortController().signal,
    });

    await expect(opened).rejects.toBeInstanceOf(ControlSseParseError);
    expect(cancelReason).toBeInstanceOf(ControlSseParseError);
    expect(events).toEqual([]);
  });
});
