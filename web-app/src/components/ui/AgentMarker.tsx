import { useState, type ReactNode } from "react";
import { SquareTerminal } from "lucide-react";
import type { AgentApp } from "@/lib/types/provenance";
import { AgentAppLogoMark } from "./AgentAppLogo";
import { agentAppIdentity } from "./agentAppIdentity";

const MARKER_CLASS = "type-meta inline-flex items-center gap-1 text-foreground-secondary";
const TOOLTIP_CLASS = "pointer-events-none absolute bottom-full left-0 z-40 mb-1 whitespace-nowrap rounded-[6px] bg-accent px-2 py-1 type-meta "
  + "text-accent-foreground shadow-ambient transition-opacity duration-150 motion-reduce:transition-none";

/**
 * Marks agent work in text, after the person's name where no avatar is shown: "via Claude" with the Claude logo for
 * a known app, "via agents.acme.dev" for another verified app, "via Monomer CLI" for a client registered in Control,
 * and "via agent" for older work without an app. The logo is chosen by the verified host, never by the app's name.
 * A verified app's accessible name and tooltip carry its name and host, visible words first: "via Claude (claude.ai)", "via agents.acme.dev (Acme Agent)".
 *
 * [focusable] makes the marker its own button so keyboard focus and a tap reveal the tooltip. Inside a link or
 * button it must stay false; the full label then reaches assistive technology through the parent's name and the
 * tooltip shows on hover.
 */
export function AgentMarker({ app, focusable }: { app: AgentApp | null; focusable: boolean }) {
  const [open, setOpen] = useState(false);
  const identity = agentAppIdentity(app);
  const visible = (
    <>
      {identity.logo ? <AgentAppLogoMark logo={identity.logo} className="h-3.5 w-3.5" /> : null}
      <span>via {identity.shortName}</span>
    </>
  );
  if (!app?.verifiedHost) {
    return <span className={MARKER_CLASS} data-agent-client-id={app?.clientId}>{visible}</span>;
  }
  const tooltip = (
    <span aria-hidden="true" className={`${TOOLTIP_CLASS} ${open ? "opacity-100" : "opacity-0"} group-hover/agent:opacity-100 group-focus-visible/agent:opacity-100`}>
      {identity.label}
    </span>
  );
  if (focusable) {
    return (
      <button
        type="button"
        className={`group/agent relative z-10 rounded-[4px] ${MARKER_CLASS} focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring`}
        aria-label={identity.label}
        data-agent-client-id={app.clientId}
        onClick={() => setOpen((shown) => !shown)}
        onBlur={() => setOpen(false)}
        onKeyDown={(event) => { if (event.key === "Escape") setOpen(false); }}
      >
        {visible}{tooltip}
      </button>
    );
  }
  return (
    <span className={`group/agent relative ${MARKER_CLASS}`} data-agent-client-id={app.clientId}>
      <span aria-hidden="true" className="inline-flex items-center gap-1">{visible}</span><span className="sr-only">{` ${identity.label}`}</span>{tooltip}
    </span>
  );
}

/**
 * A person's avatar with the agent badge on its corner: a known app's logo, chosen by the verified host, or a neutral
 * terminal mark for every other app. The badge sits outside the corner on a background ring so the initials stay
 * legible, and hovering it shows the full label. Pair it with AgentAvatarCaption under the person's name; that
 * caption carries the label to assistive technology.
 */
export function AgentAvatar({ app, children }: { app: AgentApp | null; children: ReactNode }) {
  const identity = agentAppIdentity(app);
  return (
    <span className="group/agent relative inline-flex shrink-0" data-agent-client-id={app?.clientId}>
      {children}
      <span
        aria-hidden="true"
        data-agent-badge={identity.logo ?? "neutral"}
        className="absolute -bottom-1.5 -right-[7px] flex h-4 w-4 items-center justify-center rounded-full bg-background text-label ring-1 ring-border"
      >
        {identity.logo ? <AgentAppLogoMark logo={identity.logo} className="h-2.5 w-2.5" /> : <SquareTerminal className="h-2.5 w-2.5" strokeWidth={2.25} />}
      </span>
      <span aria-hidden="true" className={`${TOOLTIP_CLASS} opacity-0 group-hover/agent:opacity-100`}>{identity.label}</span>
    </span>
  );
}

/**
 * The line under the name that goes with AgentAvatar: nothing visible for a known app, whose logo says it, and
 * otherwise the verified host, the registered client's name, or "agent". It always gives the full label to
 * assistive technology.
 */
export function AgentAvatarCaption({ app, className }: { app: AgentApp | null; className: string }) {
  const identity = agentAppIdentity(app);
  return (
    <>
      {identity.logo ? null : <span aria-hidden="true" className={className}>{identity.shortName}</span>}
      <span className="sr-only">{` ${identity.label}`}</span>
    </>
  );
}
