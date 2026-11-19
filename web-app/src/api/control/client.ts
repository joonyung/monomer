import { controlOperationDefinitions } from "./generated-operations";
import {
  createBrowserControlTransport,
  type ControlTransport,
} from "./transport";
import type {
  ControlOperationId,
  ControlRequest,
  ControlResponse,
} from "./types";

export interface ControlClient {
  request<OperationId extends ControlOperationId>(
    operationId: OperationId,
    request: ControlRequest<OperationId>,
    signal: AbortSignal | undefined,
  ): Promise<ControlResponse<OperationId>>;
}

export function createControlClient(transport: ControlTransport): ControlClient {
  return {
    request<OperationId extends ControlOperationId>(
      operationId: OperationId,
      request: ControlRequest<OperationId>,
      signal: AbortSignal | undefined,
    ): Promise<ControlResponse<OperationId>> {
      return transport.request(controlOperationDefinitions[operationId], request, signal);
    },
  };
}

export function createBrowserControlClient(): ControlClient {
  return createControlClient(createBrowserControlTransport());
}
