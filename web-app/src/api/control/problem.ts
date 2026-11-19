import problemSchemaClosure from "./problem-schema.json";
import { createControlProblemValidator } from "./problem-runtime";
import type { ControlProblem } from "./types";

const controlProblemValidator = createControlProblemValidator(problemSchemaClosure);

export class ControlProblemValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ControlProblemValidationError";
  }
}

export function parseControlProblem(value: unknown): ControlProblem {
  const parsed = controlProblemValidator.safeParse(value);
  if (!parsed.success) {
    throw new ControlProblemValidationError(parsed.error.message);
  }
  return parsed.data as ControlProblem;
}

export { controlProblemValidator };
