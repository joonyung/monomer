import { createFileRoute, notFound, Outlet } from "@tanstack/react-router";

export function requireDevelopmentRoute(isDevelopmentBuild: boolean): void {
  if (!isDevelopmentBuild) throw notFound();
}

export const Route = createFileRoute("/dev")({
  beforeLoad: () => requireDevelopmentRoute(import.meta.env.DEV),
  component: Outlet,
});
