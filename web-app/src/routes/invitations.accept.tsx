import { createFileRoute } from "@tanstack/react-router";
import InvitationPage from "@/pages/invitations/page";
export const Route = createFileRoute("/invitations/accept")({ component: InvitationPage });
