import { createRootRoute, Outlet } from "@tanstack/react-router";
import { AuthProvider } from "@/components/auth/AuthProvider";
import { Link } from "@/lib/navigation";

export const Route = createRootRoute({
  component: RootRoute,
  notFoundComponent: NotFoundRoute,
});

function RootRoute() {
  return (
    <AuthProvider>
      <Outlet />
    </AuthProvider>
  );
}

export function NotFoundRoute() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-6">
      <div className="text-center">
        <h1 className="type-display text-heading">Page not found</h1>
        <p className="mt-3 type-body text-foreground-secondary">This page doesn&apos;t exist or has moved.</p>
        <Link className="mt-4 inline-block type-body text-foreground underline underline-offset-4" href="/workspaces">
          Go to your workspaces
        </Link>
      </div>
    </main>
  );
}
