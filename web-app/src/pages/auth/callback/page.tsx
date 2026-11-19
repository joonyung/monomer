import { INVITATION_CONTINUATION_KEY } from "@/lib/auth/invitations";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useSearch } from "@tanstack/react-router";
import { Link, useRouter } from "@/lib/navigation";
import { useAuthStore } from "@/lib/auth/store";
import { OAUTH_CALLBACK_UNCORRELATED_MESSAGE, parseOAuthCallbackParams } from "@/lib/auth/oauth-callback";
import { Button, buttonClassName } from "@/components/ui/Button";
import { EntryFrame, EntryStatus } from "@/components/auth/EntryFrame";
import { completeOAuthCallbackSession } from "@/lib/auth/session";
import { consumePostLoginRedirect } from "@/lib/auth/post-login-redirect";
import { INVITATION_ACCEPT_PATH, signInAgainPath } from "@/lib/auth/login-context";

type CallbackFailure =
  // Sign-in did not finish; signing in again returns to the original target.
  | { kind: "sign-in"; message: string; retryPath: string }
  // The person is signed in, but this tab cannot prove which invitation it was continuing.
  | { kind: "invitation" };

/**
 * OAuth callback landing page.
 *
 * The auth service sets only the HttpOnly refresh cookie. This page exchanges it through
 * the serialized refresh flow, fetches `me`, and then commits the authenticated session.
 */
function CallbackInner() {
  const router = useRouter();
  const search = useSearch({ from: "/auth/callback" });
  const searchParams = useMemo(() => {
    const params = new URLSearchParams();
    if (search.error) params.set("error", search.error);
    if (search.error_description) params.set("error_description", search.error_description);
    if (search.new_user) params.set("new_user", search.new_user);
    if (search.auth_provider) params.set("auth_provider", search.auth_provider);
    if (search.oauth_state) params.set("oauth_state", search.oauth_state);
    return params;
  }, [search.auth_provider, search.error, search.error_description, search.new_user, search.oauth_state]);
  const setUnauthenticated = useAuthStore((s) => s.setUnauthenticated);
  const [failure, setFailure] = useState<CallbackFailure | null>(null);
  const completionRef = useRef<ReturnType<typeof completeOAuthCallbackSession> | null>(null);
  const redirectTargetRef = useRef<string | null | undefined>(undefined);
  const errorHandledRef = useRef(false);
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const parsed = parseOAuthCallbackParams(searchParams);
    if (redirectTargetRef.current === undefined) {
      redirectTargetRef.current = consumePostLoginRedirect(parsed.oauthState);
    }
    const target = redirectTargetRef.current;
    if (target === null) {
      if (!errorHandledRef.current) {
        errorHandledRef.current = true;
        setFailure({ kind: "sign-in", message: OAUTH_CALLBACK_UNCORRELATED_MESSAGE, retryPath: signInAgainPath(null) });
        setUnauthenticated(OAUTH_CALLBACK_UNCORRELATED_MESSAGE);
      }
      return;
    }
    if (parsed.kind === "error") {
      if (!errorHandledRef.current) {
        errorHandledRef.current = true;
        setFailure({ kind: "sign-in", message: parsed.message, retryPath: signInAgainPath(target) });
        setUnauthenticated(parsed.message);
      }
      return;
    }

    // One callback transaction continues across React StrictMode's effect replay;
    // each effect instance owns only whether it may handle the shared result.
    let cancelled = false;
    const completion = completionRef.current ?? completeOAuthCallbackSession();
    completionRef.current = completion;
    void (async () => {
      const result = await completion;
      if (cancelled || result.kind === "superseded") return;
      if (result.kind === "error") {
        setFailure({ kind: "sign-in", message: result.message, retryPath: signInAgainPath(target) });
        return;
      }
      if (target === INVITATION_ACCEPT_PATH) {
        const saved = sessionStorage.getItem(INVITATION_CONTINUATION_KEY);
        if (!saved || saved !== search.invitation_continuation) {
          sessionStorage.removeItem(INVITATION_CONTINUATION_KEY);
          setFailure({ kind: "invitation" });
          return;
        }
      }
      router.replace(target);
    })();
    return () => {
      cancelled = true;
    };
  }, [searchParams, search.invitation_continuation, router, setUnauthenticated]);

  useEffect(() => {
    if (failure) headingRef.current?.focus();
  }, [failure]);

  return (
    <EntryFrame>
      {failure === null ? (
        <EntryStatus kind="loading" title="Signing you in…" description="One moment." />
      ) : failure.kind === "invitation" ? (
        <EntryStatus
          kind="error"
          headingRef={headingRef}
          title="Invitation couldn't continue"
          description={<span data-testid="callback-error">Open the invitation link from your email again to continue.</span>}
          actions={<Link href="/" className={buttonClassName("secondary", "md", "")}>Go to Monomer</Link>}
        />
      ) : (
        <EntryStatus
          kind="error"
          headingRef={headingRef}
          title="Sign-in didn't finish"
          description={<span data-testid="callback-error">{failure.message}</span>}
          actions={<Button onClick={() => router.replace(failure.retryPath)}>Sign in again</Button>}
        />
      )}
    </EntryFrame>
  );
}

export default function CallbackPage() {
  return (
    <Suspense fallback={null}>
      <CallbackInner />
    </Suspense>
  );
}
