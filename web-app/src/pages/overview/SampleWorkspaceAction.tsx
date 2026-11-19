import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle } from "lucide-react";
import { sampleWorkspaceAPI, type SampleWorkspaceResponse } from "@/api/sample-workspace";
import { Button } from "@/components/ui/Button";
import { fetchMe } from "@/lib/auth/graphql";
import { useAuthStore } from "@/lib/auth/store";
import { useRouter } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { webPaths } from "@/lib/web-paths";

// The sample has no failed state: a setup that stops on the server stays PROVISIONING until someone
// starts it again. A setup this page started offers Retry setup only once it outlasts this wait.
const SAMPLE_SETUP_RETRY_AFTER_MS = 90_000;

export function sampleSetupLabel({ state, startedHere, failed }: { state: SampleWorkspaceResponse["sample"]["state"] | null; startedHere: boolean; failed: boolean }): string {
  if (failed) return "Retry setup";
  if (state === "PROVISIONING") return startedHere ? "Explore sample data" : "Retry setup";
  return state === "READY" ? "Open sample workspace" : "Explore sample data";
}

export function SampleWorkspaceAction({ organizationId, principalId }: { organizationId: string; principalId: string }) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const [opening, setOpening] = useState(false);
  const [setupOutlastedWait, setSetupOutlastedWait] = useState(false);
  const [openError, setOpenError] = useState<Error | null>(null);
  const completing = useRef(false);
  const queryKey = queryKeys.auth.sampleWorkspace(principalId, organizationId);
  const sample = useQuery({
    queryKey,
    queryFn: ({ signal }) => sampleWorkspaceAPI.read(organizationId, signal),
    retry: false,
    refetchInterval: (query) => query.state.error ? false : query.state.data?.sample.state === "PROVISIONING" ? 1000 : false,
  });
  const start = useMutation({
    mutationFn: () => sampleWorkspaceAPI.start(organizationId),
    retry: false,
    onSuccess: async (workspace) => {
      await queryClient.cancelQueries({ queryKey, exact: true });
      queryClient.setQueryData(queryKey, workspace);
      setOpenError(null);
      setSetupOutlastedWait(false);
      setOpening(true);
    },
  });
  const waitingForSetup = opening && sample.data?.sample.state === "PROVISIONING" && !setupOutlastedWait;
  useEffect(() => {
    if (!waitingForSetup) return;
    const timer = window.setTimeout(() => setSetupOutlastedWait(true), SAMPLE_SETUP_RETRY_AFTER_MS);
    return () => window.clearTimeout(timer);
  }, [waitingForSetup]);
  const readyWorkspaceId = opening && sample.data?.sample.state === "READY" ? sample.data.workspaceId : null;
  useEffect(() => {
    if (!readyWorkspaceId || completing.current) return;
    completing.current = true;
    let active = true;
    const controller = new AbortController();
    const open = async () => {
      const me = await fetchMe(controller.signal, false);
      if (!active || useAuthStore.getState().user?.id !== principalId) return;
      useAuthStore.getState().setMe(me);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.auth.accessibleWorkspaces(principalId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.auth.managedWorkspaces(principalId, organizationId) }),
      ]);
      if (active && useAuthStore.getState().user?.id === principalId) router.push(webPaths.workspace(readyWorkspaceId));
    };
    void open().catch((error: unknown) => {
      if (!active) return;
      completing.current = false;
      setOpening(false);
      setOpenError(error instanceof Error ? error : new Error("Sample workspace could not be opened."));
    });
    return () => { active = false; completing.current = false; controller.abort(); };
  }, [readyWorkspaceId, organizationId, principalId, queryClient, router]);

  const provisioning = sample.data?.sample.state === "PROVISIONING";
  const error = start.error ?? sample.error ?? openError;
  const busy = error === null && (waitingForSetup || (opening && !provisioning));
  return <div className="ov-sample-action">
    <Button variant="ghost" size="sm" className="ov-start-choice-action" disabled={start.isPending || sample.isPending || busy} onClick={() => start.mutate()}>
      {sampleSetupLabel({ state: sample.data?.sample.state ?? null, startedHere: waitingForSetup, failed: error !== null })}
    </Button>
    {provisioning || start.isPending || opening ? <span role="status" className="ov-meta inline-flex items-center gap-1.5">
      <LoaderCircle className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" aria-hidden="true" />
      {provisioning || start.isPending ? "Setting up sample data…" : "Opening sample workspace…"}
    </span> : null}
    {error ? <span role="alert" className="type-meta text-error">{error.message}</span> : null}
  </div>;
}
