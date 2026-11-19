import type { InfiniteData, QueryClient } from "@tanstack/react-query";
import type {
  PageContentDocumentResponse,
  PageContentVersionPageResponse,
} from "@/api/page-content";
import { queryKeys } from "@/lib/query/keys";

export async function reconcileDocumentReplacement(
  queryClient: QueryClient,
  workspaceId: string,
  principalId: string,
  pageId: string,
  versionNo: number,
  replacementContent?: PageContentDocumentResponse,
): Promise<void> {
  const latestContentKey = queryKeys.pages.documentContent(
    workspaceId,
    principalId,
    pageId,
    null,
  );
  const versionContentKey = queryKeys.pages.documentContent(
    workspaceId,
    principalId,
    pageId,
    versionNo,
  );
  if (replacementContent) {
    queryClient.setQueryData(latestContentKey, replacementContent);
    queryClient.setQueryData(versionContentKey, replacementContent);
    queryClient.setQueriesData<InfiniteData<PageContentVersionPageResponse>>(
      {
        queryKey: queryKeys.pages.documentVersionsAll(workspaceId, principalId, pageId),
      },
      (current) => {
        if (!current || current.pages.length === 0) return current;
        const [firstPage, ...remainingPages] = current.pages;
        return {
          ...current,
          pages: [
            {
              ...firstPage,
              items: [
                replacementContent.version,
                ...firstPage.items.filter(
                  (version) => version.versionNo !== versionNo,
                ),
              ],
            },
            ...remainingPages,
          ],
        };
      },
    );
  }
  await Promise.all([
    replacementContent
      ? Promise.resolve()
      : queryClient.invalidateQueries({
          queryKey: queryKeys.pages.documentVersionsAll(workspaceId, principalId, pageId),
          refetchType: "all",
        }),
    replacementContent
      ? Promise.resolve()
      : queryClient.invalidateQueries({
          queryKey: latestContentKey,
          exact: true,
          refetchType: "all",
        }),
    replacementContent
      ? Promise.resolve()
      : queryClient.invalidateQueries({
          queryKey: versionContentKey,
          exact: true,
          refetchType: "all",
        }),
  ]);
}
