export type ApplicationShellMode = "workspace" | "pages";
export type ShellContextTransition = "forward" | "backward" | null;

export function applicationShellMode(pathname: string): ApplicationShellMode {
  return /^\/workspaces\/[^/]+\/pages(?:\/|$)/.test(pathname) || pathname.startsWith("/dev/pages-demo")
    ? "pages"
    : "workspace";
}

export function shellNavigationLabel(shellMode: ApplicationShellMode): string {
  return shellMode === "pages" ? "Pages navigation" : "Workspace navigation";
}
