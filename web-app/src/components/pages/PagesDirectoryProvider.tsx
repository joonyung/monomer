import { isLoadErrorRetryable, loadErrorMessage } from "@/lib/api/load-error";
import { hasWorkspaceRole } from "@/lib/auth/roles";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  PagesApiError,
  PagesRequestAbortedError,
  pagesAPI,
  type PageDirectoryChangeResponse,
  type PageMutationResponse,
  type PagePlacementResponse,
  type PageResponse,
  type PagesTreeResponse,
} from "@/api/pages";
import {
  buildPageMovePatch,
  buildPageReorderPatch,
  findPlacementLocation,
  indexPages,
} from "@/components/pages/pagesTree";
import { usePrincipalId, useWorkspaceId } from "@/hooks/use-workspace";
import {
  useWorkspaceAccess,
} from "@/components/auth/WorkspaceAccessProvider";
import type { WorkspaceAccess } from "@/lib/auth/types";
import { queryKeys } from "@/lib/query/keys";

export type PagesDirectoryStatus = "idle" | "loading" | "ready" | "error";
export type PagesDirectoryLifecycle = "inactive" | "live" | "one-shot";

export interface PagesDirectoryFixture {
  snapshot: PagesTreeResponse;
  workspaceAccess: WorkspaceAccess;
  status?: PagesDirectoryStatus;
  error?: string | null;
  streamConnected?: boolean;
}

export interface CreateFolderInput {
  title: string;
  description?: string | null;
  parentPageId?: string | null;
  index?: number | null;
}

export interface CreateDashboardInput extends CreateFolderInput {
  dashboardId: string;
}

export interface CreateDocumentInput {
  title: string;
  description?: string | null;
  uploadRef: string;
  parentPageId?: string | null;
  index?: number | null;
}

export interface UpdatePageInput {
  title: string | null;
  description: string | null;
}

export interface PagesDirectoryContextValue {
  workspaceId: string | null;
  principalId: string | null;
  snapshot: PagesTreeResponse | null;
  pagesById: ReadonlyMap<string, PageResponse>;
  status: PagesDirectoryStatus;
  error: string | null;
  errorRetryable?: boolean;
  streamConnected: boolean;
  isFixture: boolean;
  adoptSnapshot: (snapshot: PagesTreeResponse) => void;
  retry: () => void;
  createDashboard: (input: CreateDashboardInput) => Promise<PageMutationResponse>;
  createFolder: (input: CreateFolderInput) => Promise<PageMutationResponse>;
  createDocument: (input: CreateDocumentInput) => Promise<PageMutationResponse>;
  updatePage: (pageId: string, input: UpdatePageInput) => Promise<PageMutationResponse>;
  deletePage: (pageId: string) => Promise<PageMutationResponse>;
  movePage: (
    pageId: string,
    targetParentPageId: string | null,
    targetIndex: number,
  ) => Promise<PageMutationResponse | null>;
  reorderPage: (pageId: string, targetIndex: number) => Promise<PageMutationResponse | null>;
}

interface PagesDirectoryState {
  workspaceId: string | null;
  principalId: string | null;
  snapshot: PagesTreeResponse | null;
  status: PagesDirectoryStatus;
  error: string | null;
  errorRetryable?: boolean;
  streamConnected: boolean;
}

interface RetryablePagesMutation {
  execute: () => Promise<PageMutationResponse>;
}

interface OneShotDirectoryLoad {
  workspaceId: string;
  principalId: string;
  reloadGeneration: number;
  abortController: AbortController;
  snapshotPromise: Promise<PagesTreeResponse>;
  abortTimeout: ReturnType<typeof setTimeout> | null;
}

function assertEditablePageTarget(
  access: WorkspaceAccess | null,
  _snapshot: PagesTreeResponse,
  _pageId: string | null,
): void {
  if (!hasWorkspaceRole(access, "EXPLORER")) {
    throw new Error("Requires Explorer role.");
  }
}

const PagesDirectoryContext = createContext<PagesDirectoryContextValue | null>(null);
const FIXTURE_ACTOR = { kind: "USER" as const, id: "pages-fixture-user" };
const RECONNECT_DELAY_MILLIS = 1_000;

function mutationWorkspaceMatches(
  scheduledWorkspaceId: string | null,
  scheduledWorkspaceGeneration: number,
  activeWorkspaceId: string | null,
  activeWorkspaceGeneration: number,
): boolean {
  return scheduledWorkspaceId !== null &&
    scheduledWorkspaceId === activeWorkspaceId &&
    scheduledWorkspaceGeneration === activeWorkspaceGeneration;
}

function createRetryableMutationExecution(
  snapshot: PagesTreeResponse,
  requestId: string,
  mutation: (
    snapshot: PagesTreeResponse,
    requestId: string,
  ) => Promise<PageMutationResponse>,
): () => Promise<PageMutationResponse> {
  return () => mutation(snapshot, requestId);
}

export function reconcilePagesSnapshot(
  current: PagesTreeResponse | null,
  incoming: PagesTreeResponse,
): PagesTreeResponse {
  if (current?.workspaceId === incoming.workspaceId && current.revision > incoming.revision) {
    return current;
  }
  return incoming;
}

function initialState(fixture: PagesDirectoryFixture | undefined): PagesDirectoryState {
  if (!fixture) {
    return {
      workspaceId: null,
      principalId: null,
      snapshot: null,
      status: "idle",
      error: null,
      streamConnected: false,
    };
  }
  return {
    workspaceId: fixture.snapshot.workspaceId,
    principalId: FIXTURE_ACTOR.id,
    snapshot: fixture.snapshot,
    status: fixture.status ?? "ready",
    error: fixture.error ?? null,
    streamConnected: fixture.streamConnected ?? true,
  };
}

function visibleStateForScope(
  state: PagesDirectoryState,
  workspaceId: string | null,
  principalId: string | null,
): PagesDirectoryState {
  if (state.workspaceId === workspaceId && state.principalId === principalId) return state;
  return {
    workspaceId,
    principalId,
    snapshot: null,
    status: workspaceId && principalId ? "loading" : "idle",
    error: null,
    streamConnected: false,
  };
}

export function pagesDirectoryResponseMatchesScope(
  incomingWorkspaceId: string,
  requestPrincipalId: string,
  activeWorkspaceId: string | null,
  activePrincipalId: string | null,
): boolean {
  return incomingWorkspaceId === activeWorkspaceId
    && requestPrincipalId === activePrincipalId;
}

function newRequestId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `pages-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function insertFixturePlacement(
  placements: PagePlacementResponse[],
  parentPageId: string | null,
  index: number | null,
  pageId: string,
): PagePlacementResponse[] {
  const nextPlacement = { pageId, children: [] };
  if (parentPageId === null) {
    return insertFixtureSibling(placements, index, nextPlacement);
  }
  let parentFound = false;
  const next = placements.map((placement) => {
    if (placement.pageId === parentPageId) {
      parentFound = true;
      return {
        ...placement,
        children: insertFixtureSibling(placement.children, index, nextPlacement),
      };
    }
    return {
      ...placement,
      children: insertFixturePlacementWithin(
        placement.children,
        parentPageId,
        index,
        nextPlacement,
        () => { parentFound = true; },
      ),
    };
  });
  if (!parentFound) throw new Error("Target parent was not found.");
  return next;
}

function insertFixturePlacementWithin(
  placements: PagePlacementResponse[],
  parentPageId: string,
  index: number | null,
  nextPlacement: PagePlacementResponse,
  markFound: () => void,
): PagePlacementResponse[] {
  return placements.map((placement) => {
    if (placement.pageId === parentPageId) {
      markFound();
      return {
        ...placement,
        children: insertFixtureSibling(placement.children, index, nextPlacement),
      };
    }
    return {
      ...placement,
      children: insertFixturePlacementWithin(
        placement.children,
        parentPageId,
        index,
        nextPlacement,
        markFound,
      ),
    };
  });
}

function insertFixtureSibling(
  placements: PagePlacementResponse[],
  index: number | null,
  placement: PagePlacementResponse,
): PagePlacementResponse[] {
  const insertionIndex = index ?? placements.length;
  if (!Number.isInteger(insertionIndex) || insertionIndex < 0 || insertionIndex > placements.length) {
    throw new Error("Target index is outside the sibling list.");
  }
  const next = [...placements];
  next.splice(insertionIndex, 0, placement);
  return next;
}

function deleteFixturePlacement(
  placements: PagePlacementResponse[],
  pageId: string,
): { placements: PagePlacementResponse[]; found: boolean } {
  let found = false;
  const next: PagePlacementResponse[] = [];
  for (const placement of placements) {
    if (placement.pageId === pageId) {
      found = true;
      if (placement.children.length > 0) {
        throw new Error("Folder must be empty before it can be deleted.");
      }
      continue;
    }
    const nested = deleteFixturePlacement(placement.children, pageId);
    found = found || nested.found;
    next.push({ ...placement, children: nested.placements });
  }
  return { placements: next, found };
}

function requireFixtureFolderParent(
  snapshot: PagesTreeResponse,
  parentPageId: string | null,
): void {
  if (parentPageId === null) return;
  const parent = snapshot.pages.find((page) => page.pageId === parentPageId);
  if (!parent) throw new Error("Target parent was not found.");
  if (parent.kind !== "FOLDER") throw new Error("Target parent must be a Folder.");
}

function fixtureMutationResponse(
  current: PagesTreeResponse,
  pages: PageResponse[],
  placements: PagePlacementResponse[],
  change: PageDirectoryChangeResponse,
  changedAt: string,
): PageMutationResponse {
  const snapshot: PagesTreeResponse = {
    ...current,
    revision: current.revision + 1,
    pages,
    placements,
    updatedAt: changedAt,
    updatedBy: FIXTURE_ACTOR,
  };
  return {
    result: "APPLIED",
    snapshot,
    change,
  };
}

function waitForReconnect(signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const onAbort = () => {
      clearTimeout(timeout);
      resolve();
    };
    const timeout = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, RECONNECT_DELAY_MILLIS);
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

export function PagesDirectoryProvider({
  children,
  fixture,
  lifecycle,
}: {
  children: ReactNode;
  fixture?: PagesDirectoryFixture;
  lifecycle: PagesDirectoryLifecycle;
}) {
  const selectedWorkspaceId = useWorkspaceId();
  const selectedPrincipalId = usePrincipalId();
  const workspaceId = fixture?.snapshot.workspaceId ?? selectedWorkspaceId;
  const principalId = fixture ? FIXTURE_ACTOR.id : selectedPrincipalId;
  const workspaceAccess = useWorkspaceAccess();
  const queryClient = useQueryClient();
  const currentWorkspaceAccess = workspaceAccess.workspaceId === workspaceId
    ? workspaceAccess.access
    : null;
  const [state, setState] = useState<PagesDirectoryState>(() => initialState(fixture));
  const [reloadGeneration, setReloadGeneration] = useState(0);
  const snapshotRef = useRef<PagesTreeResponse | null>(fixture?.snapshot ?? null);
  const snapshotPrincipalIdRef = useRef<string | null>(fixture ? FIXTURE_ACTOR.id : null);
  const accessRef = useRef<WorkspaceAccess | null>(currentWorkspaceAccess);
  const activeWorkspaceIdRef = useRef<string | null>(workspaceId);
  const activePrincipalIdRef = useRef<string | null>(principalId);
  const observedQueryScopeRef = useRef(`${workspaceId ?? ""}:${principalId ?? ""}`);
  const workspaceGenerationRef = useRef(0);
  const mutationQueueRef = useRef<Promise<void>>(Promise.resolve());
  const retryableMutationsRef = useRef(new Map<string, RetryablePagesMutation>());
  const oneShotLoadRef = useRef<OneShotDirectoryLoad | null>(null);
  const queryScope = `${workspaceId ?? ""}:${principalId ?? ""}`;
  if (observedQueryScopeRef.current !== queryScope) {
    observedQueryScopeRef.current = queryScope;
    workspaceGenerationRef.current += 1;
    retryableMutationsRef.current.clear();
  }
  activeWorkspaceIdRef.current = workspaceId;
  activePrincipalIdRef.current = principalId;
  accessRef.current = currentWorkspaceAccess;

  const adoptSnapshot = useCallback((
    incoming: PagesTreeResponse,
    expectedPrincipalId: string,
  ) => {
    if (
      !pagesDirectoryResponseMatchesScope(
        incoming.workspaceId,
        expectedPrincipalId,
        activeWorkspaceIdRef.current,
        activePrincipalIdRef.current,
      )
    ) return;
    const reconciled = reconcilePagesSnapshot(snapshotRef.current, incoming);
    snapshotRef.current = reconciled;
    snapshotPrincipalIdRef.current = expectedPrincipalId;
    queryClient.setQueryData(
      queryKeys.pages.directory(reconciled.workspaceId, expectedPrincipalId),
      reconciled,
    );
    setState((current) => ({
      ...current,
      workspaceId: reconciled.workspaceId,
      principalId: expectedPrincipalId,
      snapshot: reconciled,
      status: "ready",
      error: null,
    }));
  }, [queryClient]);

  const fetchTree = useCallback(
    (
      requestedWorkspaceId: string,
      signal?: AbortSignal,
    ) => pagesAPI.getTree(requestedWorkspaceId, signal),
    [],
  );

  const retry = useCallback(() => {
    if (fixture) {
      setState((current) => ({ ...current, error: null, status: "ready" }));
      return;
    }
    setReloadGeneration((generation) => generation + 1);
  }, [fixture]);

  useEffect(() => {
    if (!workspaceId || !principalId) {
      snapshotRef.current = null;
      snapshotPrincipalIdRef.current = null;
      setState(initialState(undefined));
      return;
    }
    if (fixture) {
      snapshotRef.current = fixture.snapshot;
      snapshotPrincipalIdRef.current = FIXTURE_ACTOR.id;
      setState(initialState(fixture));
      return;
    }

    if (lifecycle === "inactive") return;

    const existingSnapshot = snapshotRef.current?.workspaceId === workspaceId
      && snapshotPrincipalIdRef.current === principalId
      ? snapshotRef.current
      : null;
    if (!existingSnapshot) {
      snapshotRef.current = null;
      snapshotPrincipalIdRef.current = null;
    }
    setState({
      workspaceId,
      principalId,
      snapshot: existingSnapshot,
      status: existingSnapshot ? "ready" : "loading",
      error: null,
      streamConnected: false,
    });

    if (lifecycle === "one-shot") {
      let load = oneShotLoadRef.current;
      if (
        load &&
        (load.workspaceId !== workspaceId ||
          load.principalId !== principalId ||
          load.reloadGeneration !== reloadGeneration)
      ) {
        if (load.abortTimeout) clearTimeout(load.abortTimeout);
        load.abortController.abort();
        oneShotLoadRef.current = null;
        load = null;
      }
      if (!load) {
        const abortController = new AbortController();
        load = {
          workspaceId,
          principalId,
          reloadGeneration,
          abortController,
          snapshotPromise: fetchTree(
            workspaceId,
            abortController.signal,
          ),
          abortTimeout: null,
        };
        oneShotLoadRef.current = load;
      } else if (load.abortTimeout) {
        clearTimeout(load.abortTimeout);
        load.abortTimeout = null;
      }

      let active = true;
      const settleOneShot = async () => {
        const snapshotResult = await load.snapshotPromise.then(
          (snapshot) => ({ status: "fulfilled" as const, value: snapshot }),
          (reason: unknown) => ({ status: "rejected" as const, reason }),
        );
        if (!active) return;
        if (snapshotResult.status === "fulfilled") {
          adoptSnapshot(snapshotResult.value, load.principalId);
        } else if (snapshotRef.current === null) {
          setState((current) => ({
            ...current,
            status: "error",
            error: loadErrorMessage(snapshotResult.reason, "Pages could not be loaded."),
            errorRetryable: isLoadErrorRetryable(snapshotResult.reason),
          }));
        }
      };
      void settleOneShot();

      return () => {
        active = false;
        const releasedLoad = load;
        releasedLoad.abortTimeout = setTimeout(() => {
          if (oneShotLoadRef.current !== releasedLoad) return;
          releasedLoad.abortController.abort();
          oneShotLoadRef.current = null;
        }, 0);
      };
    }

    const abortController = new AbortController();
    const signal = abortController.signal;

    const refreshSnapshot = async (): Promise<void> => {
      await Promise.resolve();
      if (signal.aborted) return;
      const snapshot = await fetchTree(workspaceId, signal);
      if (!signal.aborted) adoptSnapshot(snapshot, principalId);
    };
    const stream = async (): Promise<void> => {
      while (!signal.aborted) {
        const connectionAbortController = new AbortController();
        const abortConnection = () => connectionAbortController.abort();
        signal.addEventListener("abort", abortConnection, { once: true });
        try {
          await pagesAPI.watchTree(workspaceId, {
            onSnapshot: (snapshot) => {
              if (signal.aborted) return;
              adoptSnapshot(snapshot, principalId);
              setState((current) => ({ ...current, streamConnected: true }));
            },
          }, connectionAbortController.signal);
        } catch {
          if (signal.aborted) return;
        } finally {
          signal.removeEventListener("abort", abortConnection);
        }
        if (signal.aborted) return;
        setState((current) => ({ ...current, streamConnected: false }));
        await refreshSnapshot().catch(() => undefined);
        await waitForReconnect(signal);
      }
    };
    const load = async (): Promise<void> => {
      const snapshotResult = await refreshSnapshot().then(
        () => ({ status: "fulfilled" as const }),
        (reason: unknown) => ({ status: "rejected" as const, reason }),
      );
      if (signal.aborted) return;
      if (
        snapshotResult.status === "rejected" &&
        !(snapshotResult.reason instanceof PagesRequestAbortedError) &&
        snapshotRef.current === null
      ) {
        setState((current) => ({
          ...current,
          status: "error",
          error: loadErrorMessage(snapshotResult.reason, "Pages could not be loaded."),
          errorRetryable: isLoadErrorRetryable(snapshotResult.reason),
        }));
      }
      void stream();
    };
    void load();
    return () => abortController.abort();
  }, [
    adoptSnapshot,
    fetchTree,
    fixture,
    lifecycle,
    principalId,
    reloadGeneration,
    workspaceId,
  ]);

  const recoverConflict = useCallback(async (error: unknown): Promise<void> => {
    if (!(error instanceof PagesApiError)) return;
    if (error.problem?.code !== "REVISION_CONFLICT") return;
    if (error.problem.current && principalId) {
      adoptSnapshot(error.problem.current, principalId);
    }
    if (!fixture && workspaceId && principalId) {
      await fetchTree(workspaceId)
        .then((snapshot) => adoptSnapshot(snapshot, principalId))
        .catch(() => undefined);
    }
  }, [adoptSnapshot, fetchTree, fixture, principalId, workspaceId]);

  const enqueueMutation = useCallback(<T,>(mutation: () => Promise<T>): Promise<T> => {
    const scheduledWorkspaceId = activeWorkspaceIdRef.current;
    const scheduledWorkspaceGeneration = workspaceGenerationRef.current;
    const guardedMutation = (): Promise<T> => {
      if (!mutationWorkspaceMatches(
        scheduledWorkspaceId,
        scheduledWorkspaceGeneration,
        activeWorkspaceIdRef.current,
        workspaceGenerationRef.current,
      )) {
        return Promise.reject(new Error("PagesDirectory workspace changed before the mutation started."));
      }
      return mutation();
    };
    const result = mutationQueueRef.current.then(guardedMutation, guardedMutation);
    mutationQueueRef.current = result.then(() => undefined, () => undefined);
    return result;
  }, []);

  const requireLoadedSnapshot = useCallback((): PagesTreeResponse => {
    const snapshot = snapshotRef.current;
    if (!snapshot) throw new Error("PagesDirectory is not loaded.");
    if (snapshot.workspaceId !== activeWorkspaceIdRef.current) {
      throw new Error("PagesDirectory belongs to a different workspace.");
    }
    return snapshot;
  }, []);

  const requireEditableSnapshot = useCallback((pageId: string | null): PagesTreeResponse => {
    const snapshot = requireLoadedSnapshot();
    assertEditablePageTarget(accessRef.current, snapshot, pageId);
    return snapshot;
  }, [requireLoadedSnapshot]);

  const runServerMutation = useCallback(async (
    mutationIntent: string,
    mutation: (
      snapshot: PagesTreeResponse,
      requestId: string,
    ) => Promise<PageMutationResponse>,
  ): Promise<PageMutationResponse> => {
    const snapshot = requireLoadedSnapshot();
    const retryKey = JSON.stringify([
      snapshot.workspaceId,
      workspaceGenerationRef.current,
      mutationIntent,
    ]);
    const retryable = retryableMutationsRef.current.get(retryKey);
    const execute = retryable?.execute ?? createRetryableMutationExecution(
      snapshot,
      newRequestId(),
      mutation,
    );
    try {
      const response = await execute();
      retryableMutationsRef.current.delete(retryKey);
      if (principalId) adoptSnapshot(response.snapshot, principalId);
      return response;
    } catch (error) {
      if (error instanceof PagesApiError && error.status === 0) {
        retryableMutationsRef.current.set(retryKey, { execute });
      } else {
        retryableMutationsRef.current.delete(retryKey);
      }
      await recoverConflict(error);
      throw error;
    }
  }, [adoptSnapshot, principalId, recoverConflict, requireLoadedSnapshot]);

  const createFolder = useCallback((input: CreateFolderInput): Promise<PageMutationResponse> =>
    enqueueMutation(async () => {
      const snapshot = requireEditableSnapshot(input.parentPageId ?? null);
      if (!fixture) {
        const normalizedInput = {
          title: input.title,
          description: input.description ?? null,
          parentPageId: input.parentPageId ?? null,
          index: input.index ?? null,
        };
        return runServerMutation(
          JSON.stringify(["create-folder", normalizedInput]),
          (current, requestId) => pagesAPI.createPage(current.workspaceId, {
            kind: "FOLDER",
            ...normalizedInput,
            baseRevision: current.revision,
            requestId,
          }),
        );
      }
      requireFixtureFolderParent(snapshot, input.parentPageId ?? null);
      const pageId = newRequestId();
      const changedAt = new Date().toISOString();
      const page: PageResponse = {
        pageId,
        kind: "FOLDER",
        documentFormat: null,
        dashboardId: null,
        title: input.title,
        displayTitle: input.title,
        description: input.description ?? null,
        createdAt: changedAt,
        updatedAt: changedAt,
      };
      const response = fixtureMutationResponse(
        snapshot,
        [...snapshot.pages, page],
        insertFixturePlacement(
          snapshot.placements,
          input.parentPageId ?? null,
          input.index ?? null,
          pageId,
        ),
        {
          type: "folder-created",
          pageId,
          appliedOperationCount: null,
        },
        changedAt,
      );
      if (principalId) adoptSnapshot(response.snapshot, principalId);
      return response;
    }),
  [adoptSnapshot, enqueueMutation, fixture, principalId, requireEditableSnapshot, runServerMutation]);

  const createDashboard = useCallback((input: CreateDashboardInput): Promise<PageMutationResponse> =>
    enqueueMutation(async () => {
      const snapshot = requireEditableSnapshot(input.parentPageId ?? null);
      if (!fixture) {
        const normalizedInput = {
          dashboardId: input.dashboardId,
          title: input.title,
          description: input.description ?? null,
          parentPageId: input.parentPageId ?? null,
          index: input.index ?? null,
        };
        return runServerMutation(
          JSON.stringify(["create-dashboard", normalizedInput]),
          (current, requestId) => pagesAPI.createPage(current.workspaceId, {
            kind: "DASHBOARD",
            ...normalizedInput,
            baseRevision: current.revision,
            requestId,
          }),
        );
      }
      requireFixtureFolderParent(snapshot, input.parentPageId ?? null);
      const pageId = newRequestId();
      const changedAt = new Date().toISOString();
      const page: PageResponse = {
        pageId,
        kind: "DASHBOARD",
        documentFormat: null,
        dashboardId: input.dashboardId,
        title: input.title,
        displayTitle: input.title,
        description: input.description ?? null,
        createdAt: changedAt,
        updatedAt: changedAt,
      };
      const response = fixtureMutationResponse(
        snapshot,
        [...snapshot.pages, page],
        insertFixturePlacement(
          snapshot.placements,
          input.parentPageId ?? null,
          input.index ?? null,
          pageId,
        ),
        {
          type: "dashboard-page-created",
          pageId,
          appliedOperationCount: null,
        },
        changedAt,
      );
      if (principalId) adoptSnapshot(response.snapshot, principalId);
      return response;
    }),
  [adoptSnapshot, enqueueMutation, fixture, principalId, requireEditableSnapshot, runServerMutation]);

  const createDocument = useCallback((input: CreateDocumentInput): Promise<PageMutationResponse> =>
    enqueueMutation(async () => {
      const snapshot = requireEditableSnapshot(input.parentPageId ?? null);
      if (!fixture) {
        const normalizedInput = {
          title: input.title,
          description: input.description ?? null,
          uploadRef: input.uploadRef,
          parentPageId: input.parentPageId ?? null,
          index: input.index ?? null,
        };
        return runServerMutation(
          JSON.stringify(["create-document", normalizedInput]),
          (current, requestId) => pagesAPI.createPage(current.workspaceId, {
            kind: "DOCUMENT",
            ...normalizedInput,
            baseRevision: current.revision,
            requestId,
          }),
        );
      }
      requireFixtureFolderParent(snapshot, input.parentPageId ?? null);
      const pageId = newRequestId();
      const changedAt = new Date().toISOString();
      const page: PageResponse = {
        pageId,
        kind: "DOCUMENT",
        documentFormat: null,
        dashboardId: null,
        title: input.title,
        displayTitle: input.title,
        description: input.description ?? null,
        createdAt: changedAt,
        updatedAt: changedAt,
      };
      const response = fixtureMutationResponse(
        snapshot,
        [...snapshot.pages, page],
        insertFixturePlacement(
          snapshot.placements,
          input.parentPageId ?? null,
          input.index ?? null,
          pageId,
        ),
        {
          type: "document-page-created",
          pageId,
          appliedOperationCount: null,
        },
        changedAt,
      );
      if (principalId) adoptSnapshot(response.snapshot, principalId);
      return response;
    }),
  [adoptSnapshot, enqueueMutation, fixture, principalId, requireEditableSnapshot, runServerMutation]);

  const updatePage = useCallback((
    pageId: string,
    input: UpdatePageInput,
  ): Promise<PageMutationResponse> => enqueueMutation(async () => {
    const snapshot = requireEditableSnapshot(pageId);
    if (!fixture) {
      return runServerMutation(
        JSON.stringify(["update-page", pageId, input]),
        (current, requestId) => pagesAPI.updatePage(
          current.workspaceId,
          pageId,
          {
            title: input.title,
            description: input.description,
            baseRevision: current.revision,
            requestId,
          },
        ),
      );
    }
    const existingPage = snapshot.pages.find((page) => page.pageId === pageId);
    if (!existingPage) throw new Error("Page was not found.");
    if (input.title === null) {
      throw new Error("Page title is required.");
    }
    const changedAt = new Date().toISOString();
    const updatedPage: PageResponse = {
      ...existingPage,
      title: input.title,
      displayTitle: input.title,
      description: input.description,
      updatedAt: changedAt,
    };
    const response = fixtureMutationResponse(
      snapshot,
      snapshot.pages.map((page) => page.pageId === pageId ? updatedPage : page),
      snapshot.placements,
      {
        type: "navigation-metadata-updated",
        pageId,
        appliedOperationCount: null,
      },
      changedAt,
    );
    if (principalId) adoptSnapshot(response.snapshot, principalId);
    return response;
  }), [adoptSnapshot, enqueueMutation, fixture, principalId, requireEditableSnapshot, runServerMutation]);

  const deletePage = useCallback((pageId: string): Promise<PageMutationResponse> =>
    enqueueMutation(async () => {
      const snapshot = requireEditableSnapshot(pageId);
      if (!fixture) {
        return runServerMutation(
          JSON.stringify(["delete-page", pageId]),
          (current, requestId) => pagesAPI.deletePage(
            current.workspaceId,
            pageId,
            { baseRevision: current.revision, requestId },
          ),
        );
      }
      const deletion = deleteFixturePlacement(snapshot.placements, pageId);
      if (!deletion.found) throw new Error("Page was not found.");
      const changedAt = new Date().toISOString();
      const response = fixtureMutationResponse(
        snapshot,
        snapshot.pages.filter((page) => page.pageId !== pageId),
        deletion.placements,
        {
          type: "page-deleted",
          pageId,
          appliedOperationCount: null,
        },
        changedAt,
      );
      if (principalId) adoptSnapshot(response.snapshot, principalId);
      return response;
    }),
  [adoptSnapshot, enqueueMutation, fixture, principalId, requireEditableSnapshot, runServerMutation]);

  const runPlacementMutation = useCallback((
    pageId: string,
    targetParentPageId: string | null,
    targetIndex: number,
    sameParent: boolean,
  ): Promise<PageMutationResponse | null> => enqueueMutation(async () => {
    const snapshot = requireEditableSnapshot(pageId);
    const destinationParentPageId = sameParent
      ? findPlacementLocation(snapshot.placements, pageId)?.parentPageId ?? null
      : targetParentPageId;
    assertEditablePageTarget(accessRef.current, snapshot, destinationParentPageId);
    if (targetParentPageId !== null) {
      const targetParent = snapshot.pages.find((page) => page.pageId === targetParentPageId);
      if (targetParent?.kind !== "FOLDER") throw new Error("Target parent must be a Folder.");
    }
    const move = sameParent
      ? buildPageReorderPatch(snapshot.placements, pageId, targetIndex)
      : buildPageMovePatch(snapshot.placements, pageId, targetParentPageId, targetIndex);
    if (!move.changed) return null;
    if (!fixture) {
      return runServerMutation(
        JSON.stringify([
          sameParent ? "reorder-page" : "move-page",
          pageId,
          targetParentPageId,
          targetIndex,
        ]),
        (current, requestId) => pagesAPI.patchTree(current.workspaceId, {
          baseRevision: current.revision,
          requestId,
          patch: move.patch,
        }),
      );
    }
    const changedAt = new Date().toISOString();
    const response = fixtureMutationResponse(
      snapshot,
      snapshot.pages,
      move.placements,
      {
        type: "placements-patched",
        pageId: null,
        appliedOperationCount: move.patch.length,
      },
      changedAt,
    );
    if (principalId) adoptSnapshot(response.snapshot, principalId);
    return response;
  }), [adoptSnapshot, enqueueMutation, fixture, principalId, requireEditableSnapshot, runServerMutation]);

  const movePage = useCallback((
    pageId: string,
    targetParentPageId: string | null,
    targetIndex: number,
  ) => runPlacementMutation(pageId, targetParentPageId, targetIndex, false), [runPlacementMutation]);

  const reorderPage = useCallback((pageId: string, targetIndex: number) =>
    runPlacementMutation(pageId, null, targetIndex, true), [runPlacementMutation]);

  const visibleState = visibleStateForScope(state, workspaceId, principalId);
  const adoptCurrentSnapshot = useCallback((incoming: PagesTreeResponse) => {
    if (principalId) adoptSnapshot(incoming, principalId);
  }, [adoptSnapshot, principalId]);
  const pagesById = useMemo(
    () => indexPages(visibleState.snapshot?.pages ?? []),
    [visibleState.snapshot],
  );
  const context = useMemo<PagesDirectoryContextValue>(() => ({
    workspaceId: visibleState.workspaceId,
    principalId,
    snapshot: visibleState.snapshot,
    pagesById,
    status: visibleState.status,
    error: visibleState.error,
    errorRetryable: visibleState.errorRetryable ?? true,
    streamConnected: visibleState.streamConnected,
    isFixture: fixture !== undefined,
    adoptSnapshot: adoptCurrentSnapshot,
    retry,
    createFolder,
    createDashboard,
    createDocument,
    updatePage,
    deletePage,
    movePage,
    reorderPage,
  }), [
    adoptCurrentSnapshot,
    createFolder,
    createDashboard,
    createDocument,
    deletePage,
    fixture,
    movePage,
    pagesById,
    principalId,
    reorderPage,
    retry,
    visibleState,
    updatePage,
  ]);

  return (
    <PagesDirectoryContext.Provider value={context}>
      {children}
    </PagesDirectoryContext.Provider>
  );
}

export function usePagesDirectory(): PagesDirectoryContextValue {
  const context = useOptionalPagesDirectory();
  if (!context) throw new Error("usePagesDirectory must be used within PagesDirectoryProvider.");
  return context;
}

export function useOptionalPagesDirectory(): PagesDirectoryContextValue | null {
  return useContext(PagesDirectoryContext);
}

export const _internal = {
  assertEditablePageTarget,
  createRetryableMutationExecution,
  deleteFixturePlacement,
  fixtureMutationResponse,
  insertFixturePlacement,
  mutationWorkspaceMatches,
  visibleStateForScope,
};
