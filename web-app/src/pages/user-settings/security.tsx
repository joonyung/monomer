import { useState } from "react";
import { SettingsDialog } from "@/components/settings/SettingsDialog";
import { Button } from "@/components/ui/Button";
import { useAuthStore } from "@/lib/auth/store";
import { signOutAllDevices } from "@/lib/auth/session";
import { useRouter } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { UserSettingsShell } from "./shell";

export default function SecurityPage() {
  return (
    <UserSettingsShell section="security" title="Security">
      <SignOutEverywhere />
    </UserSettingsShell>
  );
}

function SignOutEverywhere() {
  const router = useRouter();
  const logout = useAuthStore((state) => state.logout);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await signOutAllDevices();
      logout();
      router.replace(webPaths.login);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Couldn't sign out of all devices. Try again.");
      setBusy(false);
    }
  };
  return (
    <section aria-labelledby="security-sessions" className="max-w-[760px]">
      <div className="flex flex-wrap items-center justify-between gap-4 border-y border-border py-4">
        <div className="min-w-0">
          <h2 id="security-sessions" className="type-item text-heading">Sign out of all devices</h2>
          <p className="mt-1 type-body text-foreground-secondary">Ends every Monomer session, including this browser. Use it if you lost a device or signed in somewhere you don&apos;t trust.</p>
        </div>
        <Button variant="secondary" onClick={() => { setError(null); setConfirming(true); }}>Sign out of all devices</Button>
      </div>
      {confirming && (
        <SettingsDialog
          title="Sign out of all devices?"
          size="sm"
          role="alertdialog"
          busy={busy}
          onClose={() => setConfirming(false)}
          footer={
            <>
              <Button variant="ghost" disabled={busy} onClick={() => setConfirming(false)}>Cancel</Button>
              <Button pending={busy} onClick={() => void confirm()}>Sign out everywhere</Button>
            </>
          }
        >
          <p className="type-body">You&apos;ll be signed out everywhere, including this browser. Connected agents keep working — revoke them in Agents.</p>
          {error && <p role="alert" className="mt-3 type-body text-error">{error}</p>}
        </SettingsDialog>
      )}
    </section>
  );
}
