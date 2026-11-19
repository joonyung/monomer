import { useEffect, useId, useRef, useState } from "react";
import { Check } from "lucide-react";
import type {
  DashboardJsonObject,
  DashboardJsonValue,
  DashboardParameterDefinition,
} from "@/api/dashboard";
import {
  dashboardValuesEqual,
  isDashboardControlValueValid,
} from "@/features/dashboard/dashboard-value";
import { dashboardParameterLabel, formatDashboardParameterValue } from "@/features/dashboard/dashboard-parameter-presentation";
import { Button } from "@/components/ui/Button";
import {
  DashboardValueEditor,
  isDashboardValueUnset,
  type DashboardEditorValue,
  unsetDashboardValue,
} from "./DashboardValueEditor";

interface DashboardParameterToolbarProps {
  definitions: Record<string, DashboardParameterDefinition>;
  values: DashboardJsonObject;
  pendingName?: string | null;
  onSet: (name: string, value: DashboardJsonValue) => void;
}

function sameValue(type: DashboardParameterDefinition["type"], left: DashboardEditorValue, right: DashboardJsonValue | undefined): boolean {
  return !isDashboardValueUnset(left) && dashboardValuesEqual(type, left, right);
}

function isSetValue(value: DashboardEditorValue): value is DashboardJsonValue {
  return !isDashboardValueUnset(value);
}

function ParameterControl({
  name,
  definition,
  value,
  pending,
  toolbarPending,
  onSet,
}: {
  name: string;
  definition: DashboardParameterDefinition;
  value: DashboardJsonValue | undefined;
  pending: boolean;
  toolbarPending: boolean;
  onSet: (name: string, value: DashboardJsonValue) => void;
}) {
  const [draftValue, setDraftValue] = useState<DashboardEditorValue>(value === undefined ? unsetDashboardValue() : value);
  const previousServerValue = useRef(value);

  useEffect(() => {
    if (JSON.stringify(previousServerValue.current) === JSON.stringify(value)) return;
    previousServerValue.current = value;
    setDraftValue(value === undefined ? unsetDashboardValue() : value);
  }, [value]);

  const valid = !isDashboardValueUnset(draftValue)
    && (draftValue !== null || definition.nullable)
    && isDashboardControlValueValid(definition.type, draftValue, definition.control);
  const unchanged = sameValue(definition.type, draftValue, value);
  const label = definition.label ?? name;

  return (
    <div className="min-w-0" data-testid={`dashboard-parameter-${name}`}>
      <DashboardValueEditor
        type={definition.type}
        value={draftValue}
        nullable={definition.nullable}
        label={label}
        description={definition.description}
        control={definition.control}
        disabled={toolbarPending}
        onChange={setDraftValue}
      />
      <div className="mt-2 flex justify-end">
        <Button
          variant="secondary"
          size="sm"
          disabled={!valid || unchanged || toolbarPending}
          onClick={() => {
            if (isSetValue(draftValue)) onSet(name, draftValue);
          }}
        >
          <Check aria-hidden="true" />
          {pending ? "Setting…" : "Set"}
          <span className="sr-only"> {label}</span>
        </Button>
      </div>
    </div>
  );
}

// The Draft review's preview inputs: a summary of the shared Draft's Parameter values that opens
// in place into per-Parameter controls. Setting one changes only the shared Draft preview.
export function DashboardParameterToolbar({
  definitions,
  values,
  pendingName = null,
  onSet,
}: DashboardParameterToolbarProps) {
  const [editing, setEditing] = useState(false);
  const panelId = useId();
  const parameters = Object.entries(definitions).sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0);
  if (parameters.length === 0) return null;

  return (
    <section className="shrink-0 border-b border-border bg-background" aria-label="Preview inputs" data-testid="dashboard-parameter-toolbar">
      <div className="flex flex-wrap items-center gap-2 px-5 py-3 sm:px-8">
        {parameters.length > 0 ? (
          <>
            <h2 className="mr-1 type-group text-foreground-secondary">Preview inputs</h2>
            <ul className="contents">
              {parameters.map(([name, definition]) => {
                const value = values[name];
                return (
                  <li key={name} className="inline-flex h-8 min-w-0 max-w-full items-center gap-1.5 rounded-[4px] border border-border px-2.5 type-control">
                    <span className="shrink-0 text-foreground-secondary">{definition.label ?? dashboardParameterLabel(name)}</span>
                    <span className="min-w-0 truncate tabular-nums text-foreground">{value === undefined ? "Not set" : formatDashboardParameterValue(definition, value)}</span>
                  </li>
                );
              })}
            </ul>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              aria-expanded={editing}
              aria-controls={panelId}
              onClick={() => setEditing((current) => !current)}
            >
              {editing ? "Done" : "Change"}
              <span className="sr-only"> preview inputs</span>
            </Button>
          </>
        ) : null}
      </div>
      {editing && parameters.length > 0 ? (
        <div id={panelId} className="grid grid-cols-1 gap-x-6 gap-y-4 border-t border-border-light px-5 py-4 sm:grid-cols-2 sm:px-8 xl:grid-cols-3">
          {parameters.map(([name, definition]) => (
            <ParameterControl
              key={name}
              name={name}
              definition={definition}
              value={values[name]}
              pending={pendingName === name}
              toolbarPending={pendingName !== null}
              onSet={onSet}
            />
          ))}
        </div>
      ) : null}
    </section>
  );
}
