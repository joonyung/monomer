import { CONTROL_API_URL } from "@/lib/auth/config";

export function workspaceBffUrl(workspaceId: string, path: string): string {
  return `${CONTROL_API_URL}/v2/workspaces/${encodeURIComponent(workspaceId)}${path}`;
}

export function semanticBffUrl(workspaceId: string, path: string): string {
  return workspaceBffUrl(workspaceId, `/semantic${path}`);
}
