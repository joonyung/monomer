import { renderToStaticMarkup } from "react-dom/server";
import { createElement } from "react";
import { expect, it } from "vitest";
import { REPLACEMENT_WORKSPACE_ROLES, WorkspaceRolePicker } from "./WorkspaceRolePicker";
import { ROLE_DESCRIPTIONS, WORKSPACE_ROLES } from "@/lib/auth/roles";

it("renders exactly four role choices and the selected role consequence", () => {
  for (const role of WORKSPACE_ROLES) {
    const markup = renderToStaticMarkup(createElement(WorkspaceRolePicker, { label: "Workspace role", roles: WORKSPACE_ROLES, value: role, onChange: () => undefined, disabled: false }));
    expect(markup.match(/aria-pressed=/g)).toHaveLength(4);
    expect(markup.match(/aria-pressed="true"/g)).toHaveLength(1);
    expect(markup).toContain(ROLE_DESCRIPTIONS[role]);
  }
});

it("offers only the non-owner roles, with their consequences, when replacing an owner", () => {
  const markup = renderToStaticMarkup(createElement(WorkspaceRolePicker, { label: "Role for Mara Chen after removing owner", roles: REPLACEMENT_WORKSPACE_ROLES, value: "EXPLORER", onChange: () => undefined, disabled: false }));
  expect(markup.match(/aria-pressed=/g)).toHaveLength(3);
  expect(markup).not.toContain(">Owner<");
  expect(markup).toContain(ROLE_DESCRIPTIONS.EXPLORER);
  expect(markup).toContain("Role for Mara Chen after removing owner");
});
