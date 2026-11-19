import { createContext, useContext, type ReactNode } from "react";
import { browserKnowsTimeZone } from "@/lib/time-zone";

const DashboardInputTimeZoneContext = createContext<string | null>(null);

/** Dashboard time inputs read and show the Workspace Reporting time zone's wall clock when the reader knows the zone. */
export function DashboardInputTimeZoneProvider({ timeZone, children }: { timeZone: string | null; children: ReactNode }) {
  return <DashboardInputTimeZoneContext.Provider value={timeZone}>{children}</DashboardInputTimeZoneContext.Provider>;
}

/** The provided Reporting time zone, or UTC when it is unknown here or the browser lacks its data. */
export function useDashboardInputTimeZone(): string {
  const timeZone = useContext(DashboardInputTimeZoneContext);
  return timeZone && browserKnowsTimeZone(timeZone) ? timeZone : "UTC";
}

/** The note beside a time input naming the zone it is read in: "Asia/Seoul time" or "UTC". */
export function dashboardInputTimeZoneLabel(timeZone: string): string {
  return timeZone === "UTC" ? "UTC" : `${timeZone} time`;
}
