import { createFileRoute } from "@tanstack/react-router";
import { PagesDemoRouteLayout } from "@/components/pages/PagesDemoRouteLayout";

export const Route = createFileRoute("/dev/pages-demo")({ component: PagesDemoRouteLayout });
