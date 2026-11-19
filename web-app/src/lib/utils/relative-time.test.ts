import { describe, expect, it } from "vitest";
import { formatRelativeTime } from "./relative-time";

const now = new Date(2026, 9, 12, 12).getTime();

describe("product relative time", () => {
  it.each([
    [0, "Just now"],
    [59_999, "Just now"],
    [60_000, "1m ago"],
    [5 * 60_000, "5m ago"],
    [3_599_999, "59m ago"],
    [3_600_000, "1h ago"],
    [3 * 3_600_000, "3h ago"],
    [86_399_999, "23h ago"],
    [86_400_000, "1d ago"],
    [2 * 86_400_000, "2d ago"],
    [604_799_999, "6d ago"],
    [604_800_000, "Oct 5"],
  ])("formats %i elapsed milliseconds as %s", (elapsed, label) => {
    expect(formatRelativeTime(now - elapsed, now)).toBe(label);
    expect(formatRelativeTime(new Date(now - elapsed).toISOString(), now)).toBe(label);
  });

  it("keeps a future recorded timestamp at Just now", () => {
    expect(formatRelativeTime(now + 30_000, now)).toBe("Just now");
  });
});
