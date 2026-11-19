import { useLayoutEffect, useRef, useState, type RefObject } from "react";
import { ArrowRight, BarChart3, CalendarClock, ChevronRight, Compass, Database, FileText, Globe, History, LayoutDashboard, Network, Search, WifiOff } from "lucide-react";
import { Link } from "@/lib/navigation";
import type { ActivityItem } from "@/api/overview";
import { activityId, isActive, type ObservedRun } from "./observation";
import { useActivity, type ActivityScope } from "./use-activity";
import { changeFingerprint, useChangeHighlight, type ChangeHighlightItem } from "@/lib/change-highlight";
import { formatRelativeTime } from "@/lib/utils/relative-time";
import { webPaths } from "@/lib/web-paths";
import { WorkStatus } from "@/components/ui/WorkStatus";
import { Button } from "@/components/ui/Button";
import { Input, NativeSelect } from "@/components/ui/Input";
import { Dialog } from "@/components/ui/Dialog";
import { StateBlock } from "@/components/ui/StateBlock";
import { AgentAvatar, AgentAvatarCaption } from "@/components/ui/AgentMarker";
import { SAMPLE_SEED_AUTHOR } from "@/components/ui/ActorProvenance";

const ACTIVITY_SCOPES: readonly (readonly [ActivityScope, string])[] = [["all", "All"], ["mine", "Mine"], ["agents", "Agents"]];
const DEFINITION_ACTIVITY_KINDS: readonly ActivityItem["kind"][] = ["SEMANTIC_MODEL", "METRIC", "DASHBOARD"];

/** The responsible party in words: a member's name, or who ran it when no member did. */
export function actorLabel(item: Pick<ActivityItem, "actorKind" | "actorName">): string {
  switch (item.actorKind) {
    case "USER": return item.actorName ?? "Unknown user";
    case "SCHEDULE": return "Scheduled";
    case "PUBLIC_READER": return "Public reader";
    default: return "Not recorded";
  }
}

/** In a sample Workspace, a Semantic Model, Metric, or Dashboard change that recorded neither a person nor a surface is Monomer's seed. */
export function seededDefinitionActivity(item: Pick<ActivityItem, "kind" | "actorKind" | "credentialSurface">, sampleWorkspace: boolean): boolean {
  return sampleWorkspace && DEFINITION_ACTIVITY_KINDS.includes(item.kind) && item.actorKind === "UNRECORDED" && item.credentialSurface === null;
}

function Person({ item, seeded }: { item: ActivityItem; seeded: boolean }) {
  const name = seeded ? SAMPLE_SEED_AUTHOR : actorLabel(item);
  const mark = item.actorKind === "SCHEDULE" ? <CalendarClock className="h-3.5 w-3.5" />
    : item.actorKind === "PUBLIC_READER" ? <Globe className="h-3.5 w-3.5" />
      : name.split(/\s+/).slice(0, 2).map((part) => part[0]).join("");
  const avatar = <span className={item.actorKind === "USER" ? "ov-avatar" : "ov-avatar ov-avatar-system"} aria-hidden="true">{mark}</span>;
  if (item.credentialSurface !== "AGENT") return <span className="ov-person">{avatar}<span className="ov-person-name">{name}</span></span>;
  return <span className="ov-person"><AgentAvatar app={item.agentApp}>{avatar}</AgentAvatar><span className="ov-person-name">{name}<AgentAvatarCaption app={item.agentApp} className="ov-meta" /></span></span>;
}

function elapsed(item: ActivityItem, now: number) {
  if (!item.startedAt) return "";
  const seconds = Math.max(0, Math.floor(((isActive(item) ? now : Date.parse(item.occurredAt)) - Date.parse(item.startedAt)) / 1_000));
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

export function describeActivity(item: ActivityItem): string {
  return item.failureReason ? `${item.description} · ${item.failureReason}` : item.description;
}

function RunList({ rows, now, stale, unavailable, protectedIds }: {
  rows: ObservedRun[]; now: number; stale: boolean; unavailable: ActivityItem["kind"][]; protectedIds: RefObject<Set<string>>;
}) {
  const list = useRef<HTMLUListElement>(null);
  const priorPositions = useRef(new Map<string, number>());
  const interactions = useRef({ hover: null as string | null, focus: null as string | null });
  const protect = (kind: "hover" | "focus", key: string | null) => {
    const old = interactions.current[kind];
    interactions.current[kind] = key;
    if (old && !Object.values(interactions.current).includes(old)) protectedIds.current.delete(old);
    if (key) protectedIds.current.add(key);
  };
  useLayoutEffect(() => {
    const elements = list.current?.querySelectorAll<HTMLElement>("[data-run-id]") ?? [];
    const next = new Map<string, number>();
    for (const element of elements) {
      const key = element.dataset.runId!;
      const top = element.getBoundingClientRect().top - (list.current?.getBoundingClientRect().top ?? 0);
      const old = priorPositions.current.get(key);
      next.set(key, top);
      if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        if (old === undefined) element.animate([{ opacity: 0, transform: "translateY(-6px)" }, { opacity: 1, transform: "translateY(0)" }], { duration: 200, easing: "ease-out" });
        else if (old !== top) element.animate([{ transform: `translateY(${old - top}px)` }, { transform: "translateY(0)" }], { duration: 200, easing: "ease-out" });
      }
    }
    priorPositions.current = next;
  }, [rows]);
  useLayoutEffect(() => () => {
    for (const key of Object.values(interactions.current)) if (key) protectedIds.current.delete(key);
  }, [protectedIds]);
  return <ul className="ov-run-list" ref={list}>{rows.map(({ item, lastObservedAt }) => {
    const key = activityId(item);
    const active = isActive(item);
    const rowStale = stale || unavailable.includes(item.kind);
    return <li key={key} data-run-id={key} onMouseEnter={() => protect("hover", key)} onMouseLeave={() => protect("hover", null)} onFocusCapture={() => protect("focus", key)} onBlurCapture={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) protect("focus", null); }}>
      <Link href={item.href} className="ov-run-link">
        <span className="ov-run-work"><span className="ov-run-target">{item.title}</span><span className="ov-meta">{describeActivity(item)}</span></span>
        <Person item={item} seeded={false} />
        <span className="ov-run-state"><WorkStatus state={rowStale && active ? "LAST_SEEN_RUNNING" : item.state} labelDisplay="inline" size="sm" /><span className="ov-meta">{elapsed(item, rowStale ? lastObservedAt : now)}</span></span>
        <ChevronRight className="h-3.5 w-3.5" aria-hidden="true" />
      </Link>
    </li>;
  })}</ul>;
}

function activityIcon(kind: ActivityItem["kind"]) {
  if (kind === "SEMANTIC_MODEL") return Network;
  if (kind === "METRIC") return BarChart3;
  if (kind === "PAGE") return FileText;
  if (kind === "EXPLORE") return Compass;
  if (kind.startsWith("DASHBOARD")) return LayoutDashboard;
  return Database;
}

const LIVE_BOARD_ROW_LIMIT = 5;

export type Activity = ReturnType<typeof useActivity>;

function liveUnavailableSources(activity: Activity): ActivityItem["kind"][] {
  return [...new Set(activity.live.data?.pages.flatMap((page) => page.unavailableSources) ?? [])];
}

function activityClock(activity: Activity): { stale: boolean; observedAt: string | undefined; clock: number } {
  const stale = !activity.online || activity.live.isError;
  const observedAt = activity.live.data?.pages[0]?.observedAt;
  return { stale, observedAt, clock: stale && observedAt ? Date.parse(observedAt) : activity.now };
}

function checkedTime(observedAt: string) {
  return <time dateTime={observedAt}>{new Date(observedAt).toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit", second: "2-digit" })}</time>;
}

/**
 * The Live board appears only while something runs. A run that just finished keeps its row for the observation grace
 * period, and the board stays while the pointer or keyboard focus is inside it so nothing moves under the reader.
 */
export function LiveBoard({ activity }: { activity: Activity }) {
  const { rows, live, online, protectedIds } = activity;
  const [open, setOpen] = useState(false);
  const [pointerInside, setPointerInside] = useState(false);
  const [focusInside, setFocusInside] = useState(false);
  const [search, setSearch] = useState("");
  const [person, setPerson] = useState("all");
  const [requestedPage, setRequestedPage] = useState(0);
  const unavailable = liveUnavailableSources(activity);
  const { stale, observedAt, clock } = activityClock(activity);
  // A newly started run reaches rows after a short arrival batch; counting the live pages too keeps the board from
  // flickering or arriving late in that window.
  const activeRowCount = rows.filter(({ item }) => isActive(item)).length;
  const activeCount = Math.max(
    activeRowCount,
    live.data?.pages.reduce((count, page) => count + page.items.filter(isActive).length, 0) ?? 0,
  );
  const finishedCount = rows.length - activeRowCount;
  const people = [...new Map(rows.filter(({ item }) => item.actorId).map(({ item }) => [item.actorId!, item.actorName ?? "Unknown user"])).entries()];
  const matching = rows.filter(({ item }) => (person === "all" || item.actorId === person) && `${item.title} ${item.description} ${actorLabel(item)}`.toLowerCase().includes(search.toLowerCase()));
  const pageCount = Math.max(1, Math.ceil(matching.length / 10));
  const pageIndex = Math.min(requestedPage, pageCount - 1);
  const runProps = { now: clock, stale, unavailable, protectedIds };
  const visible = activeCount > 0 || live.hasNextPage || open || pointerInside || focusInside;
  const summary = [
    activeCount > 0 ? `${activeCount} ${stale ? "last seen running" : "running"}` : null,
    finishedCount > 0 ? `${finishedCount} finished` : null,
    live.hasNextPage ? "More runs to load" : null,
  ].filter(Boolean).join(" · ");
  return <>
    {!online && <p className="ov-notice" role="status"><WifiOff className="h-4 w-4" aria-hidden="true" />Disconnected. Last observed states are shown; current run states are unknown.</p>}
    {live.isError && <p className="ov-notice" role="status">Live activity could not be refreshed. <button className="ov-link" onClick={() => void live.refetch()}>Retry</button></p>}
    {unavailable.length > 0 && <p className="ov-notice" role="status">Some activity sources are unavailable. Their last observed states may be out of date. <button className="ov-link" onClick={() => void live.refetch()}>Retry</button></p>}
    {visible && <section className="ov-live" aria-labelledby="overview-live-title"
      onMouseEnter={() => setPointerInside(true)} onMouseLeave={() => setPointerInside(false)}
      onFocus={() => setFocusInside(true)} onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setFocusInside(false); }}>
      <div className="ov-section-heading ov-live-heading">
        <h2 id="overview-live-title">Live activity</h2>
        <span className="ov-meta ov-live-count">{activeCount > 0 && <WorkStatus state={stale ? "LAST_SEEN_RUNNING" : "RUNNING"} labelDisplay="external" size="sm" />}{summary || "Nothing running"}</span>
      </div>
      {rows.length > 0 && <RunList rows={rows.slice(0, LIVE_BOARD_ROW_LIMIT)} {...runProps} />}
      <div className="ov-live-footer">
        <span className="ov-meta">{rows.length > LIVE_BOARD_ROW_LIMIT && <>Showing {LIVE_BOARD_ROW_LIMIT} of {rows.length}{observedAt && " · "}</>}{observedAt && <>Last checked {checkedTime(observedAt)}</>}</span>
        <Dialog closeLabel="Close runs"
          open={open}
          onOpenChange={(next) => {
            setOpen(next);
            // The dialog is portaled but sits inside the section in React's tree, so leaving it never reports leaving
            // the section. Closing it forgets the pointer; moving back onto the board reports entering again.
            if (!next) setPointerInside(false);
          }}
          size="lg"
          trigger={<button type="button" className="ov-link ov-live-all">View all runs<ArrowRight className="h-4 w-4" aria-hidden="true" /></button>}
          title="All observed runs"
          description="Search and person filters apply to loaded runs. Load more to expand the observed range."
          bodyClassName="overview-activity px-6 py-5"
        >
          <div className="ov-search-controls"><Input label="Search runs" placeholder="Work, target, or person" value={search} onChange={(event) => { setSearch(event.target.value); setRequestedPage(0); }} /><NativeSelect label="Responsible person" value={person} onChange={(event) => { setPerson(event.target.value); setRequestedPage(0); }}><option value="all">All people</option>{people.map(([id, name]) => <option key={id} value={id}>{name}</option>)}</NativeSelect></div>
          <p className="ov-meta ov-match-count">{matching.length} matching runs · Loaded runs only{observedAt && <> · Last checked {checkedTime(observedAt)}</>}</p>
          {matching.length ? <RunList rows={matching.slice(pageIndex * 10, (pageIndex + 1) * 10)} {...runProps} /> : <StateBlock kind="empty" layout="inline" icon={Search} title="No runs match these filters." />}
          <div className="ov-pagination"><Button variant="secondary" disabled={pageIndex === 0} onClick={() => setRequestedPage(pageIndex - 1)}>Previous</Button><span className="ov-meta">Page {pageIndex + 1} of {pageCount}</span><Button variant="secondary" disabled={pageIndex + 1 >= pageCount} onClick={() => setRequestedPage(pageIndex + 1)}>Next</Button></div>
          {live.hasNextPage && <Button variant="secondary" className="ov-load-more" disabled={live.isFetchingNextPage || !online} onClick={() => void live.fetchNextPage()}> {live.isFetchingNextPage ? "Loading…" : "Load more runs"}</Button>}
        </Dialog>
      </div>
    </section>}
  </>;
}

/** The All, Mine, and Agents scope tabs, the only Activity filter. */
export function ActivityScopeTabs({ scope, onScopeChange }: { scope: ActivityScope; onScopeChange: (scope: ActivityScope) => void }) {
  return <div className="ov-filters" role="group" aria-label="Activity filter">{ACTIVITY_SCOPES.map(([value, label]) => <button key={value} type="button" aria-pressed={scope === value} onClick={() => onScopeChange(value)}>{label}</button>)}</div>;
}

/** Highlights a changed row, and a new row only when it arrives on the first page rather than through Load more. */
export function activityHighlightItems(items: ActivityItem[], arrivalIds: ReadonlySet<string>): ChangeHighlightItem[] {
  return items.map((item) => ({
    id: activityId(item),
    fingerprint: changeFingerprint(["CREATED", "UPDATED", "APPLIED", "DELETED"].includes(item.state)
      ? { title: item.title, description: item.description, occurredAt: item.occurredAt }
      : { title: item.title, description: item.description }),
    highlightArrival: arrivalIds.has(activityId(item)),
  }));
}

/** Activity rows newest first: what happened, its state, who did it and through which app, and when. */
export function ActivityTimeline({ items, clock, sampleWorkspace }: { items: ActivityItem[]; clock: number; sampleWorkspace: boolean }) {
  return <ol className="ov-timeline">{items.map((item) => {
    const Icon = activityIcon(item.kind);
    return <li key={activityId(item)} data-change-id={activityId(item)}><span aria-hidden="true" className="change-highlight-overlay" /><Link href={item.href} className="ov-recent-link"><span className="ov-resource-icon" aria-hidden="true"><Icon className="h-4 w-4" /></span><span className="ov-event-body"><span className="ov-resource-title">{item.title}</span><span className="ov-meta">{describeActivity(item)}{!DEFINITION_ACTIVITY_KINDS.includes(item.kind) && item.state !== "UPDATED" && <> · <WorkStatus state={item.state} labelDisplay="inline" size="sm" /></>}</span><span className="ov-event-attribution"><Person item={item} seeded={seededDefinitionActivity(item, sampleWorkspace)} /><time className="ov-meta" dateTime={item.occurredAt} title={new Date(item.occurredAt).toLocaleString("en-US")}>{formatRelativeTime(item.occurredAt, clock)}</time></span></span><ChevronRight className="h-3.5 w-3.5" aria-hidden="true" /></Link></li>;
  })}</ol>;
}

export function RecentActivity({ activity, workspaceId, principalId, sampleWorkspace }: { activity: Activity; workspaceId: string; principalId: string; sampleWorkspace: boolean }) {
  const { recent, filter, setFilter, online } = activity;
  const recentUnavailable = [...new Set(recent.data?.pages.flatMap((page) => page.unavailableSources) ?? [])];
  const { clock } = activityClock(activity);
  const events = [...(recent.data?.pages.flatMap((page) => page.items) ?? []).reduce((byId, item) => {
    if (!byId.has(activityId(item))) byId.set(activityId(item), item);
    return byId;
  }, new Map<string, ActivityItem>()).values()];
  const firstPageIds = new Set(recent.data?.pages[0]?.items.map(activityId));
  const highlightContainer = useChangeHighlight(JSON.stringify([workspaceId, principalId, filter]),
    recent.data ? activityHighlightItems(events, firstPageIds) : null, { duration: 1_800, animateArrival: true });
  return <section ref={highlightContainer} aria-labelledby="overview-recent-title">
    <div className="ov-section-heading ov-recent-heading"><h2 id="overview-recent-title">Recent activity</h2>
      <ActivityScopeTabs scope={filter} onScopeChange={setFilter} />
    </div>
    {(recent.isError || recentUnavailable.length > 0) && <p className="ov-notice" role="status">{recent.isError ? "Recent activity could not be refreshed." : "Some recent activity sources are unavailable."} <button className="ov-link" onClick={() => void recent.refetch()}>Retry</button></p>}
    {recent.isPending ? <StateBlock kind="loading" layout="inline" title="Loading recent activity…" /> : events.length ? <ActivityTimeline items={events} clock={clock} sampleWorkspace={sampleWorkspace} /> : !recent.isError && <StateBlock kind="empty" layout="inline" icon={History} title={recent.hasNextPage || recentUnavailable.length ? "No activity in loaded results" : filter === "agents" ? "No agent activity yet" : "No recent activity"} />}
    <div className="ov-recent-footer">
      {recent.hasNextPage && <button type="button" className="ov-link ov-load-more" disabled={recent.isFetchingNextPage || !online} onClick={() => void recent.fetchNextPage()}>{recent.isFetchingNextPage ? "Loading…" : "Load more activity"}</button>}
      <Link href={webPaths.activity(workspaceId, filter)} className="ov-link ov-recent-all">View all activity<ArrowRight className="h-4 w-4" aria-hidden="true" /></Link>
    </div>
  </section>;
}
