import { useEffect, useRef, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { overviewAPI } from "@/api/overview";
import { activityId, insertArrivals, isActive, pruneRuns, reconcileRuns, type ObservedRun } from "./observation";
import { ACTIVE_POLL_INTERVAL_MS, activityPollInterval } from "./overview-model";

/** Activity scopes: everyone, the viewer's own work, or work done through an agent connection. */
export type ActivityScope = "all" | "mine" | "agents";

export function activityQuery(scope: ActivityScope): { actor: "all" | "me"; surface?: "AGENT" } {
  if (scope === "mine") return { actor: "me" };
  if (scope === "agents") return { actor: "all", surface: "AGENT" };
  return { actor: "all" };
}

export function useActivity(workspaceId: string, principalId: string) {
  const [available, setAvailable] = useState(() => navigator.onLine && document.visibilityState !== "hidden");
  const [online, setOnline] = useState(() => navigator.onLine);
  const [rows, setRows] = useState<ObservedRun[]>([]);
  const rowsRef = useRef(rows);
  const pending = useRef<ObservedRun[]>([]);
  const batchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const protectedIds = useRef(new Set<string>());
  const [now, setNow] = useState(Date.now);
  const [filter, setFilter] = useState<ActivityScope>("all");
  // Two seconds while anything is live or the workspace shows activity; slower while it is idle and empty.
  const [pollInterval, setPollInterval] = useState(ACTIVE_POLL_INTERVAL_MS);

  useEffect(() => {
    const update = () => { setOnline(navigator.onLine); setAvailable(navigator.onLine && document.visibilityState !== "hidden"); };
    window.addEventListener("online", update); window.addEventListener("offline", update);
    document.addEventListener("visibilitychange", update);
    return () => { window.removeEventListener("online", update); window.removeEventListener("offline", update); document.removeEventListener("visibilitychange", update); };
  }, []);

  const live = useInfiniteQuery({
    queryKey: ["overview", workspaceId, principalId, "live"],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam, signal }) => {
      const checked = pageParam ? [] : [...rowsRef.current, ...pending.current].map(({ item }) => activityId(item));
      const unique = [...new Set(checked)];
      const chunks = Array.from({ length: Math.max(1, Math.ceil(unique.length / 100)) }, (_, index) => unique.slice(index * 100, (index + 1) * 100));
      const responses = await Promise.all(chunks.map((chunk) => overviewAPI.read(workspaceId, {
        section: "live", actor: "all", limit: 50, cursor: pageParam, watch: chunk.length ? chunk.join(",") : undefined,
      }, signal)));
      return { ...responses[0], checked: unique, watched: responses.flatMap((page) => page.watched), unavailableSources: [...new Set(responses.flatMap((page) => page.unavailableSources))] };
    },
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: Boolean(principalId) && available,
    refetchInterval: available ? pollInterval : false,
    refetchOnWindowFocus: true,
  });

  const recent = useInfiniteQuery({
    queryKey: ["overview", workspaceId, principalId, "recent", filter],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => overviewAPI.read(workspaceId, { section: "recent", ...activityQuery(filter), limit: 8, cursor: pageParam }, signal),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: Boolean(principalId) && available,
    refetchInterval: available ? pollInterval : false,
  });

  // Returning to the tab or reconnecting reads one fresh snapshot at once instead of waiting for the next
  // poll, which is up to 15 seconds away in an idle workspace and cannot rely on the 30 second stale time.
  const wasAvailable = useRef(available);
  const refetchLive = live.refetch;
  const refetchRecent = recent.refetch;
  useEffect(() => {
    if (available && !wasAvailable.current) { void refetchLive(); void refetchRecent(); }
    wasAvailable.current = available;
  }, [available, refetchLive, refetchRecent]);

  useEffect(() => {
    setPollInterval(activityPollInterval({
      loaded: Boolean(live.data && recent.data) && filter === "all",
      liveCount: rows.filter(({ item }) => isActive(item)).length + (live.data?.pages.some((page) => page.items.length > 0 || page.nextCursor) ? 1 : 0),
      recentCount: (recent.data?.pages.reduce((total, page) => total + page.items.length, 0) ?? 0) + (recent.data?.pages.some((page) => page.nextCursor) ? 1 : 0),
    }));
  }, [filter, live.data, recent.data, rows]);

  useEffect(() => {
    if (!live.data) return;
    const pages = live.data.pages;
    const result = reconcileRuns([...rowsRef.current, ...pending.current], pages.flatMap((page) => page.items), pages.flatMap((page) => page.watched), pages.flatMap((page) => page.checked), [...new Set(pages.flatMap((page) => page.unavailableSources))], Date.now());
    const pendingIds = new Set(pending.current.map(({ item }) => activityId(item)));
    pending.current = [...result.existing.filter(({ item }) => pendingIds.has(activityId(item))), ...result.arrivals];
    rowsRef.current = result.existing.filter(({ item }) => !pendingIds.has(activityId(item)));
    setRows(rowsRef.current);
    if (pending.current.length && batchTimer.current === null) {
      batchTimer.current = setTimeout(() => {
        rowsRef.current = insertArrivals(rowsRef.current, pending.current, protectedIds.current);
        pending.current = []; batchTimer.current = null; setRows(rowsRef.current);
      }, 400);
    }
  }, [live.data]);

  useEffect(() => () => { if (batchTimer.current !== null) clearTimeout(batchTimer.current); }, []);
  useEffect(() => {
    if (!available) return;
    const timer = setInterval(() => {
      const at = Date.now(); setNow(at);
      const next = pruneRuns(rowsRef.current, at, protectedIds.current);
      if (next.length !== rowsRef.current.length) { rowsRef.current = next; setRows(next); }
    }, 1_000);
    return () => clearInterval(timer);
  }, [available]);

  return { rows, live, recent, filter, setFilter, online, now, protectedIds };
}
