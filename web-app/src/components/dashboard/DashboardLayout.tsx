import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import { createPortal } from "react-dom";
import { registerCaptureRenderer } from "./capture/stage";
import type { DashboardDefinition, DashboardJsonObject, DashboardQueryType, DashboardValueControl, DashboardVariableDefinition, DashboardWidget, DashboardLayoutNode } from "@/api/dashboard";
import { widgetQueryNames } from "@/features/dashboard/widget-query-names";
import { pivotHeaderRowCount } from "@/features/table-widget/pivot/pivot-layout";
import { DashboardWidgetRenderer, widgetHasHeader, type DashboardWidgetActions } from "./DashboardWidgetRenderer";
import { DashboardVariableInput } from "./DashboardVariableInput";
import "./DashboardLayout.css";
import { StateBlock } from "@/components/ui/StateBlock";

interface DashboardLayoutProps {
  definition: Pick<DashboardDefinition, "widgets" | "layout">;
  queryTypes?: Readonly<Record<string, DashboardQueryType>>;
  parameters: DashboardJsonObject;
  parametersByWidgetId?: Record<string, DashboardJsonObject>;
  variables: DashboardJsonObject;
  variableRevision: number;
  variableRevisionsByWidgetId: Record<string, number>;
  variablesByWidgetId: Record<string, DashboardJsonObject>;
  variableDefinitions: Record<string, DashboardVariableDefinition>;
  resultsByWidgetId: Record<string, Record<string, import("@/features/dashboard/dashboard-result").NamedDashboardResult>>;
  displayedWidgetIds: ReadonlySet<string>;
  refreshingWidgetIds: ReadonlySet<string>;
  failureWidgetIds: ReadonlySet<string>;
  loadingMessagesByWidgetId?: Record<string, string>;
  errorMessagesByWidgetId?: Record<string, string>;
  onVariablesSet: (values: DashboardJsonObject, expectedRevision: number) => void;
  onInputVariablesSet?: (values: DashboardJsonObject) => void;
  sharedInputRevision?: number;
  onWidgetVariablesSet?: (widgetId: string, values: DashboardJsonObject, expectedRevision: number) => void;
  readOnly?: boolean;
  widgetActions?: DashboardWidgetActions;
}

function WidgetNode({
  widgetId,
  widgets,
  context,
}: {
  widgetId: string;
  widgets: Record<string, DashboardWidget>;
  context: Omit<DashboardLayoutProps, "definition">;
}) {
  const widget = widgets[widgetId];
  if (!widget) return <StateBlock kind="error" layout="inline" title={`Widget '${widgetId}' could not be found.`} />;
  if (widget.type === "INPUT") {
    const definition = context.variableDefinitions[widget.variableName];
    if (!definition) return <StateBlock kind="error" layout="inline" title={`Variable '${widget.variableName}' could not be found.`} />;
    const control = (widget as typeof widget & { control?: DashboardValueControl | null }).control;
    return (
      <DashboardVariableInput
        key={widget.variableName}
        name={widget.variableName}
        definition={definition}
        control={control}
        value={context.variables[widget.variableName] ?? null}
        disabled={context.readOnly}
        sharedRevision={context.sharedInputRevision}
        onSet={(value, expectedRevision) => context.onInputVariablesSet ? context.onInputVariablesSet({ [widget.variableName]: value }) : context.onVariablesSet({ [widget.variableName]: value }, expectedRevision ?? context.variableRevision)}
      />
    );
  }
  const queryNames = widgetQueryNames(widget);
  const ready = context.displayedWidgetIds.has(widgetId) || queryNames.length === 0;
  return (
    <DashboardWidgetRenderer
      widget={widget}
      parameters={context.parametersByWidgetId?.[widgetId] ?? context.parameters}
      variables={context.variablesByWidgetId[widgetId] ?? context.variables}
      variableDefinitions={context.variableDefinitions}
      results={context.resultsByWidgetId[widgetId] ?? {}}
      queryTypes={context.queryTypes}
      ready={ready}
      refreshing={context.refreshingWidgetIds.has(widgetId)}
      loadingMessage={context.loadingMessagesByWidgetId?.[widgetId]}
      errorMessage={context.errorMessagesByWidgetId?.[widgetId] ?? (context.failureWidgetIds.has(widgetId)
        ? (context.displayedWidgetIds.has(widgetId) ? "The latest run failed. Previous results are shown." : "The query failed.")
        : undefined)}
      variableRevision={context.variableRevisionsByWidgetId[widgetId] ?? context.variableRevision}
      onVariablesSet={context.onWidgetVariablesSet ? (values, revision) => context.onWidgetVariablesSet!(widgetId, values, revision) : context.onVariablesSet}
      readOnly={context.readOnly}
      actions={context.widgetActions}
    />
  );
}

const GUTTER = 16;
type LayoutContext = Omit<DashboardLayoutProps, "definition">;

function needsAllottedHeight(node: DashboardLayoutNode, widgets: Record<string, DashboardWidget>, parentDirection: "ROW" | "COLUMN" | null): boolean {
  if (node.type === "SPACER") return parentDirection === "COLUMN";
  if (node.type === "WIDGET") return widgets[node.widgetId]?.type === "CHART" || widgets[node.widgetId]?.type === "TABLE";
  return node.children.some((child) => needsAllottedHeight(child.node, widgets, node.direction));
}

function boxStyle(widget: DashboardWidget | undefined, slot: number, context: LayoutContext): CSSProperties | undefined {
  if (widget?.type !== "CHART" && widget?.type !== "TABLE") return undefined;
  const header = widgetHasHeader(widget, context.widgetActions, context.queryTypes ?? {}) ? 44 : 0;
  return {
    "--widget-header-height": `${header}px`,
    "--widget-body-minimum": `${widget.type === "CHART" ? 80 : 33 + 32 * ("pivot" in widget ? pivotHeaderRowCount(widget.pivot) : 1) + 3 * (widget.rowHeight ?? 40)}px`,
    "--widget-body-cap": `${widget.type === "CHART" ? 320 : 360}px`,
    "--widget-declared-slot": `${slot}px`,
  } as CSSProperties;
}

function minimumWidth(node: DashboardLayoutNode, widgets: Record<string, DashboardWidget>, variableDefinitions: Record<string, DashboardVariableDefinition>): number {
  if (node.type === "SPACER") return 0;
  if (node.type === "WIDGET") {
    const widget = widgets[node.widgetId];
    if (widget?.type === "INPUT") {
      const type = variableDefinitions[widget.variableName]?.type ?? "TEXT";
      return type.endsWith("_SET") || type.endsWith("_RANGE") ? 280 : 200;
    }
    return widget?.type === "TABLE" ? 280 : widget?.type === "TEXT" ? 240 : 140;
  }
  const childWidths = node.children.map((child) => minimumWidth(child.node, widgets, variableDefinitions));
  if (node.direction === "COLUMN") return Math.max(...childWidths);
  return childWidths.reduce((sum, width) => sum + width, 0) + (node.separation === "DIVIDER" ? 0 : GUTTER * (node.children.length - 1));
}

function useRowStacked(threshold: number) {
  const ref = useRef<HTMLDivElement>(null);
  const [stacked, setStacked] = useState(false);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => setStacked(entry.contentRect.width < threshold));
    observer.observe(element);
    return () => observer.disconnect();
  }, [threshold]);
  return [ref, stacked] as const;
}

function surfaceClass(widget: DashboardWidget | undefined): string {
  return widget?.type === "INPUT" ? "dashboard-input-surface" : "dashboard-widget-surface";
}

function LayoutNode({ node, widgets, context, slot, parentDirection }: { node: DashboardLayoutNode; widgets: Record<string, DashboardWidget>; context: LayoutContext; slot: number; parentDirection: "ROW" | "COLUMN" | null }) {
  if (node.type === "SPACER") return <div aria-hidden="true" className="dashboard-layout-spacer" style={{ flex: parentDirection === "COLUMN" ? "1 0 0px" : "none", minHeight: 0 }} />;
  if (node.type === "WIDGET") {
    const widget = widgets[node.widgetId];
    const box = widget?.type === "CHART" || widget?.type === "TABLE";
    return <div data-capture-widget-id={node.widgetId} data-change-id={node.widgetId} data-layout-box={box ? "" : undefined} data-layout-widget-type={widget?.type} data-layout-content={box ? undefined : ""} style={box ? { ...boxStyle(widget, slot, context), flex: "1 0 auto" } : { flex: widget?.type === "TEXT" && parentDirection === "ROW" ? "1 0 auto" : "none" }} className={`dashboard-layout-surface relative min-w-0 border border-border bg-background ${box ? "overflow-hidden" : ""} ${surfaceClass(widget)}`}><WidgetNode widgetId={node.widgetId} widgets={widgets} context={context} /><span aria-hidden="true" className="change-highlight-overlay" /></div>;
  }
  return <StackNode node={node} widgets={widgets} context={context} slot={slot} />;
}

function StackNode({ node, widgets, context, slot }: { node: Extract<DashboardLayoutNode, { type: "STACK" }>; widgets: Record<string, DashboardWidget>; context: LayoutContext; slot: number }) {
  const separation = node.separation ?? "GAP";
  const row = node.direction === "ROW";
  const allotted = node.children.map((child) => needsAllottedHeight(child.node, widgets, node.direction));
  const weightedTotal = node.children.filter((_, index) => allotted[index]).reduce((sum, child) => sum + (child.weight ?? 0), 0) || 1;
  const childSlot = (weight: number) => row ? slot : slot * weight / weightedTotal;
  const childMinimums = node.children.map((child) => minimumWidth(child.node, widgets, context.variableDefinitions));
  const [rowRef, stacked] = useRowStacked(row ? minimumWidth(node, widgets, context.variableDefinitions) : 0);
  return (
    <div
      ref={row ? rowRef : undefined}
      data-layout-stacked={stacked ? "" : undefined}
      data-layout-direction={node.direction}
      data-layout-separation={separation}
      style={{ flex: "1 0 auto", flexDirection: row ? "row" : "column" }}
      className={`flex min-w-0 ${separation === "DIVIDER" ? "dashboard-layout-divided overflow-hidden border border-border bg-background" : "gap-4"} ${row ? "dashboard-layout-row" : "dashboard-layout-column"}`}
    >
      {node.children.map((child, index) => {
        const widgetId = child.node.type === "WIDGET" ? child.node.widgetId : undefined;
        const divided = separation === "DIVIDER" && widgetId !== undefined;
        const widget = widgetId === undefined ? undefined : widgets[widgetId];
        const box = divided && (widget?.type === "CHART" || widget?.type === "TABLE");
        const flex = row ? `${child.weight} 1 0px` : allotted[index] ? `${child.weight} 1 0px` : "none";
        return (
          <div
            key={child.node.id}
            data-layout-spacer-child={child.node.type === "SPACER" ? "" : undefined}
            data-capture-widget-id={divided ? widgetId : undefined}
            data-change-id={divided ? widgetId : undefined}
            data-layout-box={box ? "" : undefined}
            data-layout-widget-type={widget?.type} data-layout-content={divided && !box ? "" : undefined}
            style={{ flex, minWidth: row ? `${childMinimums[index]}px` : undefined, ...(box ? boxStyle(widget, childSlot(child.weight ?? 0), context) : {}) }}
            className={`dashboard-layout-child relative flex min-w-0 flex-col ${box ? "overflow-hidden" : ""} ${divided ? surfaceClass(widget) : ""}`}
          >
            {divided && widgetId !== undefined
              ? <WidgetNode widgetId={widgetId} widgets={widgets} context={context} />
              : <LayoutNode node={child.node} widgets={widgets} context={context} slot={childSlot(child.weight ?? 0)} parentDirection={node.direction} />}
            {divided && <span aria-hidden="true" className="change-highlight-overlay" />}
          </div>
        );
      })}
    </div>
  );
}

export function DashboardLayout({
  definition,
  queryTypes,
  parameters,
  parametersByWidgetId,
  variables,
  variablesByWidgetId,
  variableRevision,
  variableRevisionsByWidgetId,
  variableDefinitions,
  resultsByWidgetId,
  displayedWidgetIds,
  refreshingWidgetIds,
  failureWidgetIds,
  errorMessagesByWidgetId,
  loadingMessagesByWidgetId,
  onVariablesSet,
  onInputVariablesSet,
  sharedInputRevision,
  onWidgetVariablesSet,
  readOnly = false,
  widgetActions,
}: DashboardLayoutProps) {
  const context = { queryTypes, parameters, parametersByWidgetId, variables, variablesByWidgetId, variableRevision, variableRevisionsByWidgetId, variableDefinitions, resultsByWidgetId, displayedWidgetIds, refreshingWidgetIds, failureWidgetIds, errorMessagesByWidgetId, loadingMessagesByWidgetId, onVariablesSet, onInputVariablesSet, sharedInputRevision, onWidgetVariablesSet, readOnly, widgetActions };
  const layoutRef = useRef<HTMLDivElement>(null);
  const [captureHost, setCaptureHost] = useState<HTMLElement | null>(null);
  useLayoutEffect(() => {
    if (layoutRef.current) return registerCaptureRenderer(layoutRef.current, setCaptureHost);
  }, []);
  const content = (capture: boolean) => (
    <div ref={capture ? undefined : layoutRef} data-capture-layout={capture ? "" : undefined} className="dashboard-layout-container min-w-0 space-y-4">
      {definition.layout.sections.map((section) => (
        <section key={section.id} className="dashboard-section relative grid min-w-0" data-section-id={section.id} data-section-height={section.height.mode} style={{ gridTemplateRows: `minmax(${section.height.mode === "SCALED" ? section.height.scale * 300 : 0}px, auto)` }}>
          <div className="dashboard-section-frame flex min-w-0 flex-col">
            <LayoutNode node={section.root} widgets={definition.widgets} context={context} slot={section.height.mode === "SCALED" ? section.height.scale * 300 : 0} parentDirection={null} />
          </div>
        </section>
      ))}
    </div>
  );
  return <>{content(false)}{captureHost && createPortal(content(true), captureHost)}</>;
}
