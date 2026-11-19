import { fetchWorkspaceAccess } from "@/lib/auth/graphql";
import { GraphQLRequestError } from "@/lib/api/graphql";
import type { WorkspaceAccess } from "@/lib/auth/types";

export async function fetchWorkspaceContext(
  workspaceId: string,
  signal?: AbortSignal,
): Promise<WorkspaceAccess | null> {
  try {
    const access = await fetchWorkspaceAccess(workspaceId, signal);
    return access.granted ? access : null;
  } catch (error) {
    if (
      error instanceof GraphQLRequestError
      && error.errors.some((entry) => entry.extensions?.classification === "FORBIDDEN")
    ) {
      return null;
    }
    throw error;
  }
}
