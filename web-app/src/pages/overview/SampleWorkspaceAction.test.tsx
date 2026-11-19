import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { queryKeys } from "@/lib/query/keys";
import { SampleWorkspaceAction, sampleSetupLabel } from "./SampleWorkspaceAction";

vi.mock("@/lib/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

describe("sample Workspace setup controls", () => {
  it.each([null, "PROVISIONING", "READY"] as const)("renders server marker %s without submitting a mutation", (state) => {
    const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } });
    client.setQueryData(queryKeys.auth.sampleWorkspace("user", "organization"), state ? { workspaceId: "sample", organizationId: "organization", displayName: "Acme Cloud (sample)", sample: { dataset: "acme-cloud@1", state, initiator: "user" } } : null);
    const markup = renderToStaticMarkup(<QueryClientProvider client={client}><SampleWorkspaceAction organizationId="organization" principalId="user" /></QueryClientProvider>);
    expect(markup).toContain(state === "PROVISIONING" ? "Retry setup" : state === "READY" ? "Open sample workspace" : "Explore sample data");
    expect(markup.includes("Setting up sample data…")).toBe(state === "PROVISIONING");
    expect(client.getMutationCache().getAll()).toHaveLength(0);
    client.clear();
  });

  it.each([
    [null, false, false, "Explore sample data"],
    ["PROVISIONING", true, false, "Explore sample data"],
    ["PROVISIONING", false, false, "Retry setup"],
    ["READY", true, false, "Open sample workspace"],
    ["PROVISIONING", true, true, "Retry setup"],
    [null, false, true, "Retry setup"],
  ] as const)("labels state %s started here %s failed %s as %s", (state, startedHere, failed, label) => {
    expect(sampleSetupLabel({ state, startedHere, failed })).toBe(label);
  });
});
