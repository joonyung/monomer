import { describe, expect, it } from "vitest";
import { ControlResponseContractError } from "@/api/control/transport";
import { isLoadErrorRetryable, loadErrorMessage } from "./load-error";

describe("load error recovery", () => {
  it("follows the owner's retryable signal even when the HTTP status looks transient", () => {
    expect(isLoadErrorRetryable({ status: 503, problem: { retryable: false } })).toBe(false);
    expect(isLoadErrorRetryable({ status: 409, problem: { retryable: true } })).toBe(true);
    expect(isLoadErrorRetryable({ status: 500, apiError: { problem: { retryable: false } } })).toBe(false);
  });
  it("keeps network retry and omits permanent status retry when no Problem is available", () => {
    expect(isLoadErrorRetryable(new TypeError("Failed to fetch"))).toBe(true);
    expect(isLoadErrorRetryable({ status: 404 })).toBe(false);
  });
  it("does not describe contract mismatches as temporary failures", () => {
    const error = new ControlResponseContractError("Undeclared response.");
    expect(isLoadErrorRetryable(error)).toBe(false);
    expect(loadErrorMessage(error, "The dashboard could not be loaded.")).toBe("The dashboard could not be loaded.");
    expect(loadErrorMessage(new Error("You do not have access."), "Unavailable")).toBe("You do not have access.");
  });
});
