import { createRouter } from "@tanstack/react-router";
import { routeTree } from "@/routeTree.gen";
import { NotFoundRoute } from "@/routes/__root";

export const router = createRouter({
  routeTree,
  defaultPreload: "intent",
  defaultPreloadStaleTime: 0,
  scrollRestoration: true,
  defaultNotFoundComponent: NotFoundRoute,
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
