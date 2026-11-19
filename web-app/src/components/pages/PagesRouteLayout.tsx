import { Outlet } from "@tanstack/react-router";
import { MainLayout } from "@/components/layout/MainLayout";

export function PagesRouteLayout() {
  return (
    <MainLayout>
      <Outlet />
    </MainLayout>
  );
}
