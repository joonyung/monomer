import { useEffect, useMemo, useState } from "react";
import { Outlet } from "@tanstack/react-router";
import { MainLayout } from "@/components/layout/MainLayout";
import type { PagesDemoSurface } from "@/components/pages/PagesDemoExperience";
import {
  createPagesFixture,
  type PagesFixtureMode,
} from "@/components/pages/pagesFixtures";
import { useAuthStore } from "@/lib/auth/store";
import { useCurrentSearch } from "@/lib/navigation";

function fixtureModeForSurface(surface: PagesDemoSurface | undefined): PagesFixtureMode {
  if (surface === "empty-member") return "empty-member";
  if (surface === "empty-editor") return "empty-editor";
  if (surface === "depth") return "depth";
  if (surface === "dense") return "dense";
  return "populated";
}

export function PagesDemoRouteLayout() {
  const { surface } = useCurrentSearch<{ surface?: PagesDemoSurface }>();
  const fixtureMode = fixtureModeForSurface(surface);
  const fixture = useMemo(() => createPagesFixture(fixtureMode), [fixtureMode]);
  const [authSeeded, setAuthSeeded] = useState(false);

  useEffect(() => {
    const organizationId = fixture.workspaceAccess.organizationId ?? "pages-demo-organization";
    const organization = {
      id: organizationId,
      plan: "FREE", createdAt: "2026-09-01T00:00:00Z",
      displayName: "MONOMER Demo",
    };
    useAuthStore.setState({
      status: "authenticated",
      user: {
        id: "pages-demo-user",
        email: "pages.demo@monomer.test",
        displayName: "Pages Demo",
      },
      organizationMemberships: [{ organization, role: "ADMIN" }],
      accessToken: "pages-demo-token",
      accessTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      error: null,
    });
    setAuthSeeded(true);
  }, [fixture.snapshot.workspaceId, fixture.workspaceAccess.organizationId]);

  if (!authSeeded) return null;

  return (
    <MainLayout pagesFixture={fixture}>
      <Outlet />
    </MainLayout>
  );
}
