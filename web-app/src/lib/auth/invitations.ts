import { graphqlRequest } from "@/lib/api/graphql";
import { CONTROL_API_URL, isTokenExpiringSoon } from "./config";
import { refreshAccessToken } from "@/lib/api/client";
import { useAuthStore } from "./store";
import { startGoogleSignIn } from "./google-sign-in";
import type { WorkspaceRole } from "./types";

// Workspace access an Organization Admin attaches to an invitation; accepting grants it.
export interface InvitationWorkspaceAccess {
  workspaceId: string;
  workspaceDisplayName: string;
  role: WorkspaceRole;
}
export interface InvitationWorkspaceAccessInput {
  workspaceId: string;
  role: WorkspaceRole;
}

export interface OrganizationInvitation {
  id: string;
  organizationId: string;
  deliveryEmail: string;
  invitedBy: { id: string; displayName: string; email: string } | null;
  createdAt: string;
  expiresAt: string;
  lastDeliveryAttemptAt: string | null;
  lastSentAt: string | null;
  status: "PENDING" | "EXPIRED";
  workspaceAccess: InvitationWorkspaceAccess[];
}
export interface InvitationDeliveryResult {
  outcome:
    | "SENT"
    | "DELIVERY_FAILED"
    | "INVITATION_PENDING"
    | "ALREADY_MEMBER"
    | "RATE_LIMITED"
    | "CANCELLED"
    | "UNAVAILABLE"
    | "INVALID_EMAIL"
    | "INVALID_WORKSPACE_ACCESS";
  invitation: OrganizationInvitation | null;
  retryAfter: number | null;
}
const invitationFields = `id organizationId deliveryEmail invitedBy { id displayName email } createdAt expiresAt lastDeliveryAttemptAt lastSentAt status workspaceAccess { workspaceId workspaceDisplayName role }`;
export async function fetchOrganizationInvitations(
  organizationId: string,
  signal?: AbortSignal,
): Promise<OrganizationInvitation[]> {
  const data = await graphqlRequest<{
    organizationMembershipInvitations: OrganizationInvitation[];
  }>({
    endpoint: `${CONTROL_API_URL}/v2/graphql`,
    query: `query OrganizationMembershipInvitations($organizationId: ID!) { organizationMembershipInvitations(organizationId: $organizationId) { ${invitationFields} } }`,
    variables: { organizationId },
    signal,
  });
  return data.organizationMembershipInvitations;
}
export async function createOrganizationInvitation(
  organizationId: string,
  email: string,
  workspaceAccess: InvitationWorkspaceAccessInput[],
): Promise<InvitationDeliveryResult> {
  const data = await graphqlRequest<{ createOrganizationMembershipInvitation: InvitationDeliveryResult }>({
    endpoint: `${CONTROL_API_URL}/v2/graphql`,
    query: `mutation createOrganizationMembershipInvitation($organizationId: ID!, $email: String!, $workspaceAccess: [InvitationWorkspaceAccessInput!]!) { createOrganizationMembershipInvitation(organizationId: $organizationId, email: $email, workspaceAccess: $workspaceAccess) { outcome retryAfter invitation { ${invitationFields} } } }`,
    variables: { organizationId, email, workspaceAccess },
  });
  return data.createOrganizationMembershipInvitation;
}
export async function mutateOrganizationInvitation(
  action: "resend" | "cancel",
  organizationId: string,
  invitationId: string,
): Promise<InvitationDeliveryResult> {
  const field = `${action}OrganizationMembershipInvitation`;
  const data = await graphqlRequest<Record<string, InvitationDeliveryResult>>({
    endpoint: `${CONTROL_API_URL}/v2/graphql`,
    query: `mutation ${field}($organizationId: ID!, $invitationId: ID!) { ${field}(organizationId: $organizationId, invitationId: $invitationId) { outcome retryAfter invitation { ${invitationFields} } } }`,
    variables: { organizationId, invitationId },
  });
  return data[field];
}
export function invitationDeliveryMessage(
  result: InvitationDeliveryResult,
): string {
  switch (result.outcome) {
    case "SENT":
      return "Invitation sent.";
    case "DELIVERY_FAILED":
      return "Invitation saved · email not delivered. Retry from Pending invitations.";
    case "INVITATION_PENDING":
      return "An invitation is already pending. Use Resend in Pending invitations.";
    case "ALREADY_MEMBER":
      return "This person is already an Organization member.";
    case "RATE_LIMITED":
      return `Sending is temporarily limited. Try again in ${result.retryAfter ?? 60} seconds.`;
    case "CANCELLED":
      return "Invitation revoked.";
    case "INVALID_EMAIL":
      return "Enter a valid email address.";
    case "INVALID_WORKSPACE_ACCESS":
      return "One of the chosen workspaces is no longer in this Organization. Refresh and choose again.";
    case "UNAVAILABLE":
      return "This invitation is unavailable. Refresh the list.";
  }
}
export interface InvitationResponse {
  outcome:
    | "AVAILABLE"
    | "MEMBER_LIMIT_REACHED"
    | "UNAVAILABLE"
    | "RATE_LIMITED"
    | "AUTHENTICATION_REQUIRED"
    | "EMAIL_MISMATCH"
    | "ACCEPTED"
    | "DECLINED"
    | "ALREADY_MEMBER";
  continuationId?: string;
  organizationId?: string;
  organizationDisplayName?: string;
  deliveryEmail?: string;
  invitedByDisplayName?: string;
  expiresAt?: string;
  retryAfter?: number;
  // Offered access before acceptance; granted access after ACCEPTED.
  workspaceAccess?: InvitationAccessResponse[] | null;
  skippedWorkspaceAccess?: InvitationAccessResponse[] | null;
}
export interface InvitationAccessResponse {
  workspaceId: string;
  workspaceDisplayName: string | null;
  role: WorkspaceRole;
}
export const INVITATION_CONTINUATION_KEY = "monomer.invitation.continuation";
export async function invitationRequest(
  action: "exchange" | "resolve" | "accept" | "decline",
  value: string,
): Promise<InvitationResponse> {
  const session = useAuthStore.getState();
  let token = session.accessToken;
  if (
    action !== "exchange" &&
    token &&
    isTokenExpiringSoon(session.accessTokenExpiresAt)
  ) {
    const refreshed = await refreshAccessToken();
    token = refreshed ? useAuthStore.getState().accessToken : null;
  }
  const response = await fetch(
    `${CONTROL_API_URL}/auth/invitations/${action}`,
    {
      method: "POST",
      credentials: "include",
      referrerPolicy: "no-referrer",
      headers: {
        "Content-Type": "application/json",
        ...(action !== "exchange" && token
          ? { Authorization: `Bearer ${token}` }
          : {}),
      },
      body: JSON.stringify(
        action === "exchange" ? { token: value } : { continuationId: value },
      ),
    },
  );
  if (!response.ok)
    throw new Error("The invitation could not be checked. Please try again.");
  return response.json() as Promise<InvitationResponse>;
}
export function startInvitationLogin(continuationId: string, chooseAccount: boolean): void {
  startGoogleSignIn({ redirect: "/invitations/accept", chooseAccount, invitationContinuation: continuationId });
}

// "in 5 days" for the invitation landing; the absolute time goes in a tooltip.
export function invitationExpiresIn(expiresAt: string, now: Date): string {
  const remaining = Date.parse(expiresAt) - now.getTime();
  const format = new Intl.RelativeTimeFormat("en", { numeric: "always" });
  const minutes = Math.max(1, Math.round(remaining / 60_000));
  if (minutes < 60) return format.format(minutes, "minute");
  const hours = Math.round(minutes / 60);
  if (hours < 48) return format.format(hours, "hour");
  return format.format(Math.round(hours / 24), "day");
}

// Who can add an invitee to a workspace when the invitation carried none.
export function workspaceAccessHelper(invitedByDisplayName: string | null | undefined): string {
  return invitedByDisplayName
    ? `${invitedByDisplayName} or a Workspace Owner can add you to a workspace.`
    : "An Admin or a Workspace Owner can add you to a workspace.";
}
