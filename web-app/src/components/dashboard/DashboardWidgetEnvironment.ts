import { createContext, useContext, type ReactNode } from "react";
import type { ChartWidgetWorkerFactory } from "@/features/chart-widget";
import type { TableWorkerFactory } from "@/features/table-widget/runtime/table-widget-runtime";

// A surface that cannot start the app's same-origin Worker URLs supplies its own Worker factories,
// and what to show when charts cannot run there. Unset fields keep the app's own behavior.
export interface DashboardWidgetEnvironment {
  readonly chartWorkerFactory?: ChartWidgetWorkerFactory;
  readonly tableWorkerFactory?: TableWorkerFactory;
  readonly chartPlaceholder?: ReactNode;
  readonly failureAction?: ReactNode;
}

const APP_ENVIRONMENT: DashboardWidgetEnvironment = {};

export const DashboardWidgetEnvironmentContext = createContext<DashboardWidgetEnvironment>(APP_ENVIRONMENT);

export function useDashboardWidgetEnvironment(): DashboardWidgetEnvironment {
  return useContext(DashboardWidgetEnvironmentContext);
}
