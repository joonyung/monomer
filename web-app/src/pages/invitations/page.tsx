import { useEffect, useRef, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button, buttonClassName } from "@/components/ui/Button";
import { EntryEyebrow, EntryFrame, EntryHeading, EntryStatus } from "@/components/auth/EntryFrame";
import { useAuthStore } from "@/lib/auth/store";
import { fetchMe } from "@/lib/auth/graphql";
import { roleLabel } from "@/lib/auth/roles";
import { signInAgainPath } from "@/lib/auth/login-context";
import { webPaths } from "@/lib/web-paths";
import { Link } from "@/lib/navigation";
import {
  INVITATION_CONTINUATION_KEY,
  invitationExpiresIn,
  invitationRequest,
  startInvitationLogin,
  workspaceAccessHelper,
  type InvitationAccessResponse,
  type InvitationResponse,
} from "@/lib/auth/invitations";

type Action = "accept" | "decline";

export default function InvitationPage() {
  const status = useAuthStore((state) => state.status);
  const user = useAuthStore((state) => state.user);
  const queryClient = useQueryClient();
  const exchange = useRef<Promise<InvitationResponse> | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const [continuation, setContinuation] = useState<string | null>(null);
  const [result, setResult] = useState<InvitationResponse | null>(null);
  const [details, setDetails] = useState<InvitationResponse | null>(null);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pending, setPending] = useState<Action | null>(null);
  const [announcement, setAnnouncement] = useState("");
  useEffect(() => {
    let cancelled = false;
    const begin = () => {
      const rawToken = new URLSearchParams(window.location.hash.slice(1)).get(
        "token",
      );
      if (rawToken) {
        window.history.replaceState(
          window.history.state,
          "",
          window.location.pathname,
        );
        sessionStorage.removeItem(INVITATION_CONTINUATION_KEY);
        setContinuation(null);
        setResult(null);
        setDetails(null);
        setCheckError(null);
        setActionError(null);
        exchange.current = invitationRequest("exchange", rawToken);
      } else if (!exchange.current) {
        const saved = sessionStorage.getItem(INVITATION_CONTINUATION_KEY);
        exchange.current = Promise.resolve(
          saved
            ? { outcome: "AVAILABLE", continuationId: saved }
            : { outcome: "UNAVAILABLE" },
        );
      }
      const current = exchange.current;
      void current
        ?.then((value) => {
          if (cancelled || exchange.current !== current) return;
          if (value.continuationId) {
            sessionStorage.setItem(
              INVITATION_CONTINUATION_KEY,
              value.continuationId,
            );
            setContinuation(value.continuationId);
          } else setResult(value);
        })
        .catch(() => {
          if (!cancelled && exchange.current === current)
            setCheckError("The invitation couldn't be checked. Open the invitation link from your email again.");
        });
    };
    begin();
    const onHashChange = () => {
      if (window.location.hash) begin();
    };
    window.addEventListener("hashchange", onHashChange);
    return () => {
      cancelled = true;
      window.removeEventListener("hashchange", onHashChange);
    };
  }, []);
  useEffect(() => {
    if (!continuation || status === "idle" || status === "hydrating") return;
    let cancelled = false;
    void invitationRequest("resolve", continuation)
      .then((value) => {
        if (!cancelled) {
          setResult(value);
          if (value.outcome !== "EMAIL_MISMATCH") setDetails(value);
          else setDetails((previous) => previous ?? value);
        }
      })
      .catch(() => {
        if (!cancelled) setCheckError("The invitation couldn't be checked. Open the invitation link from your email again.");
      });
    return () => {
      cancelled = true;
    };
  }, [continuation, status]);
  const act = async (action: Action) => {
    if (!continuation || pending) return;
    setPending(action);
    setActionError(null);
    try {
      const value = await invitationRequest(action, continuation);
      setResult(value);
      if (value.outcome === "ACCEPTED") setDetails((previous) => ({ ...previous, ...value }));
      if (
        ["ACCEPTED", "ALREADY_MEMBER", "DECLINED", "UNAVAILABLE"].includes(value.outcome)
      )
        sessionStorage.removeItem(INVITATION_CONTINUATION_KEY);
      if (value.outcome === "ACCEPTED" || value.outcome === "ALREADY_MEMBER") {
        try {
          const me = await fetchMe(undefined, false);
          useAuthStore
            .getState()
            .setMe({ user: me.user, organizationMemberships: me.organizationMemberships });
          await queryClient.invalidateQueries({ queryKey: ["auth"] });
        } catch {
          // The membership is already saved; the next session read picks it up, so the joined outcome stands.
        }
      }
    } catch {
      setActionError("Your choice couldn't be saved. Try again.");
    } finally {
      setPending(null);
    }
  };

  const outcome = checkError ? "ERROR" : result?.outcome ?? "CHECKING";
  const organization = details?.organizationDisplayName ?? "the Organization";
  const view = invitationView({
    outcome, result, details, organization, checkError, actionError, userEmail: user?.email ?? null,
    signedIn: status === "authenticated", pending, continuation, act,
  });

  // Move focus to the new state's heading and announce it so the change is not silent.
  const previousTitle = useRef<string | null>(null);
  useEffect(() => {
    if (previousTitle.current === view.title) return;
    const first = previousTitle.current === null;
    previousTitle.current = view.title;
    if (first) return;
    headingRef.current?.focus();
    setAnnouncement(view.title);
  }, [view.title]);

  return (
    <EntryFrame>
      <p className="sr-only" aria-live="polite">{announcement}</p>
      {view.kind === "loading" || view.kind === "error" ? (
        <EntryStatus kind={view.kind} title={view.title} description={view.description} actions={view.actions} headingRef={headingRef} />
      ) : (
        <>
          <EntryEyebrow>Organization invitation</EntryEyebrow>
          <EntryHeading headingRef={headingRef} className="mt-3">{view.title}</EntryHeading>
          {view.description ? <p className="type-body mt-3 text-foreground-secondary">{view.description}</p> : null}
          {view.body}
          {view.actions ? <div className="mt-8 flex flex-wrap items-center gap-3">{view.actions}</div> : null}
          {view.footnote ? <p className="type-meta mt-4 text-foreground-secondary">{view.footnote}</p> : null}
        </>
      )}
    </EntryFrame>
  );
}

interface InvitationView {
  kind: "loading" | "error" | "info";
  title: string;
  description?: ReactNode;
  body?: ReactNode;
  actions?: ReactNode;
  footnote?: ReactNode;
}

function invitationView({
  outcome, result, details, organization, checkError, actionError, userEmail, signedIn, pending, continuation, act,
}: {
  outcome: string;
  result: InvitationResponse | null;
  details: InvitationResponse | null;
  organization: string;
  checkError: string | null;
  actionError: string | null;
  userEmail: string | null;
  signedIn: boolean;
  pending: Action | null;
  continuation: string | null;
  act: (action: Action) => Promise<void>;
}): InvitationView {
  const openWorkspaces = <Link href={webPaths.workspaces} className={buttonClassName("primary", "md", "")}>Open workspaces</Link>;
  const goHome = <Link href="/" className={buttonClassName("secondary", "md", "")}>Go to Monomer</Link>;
  const invitedBy = details?.invitedByDisplayName;
  switch (outcome) {
    case "CHECKING":
      return { kind: "loading", title: "Checking invitation…" };
    case "ERROR":
      return { kind: "error", title: "Invitation couldn't be checked", description: checkError, actions: goHome };
    case "UNAVAILABLE":
      return {
        kind: "info",
        title: "Invitation unavailable",
        description: "This invitation was used, expired, or revoked. Ask the Organization Admin for a new invitation.",
        actions: goHome,
      };
    case "RATE_LIMITED":
      return {
        kind: "info",
        title: "Please try again later",
        description: `Too many attempts. Try again in ${result?.retryAfter ?? 60} seconds.`,
      };
    case "EMAIL_MISMATCH":
      return {
        kind: "info",
        title: "Use the invited Google account",
        description: (
          <>This invitation is for <span className="break-all text-foreground">{details?.deliveryEmail}</span>.
            {userEmail ? <> You&apos;re signed in as <span className="break-all text-foreground">{userEmail}</span>.</> : null}</>
        ),
        actions: (
          <Button onClick={() => { if (continuation) startInvitationLogin(continuation, true); }}>
            Choose another Google account
          </Button>
        ),
        footnote: "Your invitation stays valid while you switch accounts.",
      };
    case "ALREADY_MEMBER":
      return {
        kind: "info",
        title: `You're already in ${organization}`,
        description: "Your membership is unchanged. Open your workspaces to continue.",
        actions: openWorkspaces,
      };
    case "ACCEPTED": {
      const granted = result?.workspaceAccess ?? [];
      const skipped = result?.skippedWorkspaceAccess ?? [];
      return {
        kind: "info",
        title: `You joined ${organization}`,
        description: granted.length > 0 ? "You now have access to these workspaces." : workspaceAccessHelper(invitedBy),
        body: (
          <>
            {granted.length > 0 ? <AccessList label="Workspace access" access={granted} /> : null}
            {skipped.length > 0 ? (
              <p className="type-body mt-4 text-foreground-secondary">
                Access to {skipped.length === 1 ? "1 workspace" : `${skipped.length} workspaces`} in this invitation couldn&apos;t be
                added because it&apos;s no longer available. {workspaceAccessHelper(invitedBy)}
              </p>
            ) : null}
          </>
        ),
        actions: granted.length === 1
          ? <Link href={webPaths.workspace(granted[0].workspaceId)} className={buttonClassName("primary", "md", "")}>Open {granted[0].workspaceDisplayName ?? "workspace"}</Link>
          : openWorkspaces,
      };
    }
    case "DECLINED":
      return {
        kind: "info",
        title: "Invitation declined",
        description: `You didn't join ${organization}. Ask the Admin for a new invitation if you change your mind.`,
        actions: goHome,
      };
    default: {
      const full = outcome === "MEMBER_LIMIT_REACHED";
      const ready = signedIn && (outcome === "AVAILABLE" || full);
      return {
        kind: "info",
        title: full ? "This Organization is full" : `Join ${organization}`,
        description: full
          ? "Free Organizations can have up to 30 members. Your invitation is still valid. Ask an Admin to free a place, then accept again before it expires."
          : invitedBy ? `${invitedBy} invited you.` : "You're invited to join this Organization.",
        body: (
          <>
            <InvitationDetails details={details} />
            {actionError ? <p role="alert" className="type-body mt-4 text-error">{actionError}</p> : null}
          </>
        ),
        actions: ready ? (
          <>
            <Button pending={pending === "accept"} disabled={pending === "decline"} onClick={() => void act("accept")}>
              Accept invitation
            </Button>
            <Button variant="secondary" pending={pending === "decline"} disabled={pending === "accept"} onClick={() => void act("decline")}>
              Decline
            </Button>
          </>
        ) : signedIn ? (
          <Button onClick={() => { if (continuation) startInvitationLogin(continuation, false); }}>
            Continue with Google
          </Button>
        ) : (
          <Link href={signInAgainPath("/invitations/accept")} className={buttonClassName("primary", "md", "")}>
            Sign in to accept
          </Link>
        ),
        footnote: ready ? "You'll join as a Member. An invitation doesn't reserve a place." : null,
      };
    }
  }
}

function InvitationDetails({ details }: { details: InvitationResponse | null }) {
  const access = details?.workspaceAccess ?? [];
  return (
    <dl className="type-body mt-6 divide-y divide-border-light border-y border-border">
      <div className="py-3">
        <dt className="type-meta text-foreground-secondary">Invited email</dt>
        <dd className="mt-1 break-all">{details?.deliveryEmail}</dd>
      </div>
      <div className="py-3">
        <dt className="type-meta text-foreground-secondary">Workspace access</dt>
        {access.length > 0
          ? access.map((grant) => <dd key={grant.workspaceId} className="mt-1 break-words">{accessLabel(grant)}</dd>)
          : <dd className="mt-1 text-foreground-secondary">None yet. {workspaceAccessHelper(details?.invitedByDisplayName)}</dd>}
      </div>
      {details?.expiresAt ? (
        <div className="py-3">
          <dt className="type-meta text-foreground-secondary">Expires</dt>
          <dd className="mt-1">
            <time dateTime={details.expiresAt} title={new Date(details.expiresAt).toLocaleString("en-US")} className="tabular-nums">
              {invitationExpiresIn(details.expiresAt, new Date())}
            </time>
          </dd>
        </div>
      ) : null}
    </dl>
  );
}

function AccessList({ label, access }: { label: string; access: InvitationAccessResponse[] }) {
  return (
    <ul aria-label={label} className="type-body mt-6 divide-y divide-border-light border-y border-border">
      {access.map((grant) => <li key={grant.workspaceId} className="py-3 break-words">{accessLabel(grant)}</li>)}
    </ul>
  );
}

function accessLabel(grant: InvitationAccessResponse): string {
  return `${grant.workspaceDisplayName ?? "Workspace"} · ${roleLabel(grant.role)}`;
}
