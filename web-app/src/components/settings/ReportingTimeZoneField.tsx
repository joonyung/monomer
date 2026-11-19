import { useState, type ReactNode } from "react";
import { SettingsField } from "./SettingsPrimitives";
import { reportingTimeZoneLabel } from "./workspaceSettingsModel";

// Every member reads the Workspace Reporting time zone; only managers get the action to change it.
export function ReportingTimeZoneField({ timeZone, action }: { timeZone: string; action?: ReactNode }) {
  const [now] = useState(() => new Date());
  return (
    <SettingsField label="Reporting time zone" action={action}>
      <span className="tabular-nums">{reportingTimeZoneLabel(timeZone, now)}</span>
      <span className="mt-0.5 block type-meta text-foreground-secondary">Days and times in new Semantic results use this zone.</span>
    </SettingsField>
  );
}
