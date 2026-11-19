import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/workspaces/page";

export const Route = createFileRoute("/workspaces/")({ component: Page });
