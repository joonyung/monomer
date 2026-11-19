import ReactMarkdown from "react-markdown";
import { Code2, Ellipsis, FileSearch, LoaderCircle, Network, RefreshCw, Sigma } from "lucide-react";
import type { DashboardJsonObject, DashboardQueryType, DashboardVariableDefinition, DashboardWidget } from "@/api/dashboard";
import { DashboardChartWidget } from "./DashboardChartWidget";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { widgetQueryNames } from "@/features/dashboard/widget-query-names";
import { DashboardTableWidget } from "./DashboardTableWidget";
import styles from "./DashboardText.module.css";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Badge";
import { IconButton } from "@/components/ui/IconButton";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "@/components/ui/Menu";
import { StateBlock } from "@/components/ui/StateBlock";
import { useDashboardWidgetEnvironment } from "./DashboardWidgetEnvironment";

type QueryWidget = Extract<DashboardWidget, { type: "CHART" | "TABLE" }>;

export interface DashboardWidgetMetricsTarget {
  lineage?: boolean;
  widgetTitle: string | null;
  queryNames: readonly string[];
  queryType: DashboardQueryType;
}

// Retry, run details, and metric definitions act on the query names the widget reads.
export interface DashboardWidgetActions {
  pending: boolean;
  onRefresh?: (queryNames: readonly string[]) => void;
  onShowRuns?: (queryNames: readonly string[]) => void;
  onShowMetrics?: (target: DashboardWidgetMetricsTarget) => void;
  onShowSql?: (target: DashboardWidgetMetricsTarget) => void;
}

interface DashboardWidgetRendererProps {
  widget: DashboardWidget;
  parameters: DashboardJsonObject;
  variables: DashboardJsonObject;
  variableDefinitions: Record<string, DashboardVariableDefinition>;
  results: Record<string, NamedDashboardResult>;
  queryTypes?: Readonly<Record<string, DashboardQueryType>>;
  ready: boolean;
  refreshing?: boolean;
  errorMessage?: string;
  loadingMessage?: string;
  onVariablesSet: (values: DashboardJsonObject, expectedRevision: number) => void;
  variableRevision: number;
  readOnly?: boolean;
  actions?: DashboardWidgetActions;
}

export function widgetAccessibleName(widget: QueryWidget): string {
  if (widget.title) return widget.title;
  if (widget.type === "CHART") return `Chart of ${widget.queryNames.join(", ")}`;
  return `Table of ${[...new Set([widget.queryName, ...(widget.module?.inputs.queries ?? [])])].join(", ")}`;
}

export function widgetHasMenu(widget: QueryWidget, actions: DashboardWidgetActions | undefined, queryTypes: Readonly<Record<string, DashboardQueryType>>): boolean {
  const queryNames = widgetQueryNames(widget);
  return Boolean(actions && (actions.onRefresh || actions.onShowRuns
    || actions.onShowMetrics && queryNames.some(name => queryTypes[name] === "SEMANTIC")
    || actions.onShowSql && queryNames.some(name => queryTypes[name] === "SQL")));
}

export function widgetHasHeader(widget: QueryWidget, actions: DashboardWidgetActions | undefined, queryTypes: Readonly<Record<string, DashboardQueryType>>): boolean {
  return Boolean(widget.title || widgetQueryNames(widget).some(name => queryTypes[name] === "SQL")
    || widget.type === "TABLE" && widgetHasMenu(widget, actions, queryTypes));
}

function LoadingWidget({ label = "Loading results…" }: { label?: string }) {
  return <div className="dashboard-widget-state flex h-full min-h-0 flex-1 flex-col"><StateBlock kind="loading" layout="inline" title={label} /></div>;
}

function FailedWidget({ widget, message, actions }: { widget: QueryWidget; message: string; actions?: DashboardWidgetActions }) {
  const queryNames = widgetQueryNames(widget);
  const { onRefresh, onShowRuns } = actions ?? {};
  const { failureAction } = useDashboardWidgetEnvironment();
  return <div className="dashboard-widget-state flex h-full min-h-0 flex-1 flex-col">
    <StateBlock kind="error" layout="inline"
      title={widget.type === "CHART" ? "This chart couldn't load." : "This table couldn't load."}
      description={<span className="dashboard-widget-state-description" tabIndex={0} title={message}>{message}</span>}
      action={<>
        {onRefresh ? <Button type="button" variant="secondary" size="sm" pending={actions?.pending} onClick={() => onRefresh(queryNames)}>Retry</Button> : null}
        {onShowRuns ? <Button type="button" variant="ghost" size="sm" onClick={() => onShowRuns(queryNames)}>Details</Button> : null}
        {failureAction}
      </>}
    />
  </div>;
}

function WidgetMenu({ widget, actions, queryTypes, floating }: { widget: QueryWidget; actions: DashboardWidgetActions; queryTypes: Readonly<Record<string, DashboardQueryType>>; floating: boolean }) {
  const queryNames = widgetQueryNames(widget);
  const semanticNames = queryNames.filter(name => queryTypes[name] === "SEMANTIC");
  const sqlNames = queryNames.filter(name => queryTypes[name] === "SQL");
  return <div data-widget-menu="" className={floating ? "absolute right-0 top-0 z-10 opacity-0 transition-opacity [@media(hover:none)]:opacity-100 group-hover/widget:opacity-100 focus-within:opacity-100 has-[[data-state=open]]:opacity-100 motion-reduce:transition-none" : "shrink-0"}>
    <Menu modal={false}>
      <MenuTrigger asChild><IconButton label={`More actions for ${widgetAccessibleName(widget)}`} icon={Ellipsis} /></MenuTrigger>
      <MenuContent align="end" className="min-w-[200px]">
        {actions.onRefresh ? <MenuItem icon={RefreshCw} disabled={actions.pending} onSelect={() => actions.onRefresh!(queryNames)}>Refresh this widget</MenuItem> : null}
        {actions.onShowRuns ? <MenuItem icon={FileSearch} onSelect={() => actions.onShowRuns!(queryNames)}>View run details</MenuItem> : null}
        {actions.onShowSql && sqlNames.length > 0 ? <MenuItem icon={Code2} onSelect={() => actions.onShowSql!({ widgetTitle: widget.title ?? null, queryNames: sqlNames, queryType: "SQL" })}>Show SQL</MenuItem> : null}
        {actions.onShowMetrics && semanticNames.length > 0 ? <MenuItem icon={Network} onSelect={() => actions.onShowMetrics!({ lineage: true, widgetTitle: widget.title ?? null, queryNames: semanticNames, queryType: "SEMANTIC" })}>Open Lineage</MenuItem> : null}
        {actions.onShowMetrics && semanticNames.length > 0 ? <MenuItem icon={Sigma} onSelect={() => actions.onShowMetrics!({ widgetTitle: widget.title ?? null, queryNames: semanticNames, queryType: "SEMANTIC" })}>Show metric definitions</MenuItem> : null}
      </MenuContent>
    </Menu>
  </div>;
}

export function DashboardWidgetRenderer({
  widget,
  parameters,
  variables,
  variableDefinitions,
  results,
  queryTypes = {},
  ready,
  refreshing = false,
  onVariablesSet,
  variableRevision,
  readOnly = false,
  errorMessage,
  loadingMessage,
  actions,
}: DashboardWidgetRendererProps) {
  const { chartPlaceholder } = useDashboardWidgetEnvironment();
  if (widget.type === "INPUT") return null;
  if (widget.type === "TEXT") {
    return <div data-dashboard-text="" className={styles.content}><ReactMarkdown skipHtml>{widget.content}</ReactMarkdown></div>;
  }
  const queryNames = widgetQueryNames(widget);
  const usesSql = queryNames.some(name => (ready ? results[name]?.queryType : queryTypes[name]) === "SQL");
  const hasMenu = widgetHasMenu(widget, actions, queryTypes);
  const hasHeader = widgetHasHeader(widget, actions, queryTypes);
  const menu = hasMenu && actions
    ? <WidgetMenu widget={widget} actions={actions} queryTypes={queryTypes} floating={!hasHeader} /> : null;
  const floatingBadge = usesSql && !hasHeader ? <div data-widget-sql-badge="floating" className="absolute left-0 top-0 z-10"><Badge variant="secondary">SQL</Badge></div> : null;
  const header = hasHeader ? <div data-widget-header="" className="mb-3 flex h-8 shrink-0 items-center justify-between gap-2">
    <div className="flex min-w-0 items-center gap-2">
      {widget.title ? <h2 className="min-w-0 truncate type-control text-foreground" title={widget.title}>{widget.title}</h2> : null}
      {usesSql ? <Badge variant="secondary" className="shrink-0">SQL</Badge> : null}
    </div>
    {menu}
  </div> : null;
  let body;
  if (!ready) {
    body = errorMessage ? <FailedWidget widget={widget} message={errorMessage} actions={actions} /> : <LoadingWidget label={loadingMessage} />;
  } else {
    const content = widget.type === "TABLE"
      ? <DashboardTableWidget widget={widget} results={results} parameters={parameters} variables={variables} />
      : chartPlaceholder ?? (
        <DashboardChartWidget
          accessibleName={widgetAccessibleName(widget)}
          moduleSource={widget.module.source}
          queryNames={widget.queryNames}
          parameterNames={widget.module.inputs.parameters}
          variableNames={widget.module.inputs.variables}
          parameters={parameters}
          variables={variables}
          variableDefinitions={variableDefinitions}
          results={results}
          onVariablesSet={onVariablesSet}
          variableRevision={variableRevision}
          readOnly={readOnly}
        />
      );
    body = <div aria-busy={refreshing} className={`relative h-full min-h-0 ${widget.type === "CHART" ? "dashboard-chart-widget-host" : ""} ${header ? "min-h-0 flex-1" : ""}`}>
      <div className={`h-full transition-[filter,opacity] duration-150 motion-reduce:transition-none ${refreshing ? "pointer-events-none grayscale opacity-50" : ""}`} inert={refreshing}>{content}</div>
      {refreshing ? <div role="status" className="pointer-events-none absolute inset-0 flex items-center justify-center bg-surface/20">
        <LoaderCircle aria-hidden="true" className="h-5 w-5 animate-spin text-foreground-secondary motion-reduce:animate-none" /><span className="sr-only">Updating results</span>
      </div> : null}{errorMessage ? <p role="alert" className={`absolute top-2 max-w-[240px] rounded-[4px] bg-background/90 px-2 py-1 type-status text-error shadow-ambient ${menu && !header ? "right-10" : "right-2"}`}>{errorMessage}</p> : null}</div>;
  }
  if (!header && !menu && !floatingBadge) return body;
  return <div className={`group/widget relative h-full min-h-0 ${header ? "flex flex-col" : ""}`}>
    {header ?? menu}
    {floatingBadge}
    {body}
  </div>;
}
