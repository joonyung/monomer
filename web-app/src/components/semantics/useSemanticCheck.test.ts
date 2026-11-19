import { beforeEach, describe, expect, it, vi } from "vitest";
import { useSemanticCheck } from "@/components/semantics/SemanticCheckPanel";

const mocks = vi.hoisted(() => ({ checkedKey: null as string | null, setCheckedKey: vi.fn(), refetch: vi.fn(), useQuery: vi.fn() }));
vi.mock("react", async (importOriginal) => ({
  ...(await importOriginal<typeof import("react")>()),
  useState: (initial: string) => [mocks.checkedKey ?? initial, mocks.setCheckedKey],
  useEffect: vi.fn(),
  useCallback: (callback: () => void) => callback,
}));
vi.mock("@tanstack/react-query", async (importOriginal) => ({ ...(await importOriginal<typeof import("@tanstack/react-query")>()), useQuery: mocks.useQuery }));
vi.mock("@/hooks/use-workspace", () => ({ useWorkspaceId: () => "w1", usePrincipalId: () => "p1" }));

describe("useSemanticCheck retry", () => {
  beforeEach(() => {
    mocks.checkedKey = null;
    mocks.setCheckedKey.mockReset();
    mocks.refetch.mockReset();
    mocks.useQuery.mockReturnValue({ data: null, isFetching: false, isError: true, refetch: mocks.refetch });
  });
  const draft = { kind: "METRIC" as const, metricId: "m1", request: { name: "revenue", type: "simple" as const, typeParams: { semanticModelName: "billing", measure: "revenue" } } };

  it("retries an unchanged failed draft instead of setting the same key", () => {
    const check = useSemanticCheck(draft);
    expect(check.failed).toBe(true);
    check.checkNow();
    expect(mocks.refetch).toHaveBeenCalledOnce();
    expect(mocks.setCheckedKey).not.toHaveBeenCalled();
  });

  it("checks the new draft instead of retrying the stale one", () => {
    mocks.checkedKey = JSON.stringify({ ...draft, metricId: "old" });
    const check = useSemanticCheck(draft);
    expect(check.checking).toBe(true);
    check.checkNow();
    expect(mocks.setCheckedKey).toHaveBeenCalledWith(JSON.stringify(draft));
    expect(mocks.refetch).not.toHaveBeenCalled();
  });

  it("does not send an incomplete draft", () => {
    useSemanticCheck(null).checkNow();
    expect(mocks.refetch).not.toHaveBeenCalled();
    expect(mocks.setCheckedKey).not.toHaveBeenCalled();
    expect(mocks.useQuery).toHaveBeenCalledWith(expect.objectContaining({ enabled: false }));
  });
});
