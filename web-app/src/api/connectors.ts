import type { Connector } from "@/lib/types/connector";
import type {
  CreateConnectorRequest,
  ReviseConnectorRequest,
  RotateConnectorCredentialsRequest,
  ConnectorMutationResponse,
} from "@/lib/connectors/mutation-contract";
import { apiFetch } from "@/lib/api/client";
import { CONTROL_API_URL } from "@/lib/auth/config";

const workspaceConnectorsUrl = (workspaceId: string): string =>
  `${CONTROL_API_URL}/v2/workspaces/${encodeURIComponent(workspaceId)}/connectors`;

const connectorUrl = (workspaceId: string, connectorId: string): string =>
  `${workspaceConnectorsUrl(workspaceId)}/${encodeURIComponent(connectorId)}`;

async function mutateConnector(
  url: string,
  method: "POST" | "PUT",
  request: CreateConnectorRequest | ReviseConnectorRequest,
  idempotencyKey: string,
): Promise<ConnectorMutationResponse> {
  const response = await apiFetch(url, {
    method,
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(request),
  });
  return response.json() as Promise<ConnectorMutationResponse>;
}

export const connectorsAPI = {
  list: async ({
    workspaceId,
  }: { workspaceId: string }): Promise<Connector[]> => {
    const response = await apiFetch(workspaceConnectorsUrl(workspaceId));

    const body = (await response.json()) as { connectors: Connector[] };
    return body.connectors;
  },

  getById: async (workspaceId: string, id: string): Promise<Connector> => {
    const response = await apiFetch(connectorUrl(workspaceId, id));

    return response.json();
  },

  create: async ({
    workspaceId,
    request,
    idempotencyKey,
  }: {
    workspaceId: string;
    request: CreateConnectorRequest;
    idempotencyKey: string;
  }): Promise<ConnectorMutationResponse> =>
    mutateConnector(workspaceConnectorsUrl(workspaceId), "POST", request, idempotencyKey),

  revise: async ({
    workspaceId,
    connectorId,
    request,
    idempotencyKey,
  }: {
    workspaceId: string;
    connectorId: string;
    request: ReviseConnectorRequest;
    idempotencyKey: string;
  }): Promise<ConnectorMutationResponse> =>
    mutateConnector(connectorUrl(workspaceId, connectorId), "PUT", request, idempotencyKey),
  delete: async ({ workspaceId, connectorId }: { workspaceId: string; connectorId: string }): Promise<void> => {
    await apiFetch(connectorUrl(workspaceId, connectorId), { method: "DELETE" });
  },

  rotateCredentials: async ({ workspaceId, connectorId, request, idempotencyKey }: {
    workspaceId: string;
    connectorId: string;
    request: RotateConnectorCredentialsRequest;
    idempotencyKey: string;
  }): Promise<void> => {
    await apiFetch(`${connectorUrl(workspaceId, connectorId)}/credentials`, {
      method: "PUT",
      headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(request),
    });
  },
};
