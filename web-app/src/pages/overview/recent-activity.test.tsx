import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { RecentActivity, type Activity } from "./OverviewActivity";
import type { ActivityScope } from "./use-activity";

function activity(filter: ActivityScope): Activity {
  return {
    recent: { data: { pages: [], pageParams: [] }, isPending: false, isError: false, hasNextPage: true, isFetching: false, isFetchingNextPage: false },
    live: { data: undefined, isError: false },
    filter, setFilter: () => undefined,
    online: true, now: Date.parse("2026-10-04T00:00:00Z"),
  } as unknown as Activity;
}

describe("Overview Recent activity", () => {
  it("links to the Activity page with the scope the reader chose", () => {
    const agents = renderToStaticMarkup(<RecentActivity activity={activity("agents")} workspaceId="w" principalId="p" sampleWorkspace={false} />);
    expect(agents).toContain('href="/workspaces/w/activity?scope=agents"');
    expect(agents).toContain(">Load more activity<");
    expect(renderToStaticMarkup(<RecentActivity activity={activity("mine")} workspaceId="w" principalId="p" sampleWorkspace={false} />))
      .toContain('href="/workspaces/w/activity?scope=mine"');
    expect(renderToStaticMarkup(<RecentActivity activity={activity("all")} workspaceId="w" principalId="p" sampleWorkspace={false} />))
      .toMatch(/href="\/workspaces\/w\/activity"[^>]*>View all activity/);
  });
});
