import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import console from "node:console";
import { fileURLToPath, URL } from "node:url";
import ts from "typescript";

const webRoot = fileURLToPath(new URL("../", import.meta.url));
const virtualRoot = path.join(webRoot, "src/features/chart-widget/authoring");

function valueType(definition) {
  if (!definition) throw new Error("Missing declared Dashboard input.");
  const types = {
    TEXT: "string", NUMBER: "string", BOOLEAN: "boolean", DATE: "string", TIMESTAMP: "string",
    TEXT_SET: "readonly string[]", NUMBER_SET: "readonly string[]", DATE_SET: "readonly string[]",
    TIMESTAMP_SET: "readonly string[]", DATE_RANGE: "Readonly<{ start: string; end: string }>",
    TIMESTAMP_RANGE: "Readonly<{ start: string; end: string }>",
  };
  if (!types[definition.type]) throw new Error(`Unsupported Dashboard input type: ${definition.type}`);
  return `${types[definition.type]}${definition.nullable ? " | null" : ""}`;
}

function objectType(entries) {
  return `{ ${entries.map(([name, type]) => `readonly ${JSON.stringify(name)}: ${type}`).join("; ")} }`;
}

function declarations(definition, widget, queryColumns) {
  const parameters = objectType(widget.module.inputs.parameters.map((name) => [name, valueType(definition.parameters[name])]));
  const variables = objectType(widget.module.inputs.variables.map((name) => [name, valueType(definition.variables[name])]));
  const queries = objectType(widget.queryNames.map((name) => {
    if (!Object.hasOwn(definition.queries, name)) throw new Error(`Missing declared Dashboard query: ${name}`);
    const columns = queryColumns[name];
    const row = columns ? objectType(columns.map((column) => [column.name,
      `${column.logicalType === "BOOLEAN" ? "boolean" : "string"}${column.nullable ? " | null" : ""}`,
    ])) : "Readonly<Record<string, string | boolean | null>>";
    return [name, `Omit<ChartQueryResult, 'rows'> & { readonly rows: readonly ${row}[] }`];
  }));
  return `import type { EChartsOption } from "echarts";
import type { ChartRenderFrame, ChartHandle, ChartQueryResult, ChartEventName, ChartEventQuery, SafeChartEventParams } from "../contract/abi";
type Parameters = ${parameters};
type Variables = ${variables};
type Frame = Omit<ChartRenderFrame, 'queries' | 'parameters' | 'variables'> & {
  readonly queries: ${queries}; readonly parameters: Parameters; readonly variables: Variables;
};
type EventFrame = Frame & { setVariables(values: Partial<Variables>): void };
type Handle = Omit<ChartHandle, 'on'> & {
  on(name: ChartEventName, handler: (event: SafeChartEventParams, frame: EventFrame) => void): void;
  on(name: ChartEventName, query: ChartEventQuery, handler: (event: SafeChartEventParams, frame: EventFrame) => void): void;
};
declare global {
  function defineChart(module: { setup?: (context: Readonly<{ chart: Handle }>) => void; render(frame: Frame): EChartsOption }): unknown;
  namespace Intl { interface NumberFormat { format(value: string): string; } }
}
`;
}

/** Type-checks source without evaluating it or admitting it to the runtime. */
export function checkChartSource({ definition, widgetId, queryColumns = {}, sourceName = "chart-widget.ts" }) {
  const widget = definition.widgets[widgetId];
  if (widget?.type !== "CHART") throw new Error(`Not a Chart Widget: ${widgetId}`);
  const sourcePath = path.join(virtualRoot, "chart-widget.ts");
  const ambientPath = path.join(virtualRoot, "chart-inputs.d.ts");
  const dashboardTypesPath = path.join(virtualRoot, "dashboard-types.d.ts");
  const files = new Map([
    [sourcePath, widget.module.source],
    [ambientPath, declarations(definition, widget, queryColumns)],
    [dashboardTypesPath, 'export type DashboardFrameColumn = Readonly<{ name: string; logicalType: string; nullable: boolean; numberFormat: Readonly<{ style: "decimal" | "currency" | "percent" | "unit"; currency?: string; unit?: string; minimumFractionDigits: number; maximumFractionDigits: number; signDisplay: "negative" }> | null }>;'],
  ]);
  const options = {
    target: ts.ScriptTarget.ES2023, module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.Bundler,
    lib: ["lib.es2023.d.ts"], types: [], strict: true, noEmit: true, skipLibCheck: true,
    paths: { "@/features/dashboard/dashboard-result": [dashboardTypesPath] },
  };
  const host = ts.createCompilerHost(options);
  const readFile = host.readFile.bind(host);
  const fileExists = host.fileExists.bind(host);
  host.readFile = (name) => files.get(name) ?? readFile(name);
  host.fileExists = (name) => files.has(name) || fileExists(name);
  host.getSourceFile = (name, languageVersion) => {
    const source = host.readFile(name);
    return source === undefined ? undefined : ts.createSourceFile(name, source, languageVersion, true);
  };
  const program = ts.createProgram([sourcePath, ambientPath], options, host);
  const sourceFile = program.getSourceFile(sourcePath);
  const diagnostics = ts.getPreEmitDiagnostics(program).map((diagnostic) => {
    const start = diagnostic.file?.getLineAndCharacterOfPosition(diagnostic.start ?? 0);
    return {
      sourceName: diagnostic.file?.fileName === sourcePath ? sourceName : diagnostic.file?.fileName ?? sourceName,
      line: (start?.line ?? 0) + 1, column: (start?.character ?? 0) + 1,
      code: `TS${diagnostic.code}`, message: ts.flattenDiagnosticMessageText(diagnostic.messageText, "\n"),
    };
  });
  const add = (node, message) => {
    const location = sourceFile.getLineAndCharacterOfPosition(node.getStart(sourceFile));
    diagnostics.push({ sourceName, line: location.line + 1, column: location.character + 1, code: "CHART_ABI", message });
  };
  const checker = program.getTypeChecker();
  const standardLibraryDirectory = path.dirname(ts.getDefaultLibFilePath(options));
  const isMissingValue = (expression) => !expression
    || (checker.getTypeAtLocation(expression).flags & ts.TypeFlags.Undefined) !== 0;
  const checkStandardCall = (node) => {
    const declaration = checker.getResolvedSignature(node)?.getDeclaration();
    if (!declaration || path.dirname(declaration.getSourceFile().fileName) !== standardLibraryDirectory) return;
    const owner = declaration.parent;
    const ownerName = ts.isInterfaceDeclaration(owner) ? owner.name.text
      : ts.isTypeLiteralNode(owner) && ts.isVariableDeclaration(owner.parent) ? owner.parent.name.getText() : "";
    const methodName = declaration.name?.getText();
    const args = node.arguments ?? [];
    if (ownerName === "Number" && methodName === "toLocaleString") {
      add(node, "Use Intl.NumberFormat with an explicit locale; toLocaleString options are ignored in the chart runtime.");
    }
    if ((ownerName === "DateConstructor" && methodName === "now") || (ownerName === "Math" && methodName === "random")) {
      add(node, "Chart source cannot read the current time or ambient randomness.");
    }
    if (ownerName === "DateConstructor" && !methodName
      && (ts.isCallExpression(node) || args.length === 0)) {
      add(node, "Chart dates require new Date with an explicit date value; Date() and the current time are unavailable.");
    }
    let ancestor = owner.parent;
    while (ancestor && !ts.isModuleDeclaration(ancestor)) ancestor = ancestor.parent;
    if (!ancestor || ancestor.name.getText() !== "Intl") return;
    const constructorName = ownerName.replace(/Constructor$/, "");
    if (!methodName) {
      switch (constructorName) {
        case "NumberFormat": case "DateTimeFormat": case "Collator": case "PluralRules":
        case "ListFormat": case "RelativeTimeFormat": case "Segmenter": case "DisplayNames": case "Locale":
          if (isMissingValue(args[0])) add(node, `Intl.${constructorName} requires an explicit locale.`);
          if (constructorName === "DateTimeFormat") {
            const optionsType = args[1] && checker.getTypeAtLocation(args[1]);
            const timeZone = optionsType && checker.getPropertyOfType(optionsType, "timeZone");
            if (!timeZone || (checker.getTypeOfSymbolAtLocation(timeZone, args[1]).flags & ts.TypeFlags.Undefined) !== 0) {
              add(node, "Intl.DateTimeFormat requires an explicit timeZone option.");
            }
          }
      }
    }
    if (ownerName === "DateTimeFormat" && (methodName === "format" || methodName === "formatToParts") && isMissingValue(args[0])) {
      add(node, `Intl.DateTimeFormat.${methodName} requires an explicit date value.`);
    }
  };
  const visit = (node) => {
    if (ts.isCallExpression(node) || ts.isNewExpression(node)) checkStandardCall(node);
    if (node.kind === ts.SyntaxKind.AsyncKeyword || ts.isAwaitExpression(node)) {
      add(node, "Chart setup, render, and callbacks must be synchronous; async/await is unavailable.");
    }
    if (ts.isImportDeclaration(node) || ts.isImportEqualsDeclaration(node)
      || (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword)
      || (ts.isExportDeclaration(node) && node.moduleSpecifier)) {
      add(node, "Chart source cannot import or re-export external modules.");
    }
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  return diagnostics.sort((left, right) => left.line - right.line || left.column - right.column);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [definitionPath, widgetId, columnsPath] = process.argv.slice(2);
    if (!definitionPath || !widgetId) throw new Error("Usage: node scripts/check-chart-source.mjs <dashboard.json> <widget-id> [query-columns.json]");
    const diagnostics = checkChartSource({
      definition: JSON.parse(fs.readFileSync(definitionPath, "utf8")), widgetId,
      queryColumns: columnsPath ? JSON.parse(fs.readFileSync(columnsPath, "utf8")) : {},
      sourceName: `${definitionPath}#widgets.${widgetId}.module.source`,
    });
    for (const diagnostic of diagnostics) {
      console.error(`${diagnostic.sourceName}:${diagnostic.line}:${diagnostic.column} ${diagnostic.code}: ${diagnostic.message}`);
    }
    process.exitCode = diagnostics.length ? 1 : 0;
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
