import { describe, expect, it } from "vitest";
import type { OrganizationMember, OrganizationMemberWorkspace } from "@/lib/auth/types";
import {
  USAGE_COUNTERS,
  filterMembers,
  firstName,
  formatSettingsDate,
  invitationExpiryLabel,
  memberAccessConsequences,
  memberFilterCounts,
  memberRemovalBlock,
  nameInitials,
  organizationRoleBlock,
  planLabel,
  usageRatio,
  usageWarning,
  workspaceAccessSummary,
  workspaceOwners,
} from "./organizationSettingsModel";

const workspace = (id: string, name: string, role: OrganizationMemberWorkspace["role"], ownerCount = 1): OrganizationMemberWorkspace => ({
  id, name, role, memberCount: 2, ownerCount,
});

const member = (
  id: string,
  displayName: string,
  overrides: Partial<Pick<OrganizationMember, "role" | "workspaces">> = {},
): OrganizationMember => ({
  user: { id, email: `${id}@acme.test`, displayName },
  role: "MEMBER",
  joinedAt: "2026-09-12T00:00:00Z",
  workspaces: [],
  ...overrides,
});

const members = [
  member("ana", "Ana Park", { role: "ADMIN", workspaces: [workspace("finance", "Finance", "OWNER", 2)] }),
  member("jordan", "Jordan Lee", { role: "ADMIN", workspaces: [workspace("growth", "Growth", "OWNER", 1), workspace("finance", "Finance", "OWNER", 2)] }),
  member("sam", "Sam Kim", { workspaces: [workspace("revenue", "Revenue Analytics", "EXPLORER"), workspace("growth", "Growth", "EDITOR")] }),
  member("taylor", "Taylor Wu"),
];

describe("Organization settings formatting", () => {
  it("formats dates, initials, first names, and plans for English UI", () => {
    expect(formatSettingsDate("2026-09-12T12:00:00Z")).toBe("Sep 12, 2026");
    expect(nameInitials("Ana  Park Lee")).toBe("AP");
    expect(nameInitials("  ")).toBe("?");
    expect(firstName("Sam Kim")).toBe("Sam");
    expect(planLabel("FREE")).toBe("Free plan");
  });

  it("names what is left once usage reaches 80% and stays quiet below it or without a limit", () => {
    const workspaces = USAGE_COUNTERS.find((counter) => counter.key === "workspaces")!;
    const people = USAGE_COUNTERS.find((counter) => counter.key === "members")!;
    expect(usageWarning(3, 5, workspaces)).toBeNull();
    expect(usageWarning(4, 5, workspaces)).toBe("1 workspace left");
    expect(usageWarning(25, 30, people)).toBe("5 members left");
    expect(usageWarning(30, 30, people)).toBe("Limit reached");
    expect(usageWarning(31, 30, people)).toBe("Limit reached");
    expect(usageWarning(12, null, people)).toBeNull();
    expect(usageRatio(12, 30)).toBeCloseTo(0.4);
    expect(usageRatio(40, 30)).toBe(1);
  });

  it("summarizes workspace access by name with an overflow count", () => {
    expect(workspaceAccessSummary([], 3)).toBeNull();
    expect(workspaceAccessSummary([{ name: "Ops" }, { name: "Finance" }, { name: "Growth" }, { name: "Analytics" }], 3))
      .toEqual({ names: "Analytics, Finance, Growth", overflow: 1 });
  });

  it("labels invitation expiry in whole days", () => {
    const now = new Date("2026-10-01T00:00:00Z");
    expect(invitationExpiryLabel({ expiresAt: "2026-10-06T12:00:00Z", status: "PENDING" }, now)).toBe("Expires in 5 days");
    expect(invitationExpiryLabel({ expiresAt: "2026-10-02T06:00:00Z", status: "PENDING" }, now)).toBe("Expires in 1 day");
    expect(invitationExpiryLabel({ expiresAt: "2026-10-01T06:00:00Z", status: "PENDING" }, now)).toBe("Expires today");
    expect(invitationExpiryLabel({ expiresAt: "2026-09-30T00:00:00Z", status: "PENDING" }, now)).toBe("Expired");
    expect(invitationExpiryLabel({ expiresAt: "2026-10-06T00:00:00Z", status: "EXPIRED" }, now)).toBe("Expired");
  });
});

describe("Organization member filters and blocking rules", () => {
  it("counts and filters members by search and Admin role", () => {
    expect(memberFilterCounts(members)).toEqual({ all: 4, admins: 2 });
    expect(filterMembers(members, "", "admins").map((value) => value.user.id)).toEqual(["ana", "jordan"]);
    expect(filterMembers(members, "SAM@ACME", "all").map((value) => value.user.id)).toEqual(["sam"]);
    expect(filterMembers(members, "sam", "admins")).toEqual([]);
  });

  it("blocks self changes, the last Admin, and the only owner of a workspace", () => {
    expect(memberRemovalBlock(members[0], members, true)?.reason).toBe("You can't remove yourself. Ask another Admin.");
    const jordan = memberRemovalBlock(members[1], members, false);
    expect(jordan?.reason).toBe("Jordan is the only owner of Growth. Make someone else an owner first.");
    expect(jordan?.soleOwnerWorkspaces.map((value) => value.id)).toEqual(["growth"]);
    expect(memberRemovalBlock(members[2], members, false)).toBeNull();
    const soleAdmin = [members[0], members[2]];
    expect(memberRemovalBlock(soleAdmin[0], soleAdmin, false)?.reason).toBe("Ana is the only Admin. Make another member an Admin first.");
    expect(organizationRoleBlock(soleAdmin[0], soleAdmin, false)).toBe("Ana is the only Admin. Make another member an Admin first.");
    expect(organizationRoleBlock(members[0], members, true)).toBe("You can't change your own role. Ask another Admin.");
    expect(organizationRoleBlock(members[3], members, false)).toBeNull();
    expect(organizationRoleBlock(members[2], members, false)).toBeNull();
  });

  it("lists the workspaces and apps a removed member loses", () => {
    expect(memberAccessConsequences(members[2], "Acme Analytics")).toEqual(["Loses access to Growth and Revenue Analytics", "Apps Sam connected can no longer reach Acme Analytics."]);
    expect(memberAccessConsequences(members[3], "Acme Analytics")).toEqual(["Apps Taylor connected can no longer reach Acme Analytics."]);
  });

  it("derives workspace owners from member roles", () => {
    expect(workspaceOwners("finance", members).map((value) => value.user.id)).toEqual(["ana", "jordan"]);
    expect(workspaceOwners("revenue", members)).toEqual([]);
  });
});
