import { useEffect, useRef, useState } from "react";
import { useLocation } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { LayoutDashboard } from "lucide-react";
import { dashboardAPI } from "@/api/dashboard";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import { useRouter } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { webPaths } from "@/lib/web-paths";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { Button } from "@/components/ui/Button";
import { StateBlock } from "@/components/ui/StateBlock";
import { Input, NativeSelect } from "@/components/ui/Input";
import { PagesDialog } from "./PagesDialog";
import { usePagesDirectory } from "./PagesDirectoryProvider";
import { useCanEditPages } from "./usePagesPermissions";
import { buildPageDestinationOptions } from "./pageDialogSelectors";
import { mutationErrorMessage } from "./PageMutationDialogs";

export function AddDashboardButton({ parentPageId }: { parentPageId: string | null }) {
  const scrollbar = useTransientScrollbar();
  const canEdit = useCanEditPages();
  const workspaceId = useWorkspaceId();
  const principalId = usePrincipalId();
  const router = useRouter();
  const locationHref = useLocation({ select: location => location.href });
  const submissionScope = useRef<object | null>(null);
  const { snapshot, createDashboard } = usePagesDirectory();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [dashboardId, setDashboardId] = useState("");
  const [title, setTitle] = useState("");
  const [destination, setDestination] = useState(parentPageId ?? "");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    submissionScope.current = {};
    setOpen(false);
    setPending(false);
    setError(null);
    return () => { submissionScope.current = null; };
  }, [locationHref, workspaceId, principalId, parentPageId]);
  const dashboards = useQuery({
    queryKey: queryKeys.dashboards.list(workspaceId, principalId ?? ""),
    queryFn: ({ signal }) => dashboardAPI.list(workspaceId, signal),
    enabled: open && canEdit && Boolean(principalId),
    staleTime: 0,
    retry: false,
  });
  if (!canEdit) return null;
  const options = snapshot ? buildPageDestinationOptions(snapshot, null) : [];
  const filtered = (dashboards.data ?? []).filter(item => `${item.title} ${item.description ?? ""}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
  const submit = async () => {
    const scope = submissionScope.current;
    if (!dashboardId || !title.trim() || pending || !scope) return;
    setPending(true);
    setError(null);
    try {
      const response = await createDashboard({ dashboardId, title: title.trim(), description: null, parentPageId: destination || null, index: null });
      if (submissionScope.current !== scope) return;
      setOpen(false);
      if (response.change?.pageId) router.push(webPaths.page(workspaceId, response.change.pageId));
    } catch (caught) {
      if (submissionScope.current === scope) setError(mutationErrorMessage(caught));
    } finally {
      if (submissionScope.current === scope) setPending(false);
    }
  };
  return <PagesDialog open={open} onOpenChange={next => {
    if (pending) return;
    setOpen(next);
    if (next) { setSearch(""); setDashboardId(""); setTitle(""); setDestination(parentPageId ?? ""); setError(null); }
  }} pending={pending} title="Add dashboard" description="Every workspace member can read this dashboard's past and future results. When Public Pages is on, anyone with a link can read them." testId="add-dashboard-dialog"
    trigger={<Button variant="primary" size="sm"><LayoutDashboard aria-hidden="true" />Add dashboard</Button>}
    footer={<><Button variant="ghost" size="sm" disabled={pending} onClick={() => setOpen(false)}>Cancel</Button><Button variant="primary" size="sm" pending={pending} disabled={!dashboardId || !title.trim() || !options.some(option => (option.pageId ?? "") === destination)} onClick={() => void submit()}>Add to Pages</Button></>}>
    <div className="space-y-4">
      <Input label="Search dashboards" value={search} onChange={event => setSearch(event.target.value)} disabled={pending} />
      {dashboards.isPending ? <StateBlock kind="loading" layout="inline" title="Loading dashboards…" /> : dashboards.isError ? <StateBlock kind="error" layout="inline" title={dashboards.error.message} action={<Button variant="ghost" size="sm" onClick={() => void dashboards.refetch()}>Retry</Button>} /> : filtered.length === 0 ? <StateBlock kind="empty" layout="inline" icon={LayoutDashboard} title={dashboards.data?.length ? "No dashboards match your search." : "No dashboards yet."} /> : <fieldset onScroll={scrollbar.onScroll} className={`max-h-56 overflow-y-auto border-y border-border ${scrollbar.className}`}><legend className="sr-only">Dashboard</legend>{filtered.map(item => <label key={item.dashboardId} className="flex cursor-pointer items-start gap-3 border-b border-border-light px-3 py-3 last:border-0 has-checked:bg-surface"><input type="radio" name="dashboard-target" value={item.dashboardId} checked={dashboardId === item.dashboardId} disabled={pending} onChange={() => { setDashboardId(item.dashboardId); setTitle(item.title); }} className="mt-1" /><span className="min-w-0 type-control"><span className="block wrap-anywhere">{item.title}</span>{item.description && <span className="mt-1 block type-meta text-foreground-secondary">{item.description}</span>}</span></label>)}</fieldset>}
      <Input label="Name in Pages" value={title} onChange={event => setTitle(event.target.value)} disabled={pending} required />
      <NativeSelect label="Destination" value={destination} onChange={event => setDestination(event.target.value)} disabled={pending}>{options.map(option => <option key={option.pageId ?? ""} value={option.pageId ?? ""} disabled={option.disabled}>{option.label}</option>)}</NativeSelect>
      {error && <p role="alert" className="type-control text-error">{error}</p>}
    </div>
  </PagesDialog>;
}
