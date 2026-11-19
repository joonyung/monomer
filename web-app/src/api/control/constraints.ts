import type {
  ControlSafeJsonInteger,
  ControlSemanticDecimal38_18,
  ControlUuidV7,
} from "./generated";

const controlUuidV7Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const controlSemanticDecimal38_18Pattern = /^(?:0|-?(?:(?:[1-9][0-9]{0,19})(?:\.[0-9]{0,17}[1-9])?|0\.[0-9]{0,17}[1-9]))$/u;

export class ControlConstraintError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ControlConstraintError";
  }
}

export function controlUuidV7(value: string): ControlUuidV7 {
  if (typeof value !== "string" || !controlUuidV7Pattern.test(value)) {
    throw new ControlConstraintError("Control UUID must be a lowercase UUIDv7.");
  }
  return value as ControlUuidV7;
}

export function controlSafeJsonInteger(value: number): ControlSafeJsonInteger {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new ControlConstraintError("Control safe JSON integer must be a non-negative IEEE-754 safe integer.");
  }
  return value as ControlSafeJsonInteger;
}

export function controlSemanticDecimal38_18(value: string): ControlSemanticDecimal38_18 {
  if (typeof value !== "string" || !controlSemanticDecimal38_18Pattern.test(value)) {
    throw new ControlConstraintError("Control semantic decimal must use canonical DECIMAL(38,18) text.");
  }
  return value as ControlSemanticDecimal38_18;
}
