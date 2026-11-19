import { useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { LOCAL_LOGIN_URL } from "@/lib/auth/config";
import { startGoogleSignIn } from "@/lib/auth/google-sign-in";
import { storePostLoginRedirect } from "@/lib/auth/post-login-redirect";

interface GoogleSignInTarget {
  redirect: string;
  invitationContinuation: string | null;
}

// A sign-in action stays pending until the browser leaves; back navigation restores the page from
// the back-forward cache, so the pending state resets on pageshow.
function useLeavingPage(): [boolean, () => void] {
  const [leaving, setLeaving] = useState(false);
  useEffect(() => {
    const reset = () => setLeaving(false);
    window.addEventListener("pageshow", reset);
    return () => window.removeEventListener("pageshow", reset);
  }, []);
  return [leaving, () => setLeaving(true)];
}

export function LoginButton({ redirect, invitationContinuation, variant }: GoogleSignInTarget & {
  variant: "primary" | "secondary";
}) {
  const [leaving, leave] = useLeavingPage();

  const handleClick = () => {
    if (leaving) return;
    leave();
    startGoogleSignIn({ redirect, chooseAccount: false, invitationContinuation });
  };

  return (
    <Button
      variant={variant}
      className="w-full"
      onClick={handleClick}
      pending={leaving}
      data-testid="login-google-button"
    >
      <span className="inline-flex items-center gap-3">
        {!leaving && (
          <img src="/images/google-g.png" alt="" aria-hidden="true" width={20} height={20} className="shrink-0" />
        )}
        Continue with Google
      </span>
    </Button>
  );
}

// Opens Google's account chooser so a person whose usual Google account was refused can pick another.
export function ChooseGoogleAccountButton({ redirect, invitationContinuation }: GoogleSignInTarget) {
  const [leaving, leave] = useLeavingPage();

  const handleClick = () => {
    if (leaving) return;
    leave();
    startGoogleSignIn({ redirect, chooseAccount: true, invitationContinuation });
  };

  return (
    <Button
      variant="ghost"
      className="w-full"
      onClick={handleClick}
      pending={leaving}
      data-testid="login-choose-account-button"
    >
      Use another Google account
    </Button>
  );
}

export function LocalLoginButton({ redirect }: { redirect: string }) {
  const handleClick = () => {
    const target = new URL(LOCAL_LOGIN_URL);
    target.searchParams.set("oauth_state", storePostLoginRedirect(redirect));
    window.location.href = target.toString();
  };

  return (
    <Button
      variant="primary"
      className="w-full"
      onClick={handleClick}
      data-testid="login-local-button"
    >
      Continue as Local User
    </Button>
  );
}
