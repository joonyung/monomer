import { CornerDownLeft } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/Button";
import type { DashboardJsonValue, DashboardValueControl, DashboardValueType, DashboardVariableDefinition } from "@/api/dashboard";
import { dashboardValuesEqual, isDashboardControlValueValid } from "@/features/dashboard/dashboard-value";
import {
  DashboardValueEditor,
  isDashboardChoiceControl,
  isDashboardValueUnset,
  type DashboardEditorValue,
} from "./DashboardValueEditor";

interface DashboardVariableInputProps {
  name: string;
  definition: DashboardVariableDefinition;
  control?: DashboardValueControl | null;
  value: DashboardJsonValue;
  disabled?: boolean;
  sharedRevision?: number;
  onSet: (value: DashboardJsonValue, expectedRevision?: number) => void;
}

export function DashboardVariableInput({
  name,
  definition,
  control,
  value,
  disabled = false,
  onSet,
  sharedRevision,
}: DashboardVariableInputProps) {
  const [draft, setDraft] = useState<DashboardEditorValue>(value);
  const [baseRevision, setBaseRevision] = useState<number | null>(null);
  const [choicePending, setChoicePending] = useState<{ value: DashboardJsonValue } | null>(null);
  const previousValue = useRef(value);
  useEffect(() => { setChoicePending(null); }, [value, sharedRevision]);
  // A write that ends without changing the value (a failed Draft write) shows the shared value again.
  useEffect(() => { if (!disabled) setChoicePending(null); }, [disabled]);
  // Accepted inputs arrive as new objects; only a changed value replaces an unapplied draft.
  useEffect(() => {
    const changed = !dashboardValuesEqual(definition.type, previousValue.current, value);
    previousValue.current = value;
    if (changed && sharedRevision === undefined) setDraft(value);
  }, [value, sharedRevision, definition.type]);
  useEffect(() => {
    if (sharedRevision === undefined) return;
    if (baseRevision === null) setDraft(value);
    else if (dashboardValuesEqual(definition.type, draft, value)) setBaseRevision(null);
  }, [value, sharedRevision, baseRevision, definition.type, draft]);
  const remoteChanged = sharedRevision !== undefined && baseRevision !== null && sharedRevision !== baseRevision;
  const edit = (next: DashboardEditorValue) => {
    if (sharedRevision !== undefined && baseRevision === null) setBaseRevision(sharedRevision);
    setDraft(next);
  };
  const valid = draft !== undefined && draft !== null
    ? isDashboardControlValueValid(definition.type, draft, control)
    : definition.nullable && draft === null;

  const setType = definition.type.endsWith("_SET");
  const itemType = definition.type.slice(0, -4) as DashboardValueType;
  const sameSet = setType && Array.isArray(draft) && Array.isArray(value)
    && isDashboardControlValueValid(definition.type, draft)
    && isDashboardControlValueValid(definition.type, value)
    && draft.length === value.length
    && draft.every((item) => value.some((candidate) => dashboardValuesEqual(itemType, item, candidate)));
  const changed = !sameSet && !dashboardValuesEqual(definition.type, draft, value);
  const applyLabel = `Apply ${definition.label ?? name}`;

  // A choice applies as soon as it is picked; free text, dates, and ranges keep an explicit Apply.
  if (isDashboardChoiceControl(definition.type, control)) {
    const choose = (next: DashboardEditorValue) => {
      if (isDashboardValueUnset(next)) return;
      const chosen = next as DashboardJsonValue;
      const acceptable = chosen === null ? definition.nullable : isDashboardControlValueValid(definition.type, chosen, control);
      if (!acceptable) return;
      if (dashboardValuesEqual(definition.type, chosen, value)) {
        setChoicePending(null);
        return;
      }
      setChoicePending({ value: chosen });
      onSet(chosen, sharedRevision);
    };
    return (
      <div className={setType ? "h-full min-h-0 min-w-0" : "min-w-0"}>
        <DashboardValueEditor
          testId={`dashboard-variable-${name}`}
          type={definition.type}
          value={choicePending ? choicePending.value : value}
          nullable={definition.nullable}
          label={definition.label ?? name}
          description={definition.description}
          control={control}
          disabled={disabled}
          onChange={choose}
          setChoiceVariant={setType ? "widget" : undefined}
        />
      </div>
    );
  }

  return (
    <div className={setType ? "h-full min-h-0 min-w-0" : "min-w-0"}>
      <DashboardValueEditor
        testId={`dashboard-variable-${name}`}
        type={definition.type}
        value={draft}
        nullable={definition.nullable}
        label={definition.label ?? name}
        description={definition.description}
        control={control}
        disabled={disabled}
        onChange={edit}
        setChoiceVariant={setType ? "widget" : undefined}
        action={
          setType ? <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => { if (valid && draft !== undefined) onSet(draft as DashboardJsonValue, baseRevision ?? sharedRevision); }}
            disabled={disabled || remoteChanged || !valid || draft === undefined || !changed}
            aria-label={applyLabel}
          >
            Apply
          </Button> : <button
            type="button"
            onClick={() => { if (valid && draft !== undefined) onSet(draft as DashboardJsonValue, baseRevision ?? sharedRevision); }}
            disabled={disabled || remoteChanged || !valid || draft === undefined || !changed}
            aria-label={applyLabel}
            title={applyLabel}
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-[4px] border border-border bg-accent text-accent-foreground transition-colors hover:bg-accent-hover disabled:cursor-not-allowed disabled:bg-surface disabled:text-foreground-secondary"
          >
            <CornerDownLeft className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
        }
      />
      {remoteChanged && <div role="status" className="mt-2 type-meta text-foreground-secondary">
        <p>The shared Draft changed. Your input is kept.</p>
        <div className="mt-1 flex flex-wrap gap-2">
          <Button size="sm" variant="secondary" disabled={disabled} onClick={() => { setDraft(value); setBaseRevision(null); }}>Use latest</Button>
          <Button size="sm" variant="secondary" disabled={disabled} onClick={() => setBaseRevision(sharedRevision!)}>Keep my input</Button>
        </div>
      </div>}
    </div>
  );
}
