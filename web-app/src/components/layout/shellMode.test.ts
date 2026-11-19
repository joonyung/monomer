import { describe, expect, it } from "vitest";
import {
  applicationShellMode,
  shellNavigationLabel,
} from "@/components/layout/shellMode";

describe("application shell mode", () => {
  it("selects the Pages shell for root, detail, and demo routes", () => {
    expect(applicationShellMode("/workspaces/workspace-1/pages")).toBe("pages");
    expect(applicationShellMode("/workspaces/workspace-1/pages/page-1")).toBe("pages");
    expect(applicationShellMode("/dev/pages-demo")).toBe("pages");
    expect(applicationShellMode("/dev/pages-demo/page-1")).toBe("pages");
  });

  it("keeps similarly prefixed and ordinary routes in the Workspace shell", () => {
    expect(applicationShellMode("/pages-archive")).toBe("workspace");
    expect(applicationShellMode("/retired-route")).toBe("workspace");
    expect(applicationShellMode("/")).toBe("workspace");
  });

  it("names each shell navigation for assistive technology", () => {
    expect(shellNavigationLabel("workspace")).toBe("Workspace navigation");
    expect(shellNavigationLabel("pages")).toBe("Pages navigation");
  });
});
