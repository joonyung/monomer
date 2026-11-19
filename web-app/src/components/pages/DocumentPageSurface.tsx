import { isLoadErrorRetryable, loadErrorMessage } from "@/lib/api/load-error";
import { useState } from "react";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@/lib/navigation";
import {
  Download,
  History,
  Loader2,
} from "lucide-react";
import {
  pageContentAPI,
  type PageContentDocumentResponse,
  type PageContentVersionResponse,
} from "@/api/page-content";
import { PagesApiError, type PageResponse, type PagesTreeResponse } from "@/api/pages";
import { ActorProvenance } from "@/components/ui/ActorProvenance";
import { Button } from "@/components/ui/Button";
import { NativeSelect } from "@/components/ui/Input";
import { ReplaceDocumentContentButton } from "@/components/pages/DocumentPageDialogs";
import { PageActionsButton } from "@/components/pages/PageMutationDialogs";
import { PageShareActions } from "@/components/pages/PageShareActions";
import { PagesErrorState, PagesLoadingState } from "@/components/pages/PagesRouteState";
import { usePagesDirectory } from "@/components/pages/PagesDirectoryProvider";
import {
  getDocumentVersionFixture,
  resolveDocumentContentFixture,
  resolveDocumentSourceFixture,
} from "@/components/pages/documentContentFixtures";
import { DocumentRenderer } from "@/components/pages/DocumentRenderer";
import { findPagePath, findPlacementLocation } from "@/components/pages/pagesTree";
import { queryKeys } from "@/lib/query/keys";
import { reconcileDocumentReplacement } from "@/lib/query/invalidation";
import { formatRelativeTime } from "@/lib/utils/relative-time";
import { webPaths } from "@/lib/web-paths";

function formatLabel(format: PageContentVersionResponse["format"]): string {
  if (format === "MARKDOWN") return "Markdown";
  if (format === "NOTEBOOK") return "Notebook";
  return "HTML";
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function UpdatedLine({ version, principalId }: { version: PageContentVersionResponse; principalId: string | null }) {
  const { kind, id, displayName, agentApp } = version.finalizedBy;
  return (
    <ActorProvenance
      verb="Updated"
      actor={kind === "SYSTEM" ? null : { id, displayName }}
      surface={kind === "AGENT" ? "AGENT" : kind === "USER" ? "WEB" : null}
      agentApp={agentApp}
      principalId={principalId}
      time={formatRelativeTime(version.finalizedAt)}
    />
  );
}

function browserDownload(blob: Blob, filename: string): void {
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = filename;
  anchor.rel = "noopener noreferrer";
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 0);
}

export function DocumentPageSurface({
  page,
  snapshot,
  canEdit,
}: {
  page: PageResponse;
  snapshot: PagesTreeResponse;
  canEdit: boolean;
}) {
  const { workspaceId, principalId, isFixture } = usePagesDirectory();
  const queryClient = useQueryClient();
  const [selectedVersionNo, setSelectedVersionNo] = useState<number | undefined>();
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [fixtureReplacement, setFixtureReplacement] = useState<PageContentDocumentResponse | null>(null);
  const [fixtureReplacementSource, setFixtureReplacementSource] = useState<File | null>(null);

  const contentQuery = useQuery({
    queryKey: queryKeys.pages.documentContent(
      workspaceId ?? "",
      principalId ?? "",
      page.pageId,
      selectedVersionNo ?? null,
    ),
    queryFn: async ({ signal }) => {
      if (isFixture) {
        const fixtureContent = resolveDocumentContentFixture(
          page.pageId,
          selectedVersionNo,
          fixtureReplacement,
        );
        if (!fixtureContent) throw new PagesApiError("Fixture content was not found.", 404, null, null);
        const fixtureSource = await resolveDocumentSourceFixture(
          page.pageId,
          selectedVersionNo,
          fixtureReplacement,
          fixtureReplacementSource,
        );
        if (!fixtureSource) throw new PagesApiError("Fixture source was not found.", 404, null, null);
        return { content: fixtureContent, source: fixtureSource };
      }
      const content = await pageContentAPI.getContent(
        workspaceId as string,
        page.pageId,
        selectedVersionNo,
        signal,
      );
      const source = await pageContentAPI.readVerifiedSource(content, signal);
      return { content, source };
    },
    enabled: Boolean(workspaceId && principalId),
  });
  const historyQuery = useInfiniteQuery({
    queryKey: queryKeys.pages.documentVersions(
      workspaceId ?? "",
      principalId ?? "",
      page.pageId,
      100,
    ),
    queryFn: async ({ pageParam, signal }) => {
      if (isFixture) {
        const fixtureVersions = getDocumentVersionFixture(page.pageId);
        if (!fixtureVersions) throw new PagesApiError("Fixture history was not found.", 404, null, null);
        return {
          ...fixtureVersions,
          items: fixtureReplacement
            ? [
                fixtureReplacement.version,
                ...fixtureVersions.items.filter(
                  (version) =>
                    version.versionNo !== fixtureReplacement.version.versionNo,
                ),
              ]
            : fixtureVersions.items,
        };
      }
      return pageContentAPI.listVersions(
        workspaceId as string,
        page.pageId,
        pageParam,
        100,
        signal,
      );
    },
    initialPageParam: null as number | null,
    getNextPageParam: (lastPage) => lastPage.nextBeforeVersionNo ?? undefined,
    enabled: Boolean(workspaceId && principalId),
  });
  const content = contentQuery.data?.content ?? null;
  const source = contentQuery.data?.source ?? null;
  const persistedVersions = historyQuery.data?.pages.flatMap((history) => history.items) ?? [];
  const versions = fixtureReplacement
    ? [
        fixtureReplacement.version,
        ...persistedVersions.filter((version) => version.versionNo !== fixtureReplacement.version.versionNo),
      ]
    : persistedVersions;
  const nextBeforeVersionNo = historyQuery.hasNextPage
    ? historyQuery.data?.pages.at(-1)?.nextBeforeVersionNo ?? null
    : null;
  const loading = Boolean(workspaceId) && contentQuery.isPending;
  const loadingHistory = Boolean(workspaceId) && historyQuery.isPending;
  const loadingOlderVersions = historyQuery.isFetchingNextPage;
  const error = contentQuery.error ? loadErrorMessage(contentQuery.error, "The document could not be loaded.") : null;
  const errorStatus =
    contentQuery.error instanceof PagesApiError ? contentQuery.error.status : null;
  const historyError = historyQuery.error?.message ?? null;
  const loadContent = () => contentQuery.refetch();
  const loadInitialHistory = () => historyQuery.refetch();
  const loadOlderVersions = () => historyQuery.fetchNextPage();

  const path = findPagePath(snapshot.placements, page.pageId) ?? [page.pageId];
  const breadcrumb = path.slice(0, -1).map((pathPageId) => snapshot.pages.find((candidate) => candidate.pageId === pathPageId))
    .filter((candidate): candidate is PageResponse => candidate !== undefined);
  const location = findPlacementLocation(snapshot.placements, page.pageId);
  const siblingCount = location?.parentPageId === null
    ? snapshot.placements.length
    : location?.parentPageId
      ? findPlacementLocation(snapshot.placements, location.parentPageId)?.placement.children.length ?? 1
      : 1;
  const currentVersion = content?.version;
  const latestVersionNo = versions[0]?.versionNo
    ?? (selectedVersionNo === undefined ? currentVersion?.versionNo : undefined);

  const download = async () => {
    if (!content || !workspaceId || downloading) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      if (!source) throw new Error("The original source is not loaded.");
      browserDownload(source.blob, source.filename);
    } catch (cause) {
      setDownloadError(cause instanceof Error ? cause.message : "The original file could not be downloaded.");
    } finally {
      setDownloading(false);
    }
  };

  const onReplaced = (version: PageContentVersionResponse, sourceFile: File | null) => {
    setSelectedVersionNo(version.versionNo);
    if (!workspaceId || !principalId) return;
    if (isFixture) {
      const nextFixtureContent: PageContentDocumentResponse = {
        title: page.displayTitle,
        version,
        attachmentUrl: `/fixture/${page.pageId}/${version.versionNo}`,
      };
      setFixtureReplacement(nextFixtureContent);
      setFixtureReplacementSource(sourceFile);
      return;
    }
    void reconcileDocumentReplacement(
      queryClient,
      workspaceId,
      principalId,
      page.pageId,
      version.versionNo,
    );
  };

  const displayTitle = content?.title ?? page.displayTitle;

  if (loading && !content) return <PagesLoadingState label="Loading document…" />;
  if (errorStatus === 403) {
    return <PagesErrorState backHref={workspaceId ? webPaths.pages(workspaceId) : undefined} title="Page unavailable" description="You do not have access to this Page." />;
  }
  if (errorStatus === 404) {
    return <PagesErrorState backHref={workspaceId ? webPaths.pages(workspaceId) : undefined} title="Page not found" description="This Page or version is not available in the current workspace." />;
  }
  if (error && !content) {
    return <PagesErrorState backHref={workspaceId ? webPaths.pages(workspaceId) : undefined} title="Document could not be loaded" description={error} onRetry={isLoadErrorRetryable(contentQuery.error) ? () => void loadContent() : undefined} />;
  }
  if (!content || !currentVersion || !source) {
    return <PagesErrorState backHref={workspaceId ? webPaths.pages(workspaceId) : undefined} title="Document unavailable" description="No readable version is available." />;
  }

  return (
    <div className="h-full overflow-y-auto bg-background">
      <div className="mx-auto w-full max-w-[1220px] px-4 py-6 sm:px-7 sm:py-8">
        <nav aria-label="Page breadcrumb" className="mb-4 flex min-w-0 flex-wrap items-center gap-1.5 type-meta text-foreground-secondary">
          <Link href={webPaths.pages(workspaceId as string)} className="rounded-[4px] px-1 py-0.5 hover:text-foreground focus:outline-none">Pages</Link>
          {breadcrumb.map((entry) => (
            <span key={entry.pageId} className="contents">
              <span aria-hidden="true">/</span>
              <Link href={webPaths.page(workspaceId as string, entry.pageId)} className="max-w-[220px] truncate rounded-[4px] px-1 py-0.5 hover:text-foreground focus:outline-none">
                {entry.displayTitle}
              </Link>
            </span>
          ))}
        </nav>

        <header className="mb-6 border-b border-border pb-5">
          <div className="flex min-w-0 flex-col gap-1 sm:flex-row sm:items-baseline sm:justify-between sm:gap-5">
            <h1 className="type-display min-w-0 truncate text-heading" title={displayTitle}>{displayTitle}</h1>
            <span className="flex-none type-meta tabular-nums text-foreground-secondary" title={formatDate(currentVersion.finalizedAt)}>
              <UpdatedLine version={currentVersion} principalId={principalId ?? null} />
            </span>
          </div>
          {page.description ? (
            <p className="mt-2 max-w-[72ch] type-body leading-6 text-foreground-secondary">{page.description}</p>
          ) : null}
          <div className="mt-4 flex flex-wrap items-center justify-end gap-2">
            <PageShareActions />
            <Button variant="ghost" size="sm" pending={downloading} onClick={() => void download()}>
              {!downloading && <Download aria-hidden="true" />}
              Original
            </Button>
            {canEdit && (
              <ReplaceDocumentContentButton
                pageId={page.pageId}
                currentFormat={versions[0]?.format ?? currentVersion.format}
                nextFixtureVersionNo={(latestVersionNo ?? 0) + 1}
                onReplaced={onReplaced}
              />
            )}
            {canEdit && location && (
              <PageActionsButton
                page={page}
                parentPageId={location.parentPageId}
                siblingIndex={location.index}
                siblingCount={siblingCount}
              />
            )}
          </div>
        </header>

        {downloadError && <p role="alert" className="mb-4 rounded-[4px] border border-error/20 bg-error/10 px-3 py-2 type-body text-error">{downloadError}</p>}
        {(currentVersion.format === "NOTEBOOK" || currentVersion.format === "HTML") && (
          <p className="mb-4 rounded-[4px] border border-border bg-surface px-3 py-2 type-meta text-foreground-secondary">
            Monomer does not execute this {formatLabel(currentVersion.format)}. Downloaded originals may contain executable code when opened locally.
          </p>
        )}

        <section className="mb-5 rounded-[6px] border border-border bg-background p-3 lg:hidden" aria-label="Version history">
          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-0 flex-1">
              <NativeSelect
                label="Document version"
                aria-label="Document version"
                value={currentVersion.versionNo}
                disabled={loadingHistory && versions.length === 0}
                onChange={(event) => setSelectedVersionNo(Number(event.target.value))}
              >
                {(versions.length > 0 ? versions : [currentVersion]).map((version) => (
                  <option key={version.versionNo} value={version.versionNo}>
                    Version {version.versionNo}{version.versionNo === latestVersionNo ? " · Latest" : ""} · {formatLabel(version.format)}
                  </option>
                ))}
              </NativeSelect>
            </div>
            {latestVersionNo !== undefined && latestVersionNo !== currentVersion.versionNo && (
              <Button variant="ghost" size="sm" onClick={() => setSelectedVersionNo(latestVersionNo)}>
                Back to latest
              </Button>
            )}
          </div>
          {loadingHistory && (
            <p className="mt-2 inline-flex items-center gap-2 type-meta text-foreground-secondary" aria-live="polite">
              <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
              Loading history…
            </p>
          )}
          {historyError && (
            <div className="mt-2 flex flex-wrap items-center justify-between gap-2 rounded-[4px] border border-error/20 bg-error/10 px-3 py-2">
              <p role="alert" className="type-meta text-error">{historyError}</p>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => void (
                  versions.length > 0 && nextBeforeVersionNo !== null
                    ? loadOlderVersions()
                    : loadInitialHistory()
                )}
              >
                {versions.length > 0 && nextBeforeVersionNo !== null ? "Retry older versions" : "Retry history"}
              </Button>
            </div>
          )}
          {nextBeforeVersionNo !== null && !historyError && (
            <Button
              pending={loadingOlderVersions}
              variant="ghost"
              size="sm"
              className="mt-2"
              onClick={() => void loadOlderVersions()}
            >
              Load older versions
            </Button>
          )}
        </section>

        <div className="grid min-w-0 grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_250px]">
          <main className="min-w-0">
            <DocumentRenderer
              bytes={source.bytes}
              format={currentVersion.format}
              title={displayTitle}
              downloading={downloading}
              onDownload={() => void download()}
            />
          </main>

          <aside className="hidden min-w-0 space-y-4 lg:sticky lg:top-6 lg:block lg:max-h-[calc(100vh-3rem)] lg:self-start lg:overflow-y-auto lg:pr-1" aria-label="Document details">
            <section className="rounded-[6px] border border-border bg-background p-4">
              <div className="flex items-center gap-2 type-body text-heading">
                <History className="h-4 w-4 text-foreground" aria-hidden="true" />
                <h2>Version history</h2>
              </div>
              <ol className="mt-3 space-y-1">
                {versions.map((version) => {
                  const selected = version.versionNo === currentVersion.versionNo;
                  return (
                    <li key={version.versionNo}>
                      <button
                        type="button"
                        aria-current={selected ? "true" : undefined}
                        onClick={() => setSelectedVersionNo(version.versionNo)}
                        className={`w-full rounded-[4px] border px-3 py-2 text-left focus:outline-none ${selected ? "border-border bg-surface" : "border-transparent hover:bg-surface"}`}
                      >
                        <span className="flex items-center justify-between gap-2">
                          <span className="type-body text-heading">Version <span className="tabular-nums">{version.versionNo}</span></span>
                          <span className={`type-status ${selected ? "text-foreground" : "text-foreground-secondary"}`}>
                            {formatLabel(version.format)}{version.versionNo === latestVersionNo ? " · Latest" : ""}
                          </span>
                        </span>
                        <span className={`mt-0.5 block type-status tabular-nums ${selected ? "text-foreground" : "text-foreground-secondary"}`}>
                          {formatDate(version.finalizedAt)}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ol>
              {loadingHistory && (
                <p className="mt-3 inline-flex items-center gap-2 type-meta text-foreground-secondary" aria-live="polite">
                  <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
                  Loading history…
                </p>
              )}
              {historyError && (
                <div className="mt-3 rounded-[4px] border border-error/20 bg-error/10 px-3 py-2">
                  <p role="alert" className="type-meta text-error">{historyError}</p>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="mt-2"
                    onClick={() => void (
                      versions.length > 0 && nextBeforeVersionNo !== null
                        ? loadOlderVersions()
                        : loadInitialHistory()
                    )}
                  >
                    {versions.length > 0 && nextBeforeVersionNo !== null ? "Retry older versions" : "Retry history"}
                  </Button>
                </div>
              )}
              {nextBeforeVersionNo !== null && !historyError && (
                <Button
                  pending={loadingOlderVersions}
                  variant="ghost"
                  size="sm"
                  className="mt-3 w-full"
                  onClick={() => void loadOlderVersions()}
                >
                  Load older versions
                </Button>
              )}
            </section>

          </aside>
        </div>
      </div>
    </div>
  );
}
