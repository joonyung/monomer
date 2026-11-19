import { Fragment, useRef, useState, type KeyboardEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, ExternalLink, TriangleAlert, Unplug } from "lucide-react";
import { CopyButton } from "@/components/settings/SettingsPrimitives";
import { SettingsDialog } from "@/components/settings/SettingsDialog";
import { Button } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import { AGENT_MCP_URL, CURSOR_CONFIGURATION, CURSOR_INSTALL_URL, agentClientGuides, verifiedClientNames, type AgentClientGuide } from "@/lib/agent-clients";
import { agentResourceLabel, revokeAgentConnection, type AgentConnection } from "@/lib/auth/agent-oauth";
import { useAuthStore } from "@/lib/auth/store";
import { publicDocsUrls } from "@/lib/public-docs";
import { UserSettingsShell } from "./shell";
import { agentConnectionsKey, useAgentConnections } from "./use-agent-connections";
import {
  clientIdentityLabel,
  connectionAppName,
  endedConnectionLabel,
  expiresSoon,
  expiryLabel,
  groupConnections,
  lastUsedLabel,
  lastUsedPhrase,
  partitionConnections,
  revokeKeepsNote,
  settingsDateLabel,
  type ConnectionGroup,
} from "./userSettingsModel";

export default function AgentsPage() {
  return (
    <UserSettingsShell section="agents" title="Agents">
      <Agents />
    </UserSettingsShell>
  );
}

function Agents() {
  return (
    <div>
      <p className="max-w-[640px] type-body text-foreground-secondary">
        Agents you connect act as you, with your roles, in every Workspace you’re a member of.
      </p>
      <ConnectAgent />
      <YourConnections />
    </div>
  );
}

function ExternalDocLink({ href, children }: { href: string; children: string }) {
  return (
    <a href={href} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 type-control text-foreground underline underline-offset-4 hover:text-heading">
      {children}
      <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
      <span className="sr-only"> (opens in a new tab)</span>
    </a>
  );
}

function ConnectAgent() {
  const [selectedId, setSelectedId] = useState(agentClientGuides[0].id);
  const tabs = useRef(new Map<string, HTMLButtonElement>());
  const selected = agentClientGuides.find((client) => client.id === selectedId) ?? agentClientGuides[0];
  const selectAt = (index: number) => {
    const client = agentClientGuides[(index + agentClientGuides.length) % agentClientGuides.length];
    setSelectedId(client.id);
    tabs.current.get(client.id)?.focus();
  };
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const target = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: agentClientGuides.length - 1 }[event.key];
    if (target === undefined) return;
    event.preventDefault();
    selectAt(target);
  };
  return (
    <section aria-labelledby="connect-agent-title" className="mt-8 rounded-[6px] border border-border p-4 md:p-5" data-testid="connect-agent">
      <h2 id="connect-agent-title" className="type-section text-heading">Connect an agent</h2>
      <p className="mt-1 type-meta text-foreground-secondary">Give your MCP client this server address, then sign in to Monomer when it asks.</p>
      <div className="mt-3 flex min-w-0 items-center gap-2 rounded-[4px] border border-border bg-surface py-1.5 pl-3 pr-1.5">
        <code className="min-w-0 flex-1 break-all type-mono text-foreground" data-testid="agent-mcp-url">{AGENT_MCP_URL}</code>
        <CopyButton value={AGENT_MCP_URL} label="Copy MCP server URL" copiedAnnouncement="MCP server URL copied" failedAnnouncement="Couldn't copy. Select the URL and copy it." />
      </div>
      <div className="mt-5 flex flex-wrap border-b border-border" role="tablist" aria-label="Agent clients">
        {agentClientGuides.map((client, index) => {
          const isSelected = client.id === selected.id;
          return (
            <button
              key={client.id}
              ref={(node) => {
                if (node) tabs.current.set(client.id, node);
                else tabs.current.delete(client.id);
              }}
              id={`agent-client-${client.id}-tab`}
              type="button"
              role="tab"
              aria-selected={isSelected}
              aria-controls="agent-client-panel"
              tabIndex={isSelected ? 0 : -1}
              onClick={() => setSelectedId(client.id)}
              onKeyDown={(event) => onKeyDown(event, index)}
              className={`-mb-px border-b-2 px-3 py-2 type-control focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus-ring ${isSelected ? "border-foreground text-foreground" : "border-transparent text-foreground-secondary hover:text-foreground"}`}
            >
              {client.name}
            </button>
          );
        })}
      </div>
      <ClientPanel client={selected} />
    </section>
  );
}

function ClientPanel({ client }: { client: AgentClientGuide }) {
  return (
    <div id="agent-client-panel" role="tabpanel" aria-labelledby={`agent-client-${client.id}-tab`} className="pt-4">
      {client.verified === false ? (
        <p className="mb-3 type-meta text-foreground-secondary" data-testid="agent-client-unverified">Not yet verified with Monomer sign-in.</p>
      ) : null}
      <ol className="space-y-2.5">
        {client.steps.map((step, index) => (
          <li key={step.text} className="flex gap-3 type-body text-foreground">
            <span className="flex h-5 w-5 flex-none items-center justify-center rounded-full border border-border type-status tabular-nums text-foreground-secondary" aria-hidden="true">{index + 1}</span>
            <div className="min-w-0 flex-1">
              {step.text}
              {step.command ? <div className="mt-1.5 flex min-w-0 items-start gap-2 rounded-[4px] bg-surface py-1.5 pl-2.5 pr-1.5">
                <code className="min-w-0 flex-1 break-all py-1 type-mono text-foreground">{step.command}</code>
                <CopyButton value={step.command} label="Copy command" copiedAnnouncement="Command copied" failedAnnouncement="Couldn't copy. Select the command and copy it." />
              </div> : null}
              {client.id === "cursor" && index === 0 ? <div className="mt-1.5 flex min-w-0 items-start gap-2 rounded-[4px] bg-surface py-1.5 pl-2.5 pr-1.5">
                <pre className="min-w-0 flex-1 whitespace-pre-wrap break-all py-1 type-mono text-foreground"><code>{CURSOR_CONFIGURATION}</code></pre>
                <CopyButton value={CURSOR_CONFIGURATION} label="Copy configuration" copiedAnnouncement="Configuration copied" failedAnnouncement="Couldn't copy. Select the configuration and copy it." />
              </div> : null}
            </div>
          </li>
        ))}
      </ol>
      <p className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
        {client.id === "cursor" ? <a href={CURSOR_INSTALL_URL} className="type-control text-foreground underline underline-offset-4 hover:text-heading">Add to Cursor</a> : null}
        <ExternalDocLink href={client.guideUrl}>{client.verified === null ? "Full connection guide" : `Full guide for ${client.name}`}</ExternalDocLink>
        <span className="type-meta text-foreground-secondary">
          Using the REST API instead?{" "}
          <a href={publicDocsUrls.connectRestClient} target="_blank" rel="noreferrer" className="text-foreground underline underline-offset-4 hover:text-heading">
            REST setup<span className="sr-only"> (opens in a new tab)</span>
          </a>
        </span>
      </p>
    </div>
  );
}

interface RevokeTarget {
  group: ConnectionGroup;
  connections: AgentConnection[];
}

function YourConnections() {
  const status = useAuthStore((state) => state.status);
  const epoch = useAuthStore((state) => state.authEpoch);
  const queryClient = useQueryClient();
  const connections = useAgentConnections();
  const [revoking, setRevoking] = useState<RevokeTarget | null>(null);
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const [showEnded, setShowEnded] = useState(false);
  const [notice, setNotice] = useState("");
  const [now] = useState(Date.now);
  const revoke = useMutation({
    mutationFn: async (ids: string[]) => {
      for (const id of ids) await revokeAgentConnection(id);
    },
    // The dialog closes after the list refreshes, so its opener row is gone and focus moves to the page heading.
    onSuccess: async (_, ids) => {
      if (useAuthStore.getState().authEpoch !== epoch) return;
      setNotice(ids.length === 1 ? "Access revoked." : `${ids.length} connections revoked.`);
      await queryClient.invalidateQueries({ queryKey: agentConnectionsKey(epoch) });
      if (useAuthStore.getState().authEpoch === epoch) setRevoking(null);
    },
    // Part of a group may already be revoked when a later revocation fails.
    onError: async () => {
      if (useAuthStore.getState().authEpoch === epoch) await queryClient.invalidateQueries({ queryKey: agentConnectionsKey(epoch) });
    },
  });
  const items = status === "authenticated" ? connections.data : undefined;
  const { active, ended } = partitionConnections(items ?? [], now);
  const groups = groupConnections(active);
  const startRevoke = (target: RevokeTarget) => { revoke.reset(); setNotice(""); setRevoking(target); };
  const toggle = (key: string) => setExpanded((current) => {
    const next = new Set(current);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    return next;
  });
  return (
    <section aria-labelledby="your-connections-title" className="mt-10">
      <h2 id="your-connections-title" className="type-section text-heading">Your connections</h2>
      {notice && <p role="status" className="mt-3 type-body text-success-text">{notice}</p>}
      {connections.isError ? (
        <div className="mt-4 border-y border-border">
          <StateBlock kind="error" layout="inline" title="Couldn't load your connections." action={<Button size="sm" variant="secondary" onClick={() => void connections.refetch()}>Try again</Button>} />
        </div>
      ) : !items ? (
        <div className="mt-4 border-y border-border"><StateBlock kind="loading" layout="inline" title="Loading your connections…" /></div>
      ) : (
        <>
          {groups.length === 0 ? (
            <div className="mt-4 border-y border-border">
              <StateBlock
                kind="empty"
                layout="inline"
                icon={Unplug}
                title={items.length === 0 ? "No agents connected yet" : "No active connections"}
                description={`Connect ${verifiedClientNames()} with the steps above to work with your Monomer data.`}
              />
            </div>
          ) : (
            <table className="mt-4 w-full table-fixed text-left" aria-label="Active connections">
              <colgroup className="hidden md:table-column-group">
                <col />
                <col className="w-[14%]" />
                <col className="w-[18%]" />
                <col className="w-[18%]" />
                <col className="w-[104px]" />
              </colgroup>
              <thead className="hidden border-b border-border md:table-header-group">
                <tr>
                  <th scope="col" className="py-2.5 pl-2 pr-3 type-group text-label">App</th>
                  <th scope="col" className="px-3 py-2.5 type-group text-label">Connects to</th>
                  <th scope="col" className="px-3 py-2.5 type-group text-label">Last used</th>
                  <th scope="col" className="px-3 py-2.5 type-group text-label">Expires</th>
                  <th scope="col" className="py-2.5"><span className="sr-only">Revoke</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-light border-y border-border md:border-t-0">
                {groups.map((group) => (
                  <ConnectionGroupRows
                    key={group.key}
                    group={group}
                    now={now}
                    expanded={expanded.has(group.key)}
                    onToggle={() => toggle(group.key)}
                    onRevoke={startRevoke}
                  />
                ))}
              </tbody>
            </table>
          )}
          {ended.length > 0 ? (
            <div className="mt-6">
              <button
                type="button"
                aria-expanded={showEnded}
                aria-controls="ended-connections"
                onClick={() => setShowEnded((current) => !current)}
                className="inline-flex items-center gap-1.5 type-control text-foreground-secondary underline-offset-4 hover:text-foreground focus-visible:underline"
              >
                {showEnded ? <ChevronDown className="h-4 w-4" aria-hidden="true" /> : <ChevronRight className="h-4 w-4" aria-hidden="true" />}
                {showEnded ? "Hide revoked and expired" : `Show revoked and expired (${ended.length})`}
              </button>
              {showEnded ? (
                <ul id="ended-connections" aria-label="Revoked and expired connections" className="mt-3 divide-y divide-border-light border-y border-border">
                  {ended.map((connection) => (
                    <li key={connection.id} className="grid gap-x-4 gap-y-1 py-3 pl-2 pr-2 md:grid-cols-[minmax(0,1fr)_14%_18%_minmax(0,1fr)]">
                      <AppIdentity name={connectionAppName(connection)} clientId={connection.clientId} />
                      <span className="break-all type-body text-foreground-secondary">{agentResourceLabel(connection.resource)}</span>
                      <span className="type-body tabular-nums text-foreground-secondary">
                        Connected <time dateTime={connection.issuedAt}>{settingsDateLabel(connection.issuedAt)}</time>
                      </span>
                      <span className="type-body tabular-nums text-foreground-secondary">{endedConnectionLabel(connection)}</span>
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
          ) : null}
          {items.length > 0 ? (
            <p className="mt-4 type-meta text-foreground-secondary">App names come from client metadata and may not identify the software conclusively.</p>
          ) : null}
        </>
      )}
      {revoking && <RevokeDialog target={revoking} pending={revoke.isPending} failed={revoke.isError} onCancel={() => setRevoking(null)} onConfirm={() => revoke.mutate(revoking.connections.map((connection) => connection.id))} />}
    </section>
  );
}

function ExpiryCell({ expiresAt, now }: { expiresAt: string; now: number }) {
  if (!expiresSoon(expiresAt, now)) return <time dateTime={expiresAt}>{expiryLabel(expiresAt, now)}</time>;
  return (
    <span className="block">
      <span className="inline-flex items-center gap-1 text-warning">
        <TriangleAlert className="h-3.5 w-3.5 flex-none" aria-hidden="true" />
        <time dateTime={expiresAt}>{expiryLabel(expiresAt, now)}</time>
      </span>
      <span className="block type-meta text-foreground-secondary">Reconnect from the app to keep access</span>
    </span>
  );
}

function ConnectionGroupRows({ group, now, expanded, onToggle, onRevoke }: {
  group: ConnectionGroup;
  now: number;
  expanded: boolean;
  onToggle: () => void;
  onRevoke: (target: RevokeTarget) => void;
}) {
  const count = group.connections.length;
  const target = agentResourceLabel(group.resource);
  const detailsId = `connection-group-${group.connections[0].id}`;
  return (
    <Fragment>
      <tr className="text-foreground" data-testid="connection-group">
        <td className="py-3 pl-2 pr-3 align-top md:align-middle">
          <AppIdentity name={group.appName} clientId={group.clientId} />
          {count > 1 ? (
            <button
              type="button"
              aria-expanded={expanded}
              aria-controls={detailsId}
              onClick={onToggle}
              className="mt-1 inline-flex items-center gap-1 type-meta text-foreground-secondary underline-offset-4 hover:text-foreground focus-visible:underline"
            >
              {expanded ? <ChevronDown className="h-3.5 w-3.5" aria-hidden="true" /> : <ChevronRight className="h-3.5 w-3.5" aria-hidden="true" />}
              {count} connections
            </button>
          ) : null}
          <div className="mt-1 space-y-0.5 type-meta tabular-nums text-foreground-secondary md:hidden">
            <p>{target} · {lastUsedPhrase(group.lastUsedAt, now)}</p>
            <div className="flex gap-1">Expires <ExpiryCell expiresAt={group.expiresAt} now={now} /></div>
          </div>
        </td>
        <td className="hidden break-all px-3 py-3 type-body md:table-cell">{target}</td>
        <td className="hidden px-3 py-3 type-body tabular-nums md:table-cell">
          {group.lastUsedAt ? <time dateTime={group.lastUsedAt}>{lastUsedLabel(group.lastUsedAt, now)}</time> : lastUsedLabel(null, now)}
        </td>
        <td className="hidden px-3 py-3 type-body tabular-nums md:table-cell"><ExpiryCell expiresAt={group.expiresAt} now={now} /></td>
        <td className="w-[104px] py-3 pr-2 text-right align-top md:align-middle">
          <Button
            variant="secondary"
            size="sm"
            aria-label={count > 1 ? `Revoke all ${count} ${group.appName} connections` : `Revoke ${group.appName}`}
            onClick={() => onRevoke({ group, connections: group.connections })}
          >
            Revoke
          </Button>
        </td>
      </tr>
      {count > 1 && expanded ? group.connections.map((connection, index) => (
        <tr key={connection.id} id={index === 0 ? detailsId : undefined} className="bg-surface/50 text-foreground" data-testid="connection-detail">
          <td className="py-2.5 pl-6 pr-3 align-top md:align-middle">
            <p className="type-body tabular-nums">Connected <time dateTime={connection.issuedAt}>{settingsDateLabel(connection.issuedAt)}</time></p>
            <p className="mt-0.5 type-meta tabular-nums text-foreground-secondary md:hidden">
              {lastUsedPhrase(connection.lastUsedAt, now)} · Expires {expiryLabel(connection.expiresAt, now)}
            </p>
          </td>
          <td className="hidden px-3 py-2.5 type-body md:table-cell" />
          <td className="hidden px-3 py-2.5 type-body tabular-nums md:table-cell">{lastUsedLabel(connection.lastUsedAt, now)}</td>
          <td className="hidden px-3 py-2.5 type-body tabular-nums md:table-cell"><ExpiryCell expiresAt={connection.expiresAt} now={now} /></td>
          <td className="w-[104px] py-2.5 pr-2 text-right align-top md:align-middle">
            <Button
              variant="ghost"
              size="sm"
              aria-label={`Revoke ${group.appName} connection from ${settingsDateLabel(connection.issuedAt)}`}
              onClick={() => onRevoke({ group, connections: [connection] })}
            >
              Revoke
            </Button>
          </td>
        </tr>
      )) : null}
    </Fragment>
  );
}

function RevokeDialog({ target, pending, failed, onCancel, onConfirm }: {
  target: RevokeTarget;
  pending: boolean;
  failed: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const count = target.connections.length;
  const app = target.group.appName;
  return (
    <SettingsDialog
      title={count === 1 ? `Revoke access for ${app}?` : `Revoke ${count} connections for ${app}?`}
      size="sm"
      role="alertdialog"
      onClose={onCancel}
      busy={pending}
      footer={
        <>
          <Button variant="secondary" disabled={pending} onClick={onCancel}>Cancel</Button>
          <Button variant="danger" pending={pending} onClick={onConfirm}>
            {pending ? "Revoking…" : "Confirm revocation"}
          </Button>
        </>
      }
    >
      <div className="type-body space-y-4">
        <p>
          {count === 1
            ? "This revokes this connection and invalidates its access and refresh tokens."
            : `This revokes all ${count} connections and invalidates their access and refresh tokens.`}
        </p>
        <p className="text-foreground-secondary">{revokeKeepsNote(target.group.resourceKind)}</p>
        {failed && <p role="alert" className="text-error">Couldn&apos;t revoke access. Try again.</p>}
      </div>
    </SettingsDialog>
  );
}

function AppIdentity({ name, clientId }: { name: string; clientId: string }) {
  return (
    <div className="min-w-0">
      <p className="break-words type-body text-heading">{name}</p>
      {name !== clientId ? <p className="break-all type-meta text-foreground-secondary" title={clientId}>{clientIdentityLabel(clientId)}</p> : null}
    </div>
  );
}
