import { Link } from "@/lib/navigation";
import { PageRoute } from "@/components/pages/PageRoute";
import { PagesRootRoute } from "@/components/pages/PagesRootRoute";
import { PagesErrorState, PagesLoadingState } from "@/components/pages/PagesRouteState";
import {
  PAGES_FIXTURE_HTML_PAGE_ID,
  PAGES_FIXTURE_MARKDOWN_PAGE_ID,
  PAGES_FIXTURE_NESTED_FOLDER_ID,
  PAGES_FIXTURE_NOTEBOOK_PAGE_ID,
} from "@/components/pages/pagesFixtures";

export type PagesDemoSurface =
  | "root"
  | "folder"
  | "markdown"
  | "notebook"
  | "html"
  | "empty-editor"
  | "empty-member"
  | "depth"
  | "dense"
  | "loading"
  | "error"
  | "not-found";

export function PagesDemoExperience({
  surface,
  pageId,
  showControls,
}: {
  surface: PagesDemoSurface;
  pageId?: string;
  showControls: boolean;
}) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      {showControls && <PagesDemoControls surface={surface} />}
      <div className="min-h-0 flex-1">
        <PagesDemoSurfaceContent surface={surface} pageId={pageId} />
      </div>
    </div>
  );
}

function PagesDemoControls({ surface }: { surface: PagesDemoSurface }) {
  const controls: Array<{ surface: PagesDemoSurface; label: string; href?: string }> = [
    { surface: "root", label: "Root" },
    { surface: "folder", label: "Deep folder", href: `/dev/pages-demo/${PAGES_FIXTURE_NESTED_FOLDER_ID}` },
    { surface: "markdown", label: "Markdown", href: `/dev/pages-demo/${PAGES_FIXTURE_MARKDOWN_PAGE_ID}` },
    { surface: "notebook", label: "Notebook", href: `/dev/pages-demo/${PAGES_FIXTURE_NOTEBOOK_PAGE_ID}` },
    { surface: "html", label: "HTML", href: `/dev/pages-demo/${PAGES_FIXTURE_HTML_PAGE_ID}` },
    { surface: "empty-editor", label: "Empty editor" },
    { surface: "empty-member", label: "Empty member" },
    { surface: "depth", label: "Depth 6" },
    { surface: "dense", label: "500 Pages" },
  ];
  return (
    <nav aria-label="Pages fixture controls" className="flex flex-shrink-0 flex-wrap items-center gap-1 border-b border-border bg-background px-3 py-2">
      <span className="mr-2 type-status text-foreground-secondary">Fixture</span>
      {controls.map((control) => (
        <Link
          key={`${control.surface}-${control.label}`}
          href={control.href ?? `/dev/pages-demo?surface=${control.surface}`}
          className={`type-status rounded-[4px] px-2 py-1 focus:outline-none ${
            surface === control.surface
              ? "bg-accent text-accent-foreground"
              : "text-foreground-secondary hover:bg-surface"
          }`}
        >
          {control.label}
        </Link>
      ))}
    </nav>
  );
}

function PagesDemoSurfaceContent({
  surface,
  pageId,
}: {
  surface: PagesDemoSurface;
  pageId?: string;
}) {
  if (surface === "loading") return <PagesLoadingState />;
  if (surface === "error") {
    return <PagesErrorState title="Pages could not be loaded" description="The fixture simulates a temporary directory failure." onRetry={() => undefined} />;
  }
  if (surface === "not-found") {
    return <PagesErrorState title="Page not found" description="This Page does not exist in the current workspace." />;
  }
  if (pageId) return <PageRoute pageId={pageId} />;
  if (surface === "markdown") return <PageRoute pageId={PAGES_FIXTURE_MARKDOWN_PAGE_ID} />;
  if (surface === "notebook") return <PageRoute pageId={PAGES_FIXTURE_NOTEBOOK_PAGE_ID} />;
  if (surface === "html") return <PageRoute pageId={PAGES_FIXTURE_HTML_PAGE_ID} />;
  if (surface === "folder") return <PageRoute pageId={PAGES_FIXTURE_NESTED_FOLDER_ID} />;
  return <PagesRootRoute />;
}
