import { useParams, useSearch } from "@tanstack/react-router";
import { notFound } from "@/lib/navigation";
import { PagesDemoExperience } from "@/components/pages/PagesDemoExperience";
import { isPagesDemoAvailable } from "@/components/pages/demoAccess";
import {
  PAGES_FIXTURE_HTML_PAGE_ID,
  PAGES_FIXTURE_MARKDOWN_PAGE_ID,
  PAGES_FIXTURE_NOTEBOOK_PAGE_ID,
} from "@/components/pages/pagesFixtures";

function surfaceForPageId(pageId: string) {
  if (pageId === PAGES_FIXTURE_MARKDOWN_PAGE_ID) return "markdown" as const;
  if (pageId === PAGES_FIXTURE_NOTEBOOK_PAGE_ID) return "notebook" as const;
  if (pageId === PAGES_FIXTURE_HTML_PAGE_ID) return "html" as const;
  return "folder" as const;
}

export default function PagesDeepLinkDemoPage() {
  if (!isPagesDemoAvailable(import.meta.env.DEV)) notFound();
  const { pageId } = useParams({ from: "/dev/pages-demo/$pageId" });
  const query = useSearch({ from: "/dev/pages-demo/$pageId" });
  return (
    <PagesDemoExperience
      surface={surfaceForPageId(pageId)}
      pageId={pageId}
      showControls={query.controls !== "0"}
    />
  );
}
