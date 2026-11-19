import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { createControlProblemValidator } from "./problem-runtime";

interface Schema {
  pattern?: string;
  maxLength?: number;
  allOf?: Schema[];
  oneOf?: Schema[];
  properties?: Record<string, Schema>;
  items?: Schema;
}

const schema = JSON.parse(readFileSync(fileURLToPath(new URL("../../../../contracts/agent-workspace.schema.json", import.meta.url)), "utf8")) as { $defs: Record<string, Schema> };
const definitions = schema.$defs;
const connectorRoot = "workspace/connectors/0198ef28-1000-7000-8000-000000000001/catalog";
const encodeName = (name: string) => name.replaceAll("~", "~0").replaceAll("/", "~1");
const pathValidator = (definition: string) => createControlProblemValidator({
  $schema: "https://json-schema.org/draft/2020-12/schema",
  $defs: definitions,
  $ref: `#/$defs/${definition}`,
});
const canonical = pathValidator("CanonicalResourcePath");
const pathValidators = new Map<string, ReturnType<typeof pathValidator>>();
const acceptsPath = (definition: string, path: string) => {
  if (!pathValidators.has(definition)) pathValidators.set(definition, pathValidator(definition));
  return pathValidators.get(definition)!.safeParse(path).success;
};

function stringLimits(node: Schema): number[] {
  return [
    ...(node.maxLength === undefined ? [] : [node.maxLength]),
    ...Object.values(node.properties ?? {}).flatMap(stringLimits),
    ...(node.items ? stringLimits(node.items) : []),
    ...(node.oneOf ?? []).flatMap(stringLimits),
  ];
}

describe("Agent resource address contract", () => {
  it("accepts Organization addresses and rejects retired tenant addresses", () => {
    const identifier = "0198ef28-1000-7000-8000-000000000001";
    const addresses = {
      OrganizationResourcePath: "organization",
      OrganizationMemberPath: `organization/members/${identifier}`,
      OrganizationInvitationPath: `organization/invitations/${identifier}`,
      OrganizationWorkspaceMemberPath: `organization/workspaces/${identifier}/members/${identifier}`,
    };
    for (const [definition, path] of Object.entries(addresses)) {
      expect(canonical.safeParse(path).success, path).toBe(true);
      expect(acceptsPath(definition, path), definition).toBe(true);
      const retiredPath = path.replace("organization", "account");
      expect(canonical.safeParse(retiredPath).success, retiredPath).toBe(false);
      expect(acceptsPath(definition, retiredPath), definition).toBe(false);
    }
    const request = pathValidator("OrganizationInvitationCreateRequest");
    const input = { kind: "ORGANIZATION_INVITATION_CREATE", email: "member@example.com" };
    expect(request.safeParse({ path: "organization/invitations", input }).success).toBe(true);
    expect(request.safeParse({ path: "account/invitations", input }).success).toBe(false);
    expect(request.safeParse({
      path: "organization/invitations",
      input: { ...input, kind: "ACCOUNT_INVITATION_CREATE" },
    }).success).toBe(false);
  });

  it("rejects Group management while keeping direct member role changes", () => {
    const identifier = "0198ef28-1000-7000-8000-000000000001";
    const listing = pathValidator("AgentOrganizationListRequest");
    expect(listing.safeParse({ path: "organization/members", limit: 20 }).success).toBe(true);
    for (const path of ["organization/groups", `organization/groups/${identifier}/members`]) {
      expect(listing.safeParse({ path, limit: 20 }).success, path).toBe(false);
    }
    const reading = pathValidator("AgentOrganizationReadRequest");
    expect(reading.safeParse({ path: `organization/groups/${identifier}` }).success).toBe(false);
    const invoking = pathValidator("WorkspaceMemberRoleInvokeRequest");
    expect(invoking.safeParse({
      path: `workspace/members/${identifier}`,
      action: "set-role",
      input: { kind: "WORKSPACE_MEMBER_ROLE", role: "OWNER" },
    }).success).toBe(true);
    const operation = pathValidator("AgentAvailableOperation");
    for (const [action, kind] of [
      ["set-group-role", "WORKSPACE_GROUP_ROLE"],
      ["remove-group-role", "WORKSPACE_GROUP_ROLE_REMOVE"],
    ]) {
      expect(invoking.safeParse({
        path: "workspace/authorization", action, input: { kind, groupId: identifier, role: "OWNER" },
      }).success, action).toBe(false);
      expect(operation.safeParse({
        operation: "INVOKE", path: "workspace/authorization", action, inputKind: kind,
      }).success, action).toBe(false);
    }
    const authorization = pathValidator("AgentWorkspaceAuthorizationBody");
    const body = { authorizationRevision: "0", members: [] };
    expect(authorization.safeParse(body).success).toBe(true);
    expect(authorization.safeParse({ ...body, groupRoles: [] }).success).toBe(false);
    expect(authorization.safeParse({ ...body, principals: [] }).success).toBe(false);
    for (const definition of ["AgentWorkspacePrincipal", "AgentManagementPrincipal", "AgentManagementPrincipalKind"]) {
      expect(definitions).not.toHaveProperty(definition);
    }
  });

  it("accepts readable Unicode and escaped delimiters for every Catalog address depth", () => {
    for (const name of ["매출 😀", "a/b~c", "%2F%ZZ+?#|", ".", "..", "a\u2028b\u2029c"]) {
      const segment = encodeName(name);
      const entry = `${connectorRoot}/catalogs/${segment}`;
      const cases = {
        CatalogEntryResourcePath: entry,
        CatalogSelectionResourcePath: `${entry}/selection`,
        CatalogSchemaResourcePath: `${entry}/schemas/${segment}`,
        CatalogRelationResourcePath: `${entry}/schemas/${segment}/relations/${segment}`,
        CatalogOptionResourcePath: `${entry}/selection/options/schemas/${segment}/relations/${segment}`,
        CatalogOptionCollectionPath: `${entry}/selection/options/schemas/${segment}/relations`,
        CatalogCollectionPath: `${entry}/schemas/${segment}/relations`,
      };
      for (const [definition, path] of Object.entries(cases)) {
        expect(canonical.safeParse(path).success, path).toBe(true);
        expect(acceptsPath(definition, path), definition).toBe(true);
      }
    }
  });

  it("rejects empty segments, noncanonical tilde escapes and controls", () => {
    for (const segment of ["", "~", "~2", "bad~escape", "a\u0000b", "a\u001fb", "a\u007fb", "a\u009fb"]) {
      const path = `${connectorRoot}/catalogs/${segment}`;
      expect(canonical.safeParse(path).success, segment).toBe(false);
      expect(acceptsPath("CatalogEntryResourcePath", path), segment).toBe(false);
    }
    expect(canonical.safeParse(`${connectorRoot}//schemas/x`).success).toBe(false);
    expect(canonical.safeParse(`${connectorRoot}/`).success).toBe(false);
    expect(canonical.safeParse(`${connectorRoot}/catalogs/name\n`).success).toBe(false);
  });

  it("covers the maximum owner name at all three address levels and in a binary cursor", () => {
    const maximumName = "/".repeat(1024);
    const segment = encodeName(maximumName);
    const collection = `${connectorRoot}/catalogs/${segment}/selection/options/schemas/${segment}/relations`;
    const path = `${collection}/${segment}`;
    expect(Buffer.byteLength(path)).toBe(definitions.CanonicalResourcePath.maxLength);
    expect(canonical.safeParse(path).success).toBe(true);
    expect(acceptsPath("CatalogOptionResourcePath", path)).toBe(true);
    const binaryCursorBytes = 2 + 4 + Buffer.byteLength(collection) + 4 + Buffer.byteLength(maximumName);
    const cursorCharacters = Math.ceil(binaryCursorBytes * 4 / 3);
    expect(definitions.AgentListRequest.properties!.cursor.maxLength).toBe(cursorCharacters);
    expect(definitions.AgentSearchRequest.properties).not.toHaveProperty("cursor");
    expect(definitions.AgentResourceListingResponse.properties!.nextCursor.maxLength).toBe(cursorCharacters);
    for (const definition of ["CatalogResourceBody", "CatalogColumn", "CatalogPrimaryKey", "CatalogForeignKey", "ConnectorCatalogSelectionResourceBody", "CatalogRelationSelection", "CatalogSelectionResourceBody"]) {
      expect(stringLimits(definitions[definition]), definition).not.toContain(256);
      expect(stringLimits(definitions[definition]), definition).toContain(1024);
    }
  });
});
