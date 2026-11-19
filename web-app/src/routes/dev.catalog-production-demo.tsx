import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import Page from "@/pages/catalog/production-demo/page";
import { catalogSearchSchema } from "@/lib/route-search";

export const Route = createFileRoute("/dev/catalog-production-demo")({ validateSearch: catalogSearchSchema.extend({ role: z.enum(["EXPLORER", "EDITOR", "OWNER"]).catch("EDITOR") }), component: Page });
