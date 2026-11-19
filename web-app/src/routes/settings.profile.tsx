import { createFileRoute } from "@tanstack/react-router";
import ProfilePage from "@/pages/user-settings/profile";
export const Route = createFileRoute("/settings/profile")({ component: ProfilePage });
