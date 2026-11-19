import { useMemo, useState } from "react";
import type { DashboardJsonObject, DashboardJsonValue, DashboardParameterDefinition } from "@/api/dashboard";
import { dashboardValuesEqual, isDashboardControlValueValid } from "@/features/dashboard/dashboard-value";
import { dashboardParameterLabel, formatDashboardParameterValue, formatUnknownDashboardParameterValue } from "@/features/dashboard/dashboard-parameter-presentation";
import { Button } from "@/components/ui/Button";
import {
  DashboardValueEditor,
  emptyChoiceLabel,
  isDashboardChoiceControl,
  isDashboardValueUnset,
  type DashboardEditorValue,
  unsetDashboardValue,
} from "./DashboardValueEditor";

interface DashboardParameterFormProps {
  parameters: Record<string, DashboardParameterDefinition>;
  // Prefills the form and marks every value that differs from it.
  initialValues?: DashboardJsonObject;
  allowUnchanged?: boolean;
  pending?: boolean;
  error?: string | null;
  variant?: "page" | "dialog";
  submitLabel?: string;
  onCancel?: () => void;
  onOpen: (bindings: DashboardJsonObject) => void;
}

export function dashboardInputValueText(definition: DashboardParameterDefinition | undefined, value: DashboardJsonValue): string {
  if (value === null) return emptyChoiceLabel(definition?.control);
  return definition ? formatDashboardParameterValue(definition, value) : formatUnknownDashboardParameterValue(value);
}

export interface DashboardInputChip {
  name: string;
  label: string;
  value: string;
}

// Inputs keep the definition's Parameter order; bound names the definition no longer declares follow it.
export function dashboardInputChips(
  bindings: DashboardJsonObject,
  definitions: Record<string, DashboardParameterDefinition>,
): DashboardInputChip[] {
  const names = [...Object.keys(definitions).filter((name) => Object.hasOwn(bindings, name)), ...Object.keys(bindings).filter((name) => !Object.hasOwn(definitions, name))];
  return names.map((name) => ({
    name,
    label: definitions[name]?.label ?? dashboardParameterLabel(name),
    value: dashboardInputValueText(definitions[name], bindings[name]),
  }));
}

// History names the first input and lists the remaining values; the narrow Inputs line lists values only.
export function dashboardInputsSummary(chips: readonly DashboardInputChip[], style: "first-labelled" | "values"): string {
  if (chips.length === 0) return "No inputs";
  return chips.map((chip, index) => style === "first-labelled" && index === 0 ? `${chip.label} ${chip.value}` : chip.value).join(" · ");
}

function startingValue(definition: DashboardParameterDefinition, name: string, initialValues: DashboardJsonObject | undefined): DashboardEditorValue {
  if (initialValues && Object.hasOwn(initialValues, name)) return initialValues[name];
  return definition.nullable && isDashboardChoiceControl(definition.type, definition.control) ? null : unsetDashboardValue();
}

export function DashboardParameterForm({
  parameters,
  initialValues,
  allowUnchanged = false,
  pending = false,
  error = null,
  variant = "page",
  submitLabel = "Show results",
  onCancel,
  onOpen,
}: DashboardParameterFormProps) {
  const entries = useMemo(() => Object.entries(parameters), [parameters]);
  const [values, setValues] = useState<Record<string, DashboardEditorValue>>(() =>
    Object.fromEntries(entries.map(([name, definition]) => [name, startingValue(definition, name, initialValues)])),
  );
  const complete = entries.every(([name, definition]) => {
    const value = values[name];
    return !isDashboardValueUnset(value)
      && (definition.nullable || value !== null)
      && isDashboardControlValueValid(definition.type, value, definition.control);
  });
  const changedFrom = (name: string, definition: DashboardParameterDefinition): string | null => {
    if (!initialValues || !Object.hasOwn(initialValues, name)) return null;
    const initial = initialValues[name];
    const current = values[name];
    if (!isDashboardValueUnset(current) && dashboardValuesEqual(definition.type, current as DashboardJsonValue, initial)) return null;
    return dashboardInputValueText(definition, initial);
  };
  // The current definition may add or drop parameters since the restored results; either counts as a change.
  const definitionChanged = !!initialValues && (
    entries.some(([name]) => !Object.hasOwn(initialValues, name))
    || Object.keys(initialValues).some((name) => !Object.hasOwn(parameters, name))
  );
  const changed = allowUnchanged || !initialValues || definitionChanged || entries.some(([name, definition]) => changedFrom(name, definition) !== null);

  const setValue = (name: string, value: DashboardEditorValue) => {
    setValues((current) => ({ ...current, [name]: value }));
  };

  const submit = () => {
    if (!complete || !changed || pending) return;
    const bindings: DashboardJsonObject = Object.fromEntries(
      entries.map(([name]) => [name, values[name] as DashboardJsonObject[string]]),
    ) as DashboardJsonObject;
    onOpen(bindings);
  };

  const editors = (
    <>
      {entries.map(([name, definition]) => {
        const previous = changedFrom(name, definition);
        return (
          <div key={name} className={`min-w-0 ${previous !== null ? "[&_[role=combobox]]:border-label [&_input:not([type=checkbox])]:border-label" : ""}`} data-changed={previous !== null || undefined}>
            <DashboardValueEditor
              testId={`dashboard-parameter-${name}`}
              type={definition.type}
              value={values[name] === undefined ? unsetDashboardValue() : values[name]}
              nullable={definition.nullable}
              label={definition.label ?? name}
              description={definition.description}
              control={definition.control}
              disabled={pending}
              onChange={(value) => setValue(name, value)}
            />
            {previous !== null ? <p className="mt-1.5 break-words type-meta text-foreground-secondary">Changed from {previous}</p> : null}
          </div>
        );
      })}
      {entries.length === 0 ? <p className="type-control text-foreground-secondary">No inputs required.</p> : null}
    </>
  );

  const action = (
    <Button
      type="button"
      onClick={submit}
      disabled={!complete || !changed}
      pending={pending}
    >
      {pending ? "Opening…" : submitLabel}
    </Button>
  );
  const incompleteHint = !complete ? <p className="type-meta text-foreground-secondary">Fill in all required inputs (*) to continue.</p> : null;

  if (variant === "dialog") {
    return (
      <div>
        <div className="grid gap-x-10 gap-y-8 sm:grid-cols-2">{editors}</div>
        <div className="mt-8 flex flex-wrap items-center justify-between gap-4 border-t border-border pt-4">
          {error ? <p role="alert" className="type-meta text-error">{error}</p> : incompleteHint ?? <span />}
          <div className="ml-auto flex items-center gap-2">
            {onCancel ? <Button type="button" variant="secondary" disabled={pending} onClick={onCancel}>Cancel</Button> : null}
            {action}
          </div>
        </div>
      </div>
    );
  }

  return (
    <section className="px-0 py-2" aria-labelledby="dashboard-choose-inputs">
      <section className="mx-auto w-full max-w-[720px]">
        <h2 id="dashboard-choose-inputs" className="type-section text-heading">Choose inputs</h2>
        <p className="mt-2 max-w-[560px] type-body text-foreground-secondary">Every chart uses these values.</p>
        <div className="mt-8 rounded-[6px] border border-border bg-background p-5 sm:p-6">
          <div className="grid gap-5 sm:grid-cols-2">{editors}</div>
          <div className="mt-6 flex flex-wrap items-center gap-3">
            {error ? <p role="alert" className="w-full type-meta text-error">{error}</p> : null}
            {action}
            {incompleteHint}
          </div>
        </div>
      </section>
    </section>
  );
}
