import { createFileRoute } from "@tanstack/react-router";
import Page from "@/pages/dev/connectors-demo/page";

export const Route = createFileRoute("/dev/connectors-demo")({ component: Page });
