// Signed-in readers always see which workspace's Pages they are in, even with the sidebar
// collapsed. Desktop only; the mobile top bar names the workspace itself.
export function PagesContextBar({ workspaceName }: { workspaceName: string }) {
  return (
    <div className="hidden h-12 shrink-0 items-center gap-2 border-b border-border px-6 lg:flex" data-testid="pages-context-bar">
      <span className="flex-shrink-0 type-control text-label">Pages</span>
      <span className="flex-shrink-0 type-control text-foreground-secondary" aria-hidden="true">·</span>
      <span className="min-w-0 truncate type-control text-foreground" title={workspaceName}>{workspaceName}</span>
    </div>
  );
}
