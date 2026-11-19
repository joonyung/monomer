import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { EntryFrame, EntryHeading, EntryStatus } from "@/components/auth/EntryFrame";
import { useAuthStore } from "@/lib/auth/store";
import { fetchOperationsConsent, submitOperationsConsent } from "@/lib/auth/operations-oauth";

export default function OperationsConsentPage({ request }: { request: string }) {
  const epoch = useAuthStore((state) => state.authEpoch);
  return <OperationsConsent key={epoch} epoch={epoch} request={request} />;
}
function OperationsConsent({ request, epoch }: { request: string; epoch: number }) {
  const status = useAuthStore((state) => state.status);
  const user = useAuthStore((state) => state.user);
  const [expired, setExpired] = useState(false);
  const consent = useQuery({
    queryKey: ["auth", epoch, "operations-consent", request],
    queryFn: ({ signal }) => fetchOperationsConsent(request, signal),
    enabled: status === "authenticated" && !!request,
    retry: false, staleTime: Infinity, gcTime: 0, refetchOnWindowFocus: false,
  });
  const data = status === "authenticated" ? consent.data : undefined;
  useEffect(() => {
    if (!data) return;
    const timer = setTimeout(() => setExpired(true), Math.max(0, Date.parse(data.expiresAt) - Date.now()));
    return () => clearTimeout(timer);
  }, [data]);
  const decision = useMutation({
    mutationFn: (approve: boolean) => {
      if (!data || Date.parse(data.expiresAt) <= Date.now()) throw new Error("This authorization request expired. Restart sign-in from Monomer Operations.");
      return submitOperationsConsent(data, approve);
    },
    onSuccess: (redirectUri) => {
      if (useAuthStore.getState().authEpoch === epoch) window.location.assign(redirectUri);
    },
  });
  if (!request || expired || consent.isError) {
    return (
      <EntryFrame>
        <EntryStatus
          kind="error"
          title="Authorize operations access"
          description={<>{!request || expired ? "This authorization request is unavailable." : consent.error?.message ?? "This authorization request is unavailable."} Restart sign-in from your local Monomer Operations app.</>}
        />
      </EntryFrame>
    );
  }
  if (!data) {
    return (
      <EntryFrame>
        <EntryStatus kind="loading" title="Authorize operations access" description="Checking your sign-in session and operations access…" />
      </EntryFrame>
    );
  }
  const deciding = decision.isPending || decision.isSuccess;
  const busy = deciding || decision.isError;
  return (
    <EntryFrame>
      <EntryHeading>Authorize operations access</EntryHeading>
      <dl className="type-body mt-6 divide-y divide-border-light border-y border-border">
        <div className="py-3">
          <dt className="type-meta text-foreground-secondary">Signed in as</dt>
          <dd className="mt-1 break-words">{user?.displayName}</dd>
          <dd className="type-meta mt-1 break-all text-foreground-secondary">{user?.email}</dd>
        </div>
        <div className="py-3"><dt className="type-meta text-foreground-secondary">App</dt><dd className="type-item mt-1 break-words">{data.clientName}</dd></div>
        <div className="py-3">
          <dt className="type-meta text-foreground-secondary">Service</dt>
          <dd className="mt-1 break-all">{data.resource}</dd>
          {data.resource === "https://api.monomer.cc/ops/v1" && <dd className="type-control mt-2 text-heading">Production</dd>}
        </div>
      </dl>
      <div className="type-body mt-6 space-y-3">
        <p>Read users, workspaces, and run metadata across Monomer.</p>
        <p>This grants read-only service operations access to the local app on this computer.</p>
        <p className="text-foreground-secondary">Access expires after one hour and cannot refresh automatically. Sign out in the local app to revoke this session.</p>
      </div>
      {decision.isError && <p role="alert" className="type-body mt-5 text-error">{decision.error.message}</p>}
      <div className="mt-8 flex flex-wrap justify-end gap-3">
        <Button variant="secondary" pending={deciding && decision.variables === false} disabled={busy} onClick={() => decision.mutate(false)}>Cancel</Button>
        <Button pending={deciding && decision.variables === true} disabled={busy} onClick={() => decision.mutate(true)}>Authorize read-only access</Button>
      </div>
    </EntryFrame>
  );
}
