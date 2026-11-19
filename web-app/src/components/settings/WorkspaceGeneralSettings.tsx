import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { RenameWorkspaceDialog } from "./RenameWorkspaceDialog";
import { ReportingTimeZoneDialog } from "./ReportingTimeZoneDialog";
import { ReportingTimeZoneField } from "./ReportingTimeZoneField";
import { CopyButton, SettingsField } from "./SettingsPrimitives";
import { WorkspaceSettingsShell } from "./WorkspaceSettingsShell";
import { formatSettingsDate } from "./organizationSettingsModel";
import {
  useInvalidateWorkspaceSettings,
  useWorkspaceSettingsContext,
  useWorkspaceSummaryQuery,
} from "./workspaceSettingsQueries";

export function WorkspaceGeneralSettings() {
  return (
    <WorkspaceSettingsShell section="general" title="General">
      <WorkspaceGeneralFields />
    </WorkspaceSettingsShell>
  );
}

function WorkspaceGeneralFields() {
  const context = useWorkspaceSettingsContext();
  const summary = useWorkspaceSummaryQuery(context.workspaceId);
  const invalidate = useInvalidateWorkspaceSettings(context.workspaceId);
  const [renameOpen, setRenameOpen] = useState(false);
  const [timeZoneOpen, setTimeZoneOpen] = useState(false);
  const pending = <span className="text-foreground-secondary">—</span>;
  return (
    <>
      <dl className="divide-y divide-border-light border-y border-border">
        <SettingsField
          label="Workspace name"
          action={<Button variant="secondary" size="sm" aria-label="Rename Workspace" onClick={() => setRenameOpen(true)}>Rename</Button>}
        >
          {context.workspaceName}
        </SettingsField>
        <SettingsField
          label="Workspace ID"
          valueClassName="break-all type-mono"
          action={<CopyButton value={context.workspaceId} label="Copy Workspace ID" copiedAnnouncement="Workspace ID copied" failedAnnouncement="Couldn't copy. Select the ID and copy it." />}
        >
          {context.workspaceId}
        </SettingsField>
        <SettingsField label="Organization">
          {context.organizationRole === "ADMIN" ? (
            <Link href={webPaths.organizationSettings(context.organizationId)} className="underline-offset-4 hover:underline focus-visible:underline">
              {context.organizationName}
            </Link>
          ) : context.organizationName}
        </SettingsField>
        <ReportingTimeZoneField
          timeZone={context.reportingTimeZone}
          action={context.capabilities.canManage
            ? <Button variant="secondary" size="sm" aria-label="Change Reporting time zone" onClick={() => setTimeZoneOpen(true)}>Change</Button>
            : undefined}
        />
        <SettingsField label="Members" valueClassName="type-body tabular-nums">
          {summary.data ? summary.data.memberCount : pending}
        </SettingsField>
        <SettingsField label="Created" valueClassName="type-body tabular-nums">
          {summary.data ? <time dateTime={summary.data.createdAt}>{formatSettingsDate(summary.data.createdAt)}</time> : pending}
        </SettingsField>
      </dl>
      {summary.isError ? (
        <p role="alert" className="mt-3 type-meta text-error">
          Couldn&apos;t load workspace details.{" "}
          <button type="button" className="underline underline-offset-4" onClick={() => void summary.refetch()}>Retry</button>
        </p>
      ) : null}
      {renameOpen && (
        <RenameWorkspaceDialog
          workspace={{ id: context.workspaceId, name: context.workspaceName }}
          onClose={() => setRenameOpen(false)}
          onRenamed={invalidate}
        />
      )}
      {timeZoneOpen && (
        <ReportingTimeZoneDialog
          workspace={{ id: context.workspaceId, reportingTimeZone: context.reportingTimeZone }}
          onClose={() => setTimeZoneOpen(false)}
          onChanged={invalidate}
        />
      )}
    </>
  );
}
