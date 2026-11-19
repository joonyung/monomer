import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { dashboardAPI, DashboardApiError } from "@/api/dashboard";
import { pagesAPI, type PagesTreeResponse } from "@/api/pages";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Input } from "@/components/ui/Input";
import { Link } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { webPaths } from "@/lib/web-paths";

export function linkedDashboardPages(tree: PagesTreeResponse, dashboardId: string): { pageId: string; path: string }[] {
  const pagesById = new Map(tree.pages.map(page => [page.pageId, page]));
  const placementPaths = new Map<string, string>();
  const visit = (placements: PagesTreeResponse["placements"], ancestors: string[]) => {
    for (const placement of placements) {
      const page = pagesById.get(placement.pageId);
      const path = [...ancestors, page?.displayTitle ?? placement.pageId];
      placementPaths.set(placement.pageId, path.join(" / "));
      visit(placement.children, path);
    }
  };
  visit(tree.placements, []);
  return tree.pages.filter(page => page.kind === "DASHBOARD" && page.dashboardId === dashboardId)
    .map(page => ({ pageId: page.pageId, path: placementPaths.get(page.pageId) ?? page.displayTitle }));
}

interface DashboardDeleteDialogProps {
  open: boolean;
  workspaceId: string;
  principalId: string;
  dashboardId: string;
  publicPages: boolean;
  onCancel: () => void;
  onDeleted: () => void;
}

export function DashboardDeleteDialog(props: DashboardDeleteDialogProps) {
  return props.open ? <OpenDashboardDeleteDialog {...props} /> : null;
}

function OpenDashboardDeleteDialog({ workspaceId, principalId, dashboardId, publicPages, onCancel, onDeleted }: DashboardDeleteDialogProps) {
  const [confirmation, setConfirmation] = useState("");
  const [alreadyDeleted, setAlreadyDeleted] = useState(false);
  const current = useQuery({
    queryKey: [...queryKeys.dashboards.detail(workspaceId, principalId, dashboardId), "delete-confirmation"],
    queryFn: ({ signal }) => dashboardAPI.get(workspaceId, dashboardId, signal),
    refetchOnMount: "always", staleTime: 0, retry: false,
  });
  const pages = useQuery({
    queryKey: [...queryKeys.pages.directory(workspaceId, principalId), "dashboard-delete", dashboardId],
    queryFn: ({ signal }) => pagesAPI.getTree(workspaceId, signal),
    refetchOnMount: "always", staleTime: 0, retry: false,
  });
  const deletion = useMutation({
    mutationFn: () => dashboardAPI.delete(workspaceId, dashboardId),
    onSuccess: onDeleted,
    onError: (error) => { if (error instanceof DashboardApiError && error.status === 404) setAlreadyDeleted(true); },
  });
  const title = current.data?.definition.meta.title;
  const missing = alreadyDeleted || current.error instanceof DashboardApiError && current.error.status === 404;
  const confirmed = Boolean(title && confirmation === title);
  const ready = current.isSuccess && pages.isSuccess && !current.isFetching && !pages.isFetching;
  const linked = pages.data ? linkedDashboardPages(pages.data, dashboardId) : [];
  const schedules = Object.keys(current.data?.definition.schedules ?? {}).length;
  return <Dialog open onOpenChange={next => { if (!next) onCancel(); }} size="md" role="alertdialog" busy={deletion.isPending} closeLabel="Close delete dashboard" title={title ? `Delete “${title}”?` : "Delete dashboard?"}
    description="You can restore it later from its history. Pages and past results don’t come back."
    footer={<div className="ml-auto flex flex-wrap gap-2"><Button variant="secondary" disabled={deletion.isPending} onClick={onCancel}>Cancel</Button>{missing ? <Button onClick={onDeleted}>Back to Dashboards</Button> : <Button type="submit" form="dashboard-delete-form" variant="danger" pending={deletion.isPending} disabled={!confirmed || !ready}>Delete dashboard</Button>}</div>}>
    {missing ? <p role="alert" className="type-body">This dashboard is already deleted.</p> : <form id="dashboard-delete-form" className="space-y-5" onSubmit={event => { event.preventDefault(); if (confirmed && ready && !deletion.isPending) deletion.mutate(); }}>
      <ul className="type-body list-disc space-y-2 pl-5 text-foreground">
        <li>{pages.isError ? <>Couldn’t load linked pages. <Button type="button" variant="ghost" size="sm" onClick={() => void pages.refetch()}>Retry linked pages</Button></> : pages.isPending || pages.isFetching ? "Checking linked pages…" : <>{linked.length === 0 ? "No linked pages are deleted." : `${linked.length} linked ${linked.length === 1 ? "page is" : "pages are"} deleted:`}{linked.length > 0 && <ul aria-label="Linked pages" className="mt-1.5 space-y-1">{linked.map(page => <li key={page.pageId}><Link href={webPaths.page(workspaceId, page.pageId)} className="break-words underline underline-offset-4">{page.path}</Link></li>)}</ul>}</>}</li>
        {publicPages && <li>This workspace’s pages are public, so people outside it lose access too.</li>}
        <li>{current.isError ? <>Couldn’t load the current dashboard. <Button type="button" variant="ghost" size="sm" onClick={() => void current.refetch()}>Retry dashboard</Button></> : current.isPending || current.isFetching ? "Checking schedules…" : schedules === 1 ? "Its 1 schedule stops." : `Its ${schedules} schedules stop.`}</li>
        <li>Queries that are running now are cancelled, and past views and their results are deleted.</li>
      </ul>
      <Input id="dashboard-delete-confirmation" label={title ? `Type “${title}” to confirm` : "Type the dashboard title to confirm"} value={confirmation} disabled={deletion.isPending || !current.isSuccess} autoComplete="off" spellCheck={false} onChange={event => setConfirmation(event.target.value)} />
      {deletion.error && !alreadyDeleted && <p role="alert" className="type-body text-error">{deletion.error.message}</p>}
    </form>}
  </Dialog>;
}
