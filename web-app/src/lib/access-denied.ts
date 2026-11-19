export const DATA_ACCESS_DENIED_REST_ERROR = "data_access_denied";
export const DATA_ACCESS_DENIED_TERMINAL_ERROR_CODE = "PERMISSION_DENIED";
export const SEMANTIC_AUTHORIZATION_REQUIRED_ERROR =
  "semantic_execution_authorization_required";

export interface AccessDeniedRestError {
  error: typeof DATA_ACCESS_DENIED_REST_ERROR;
  message?: string;
  suggestion?: string;
  supportRef?: string;
}

export interface AccessDeniedTerminalError {
  errorCode: typeof DATA_ACCESS_DENIED_TERMINAL_ERROR_CODE;
  message?: string;
}

export interface SemanticAuthorizationRequiredError {
  error: typeof SEMANTIC_AUTHORIZATION_REQUIRED_ERROR;
  message?: string;
}

export type AccessDeniedInput =
  | AccessDeniedRestError
  | AccessDeniedTerminalError
  | SemanticAuthorizationRequiredError;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function optionalString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value : null;
}

function errorPayload(value: unknown): Record<string, unknown> | null {
  if (!isRecord(value)) return null;
  if (typeof value.bodyText !== "string") return value;
  try {
    const parsed = JSON.parse(value.bodyText) as unknown;
    return isRecord(parsed) ? parsed : value;
  } catch {
    return value;
  }
}

export function accessDeniedInputFromUnknown(value: unknown): AccessDeniedInput | null {
  const payload = errorPayload(value);
  if (!payload) return null;

  if (payload.error === SEMANTIC_AUTHORIZATION_REQUIRED_ERROR) {
    const message = optionalString(payload.message);
    return { error: SEMANTIC_AUTHORIZATION_REQUIRED_ERROR, ...(message ? { message } : {}) };
  }

  if (payload.error === DATA_ACCESS_DENIED_REST_ERROR) {
    const message = optionalString(payload.message);
    const suggestion = optionalString(payload.suggestion);
    const supportRef = optionalString(payload.supportRef);
    return {
      error: DATA_ACCESS_DENIED_REST_ERROR,
      ...(message ? { message } : {}),
      ...(suggestion ? { suggestion } : {}),
      ...(supportRef ? { supportRef } : {}),
    };
  }

  if (payload.errorCode !== DATA_ACCESS_DENIED_TERMINAL_ERROR_CODE) return null;
  const message = optionalString(payload.message);
  return {
    errorCode: DATA_ACCESS_DENIED_TERMINAL_ERROR_CODE,
    ...(message ? { message } : {}),
  };
}
