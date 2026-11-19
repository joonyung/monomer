import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/pages/demo/[pageId]/page";
import { pagesDemoDetailSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/dev/pages-demo/$pageId")({ validateSearch: pagesDemoDetailSearchSchema, component: Page });
