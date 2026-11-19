import { Suspense, useEffect, useMemo, useRef } from "react";
import { useSearch } from "@tanstack/react-router";
import { LoaderCircle } from "lucide-react";
import { useRouter } from "@/lib/navigation";
import { useAuthStore } from "@/lib/auth/store";
import { hydrateSession } from "@/lib/auth/session";
import { ChooseGoogleAccountButton, LocalLoginButton, LoginButton } from "@/components/auth/LoginButton";
import { GOOGLE_OAUTH_ENABLED, LOCAL_LOGIN_ENABLED } from "@/lib/auth/config";
import { readStoredInvitationContinuation, resolveLoginContext } from "@/lib/auth/login-context";
import { publicDocsUrls, publicSiteHome } from "@/lib/public-docs";
import { MonomerLogo } from "@/components/brand/MonomerLogo";

const TEXT_LINK_CLASS = "text-foreground underline underline-offset-4 hover:text-heading";

function LoginInner() {
  const router = useRouter();
  const search = useSearch({ from: "/login" });
  const context = useMemo(
    () => resolveLoginContext(search.redirect ?? null, readStoredInvitationContinuation()),
    [search.redirect],
  );
  const status = useAuthStore((s) => s.status);
  const error = useAuthStore((s) => s.error);
  const sessionEndReason = useAuthStore((s) => s.sessionEndReason);
  const hydrationStartedRef = useRef(false);

  // AuthProvider does not restore a session on /login. A fresh page load checks the refresh
  // cookie here so a person who is still signed in goes straight to where they were headed.
  // After a sign-out or an expiry in this tab the store is already unauthenticated.
  useEffect(() => {
    if (status !== "idle" || hydrationStartedRef.current) return;
    hydrationStartedRef.current = true;
    void hydrateSession();
  }, [status]);

  useEffect(() => {
    if (status === "authenticated") {
      router.replace(context.redirect);
    }
  }, [status, context.redirect, router]);

  const checkingSession = status !== "unauthenticated";

  return (
    <main className="flex min-h-screen flex-col bg-background lg:flex-row">
      <section className="flex flex-col items-center px-6 pb-12 pt-8 sm:px-10 lg:order-2 lg:flex-1 lg:justify-center lg:py-12">
        <MonomerLogo compact className="h-7 w-auto self-start lg:hidden" />
        <div className="mt-10 w-full max-w-[360px] lg:mt-0">
          {checkingSession ? (
            <div role="status" className="flex items-center gap-2 text-foreground-secondary">
              <LoaderCircle className="h-5 w-5 animate-spin motion-reduce:animate-none" aria-hidden="true" />
              <span className="sr-only">Checking your sign-in</span>
            </div>
          ) : (
            <>
              <h1 className="type-section text-heading">{context.heading}</h1>
              <p className="type-body mt-2 text-foreground-secondary">{context.description}</p>
              {sessionEndReason === "expired" && !error ? (
                <p role="status" className="type-body mt-6 text-foreground" data-testid="login-session-expired">
                  Your session expired. Sign in again.
                </p>
              ) : null}
              <div className="mt-8 flex w-full flex-col gap-3">
                {LOCAL_LOGIN_ENABLED ? <LocalLoginButton redirect={context.redirect} /> : null}
                {GOOGLE_OAUTH_ENABLED ? (
                  <LoginButton
                    redirect={context.redirect}
                    invitationContinuation={context.invitationContinuation}
                    variant={LOCAL_LOGIN_ENABLED ? "secondary" : "primary"}
                  />
                ) : null}
                {GOOGLE_OAUTH_ENABLED && error ? (
                  <ChooseGoogleAccountButton
                    redirect={context.redirect}
                    invitationContinuation={context.invitationContinuation}
                  />
                ) : null}
              </div>
              {error ? (
                <p className="type-meta mt-4 text-error" data-testid="login-error" role="alert">
                  {error}
                </p>
              ) : null}
              {GOOGLE_OAUTH_ENABLED && context.kind === "default" ? (
                <p className="type-meta mt-4 text-foreground-secondary">
                  New to Monomer? Continuing with Google creates your free workspace.
                </p>
              ) : null}
              <p className="type-meta mt-4 text-foreground-secondary">
                By continuing, you agree to the{" "}
                <a href={publicDocsUrls.terms} className={TEXT_LINK_CLASS}>Terms</a>
                {" "}and{" "}
                <a href={publicDocsUrls.privacy} className={TEXT_LINK_CLASS}>Privacy Policy</a>.
              </p>
            </>
          )}
        </div>
      </section>
      <section aria-label="Monomer" className="flex flex-1 flex-col justify-between gap-10 bg-brand-paper px-6 py-8 sm:px-10 lg:order-1 lg:w-1/2 lg:flex-none lg:px-14 lg:py-12">
        <MonomerLogo compact className="hidden h-7 w-auto self-start lg:block" />
        <div className="max-w-[460px]">
          <p className="type-group text-label">Agentic analytics</p>
          <p className="type-display mt-3 text-heading lg:text-[40px] lg:leading-[1.08]"><span className="block">Ask your agent.</span><span className="block">Get numbers your team can trust.</span></p>
          <p className="type-body mt-4 text-foreground-secondary">One foundation for every agent on your team. Every run, dashboard, and page shows who made it and, for agent work, through which app.</p>
        </div>
        <a href={publicSiteHome.url} className="type-meta self-start text-foreground-secondary underline-offset-4 hover:text-foreground hover:underline">{publicSiteHome.label}</a>
      </section>
    </main>
  );
}

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginInner />
    </Suspense>
  );
}
