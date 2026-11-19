import type { ControlOperationId } from "./types";

export type ControlHttpMethod = "DELETE" | "GET" | "HEAD" | "OPTIONS" | "PATCH" | "POST" | "PUT" | "TRACE";

export interface ControlResponseDefinition {
  contentTypes: readonly string[];
  requiredHeaders: readonly string[];
  status: number;
}

export interface ControlOperationDefinition<OperationId extends ControlOperationId> {
  method: ControlHttpMethod;
  operationId: OperationId;
  pathTemplate: string;
  responses: readonly ControlResponseDefinition[];
}
