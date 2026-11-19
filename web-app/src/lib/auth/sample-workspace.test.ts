import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AccessibleWorkspace } from "./types";
import { sampleDashboardsInSeedOrder, visibleWorkspaces, warehouseWorkspace } from "./sample-workspace";
import { fetchAccessibleWorkspaces, fetchManagedWorkspaces } from "./graphql";
import { useAuthStore } from "./store";

const real: AccessibleWorkspace = { id: "real", organizationId: "organization", name: "My Workspace", publicPages: false, reportingTimeZone: "UTC", role: "OWNER", sample: null };
const sample: AccessibleWorkspace = { ...real, id: "sample", name: "Acme Cloud (sample)", sample: { dataset: "acme-cloud@1", state: "READY", initiator: "user" } };
const provisioning: AccessibleWorkspace = { ...sample, id: "provisioning", sample: { ...sample.sample!, state: "PROVISIONING" } };

describe("sample Workspace visibility", () => {
  beforeEach(() => { vi.restoreAllMocks(); useAuthStore.setState({ status: "authenticated", accessToken: "token" }); });
  it("keeps ready and real Workspaces but excludes provisioning from entry and directory data", () => {
    expect(visibleWorkspaces([provisioning, real, sample])).toEqual([real, sample]);
  });
  it("routes warehouse connection to a real Workspace in the sample's Organization, preferring one the reader owns", () => {
    const editor: AccessibleWorkspace = { ...real, id: "editor", name: "Finance", role: "EDITOR" };
    const owner: AccessibleWorkspace = { ...real, id: "owner", name: "Default Workspace" };
    expect(warehouseWorkspace([sample, editor, provisioning, owner], sample)).toBe(owner);
    expect(warehouseWorkspace([sample, editor], sample)).toBe(editor);
  });
  it("never routes warehouse connection to another Organization, and hides it without a same-Organization candidate", () => {
    const personal: AccessibleWorkspace = { ...real, id: "personal", organizationId: "personal-organization", name: "My Workspace" };
    const sameOrganizationViewer: AccessibleWorkspace = { ...real, id: "viewer", name: "Default Workspace", role: "VIEWER" };
    expect(warehouseWorkspace([personal, sample, sameOrganizationViewer], sample)).toBe(sameOrganizationViewer);
    expect(warehouseWorkspace([personal, sample, provisioning], sample)).toBeNull();
    expect(warehouseWorkspace([sample], sample)).toBeNull();
  });
  it("lists the first Dashboard with each seed title in seed order and keeps every other Dashboard in API order", () => {
    const dashboard = (id: string, title: string) => ({ id, title });
    const api = [
      dashboard("engagement", "Product engagement"), dashboard("copy", "Board metrics"), dashboard("mine", "Churn review"),
      dashboard("segments", "Customer segments"), dashboard("board-2", "Board metrics"), dashboard("revenue", "Revenue movements"),
      dashboard("renamed", "Board metrics (old)"),
    ];
    expect(sampleDashboardsInSeedOrder(api).map(({ id }) => id)).toEqual(["copy", "revenue", "segments", "engagement", "mine", "board-2", "renamed"]);
    const renamedSeed = [dashboard("v1", "Weekly usage"), dashboard("revenue", "Revenue movements"), dashboard("board", "Board summary")];
    expect(sampleDashboardsInSeedOrder(renamedSeed).map(({ id }) => id)).toEqual(["revenue", "v1", "board"]);
    expect(sampleDashboardsInSeedOrder([])).toEqual([]);
  });
  it("filters both GraphQL inventories at the read boundary, including direct route resolution", async () => {
    const workspaces = [provisioning, real, sample];
    const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async () => Response.json({ data: { accessibleWorkspaces: workspaces, managedWorkspaces: workspaces } }));
    await expect(fetchAccessibleWorkspaces()).resolves.toEqual([real, sample]);
    await expect(fetchManagedWorkspaces("organization")).resolves.toEqual([real, sample]);
    for (const [, request] of fetch.mock.calls) expect(JSON.parse(String(request?.body)).query).toContain("sample { dataset state initiator }");
  });
});
