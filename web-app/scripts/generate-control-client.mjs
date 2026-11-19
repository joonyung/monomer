import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { dirname, relative, resolve } from "node:path";
import { argv } from "node:process";
import { fileURLToPath } from "node:url";
import openapiTypeScript, { astToString } from "openapi-typescript";
import typescript from "typescript";
import { parseStrictJson } from "../../scripts/strict-json.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const webAppRoot = resolve(dirname(scriptPath), "..");
const repositoryRoot = resolve(webAppRoot, "..");
const rootContractPath = resolve(repositoryRoot, "contracts/control-service.openapi.json");
const generatedDirectory = resolve(webAppRoot, "src/api/control");
const generatedTypesPath = resolve(generatedDirectory, "generated.ts");
const generatedOperationsPath = resolve(generatedDirectory, "generated-operations.ts");
const bundleManifestPath = resolve(generatedDirectory, "bundle-manifest.json");
const problemSchemaPath = resolve(generatedDirectory, "problem-schema.json");

const controlUuidV7Pattern = "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";
const controlSemanticDecimal38_18Pattern = "^(?:0|-?(?:(?:[1-9][0-9]{0,19})(?:\\.[0-9]{0,17}[1-9])?|0\\.[0-9]{0,17}[1-9]))$";
const controlSafeJsonIntegerMaximum = 9_007_199_254_740_991;

const sourceDocuments = [
  {
    sourcePath: "contracts/metric-display.schema.json",
    id: "https://monomer.cc/schemas/metric-display.schema.json",
    componentPrefix: "MetricDisplay",
  },
  {
    sourcePath: "control-service/modules/connector/src/main/resources/schema/connector.schema.json",
    id: "monomer://schema/connector/1",
    componentPrefix: "Connector",
  },
  {
    sourcePath: "contracts/agent-workspace.schema.json",
    id: "https://monomer.cc/schemas/agent-workspace/v1",
    componentPrefix: "AgentWorkspace",
  },
  {
    sourcePath: "contracts/problem-details.schema.json",
    id: "https://monomer.cc/schemas/problem-details/v1",
    componentPrefix: "ProblemDetails",
  },
  {
    sourcePath: "control-service/modules/semantic/src/main/resources/schema/semantic/model.schema.json",
    id: "monomer://schema/semantic/model/3",
    componentPrefix: "SemanticModel",
  },
  {
    sourcePath: "control-service/modules/semantic/src/main/resources/schema/semantic/metric.schema.json",
    id: "monomer://schema/semantic/metric/3",
    componentPrefix: "SemanticMetric",
  },
  {
    sourcePath: "control-service/modules/semantic/src/main/resources/schema/semantic/query.schema.json",
    id: "monomer://schema/semantic/query/4",
    componentPrefix: "SemanticQuery",
  },
  {
    sourcePath: "control-service/modules/semantic/src/main/resources/schema/semantic/time-spine.schema.json",
    id: "monomer://schema/semantic/time-spine/1",
    componentPrefix: "SemanticTimeSpine",
  },
  {
    sourcePath: "control-service/modules/dashboard/src/main/resources/schema/dashboard.schema.json",
    id: "monomer://schema/dashboard/1",
    componentPrefix: "Dashboard",
  },
];

function sha256Text(value) {
  return `sha256:${createHash("sha256").update(value).digest("hex")}`;
}

function canonicalizeGeneratorJson(value) {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) {
      throw new Error("Control client generator cannot serialize a non-finite JSON number");
    }
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalizeGeneratorJson).join(",")}]`;
  }
  if (typeof value !== "object") {
    throw new Error("Control client generator received a non-JSON value");
  }
  return `{${Object.keys(value)
    .sort((left, right) => left.localeCompare(right, "en"))
    .map((key) => `${JSON.stringify(key)}:${canonicalizeGeneratorJson(value[key])}`)
    .join(",")}}`;
}

function typeScriptJsonLiteral(value) {
  return JSON.stringify(JSON.parse(canonicalizeGeneratorJson(value)), null, 2);
}

function assertUnicodeScalars(value, sourcePath) {
  for (let index = 0; index < value.length; index += 1) {
    const codeUnit = value.charCodeAt(index);
    if (codeUnit >= 0xd800 && codeUnit <= 0xdbff) {
      const nextCodeUnit = value.charCodeAt(index + 1);
      if (!Number.isInteger(nextCodeUnit) || nextCodeUnit < 0xdc00 || nextCodeUnit > 0xdfff) {
        throw new Error(`Control client schema has a lone high surrogate: ${sourcePath}`);
      }
      index += 1;
    } else if (codeUnit >= 0xdc00 && codeUnit <= 0xdfff) {
      throw new Error(`Control client schema has a lone low surrogate: ${sourcePath}`);
    }
  }
}

function assertSchemaUnicodeScalars(value, sourcePath) {
  if (typeof value === "string") {
    assertUnicodeScalars(value, sourcePath);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((item) => assertSchemaUnicodeScalars(item, sourcePath));
    return;
  }
  if (!isPlainObject(value)) return;
  for (const [key, nestedValue] of Object.entries(value)) {
    assertUnicodeScalars(key, sourcePath);
    assertSchemaUnicodeScalars(nestedValue, sourcePath);
  }
}

function parseSchemaDocument(documentText, sourcePath) {
  try {
    parseStrictJson(documentText);
  } catch (error) {
    const message = error instanceof Error ? error.message : "unknown validation failure";
    throw new Error(`Control client schema is not strict I-JSON: ${sourcePath}: ${message}`);
  }
  try {
    const document = JSON.parse(documentText);
    assertSchemaUnicodeScalars(document, sourcePath);
    return document;
  } catch (error) {
    const message = error instanceof Error ? error.message : "unknown parsing failure";
    throw new Error(`Control client schema is not strict I-JSON: ${sourcePath}: ${message}`);
  }
}

function resolveJsonPointer(document, fragment, reference) {
  if (!fragment.startsWith("/")) {
    throw new Error(`Control client generator has an unsupported JSON Pointer: ${reference}`);
  }
  let resolvedValue = document;
  for (const encodedSegment of fragment.slice(1).split("/")) {
    const segment = encodedSegment.replaceAll("~1", "/").replaceAll("~0", "~");
    if (
      resolvedValue === null
      || typeof resolvedValue !== "object"
      || !(segment in resolvedValue)
    ) {
      throw new Error(`Control client generator cannot resolve JSON Pointer: ${reference}`);
    }
    resolvedValue = resolvedValue[segment];
  }
  return resolvedValue;
}

function splitReference(reference) {
  const fragmentStart = reference.indexOf("#");
  return fragmentStart === -1
    ? { documentReference: reference, fragment: "" }
    : {
      documentReference: reference.slice(0, fragmentStart),
      fragment: reference.slice(fragmentStart + 1),
    };
}

function sourceRelativePath(path) {
  return relative(repositoryRoot, path).replaceAll("\\", "/");
}

async function loadSchemaRegistry() {
  const rootText = await readFile(rootContractPath, "utf8");
  const rootDocument = parseSchemaDocument(rootText, sourceRelativePath(rootContractPath));
  const documentsByPath = new Map();
  const documentsById = new Map();
  const root = {
    document: rootDocument,
    documentPath: rootContractPath,
    sourcePath: sourceRelativePath(rootContractPath),
    sourceSha256: sha256Text(rootText),
    componentPrefix: null,
    isRootContract: true,
  };
  documentsByPath.set(rootContractPath, root);

  for (const sourceDocument of sourceDocuments) {
    const documentPath = resolve(repositoryRoot, sourceDocument.sourcePath);
    const documentText = await readFile(documentPath, "utf8");
    const document = parseSchemaDocument(documentText, sourceDocument.sourcePath);
    if (document.$id !== sourceDocument.id) {
      throw new Error(
        `Control client source identity drift for ${sourceDocument.sourcePath}: expected ${sourceDocument.id}`,
      );
    }
    if (documentsById.has(document.$id)) {
      throw new Error(`Duplicate Control client schema $id: ${document.$id}`);
    }
    const entry = {
      document,
      documentPath,
      sourcePath: sourceDocument.sourcePath,
      sourceSha256: sha256Text(documentText),
      componentPrefix: sourceDocument.componentPrefix,
      isRootContract: false,
    };
    documentsByPath.set(documentPath, entry);
    documentsById.set(document.$id, entry);
  }

  return { root, documentsByPath, documentsById };
}

function resolveReference(sourceDocument, reference, registry) {
  const { documentReference, fragment } = splitReference(reference);
  let targetDocument;
  if (documentReference.length === 0) {
    targetDocument = sourceDocument;
  } else if (
    documentReference.startsWith("monomer://")
    || documentReference.startsWith("https://monomer.cc/schemas/")
  ) {
    targetDocument = registry.documentsById.get(documentReference);
  } else if (documentReference.startsWith("http:") || documentReference.startsWith("https:")) {
    throw new Error(`Control client source references a remote schema: ${reference}`);
  } else {
    targetDocument = registry.documentsByPath.get(
      resolve(dirname(sourceDocument.documentPath), documentReference),
    );
  }
  if (targetDocument === undefined) {
    throw new Error(`Control client source cannot resolve schema: ${reference}`);
  }
  return {
    document: targetDocument,
    fragment,
    schema: fragment
      ? resolveJsonPointer(targetDocument.document, fragment, reference)
      : targetDocument.document,
  };
}

function componentNameForReference(reference) {
  const { document, fragment } = reference;
  if (document.isRootContract) {
    return null;
  }
  if (fragment.length === 0) {
    return `${document.componentPrefix}Document`;
  }
  const definitionPrefix = "/$defs/";
  if (fragment.startsWith(definitionPrefix)) {
    const definitionName = fragment.slice(definitionPrefix.length);
    if (definitionName.length > 0 && !definitionName.includes("/")) {
      return `${document.componentPrefix}${definitionName}`;
    }
  }
  throw new Error(
    `Control client can only bundle owner schemas and paths under named $defs: ${document.sourcePath}#${fragment}`,
  );
}

function collectSchemaReferences(
  value,
  sourceDocument,
  registry,
  reachableDocuments,
  visitedReferences,
) {
  if (Array.isArray(value)) {
    for (const item of value) {
      collectSchemaReferences(item, sourceDocument, registry, reachableDocuments, visitedReferences);
    }
    return;
  }
  if (value === null || typeof value !== "object") {
    return;
  }
  if (typeof value.$ref === "string") {
    const resolvedReference = resolveReference(sourceDocument, value.$ref, registry);
    if (!resolvedReference.document.isRootContract) {
      reachableDocuments.add(resolvedReference.document);
    }
    const referenceIdentity = `${resolvedReference.document.documentPath}#${resolvedReference.fragment}`;
    if (!visitedReferences.has(referenceIdentity)) {
      visitedReferences.add(referenceIdentity);
      collectSchemaReferences(
        resolvedReference.schema,
        resolvedReference.document,
        registry,
        reachableDocuments,
        visitedReferences,
      );
    }
  }
  for (const nestedValue of Object.values(value)) {
    collectSchemaReferences(nestedValue, sourceDocument, registry, reachableDocuments, visitedReferences);
  }
}

function transformReferences(value, sourceDocument, registry) {
  if (Array.isArray(value)) {
    return value.map((item) => transformReferences(item, sourceDocument, registry));
  }
  if (value === null || typeof value !== "object") {
    return value;
  }
  const transformed = Object.fromEntries(
    Object.entries(value).map(([key, nestedValue]) => [
      key,
      transformReferences(nestedValue, sourceDocument, registry),
    ]),
  );
  if (typeof value.$ref === "string") {
    const resolvedReference = resolveReference(sourceDocument, value.$ref, registry);
    if (!resolvedReference.document.isRootContract && /^\/\$defs\/[^/]+\//.test(resolvedReference.fragment)) {
      const resolvedSchema = transformReferences(resolvedReference.schema, resolvedReference.document, registry);
      const siblings = Object.fromEntries(Object.entries(transformed).filter(([key]) => key !== "$ref"));
      return Object.keys(siblings).length === 0 ? resolvedSchema : { allOf: [resolvedSchema, siblings] };
    }
    const componentName = componentNameForReference(resolvedReference);
    if (componentName !== null) {
      transformed.$ref = `#/components/schemas/${componentName}`;
    }
  }
  return transformed;
}

function withoutDefinitions(schema) {
  return Object.fromEntries(Object.entries(schema).filter(([key]) => key !== "$defs"));
}

function collectReferenceLocations(value, locations) {
  if (Array.isArray(value)) {
    value.forEach((item) => collectReferenceLocations(item, locations));
    return locations;
  }
  if (value !== null && typeof value === "object") {
    if (typeof value.$ref === "string") {
      locations.push(value.$ref);
    }
    Object.values(value).forEach((nestedValue) => collectReferenceLocations(nestedValue, locations));
  }
  return locations;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function requirePlainObject(value, context) {
  if (!isPlainObject(value)) {
    throw new Error(`Control client ${context} must be an object`);
  }
  return value;
}

function cloneJson(value) {
  if (Array.isArray(value)) return value.map(cloneJson);
  if (!isPlainObject(value)) return value;
  return Object.fromEntries(Object.entries(value).map(([key, nestedValue]) => [key, cloneJson(nestedValue)]));
}

function componentAliasTarget(reference, componentSection) {
  const prefix = `#/components/${componentSection}/`;
  if (!reference.startsWith(prefix)) return null;
  const encodedName = reference.slice(prefix.length);
  if (encodedName.length === 0 || encodedName.includes("/")) {
    throw new Error(`Control client component alias has an invalid target: ${reference}`);
  }
  return encodedName.replaceAll("~1", "/").replaceAll("~0", "~");
}

function dereferenceComponentAliases(bundle) {
  if (!isPlainObject(bundle.components)) {
    throw new Error("Control client bundle is missing components");
  }
  const resolvedSections = {};
  for (const [componentSection, sourceSection] of Object.entries(bundle.components)) {
    if (!isPlainObject(sourceSection)) {
      resolvedSections[componentSection] = sourceSection;
      continue;
    }
    const resolvedComponents = {};
    const resolveComponent = (componentName, stack) => {
      if (componentName in resolvedComponents) return resolvedComponents[componentName];
      const sourceComponent = sourceSection[componentName];
      if (sourceComponent === undefined) {
        throw new Error(`Control client component alias cannot resolve ${componentSection}/${componentName}`);
      }
      if (!isPlainObject(sourceComponent) || typeof sourceComponent.$ref !== "string") {
        resolvedComponents[componentName] = cloneJson(sourceComponent);
        return resolvedComponents[componentName];
      }
      const targetName = componentAliasTarget(sourceComponent.$ref, componentSection);
      if (targetName === null) {
        resolvedComponents[componentName] = cloneJson(sourceComponent);
        return resolvedComponents[componentName];
      }
      const siblingKeys = Object.keys(sourceComponent).filter((key) =>
        key !== "$ref" && key !== "description" && key !== "summary",
      );
      if (siblingKeys.length > 0) {
        resolvedComponents[componentName] = cloneJson(sourceComponent);
        return resolvedComponents[componentName];
      }
      if (stack.includes(componentName)) {
        throw new Error(`Control client component alias cycle: ${[...stack, componentName].join(" -> ")}`);
      }
      const targetComponent = resolveComponent(targetName, [...stack, componentName]);
      const annotations = Object.fromEntries(
        Object.entries(sourceComponent).filter(([key]) => key !== "$ref"),
      );
      resolvedComponents[componentName] = { ...cloneJson(targetComponent), ...annotations };
      return resolvedComponents[componentName];
    };
    for (const componentName of Object.keys(sourceSection).sort((left, right) => left.localeCompare(right, "en"))) {
      resolveComponent(componentName, []);
    }
    resolvedSections[componentSection] = resolvedComponents;
  }
  return {
    ...bundle,
    components: {
      ...bundle.components,
      ...resolvedSections,
    },
  };
}

function bundleControlContract(registry) {
  const reachableDocuments = new Set();
  collectSchemaReferences(registry.root.document, registry.root, registry, reachableDocuments, new Set());
  const externalSchemas = {};
  const resolverEntries = [];
  const orderedDocuments = [...reachableDocuments].sort((left, right) =>
    left.sourcePath.localeCompare(right.sourcePath, "en"),
  );

  for (const sourceDocument of orderedDocuments) {
    const documentComponentName = `${sourceDocument.componentPrefix}Document`;
    externalSchemas[documentComponentName] = transformReferences(
      withoutDefinitions(sourceDocument.document),
      sourceDocument,
      registry,
    );
    resolverEntries.push({
      source: sourceDocument.document.$id,
      sourcePath: sourceDocument.sourcePath,
      pointer: "#",
      component: documentComponentName,
    });
    for (const definitionName of Object.keys(sourceDocument.document.$defs ?? {}).sort((left, right) =>
      left.localeCompare(right, "en"),
    )) {
      const componentName = `${sourceDocument.componentPrefix}${definitionName}`;
      externalSchemas[componentName] = transformReferences(
        sourceDocument.document.$defs[definitionName],
        sourceDocument,
        registry,
      );
      resolverEntries.push({
        source: sourceDocument.document.$id,
        sourcePath: sourceDocument.sourcePath,
        pointer: `#/$defs/${definitionName}`,
        component: componentName,
      });
    }
  }

  const rootContract = transformReferences(registry.root.document, registry.root, registry);
  const bundleWithoutComponentAliases = {
    ...rootContract,
    components: {
      ...rootContract.components,
      schemas: {
        ...rootContract.components.schemas,
        ...externalSchemas,
      },
    },
  };
  const bundle = dereferenceComponentAliases(bundleWithoutComponentAliases);
  for (const reference of collectReferenceLocations(bundle, [])) {
    if (!reference.startsWith("#/")) {
      throw new Error(`Control client bundle retained a non-local schema reference: ${reference}`);
    }
    resolveJsonPointer(bundle, reference.slice(1), reference);
  }

  const dependencyGraph = orderedDocuments.map((sourceDocument) => ({
    id: sourceDocument.document.$id,
    sourcePath: sourceDocument.sourcePath,
    sha256: sourceDocument.sourceSha256,
  }));
  return { bundle, dependencyGraph, resolverEntries };
}

function schemaHasSingleType(schemaObject, type) {
  return schemaObject.type === type;
}

function controlTypeTransform(schemaObject) {
  if (schemaHasSingleType(schemaObject, "string") && schemaObject.pattern === controlUuidV7Pattern) {
    return typescript.factory.createTypeReferenceNode("ControlUuidV7");
  }
  if (
    schemaHasSingleType(schemaObject, "integer")
    && schemaObject.minimum === 0
    && schemaObject.maximum === controlSafeJsonIntegerMaximum
  ) {
    return typescript.factory.createTypeReferenceNode("ControlSafeJsonInteger");
  }
  if (
    schemaHasSingleType(schemaObject, "string")
    && schemaObject.pattern === controlSemanticDecimal38_18Pattern
  ) {
    return typescript.factory.createTypeReferenceNode("ControlSemanticDecimal38_18");
  }
  return undefined;
}

function editPathPatternType(pattern) {
  // Only path categories survive in TypeScript; character classes, lengths and name validity remain runtime checks.
  const partialSuffix = String.raw`)(?:/[\s\S]*)?$`;
  if (pattern.startsWith("^/(?:") && pattern.endsWith(partialSuffix)) {
    const roots = pattern.slice(5, -partialSuffix.length).split("|");
    if (!roots.every((root) => /^[A-Za-z_][A-Za-z0-9_]*$/.test(root))) {
      throw new Error(`Unsupported Agent edit path pattern: ${pattern}`);
    }
    return typescript.factory.createUnionTypeNode(roots.flatMap((root) => [
      typescript.factory.createLiteralTypeNode(typescript.factory.createStringLiteral(`/${root}`)),
      typescript.factory.createTemplateLiteralType(
        typescript.factory.createTemplateHead(`/${root}/`),
        [typescript.factory.createTemplateLiteralTypeSpan(
          typescript.factory.createKeywordTypeNode(typescript.SyntaxKind.StringKeyword),
          typescript.factory.createTemplateTail(""),
        )],
      ),
    ]));
  }
  if (!pattern.startsWith("^") || !pattern.endsWith("$")) {
    throw new Error(`Agent edit path pattern must be anchored: ${pattern}`);
  }
  const source = pattern.slice(1, -1).replace(/\[[^\]]+\]/g, "@").replace(/\(\?![^()]*\)/g, "");
  const optional = source.match(/\(\?:(\/[^()]*)\)\?/);
  const variants = optional
    ? [source.replace(optional[0], ""), source.replace(optional[0], optional[1])]
    : [source];
  const types = variants.map((variant) => {
    const chunks = [""];
    for (const [index, segment] of variant.split("/").entries()) {
      if (index > 0) chunks[chunks.length - 1] += "/";
      if (/^[A-Za-z0-9_-]*$/.test(segment)) {
        chunks[chunks.length - 1] += segment;
      } else if (segment === ".*") {
        chunks.push("");
      } else if (segment.includes("@") && /^(?:@(?:\{\d+(?:,\d*)?\}|[+*?])?|[A-Za-z0-9_-]|\(\?:|\)[+*?]?)+$/.test(segment)) {
        chunks.push("");
      } else {
        throw new Error(`Unsupported Agent edit path pattern: ${pattern}`);
      }
    }
    if (chunks.length === 1) {
      return typescript.factory.createLiteralTypeNode(typescript.factory.createStringLiteral(chunks[0]));
    }
    return typescript.factory.createTemplateLiteralType(
      typescript.factory.createTemplateHead(chunks[0]),
      chunks.slice(1).map((chunk, index) => typescript.factory.createTemplateLiteralTypeSpan(
        typescript.factory.createKeywordTypeNode(typescript.SyntaxKind.StringKeyword),
        index === chunks.length - 2
          ? typescript.factory.createTemplateTail(chunk)
          : typescript.factory.createTemplateMiddle(chunk),
      )),
    );
  });
  return types.length === 1 ? types[0] : typescript.factory.createUnionTypeNode(types);
}

function agentContractTypeTransform(bundle) {
  const resolveSchema = (schema) => schema.$ref
    ? resolveJsonPointer(bundle, schema.$ref.slice(1), schema.$ref)
    : schema;
  const pathTypes = new Map();
  const singletonItems = new Set();
  const addPathType = (schema) => {
    const pathSchema = resolveSchema(schema);
    if (typeof pathSchema.const === "string") return;
    const pattern = pathSchema.pattern ?? pathSchema.allOf?.find((item) => item.pattern)?.pattern;
    if (!pattern) throw new Error("Agent edit path lacks a concrete owner pattern");
    pathTypes.set(pattern, editPathPatternType(pattern));
  };
  const addOperationPaths = (schema) => {
    const operation = resolveSchema(schema);
    if (operation.properties?.path && !operation.properties.path.$ref && operation.properties.path.const === undefined) {
      addPathType(operation.properties.path);
    }
    for (const branch of operation.oneOf ?? operation.allOf ?? []) addOperationPaths(branch);
  };
  for (const branch of bundle.components.schemas.AgentEditRequest.oneOf) {
    const request = resolveSchema(branch);
    addPathType(request.properties.path);
    const patch = request.properties.patch;
    if (patch.minItems === 1 && patch.maxItems === 1) singletonItems.add(patch.items.$ref);
    addOperationPaths(patch.items);
  }
  addPathType(bundle.components.schemas.AgentWorkspaceDashboardSessionResourcePath);
  addPathType(bundle.components.schemas.AgentWorkspaceDashboardSessionQueryRunResourcePath);
  return (schemaObject) => {
    if (schemaObject.type === "string" && pathTypes.has(schemaObject.pattern)) {
      return pathTypes.get(schemaObject.pattern);
    }
    if (schemaObject.type === "array" && schemaObject.minItems === 1 && schemaObject.maxItems === 1
      && singletonItems.has(schemaObject.items?.$ref)) {
      const reference = schemaObject.items.$ref;
      const componentName = reference.slice("#/components/schemas/".length);
      const itemType = typescript.factory.createIndexedAccessTypeNode(
        typescript.factory.createIndexedAccessTypeNode(
          typescript.factory.createTypeReferenceNode("components"),
          typescript.factory.createLiteralTypeNode(typescript.factory.createStringLiteral("schemas")),
        ),
        typescript.factory.createLiteralTypeNode(typescript.factory.createStringLiteral(componentName)),
      );
      return typescript.factory.createTupleTypeNode([itemType]);
    }
    return controlTypeTransform(schemaObject);
  };
}

function controlTypeInjection() {
  return [
    "declare const controlConstraintBrand: unique symbol;",
    "export type ControlUuidV7 = string & { readonly [controlConstraintBrand]: \"uuid-v7\" };",
    "export type ControlSafeJsonInteger = number & { readonly [controlConstraintBrand]: \"safe-json-integer\" };",
    "export type ControlSemanticDecimal38_18 = string & { readonly [controlConstraintBrand]: \"semantic-decimal-38-18\" };",
  ].join("\n");
}

async function generateOpenApiTypes(bundle) {
  const ast = await openapiTypeScript(bundle, {
    alphabetize: true,
    inject: controlTypeInjection(),
    silent: true,
    transform: agentContractTypeTransform(bundle),
  });
  return astToString(ast);
}

const controlHttpMethods = [
  ["get", "GET"],
  ["put", "PUT"],
  ["post", "POST"],
  ["patch", "PATCH"],
  ["delete", "DELETE"],
  ["options", "OPTIONS"],
  ["head", "HEAD"],
  ["trace", "TRACE"],
];

function resolveBundleObjectReference(bundle, value, context) {
  if (!isPlainObject(value)) {
    throw new Error(`Control client ${context} must be an object`);
  }
  if (typeof value.$ref !== "string") return value;
  if (!value.$ref.startsWith("#/")) {
    throw new Error(`Control client ${context} has a non-local reference: ${value.$ref}`);
  }
  const resolved = resolveJsonPointer(bundle, value.$ref.slice(1), value.$ref);
  if (!isPlainObject(resolved)) {
    throw new Error(`Control client ${context} reference does not resolve to an object: ${value.$ref}`);
  }
  return resolveBundleObjectReference(bundle, resolved, context);
}

function responseDefinition(bundle, responseStatus, response) {
  if (!/^[1-5][0-9]{2}$/.test(responseStatus)) {
    throw new Error(`Control client operation has a non-numeric response status: ${responseStatus}`);
  }
  const resolvedResponse = resolveBundleObjectReference(bundle, response, `response ${responseStatus}`);
  const contentTypes = resolvedResponse.content === undefined
    ? []
    : Object.keys(requirePlainObject(resolvedResponse.content, `response content ${responseStatus}`))
      .map((contentType) => contentType.toLowerCase())
      .sort((left, right) => left.localeCompare(right, "en"));
  const requiredHeaders = resolvedResponse.headers === undefined
    ? []
    : Object.entries(requirePlainObject(resolvedResponse.headers, `response headers ${responseStatus}`))
      .flatMap(([headerName, header]) => {
        const resolvedHeader = resolveBundleObjectReference(bundle, header, `response header ${headerName}`);
        return resolvedHeader.required === true ? [headerName] : [];
      })
      .sort((left, right) => left.localeCompare(right, "en"));
  return {
    contentTypes,
    requiredHeaders,
    status: Number(responseStatus),
  };
}

function buildOperationDefinitions(bundle) {
  if (!isPlainObject(bundle.paths)) {
    throw new Error("Control client bundle is missing paths");
  }
  const operationIds = new Set();
  const definitions = [];
  for (const pathTemplate of Object.keys(bundle.paths).sort((left, right) => left.localeCompare(right, "en"))) {
    const pathItem = bundle.paths[pathTemplate];
    if (!isPlainObject(pathItem) || typeof pathItem.$ref === "string") {
      throw new Error(`Control client path item must be inline: ${pathTemplate}`);
    }
    for (const [methodName, method] of controlHttpMethods) {
      const operation = pathItem[methodName];
      if (operation === undefined) continue;
      if (!isPlainObject(operation) || typeof operation.operationId !== "string" || operation.operationId.length === 0) {
        throw new Error(`Control client operation must declare an operationId: ${methodName.toUpperCase()} ${pathTemplate}`);
      }
      if (operationIds.has(operation.operationId)) {
        throw new Error(`Control client has duplicate operationId: ${operation.operationId}`);
      }
      if (!isPlainObject(operation.responses)) {
        throw new Error(`Control client operation is missing responses: ${operation.operationId}`);
      }
      operationIds.add(operation.operationId);
      definitions.push({
        method,
        operationId: operation.operationId,
        pathTemplate,
        responses: Object.entries(operation.responses)
          .map(([responseStatus, response]) => responseDefinition(bundle, responseStatus, response))
          .sort((left, right) => left.status - right.status),
      });
    }
  }
  return definitions.sort((left, right) => left.operationId.localeCompare(right.operationId, "en"));
}

function schemaComponentNameForReference(reference) {
  const prefix = "#/components/schemas/";
  if (!reference.startsWith(prefix)) return null;
  const encodedName = reference.slice(prefix.length);
  if (encodedName.length === 0 || encodedName.includes("/")) {
    throw new Error(`Control Problem schema closure has an invalid component reference: ${reference}`);
  }
  return encodedName.replaceAll("~1", "/").replaceAll("~0", "~");
}

function encodeJsonPointerSegment(segment) {
  return segment.replaceAll("~", "~0").replaceAll("/", "~1");
}

function collectProblemComponentDependencies(value, componentSchemas, selectedComponentNames) {
  if (Array.isArray(value)) {
    value.forEach((item) => collectProblemComponentDependencies(item, componentSchemas, selectedComponentNames));
    return;
  }
  if (!isPlainObject(value)) return;
  if (typeof value.$ref === "string") {
    const componentName = schemaComponentNameForReference(value.$ref);
    if (componentName !== null) {
      const componentSchema = componentSchemas[componentName];
      if (componentSchema === undefined) {
        throw new Error(`Control Problem schema closure cannot resolve ${value.$ref}`);
      }
      if (!selectedComponentNames.has(componentName)) {
        selectedComponentNames.add(componentName);
        collectProblemComponentDependencies(componentSchema, componentSchemas, selectedComponentNames);
      }
    } else if (!value.$ref.startsWith("#/")) {
      throw new Error(`Control Problem schema closure retained an external reference: ${value.$ref}`);
    }
  }
  Object.values(value).forEach((nestedValue) =>
    collectProblemComponentDependencies(nestedValue, componentSchemas, selectedComponentNames),
  );
}

function rewriteProblemSchemaReferences(value) {
  if (Array.isArray(value)) return value.map(rewriteProblemSchemaReferences);
  if (!isPlainObject(value)) return value;
  return Object.fromEntries(Object.entries(value).map(([key, nestedValue]) => {
    if (key === "$ref" && typeof nestedValue === "string") {
      const componentName = schemaComponentNameForReference(nestedValue);
      if (componentName !== null) {
        return [key, `#/$defs/${encodeJsonPointerSegment(componentName)}`];
      }
      if (!nestedValue.startsWith("#/$defs/")) {
        throw new Error(`Control Problem schema closure retained an unsupported reference: ${nestedValue}`);
      }
    }
    return [key, rewriteProblemSchemaReferences(nestedValue)];
  }));
}

function buildProblemSchemaClosure(bundle) {
  if (!isPlainObject(bundle.components) || !isPlainObject(bundle.components.schemas)) {
    throw new Error("Control client bundle is missing schema components");
  }
  const componentSchemas = bundle.components.schemas;
  if (componentSchemas.ProblemDetails === undefined) {
    throw new Error("Control client bundle is missing ProblemDetails");
  }
  const selectedComponentNames = new Set(["ProblemDetails"]);
  collectProblemComponentDependencies(
    componentSchemas.ProblemDetails,
    componentSchemas,
    selectedComponentNames,
  );
  const definitions = Object.fromEntries([...selectedComponentNames]
    .sort((left, right) => left.localeCompare(right, "en"))
    .map((componentName) => [
      componentName,
      rewriteProblemSchemaReferences(componentSchemas[componentName]),
    ]));
  const problemSchemaClosure = {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    $ref: "#/$defs/ProblemDetails",
    $defs: definitions,
  };
  for (const reference of collectReferenceLocations(problemSchemaClosure, [])) {
    if (!reference.startsWith("#/$defs/")) {
      throw new Error(`Control Problem schema closure retained an unsupported reference: ${reference}`);
    }
    resolveJsonPointer(problemSchemaClosure, reference.slice(1), reference);
  }
  return problemSchemaClosure;
}

function generatedTypeHeader(sourceSha256, manifestSha256) {
  return [
    "/**",
    " * Generated by web-app/scripts/generate-control-client.mjs.",
    " * Do not edit directly.",
    ` * Source: contracts/control-service.openapi.json (${sourceSha256})`,
    ` * Resolver manifest: bundle-manifest.json (${manifestSha256})`,
    " */",
    "",
  ].join("\n");
}

function generatedOperationsText(operationDefinitions, sourceSha256, manifestSha256) {
  const definitionsByOperationId = Object.fromEntries(operationDefinitions.map((definition) => [
    definition.operationId,
    definition,
  ]));
  return [
    generatedTypeHeader(sourceSha256, manifestSha256),
    'import type { ControlOperationDefinition } from "./operation";',
    'import type { ControlOperationId } from "./types";',
    "",
    "export const controlOperationDefinitions: {",
    "  [OperationId in ControlOperationId]: ControlOperationDefinition<OperationId>;",
    `} = ${typeScriptJsonLiteral(definitionsByOperationId)};`,
    "",
  ].join("\n");
}

async function buildArtifacts() {
  const registry = await loadSchemaRegistry();
  const { bundle, dependencyGraph, resolverEntries } = bundleControlContract(registry);
  const bundleText = `${canonicalizeGeneratorJson(bundle)}\n`;
  const operationDefinitions = buildOperationDefinitions(bundle);
  const problemSchemaText = `${canonicalizeGeneratorJson(buildProblemSchemaClosure(bundle))}\n`;
  const manifestWithoutDigest = {
    schemaVersion: 2,
    generator: {
      package: "openapi-typescript",
      version: "7.13.0",
    },
    source: {
      sourcePath: registry.root.sourcePath,
      sha256: registry.root.sourceSha256,
    },
    bundle: {
      sha256: sha256Text(bundleText),
      localReferencesOnly: true,
    },
    generatedProblemSchema: {
      sha256: sha256Text(problemSchemaText),
    },
    dependencyGraph,
    resolver: resolverEntries,
  };
  const manifest = {
    ...manifestWithoutDigest,
    manifestSha256: `sha256:${createHash("sha256").update(canonicalizeGeneratorJson(manifestWithoutDigest)).digest("hex")}`,
  };
  const manifestText = `${canonicalizeGeneratorJson(manifest)}\n`;
  const openApiTypes = await generateOpenApiTypes(bundle);
  return {
    generatedOperationsText: generatedOperationsText(
      operationDefinitions,
      registry.root.sourceSha256,
      manifest.manifestSha256,
    ),
    generatedTypesText: `${generatedTypeHeader(
      registry.root.sourceSha256,
      manifest.manifestSha256,
    )}${openApiTypes}`,
    manifestText,
    manifest,
    problemSchemaText,
  };
}

async function assertFileCurrent(path, expectedText) {
  let currentText;
  try {
    currentText = await readFile(path, "utf8");
  } catch (error) {
    if (error && typeof error === "object" && error.code === "ENOENT") {
      throw new Error(`Control client artifact is missing: ${sourceRelativePath(path)}`);
    }
    throw error;
  }
  if (currentText !== expectedText) {
    throw new Error(`Control client artifact is stale: ${sourceRelativePath(path)}; run npm run generate:control-client`);
  }
}

export async function generateControlClient({ checkOnly }) {
  const artifacts = await buildArtifacts();
  if (checkOnly) {
    await Promise.all([
      assertFileCurrent(generatedTypesPath, artifacts.generatedTypesText),
      assertFileCurrent(generatedOperationsPath, artifacts.generatedOperationsText),
      assertFileCurrent(bundleManifestPath, artifacts.manifestText),
      assertFileCurrent(problemSchemaPath, artifacts.problemSchemaText),
    ]);
  } else {
    await Promise.all([
      writeFile(generatedTypesPath, artifacts.generatedTypesText, "utf8"),
      writeFile(generatedOperationsPath, artifacts.generatedOperationsText, "utf8"),
      writeFile(bundleManifestPath, artifacts.manifestText, "utf8"),
      writeFile(problemSchemaPath, artifacts.problemSchemaText, "utf8"),
    ]);
  }
  return artifacts;
}

export const _internal = {
  editPathPatternType,
  buildOperationDefinitions,
  buildProblemSchemaClosure,
  bundleControlContract,
  componentNameForReference,
  dereferenceComponentAliases,
  loadSchemaRegistry,
  parseSchemaDocument,
  resolveReference,
};

if (argv[1] && resolve(argv[1]) === scriptPath) {
  await generateControlClient({ checkOnly: argv.slice(2).includes("--check") });
}
