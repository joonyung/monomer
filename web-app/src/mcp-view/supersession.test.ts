import { describe, expect, it, vi } from "vitest";
import { isNewerView, supersessionChannelName, watchSupersession, type SupersessionChannel, type ViewOrder } from "./supersession";

// Delivers each post to every other open channel of the same name, like BroadcastChannel.
function channelBus() {
  const open = new Set<SupersessionChannel & { name: string }>();
  const pending: (() => void)[] = [];
  const create = (name: string) => {
    const channel: SupersessionChannel & { name: string } = {
      name,
      onmessage: null,
      postMessage(message) {
        for (const receiver of open) {
          if (receiver !== channel && receiver.name === name) pending.push(() => receiver.onmessage?.(new MessageEvent("message", { data: structuredClone(message) })));
        }
      },
      close() {
        open.delete(channel);
      },
    };
    open.add(channel);
    return channel;
  };
  const flush = () => {
    while (pending.length > 0) pending.shift()!();
  };
  return { create, flush };
}

const view = (instanceId: string, shownAt: number, requestId = "req"): ViewOrder => ({ instanceId, shownAt, requestId });

describe("view order", () => {
  it("orders by show time, then requestId, then instance", () => {
    expect(isNewerView(view("a", 2000), view("b", 1000))).toBe(true);
    expect(isNewerView(view("a", 1000), view("b", 2000))).toBe(false);
    expect(isNewerView(view("a", 1000, "req-2"), view("b", 1000, "req-1"))).toBe(true);
    expect(isNewerView(view("b", 1000, "req-1"), view("a", 1000, "req-1"))).toBe(true);
    expect(isNewerView(view("a", 1000, "req-1"), view("b", 1000, "req-1"))).toBe(false);
  });

  it("scopes channels to one workspace target", () => {
    expect(supersessionChannelName("w1", "workspace/dashboards/d/draft")).toBe("monomer-dashboard-view:w1:workspace/dashboards/d/draft");
  });
});

describe("supersession", () => {
  it("stops the older view when a newer view announces", () => {
    const bus = channelBus();
    const older = vi.fn();
    const newer = vi.fn();
    watchSupersession({ channelName: "target", self: view("a", 1000), onSuperseded: older, createChannel: bus.create });
    watchSupersession({ channelName: "target", self: view("b", 2000), onSuperseded: newer, createChannel: bus.create });
    bus.flush();
    expect(older).toHaveBeenCalledTimes(1);
    expect(newer).not.toHaveBeenCalled();
  });

  it("settles views that mount out of order", () => {
    const bus = channelBus();
    const newest = vi.fn();
    const older = vi.fn();
    watchSupersession({ channelName: "target", self: view("b", 2000), onSuperseded: newest, createChannel: bus.create });
    bus.flush();
    watchSupersession({ channelName: "target", self: view("a", 1000), onSuperseded: older, createChannel: bus.create });
    bus.flush();
    expect(older).toHaveBeenCalledTimes(1);
    expect(newest).not.toHaveBeenCalled();
  });

  it("leaves other targets and malformed messages alone", () => {
    const bus = channelBus();
    const draft = vi.fn();
    watchSupersession({ channelName: "draft", self: view("a", 1000), onSuperseded: draft, createChannel: bus.create });
    watchSupersession({ channelName: "session", self: view("b", 2000), onSuperseded: vi.fn(), createChannel: bus.create });
    const intruder = bus.create("draft");
    intruder.postMessage({ type: "announce", instanceId: "x", shownAt: "later", requestId: "r" });
    bus.flush();
    expect(draft).not.toHaveBeenCalled();
  });

  it("skips gracefully without BroadcastChannel", () => {
    const stop = watchSupersession({ channelName: "target", self: view("a", 1000), onSuperseded: vi.fn(), createChannel: null });
    expect(stop).not.toThrow();
    const failing = watchSupersession({ channelName: "target", self: view("a", 1000), onSuperseded: vi.fn(), createChannel: () => { throw new Error("blocked"); } });
    expect(failing).not.toThrow();
  });

  it("stops listening once closed", () => {
    const bus = channelBus();
    const older = vi.fn();
    const stop = watchSupersession({ channelName: "target", self: view("a", 1000), onSuperseded: older, createChannel: bus.create });
    stop();
    watchSupersession({ channelName: "target", self: view("b", 2000), onSuperseded: vi.fn(), createChannel: bus.create });
    bus.flush();
    expect(older).not.toHaveBeenCalled();
  });
});
