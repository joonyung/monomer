import { z } from "zod";

export interface ViewOrder {
  readonly instanceId: string;
  readonly shownAt: number;
  readonly requestId: string;
}

const Announcement = z.strictObject({
  type: z.literal("announce"),
  instanceId: z.string().min(1).max(200),
  shownAt: z.number().int().min(0),
  requestId: z.string().min(1).max(200),
});

export function supersessionChannelName(workspaceId: string, targetPath: string): string {
  return `monomer-dashboard-view:${workspaceId}:${targetPath}`;
}

// The later monomer_show wins; equal times fall back to the larger requestId. Two mounts of the same
// result fall back to the instance id so exactly one of them stays live.
export function isNewerView(candidate: ViewOrder, current: ViewOrder): boolean {
  if (candidate.shownAt !== current.shownAt) return candidate.shownAt > current.shownAt;
  if (candidate.requestId !== current.requestId) return candidate.requestId > current.requestId;
  return candidate.instanceId > current.instanceId;
}

export interface SupersessionChannel {
  postMessage(message: unknown): void;
  close(): void;
  onmessage: ((event: MessageEvent) => void) | null;
}

export interface SupersessionOptions {
  channelName: string;
  self: ViewOrder;
  onSuperseded(): void;
  createChannel: ((name: string) => SupersessionChannel) | null;
}

// Every mounted view of one target announces itself. An older view that hears a newer one stops;
// a newer view answers an older announcement so views mounted out of order still settle.
export function watchSupersession(options: SupersessionOptions): () => void {
  if (!options.createChannel) return () => undefined;
  let channel: SupersessionChannel;
  try {
    channel = options.createChannel(options.channelName);
  } catch {
    return () => undefined;
  }
  let superseded = false;
  const announce = () => channel.postMessage({ type: "announce", ...options.self });
  channel.onmessage = (event) => {
    const message = Announcement.safeParse(event.data);
    if (!message.success || message.data.instanceId === options.self.instanceId) return;
    if (isNewerView(message.data, options.self)) {
      if (!superseded) {
        superseded = true;
        options.onSuperseded();
      }
    } else if (!superseded) {
      announce();
    }
  };
  announce();
  return () => {
    channel.onmessage = null;
    channel.close();
  };
}

export function browserSupersessionChannel(): ((name: string) => SupersessionChannel) | null {
  return typeof BroadcastChannel === "function" ? (name) => new BroadcastChannel(name) : null;
}
