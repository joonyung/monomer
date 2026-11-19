import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useAccessibleWorkspacesQuery } from "@/components/auth/TenantRouteContext";
import { Button } from "@/components/ui/Button";
import { EntryFrame, EntryHeading, EntryStatus } from "@/components/auth/EntryFrame";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { useAuthStore } from "@/lib/auth/store";
import { startGoogleSignIn } from "@/lib/auth/google-sign-in";
import {
  AGENT_CONSENT_FAILURE_MESSAGES,
  AgentConsentError,
  consentWorkspaceScope,
  existingConnectionNote,
  fetchAgentConsent,
  releaseAgentConsent,
  submitAgentConsent,
  type AgentConsent as AgentConsentRequest,
} from "@/lib/auth/agent-oauth";

export default function AgentConsentPage({ request }: { request: string }) {
  const epoch = useAuthStore((state) => state.authEpoch);
  return <AgentConsent key={epoch} request={request} epoch={epoch} />;
}

function AgentConsent({ request, epoch }: { request: string; epoch: number }) {
  const status = useAuthStore((state) => state.status);
  const user = useAuthStore((state) => state.user);
  const workspaces = useAccessibleWorkspacesQuery();
  const [expired, setExpired] = useState(false);
  const consent = useQuery({
    queryKey: ["auth", epoch, "agent-consent", request],
    queryFn: ({ signal }) => fetchAgentConsent(request, signal),
    enabled: status === "authenticated" && !!request, retry: false,
    staleTime: Infinity, gcTime: 0, refetchOnWindowFocus: false,
  });
  const data = status === "authenticated" ? consent.data : undefined;
  const decision = useMutation({
    mutationFn: (approve: boolean) => submitAgentConsent(consent.data!, approve),
    onSuccess: (redirectUri) => { if (useAuthStore.getState().authEpoch === epoch) window.location.assign(redirectUri); },
  });
  const switchAccount = useMutation({
    mutationFn: (current: AgentConsentRequest) => releaseAgentConsent(current),
    onSuccess: () => startGoogleSignIn({ redirect: `/oauth/consent?request=${encodeURIComponent(request)}`, chooseAccount: true, invitationContinuation: null }),
  });
  useEffect(() => {
    if (!consent.data) return;
    const timer = setTimeout(() => setExpired(true), Math.max(0, Date.parse(consent.data.expiresAt) - Date.now()));
    return () => clearTimeout(timer);
  }, [consent.data]);

  if (!request || expired || consent.isError) {
    const message = !request || expired
      ? AGENT_CONSENT_FAILURE_MESSAGES.expired
      : consent.error instanceof AgentConsentError ? consent.error.message : AGENT_CONSENT_FAILURE_MESSAGES.failed;
    return (
      <EntryFrame>
        <EntryStatus kind="error" title="This request can't continue" description={message} />
      </EntryFrame>
    );
  }
  if (!data || workspaces.isPending) {
    return (
      <EntryFrame>
        <EntryStatus kind="loading" title="Checking the request…" description="Confirming your sign-in and the app's request." />
      </EntryFrame>
    );
  }
  const deciding = decision.isPending || decision.isSuccess;
  const busy = decision.isPending || decision.isSuccess || decision.isError || switchAccount.isPending || switchAccount.isSuccess;
  const note = existingConnectionNote(data.clientName, data.existingConnectionCount, data.latestConnectionIssuedAt);
  return (
    <EntryFrame>
      <EntryHeading>Connect {data.clientName} to Monomer</EntryHeading>
      <p className="type-body mt-3 text-foreground-secondary">
        Signed in as <span className="break-all text-foreground">{user?.email}</span> ·{" "}
        <button
          type="button"
          className="text-foreground underline underline-offset-4 hover:text-heading disabled:cursor-progress"
          disabled={busy}
          aria-busy={switchAccount.isPending || undefined}
          onClick={() => switchAccount.mutate(data)}
        >
          Not you? Switch account
        </button>
      </p>
      {switchAccount.isError ? <p role="alert" className="type-body mt-3 text-error">{AGENT_CONSENT_FAILURE_MESSAGES.expired}</p> : null}
      <dl className="type-body mt-6 divide-y divide-border-light border-y border-border">
        <div className="py-3">
          <dt className="type-meta text-foreground-secondary">App</dt>
          <dd className="type-item mt-1 break-words">{data.clientName}</dd>
          <dd className="type-meta mt-1 break-all text-foreground-secondary">{data.clientOrigin ?? data.clientId}</dd>
          {data.localClient && <dd className="type-meta mt-2 text-foreground-secondary">Returns to an app on this computer</dd>}
        </div>
      </dl>
      {note ? <p className="type-body mt-4 text-foreground-secondary" data-testid="consent-existing-connection">{note}</p> : null}
      <section className="mt-6" aria-labelledby="consent-capabilities-title">
        <h2 id="consent-capabilities-title" className="type-item break-words text-heading">{data.clientName} will act as you, with your roles:</h2>
        <ul className="type-body mt-3 list-disc space-y-2 pl-5">
          <li>Ask questions and read results {consentWorkspaceScope(workspaces.data ? workspaces.data.length : null)}</li>
          <li>Create, edit, and delete what your role allows — dashboards, pages, metrics and models, data connections, and members and invitations if you manage them</li>
          <li>
            Stay connected for 30 days unless you revoke it in{" "}
            <Link href={webPaths.userAgents} className="text-foreground underline underline-offset-4 hover:text-heading">Agents</Link>
          </li>
        </ul>
      </section>
      <details className="mt-6 border-t border-border pt-4">
        <summary className="type-meta cursor-pointer text-foreground-secondary">Technical details</summary>
        <dl className="type-meta mt-3 space-y-3">
          <div><dt className="text-foreground-secondary">Resource</dt><dd className="mt-1 break-all">{data.resource}</dd></div>
          <div><dt className="text-foreground-secondary">Token lifetime</dt><dd className="mt-1">Access tokens last 15 minutes and the app refreshes them automatically. Authorization expires 30 days after approval and then needs your consent again.</dd></div>
        </dl>
      </details>
      {decision.isError && <p role="alert" className="type-body mt-5 text-error">Couldn&apos;t process your decision. Don&apos;t submit it again; start again from the app.</p>}
      <div className="mt-8 flex flex-wrap justify-end gap-3">
        <Button variant="secondary" pending={deciding && decision.variables === false} disabled={busy} onClick={() => decision.mutate(false)}>Cancel</Button>
        <Button pending={deciding && decision.variables === true} disabled={busy} onClick={() => decision.mutate(true)}>
          Connect {data.clientName}
        </Button>
      </div>
    </EntryFrame>
  );
}
