import { fieldClassName } from "@/components/ui/Input";
import { Select } from "@/components/ui/Select";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { CalendarDays, Check } from "lucide-react";
import type { DashboardJsonValue, DashboardValueControl, DashboardValueType } from "@/api/dashboard";
import {
  controlOptions,
  dashboardValuesEqual,
  isDashboardControlValueValid,
  optionLabel,
  parseEditorValue,
  dateTimeLocalToTimestamp,
  timestampToDateTimeLocal,
  valueToEditorText,
} from "@/features/dashboard/dashboard-value";
import { dashboardInputTimeZoneLabel, useDashboardInputTimeZone } from "./DashboardInputTimeZone";

const UNSET = Symbol("dashboard-value-unset");
export type DashboardEditorValue = DashboardJsonValue | typeof UNSET;

const EMPTY_CHOICE = "empty";

export function emptyChoiceLabel(control?: DashboardValueControl | null): string {
  return control?.emptyLabel ?? "Any";
}

// Choice controls are always a visible list; a nullable choice offers the empty value as its first option.
export function isDashboardChoiceControl(type: DashboardValueType, control?: DashboardValueControl | null): boolean {
  if (type.endsWith("_RANGE")) return false;
  return Boolean(controlOptions(control)) || type === "BOOLEAN";
}

interface DashboardValueEditorProps {
  type: DashboardValueType;
  value: DashboardEditorValue;
  nullable: boolean;
  label: string;
  description?: string | null;
  control?: DashboardValueControl | null;
  disabled?: boolean;
  onChange: (value: DashboardEditorValue) => void;
  onCommit?: () => void;
  testId?: string;
  action?: ReactNode;
  setChoiceVariant?: "widget";
}

function isSetType(type: DashboardValueType): boolean {
  return type.endsWith("_SET");
}

function isRangeType(type: DashboardValueType): boolean {
  return type.endsWith("_RANGE");
}

function itemType(type: DashboardValueType): DashboardValueType {
  return type.endsWith("_SET") || type.endsWith("_RANGE")
    ? type.slice(0, type.endsWith("_SET") ? -4 : -6) as DashboardValueType
    : type;
}

function isRangeValue(value: DashboardEditorValue): value is { start: string; end: string } {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    && typeof value.start === "string" && typeof value.end === "string";
}

function rawStringValue(type: DashboardValueType, value: DashboardEditorValue): string {
  return value === UNSET || value === null ? "" : valueToEditorText(type, value);
}

function scalarInputType(type: DashboardValueType): "datetime-local" | "text" {
  if (type === "TIMESTAMP") return "datetime-local";
  return "text";
}

function scalarValueFromRaw(
  type: DashboardValueType,
  raw: string,
  timeZone: string,
  onChange: (value: DashboardEditorValue) => void,
): void {
  const editorRaw = type === "TIMESTAMP" ? dateTimeLocalToTimestamp(raw, timeZone) : raw;
  if (type === "TIMESTAMP" && editorRaw === undefined) {
    onChange(raw.length === 0 ? UNSET : raw);
    return;
  }
  if (type === "BOOLEAN") {
    onChange(parseEditorValue(type, editorRaw ?? "") ?? UNSET);
    return;
  }
  const parsed = parseEditorValue(type, editorRaw ?? "");
  // Preserve an invalid intermediate draft (for example `-` or `1.`) so the
  // user can finish typing; the parent only commits shape-valid values.
  onChange(parsed ?? (raw.length === 0 ? UNSET : raw));
}

function currentChoiceIndex(
  type: DashboardValueType,
  value: DashboardEditorValue,
  options: ReturnType<typeof controlOptions>,
): number {
  if (!options || value === UNSET || value === null || Array.isArray(value) || typeof value === "object") return -1;
  return options.findIndex((option) => dashboardValuesEqual(type, option.value, value));
}

function currentChoiceIndexes(
  type: DashboardValueType,
  value: DashboardEditorValue,
  options: ReturnType<typeof controlOptions>,
): number[] {
  if (!options || !Array.isArray(value)) return [];
  return options.flatMap((option, index) => value.some((candidate) => dashboardValuesEqual(type, option.value, candidate)) ? [index] : []);
}

export function DashboardValueEditor({
  type,
  value,
  nullable,
  label,
  description,
  control,
  disabled = false,
  onChange,
  onCommit,
  testId,
  action,
  setChoiceVariant,
}: DashboardValueEditorProps) {
  const isNull = value === null;
  const isUnset = value === UNSET;
  const options = controlOptions(control);
  const setType = isSetType(type);
  const widgetSetChoices = setType && setChoiceVariant === "widget";
  const scrollbar = useTransientScrollbar();
  const rangeType = isRangeType(type);
  const scalarType = itemType(type);
  const timeZone = useDashboardInputTimeZone();
  const timeZoneNoteId = useId();
  const toWallClock = (instant: string | undefined) => timestampToDateTimeLocal(instant, timeZone);
  const zonedInput = scalarType === "TIMESTAMP" && !options;
  const [freeSetDraft, setFreeSetDraft] = useState(() => setType && !options ? rawStringValue(type, value) : "");
  const freeSetEditingRef = useRef(false);
  useEffect(() => {
    if (!freeSetEditingRef.current && setType && !options && (Array.isArray(value) || value === null || value === UNSET)) {
      setFreeSetDraft(rawStringValue(type, value));
    }
  }, [options, setType, type, value]);
  const shapeInvalid = !isNull && !isUnset && !isDashboardControlValueValid(type, value);
  const controlInvalid = !shapeInvalid && !isNull && !isUnset && !isDashboardControlValueValid(type, value, control);
  const invalid = shapeInvalid || controlInvalid;
  const valueText = rawStringValue(type, value);
  const selectedIndex = currentChoiceIndex(type, value, options);
  const selectedIndexes = currentChoiceIndexes(scalarType, value, options);
  const outsideSetValues = options && setType && Array.isArray(value)
    ? value.filter((candidate) => !options.some((option) => dashboardValuesEqual(scalarType, option.value, candidate)))
    : [];
  const choiceValueOutsideOptions = Boolean(options && !setType && !rangeType && !isNull && !isUnset && selectedIndex < 0);
  const choiceControl = isDashboardChoiceControl(type, control);
  const emptyOption = nullable ? [{ value: EMPTY_CHOICE, label: emptyChoiceLabel(control) }] : [];
  const placeholder = control?.placeholder ?? (
    type === "NUMBER"
      ? "e.g. 123.45"
      : type === "TIMESTAMP"
        ? "e.g. 2026-08-22T09:00:00Z"
        : setType
          ? "Comma-separated values"
          : undefined
  );

  const renderScalarChoice = () => (
    <Select
      className="mt-1"
      label={label}
      value={isNull ? EMPTY_CHOICE : selectedIndex < 0 ? "" : String(selectedIndex)}
      currentLabel={choiceValueOutsideOptions ? valueText : undefined}
      options={[...emptyOption, ...(options ?? []).map((option, index) => ({ value: String(index), label: optionLabel(type, option) }))]}
      disabled={disabled}
      invalid={invalid || choiceValueOutsideOptions}
      onChange={(next) => onChange(next === EMPTY_CHOICE ? null : options?.[Number(next)]?.value ?? UNSET)}
      onCommit={onCommit}
    />
  );

  const toggleSetChoice = (index: number) => {
    const option = options?.[index];
    if (!option) return;
    const selectedValues = Array.isArray(value) ? value : [];
    const next = selectedIndexes.includes(index)
      ? selectedValues.filter((candidate) => !dashboardValuesEqual(scalarType, candidate, option.value))
      : [...selectedValues, option.value];
    onChange(next.length === 0 ? (nullable ? null : UNSET) : next);
  };

  const renderSetChoice = () => (
    <div
      className={widgetSetChoices ? "mt-2 flex min-h-0 flex-col" : `mt-2 overflow-hidden border bg-background ${invalid ? "border-error" : "border-border"}`}
      role="group"
      aria-label={label}
      aria-invalid={invalid}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) onCommit?.();
      }}
    >
      <div
        className={widgetSetChoices ? `min-h-0 max-h-[184px] overflow-y-auto ${scrollbar.className}` : "max-h-[184px] overflow-y-auto"}
        onScroll={widgetSetChoices ? scrollbar.onScroll : undefined}
        onPointerEnter={widgetSetChoices ? scrollbar.onScroll : undefined}
        onPointerDown={widgetSetChoices ? scrollbar.onScroll : undefined}
        onFocusCapture={widgetSetChoices ? scrollbar.onScroll : undefined}
        onKeyDownCapture={widgetSetChoices ? scrollbar.onScroll : undefined}
      >
        {options?.map((option, index) => {
          const selected = selectedIndexes.includes(index);
          if (widgetSetChoices) {
            return (
              <label key={index} className="flex min-h-[26px] cursor-pointer items-start gap-2 px-1 py-0.5 type-control leading-5 text-foreground has-disabled:cursor-not-allowed has-disabled:text-foreground-secondary">
                <input
                  type="checkbox"
                  checked={selected}
                  disabled={disabled}
                  onChange={() => toggleSetChoice(index)}
                  className="mt-0.5 h-4 w-4 shrink-0 accent-[var(--accent)]"
                />
                <span className="min-w-0 flex-1 break-words">{optionLabel(scalarType, option)}</span>
              </label>
            );
          }
          return (
            <button
              key={index}
              type="button"
              aria-pressed={selected}
              className={`flex min-h-10 w-full items-center gap-3 border-l-2 border-b border-border-light px-3 py-2 text-left type-control outline-none last:border-b-0 focus-visible:border-l-[var(--focus-ring)] focus-visible:bg-surface-hover ${selected ? "border-l-transparent bg-surface text-foreground" : "border-l-transparent text-foreground hover:bg-surface"}`}
              onClick={() => toggleSetChoice(index)}
            >
              <span className={`grid h-4 w-4 shrink-0 place-items-center border ${selected ? "border-heading bg-heading text-background" : "border-border bg-background"}`} aria-hidden="true">
                {selected ? <Check className="h-3 w-3" /> : null}
              </span>
              <span className="min-w-0 flex-1 break-words">{optionLabel(scalarType, option)}</span>
            </button>
          );
        })}
      </div>
    </div>
  );

  const renderBoolean = () => (
    <Select
      className="mt-1"
      label={label}
      value={isNull ? EMPTY_CHOICE : typeof value === "boolean" ? String(value) : ""}
      options={[...emptyOption, { value: "true", label: "true" }, { value: "false", label: "false" }]}
      disabled={disabled}
      invalid={invalid}
      onChange={(next) => next === EMPTY_CHOICE ? onChange(null) : scalarValueFromRaw(type, next, timeZone, onChange)}
      onCommit={onCommit}
    />
  );

  const renderFreeSet = () => {
    if (scalarType === "DATE" || scalarType === "TIMESTAMP") {
      const timestamp = scalarType === "TIMESTAMP";
      const min = timestamp ? toWallClock(control?.min) || undefined : control?.min;
      const max = timestamp ? toWallClock(control?.max) || undefined : control?.max;
      return (
        <div className="mt-1 space-y-2">
          <div className="flex flex-wrap gap-1.5">
            {Array.isArray(value) ? value.map((candidate) => {
              const displayValue = timestamp ? toWallClock(String(candidate)) || String(candidate) : String(candidate);
              return (
                <span key={String(candidate)} className="inline-flex items-center gap-1 rounded-[4px] border border-border bg-surface px-2 py-1 type-status text-foreground">
                  {displayValue}
                  <button
                    type="button"
                    aria-label={`Remove ${displayValue}`}
                    className="text-foreground-secondary hover:text-foreground"
                    onClick={() => onChange(value.filter((item) => item !== candidate))}
                  >×</button>
                </span>
              );
            }) : null}
          </div>
          <input
            type={timestamp ? "datetime-local" : "text"}
            aria-label={`Add to ${label}`}
            aria-describedby={timestamp ? timeZoneNoteId : undefined}
            min={min}
            max={max}
            step={timestamp ? "any" : undefined}
            onChange={(event) => {
              const candidate = timestamp
                ? dateTimeLocalToTimestamp(event.target.value, timeZone)
                : event.target.value;
              if (candidate === undefined || candidate.length === 0) return;
              const current = Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
              const alreadySelected = timestamp
                ? current.some((item) => dashboardValuesEqual("TIMESTAMP", item, candidate))
                : current.includes(candidate);
              if (!alreadySelected) onChange([...current, candidate]);
            }}
            onBlur={onCommit}
            className={`${fieldClassName(invalid)} mt-1 h-8`}
          />
        </div>
      );
    }

    return (
      <input
        type="text"
        aria-label={label}
        value={freeSetDraft}
        placeholder={placeholder ?? "Comma-separated values"}
        onChange={(event) => {
          const raw = event.target.value;
          freeSetEditingRef.current = true;
          setFreeSetDraft(raw);
          const parsed = parseEditorValue(type, raw);
          onChange(parsed ?? (raw.trim() === "" ? UNSET : raw));
        }}
        onBlur={() => {
          freeSetEditingRef.current = false;
          setFreeSetDraft(rawStringValue(type, value));
          onCommit?.();
        }}
        aria-invalid={invalid}
        className={`${fieldClassName(invalid)} mt-1 h-8`}
      />
    );
  };

  const renderFreeScalar = () => (
    <input
      type={type === "TIMESTAMP" && invalid ? "text" : scalarInputType(type)}
      aria-label={label}
      value={type === "TIMESTAMP" && !invalid ? toWallClock(valueText) : valueText}
      aria-describedby={type === "TIMESTAMP" ? timeZoneNoteId : undefined}
      placeholder={placeholder ?? undefined}
      min={type === "TIMESTAMP" ? toWallClock(control?.min) || undefined : control?.min}
      max={type === "TIMESTAMP" ? toWallClock(control?.max) || undefined : control?.max}
      step={type === "TIMESTAMP" ? "any" : undefined}
      inputMode={type === "NUMBER" ? "decimal" : undefined}
      onChange={(event) => {
        if (type === "TIMESTAMP" && invalid) onChange(event.target.value.length === 0 ? UNSET : event.target.value);
        else scalarValueFromRaw(type, event.target.value, timeZone, onChange);
      }}
      onBlur={onCommit}
      aria-invalid={invalid}
      className={`${fieldClassName(invalid)} mt-1 h-8`}
    />
  );

  const renderRange = () => {
    const rangeValue = isRangeValue(value) ? value : { start: "", end: "" };
    const visibleRangeValue = {
      start: scalarType === "TIMESTAMP" && !invalid ? toWallClock(rangeValue.start) || rangeValue.start : rangeValue.start,
      end: scalarType === "TIMESTAMP" && !invalid ? toWallClock(rangeValue.end) || rangeValue.end : rangeValue.end,
    };
    const min = scalarType === "TIMESTAMP" ? toWallClock(control?.min) || undefined : control?.min;
    const max = scalarType === "TIMESTAMP" ? toWallClock(control?.max) || undefined : control?.max;
    return (
      <div className="mt-2 grid grid-cols-1 gap-3 @min-[320px]:grid-cols-2">
        {(["start", "end"] as const).map((key) => (
          <label key={key} className="type-status font-medium text-foreground-secondary">
            {key === "start" ? "Start" : "End"}
            <span className="relative mt-1 block">
              <input
                type={scalarType === "TIMESTAMP" && invalid ? "text" : scalarInputType(scalarType)}
                inputMode={scalarType === "DATE" ? "numeric" : undefined}
                aria-label={`${label} ${key === "start" ? "start" : "end"}`}
                aria-describedby={scalarType === "TIMESTAMP" ? timeZoneNoteId : undefined}
                value={visibleRangeValue[key]}
                placeholder={key === "start" ? control?.startPlaceholder ?? "YYYY-MM-DD" : control?.endPlaceholder ?? "YYYY-MM-DD"}
                min={min}
                max={max}
                step={scalarType === "TIMESTAMP" ? "any" : undefined}
                onInput={(event) => {
                  const raw = event.currentTarget.value;
                  const nextValue = scalarType === "TIMESTAMP" ? dateTimeLocalToTimestamp(raw, timeZone) ?? raw : raw;
                  onChange({ ...rangeValue, [key]: nextValue });
                }}
                onBlur={onCommit}
                className={`${fieldClassName(invalid)} h-8 pr-9 tabular-nums`}
                aria-invalid={invalid}
              />
              {scalarType === "DATE" ? <CalendarDays className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" /> : null}
            </span>
          </label>
        ))}
      </div>
    );
  };

  const attachedAction = action && !rangeType && !setType && (!isNull || choiceControl);

  return (
    <fieldset className={widgetSetChoices ? "@container flex h-full min-h-0 min-w-0 flex-col" : "@container min-w-0"} data-testid={testId} disabled={disabled}>
      <legend className="max-w-full break-words type-control leading-5 text-label">
        {label}
        {nullable ? <span className="ml-1 type-status text-foreground-secondary">(optional)</span> : <span className="ml-1 text-error">*</span>}
      </legend>
      {description ? <p className="mt-1 break-words type-meta leading-[1.45] text-foreground-secondary">{description}</p> : null}
      {nullable && !choiceControl ? (
        <label className="mt-2 flex items-center gap-2 type-meta text-foreground-secondary">
          <input
            type="checkbox"
            checked={isNull}
            onChange={(event) => onChange(event.target.checked ? null : UNSET)}
            className="h-3.5 w-3.5 accent-[var(--accent)]"
          />
          No value
        </label>
      ) : null}
      <div className={widgetSetChoices ? "flex min-h-0 min-w-0 flex-1 flex-col gap-2" : attachedAction ? "flex min-w-0 items-end" : action ? "grid min-w-0 grid-cols-1 items-end gap-x-2 gap-y-2 @min-[160px]:grid-cols-[minmax(0,1fr)_auto]" : "min-w-0"}>
        <div className={widgetSetChoices ? "flex min-h-0 min-w-0 flex-col" : attachedAction ? "min-w-0 flex-1 [&_button[role=combobox]]:rounded-r-none [&_input]:rounded-r-none" : "min-w-0"}>
          {!rangeType && type === "BOOLEAN" && !options ? renderBoolean() : null}
          {!rangeType && options && !setType ? renderScalarChoice() : null}
          {!rangeType && setType && options ? renderSetChoice() : null}
          {!isNull && !rangeType && type !== "BOOLEAN" && !options && !setType ? renderFreeScalar() : null}
          {!isNull && !rangeType && setType && !options ? renderFreeSet() : null}
          {!isNull && rangeType ? renderRange() : null}
        </div>
        {action ? <div className={attachedAction ? "flex shrink-0 [&>button]:rounded-l-none [&>button]:border-l-0" : "flex shrink-0 justify-end"}>{action}</div> : null}
      </div>
      {zonedInput && !isNull ? <p id={timeZoneNoteId} className="mt-1 type-status text-foreground-secondary">{dashboardInputTimeZoneLabel(timeZone)}</p> : null}
      {outsideSetValues.length > 0 ? <div className="mt-2 space-y-1">
        <p className="type-status text-warning">Current values outside this control's choices:</p>
        {outsideSetValues.map((candidate, index) => <div key={index} className="flex items-center justify-between gap-2 type-meta text-foreground">
          <span className="min-w-0 break-all">{valueToEditorText(scalarType, candidate)}</span>
          <button type="button" className="shrink-0 text-foreground-secondary hover:text-foreground" aria-label={`Remove ${valueToEditorText(scalarType, candidate)}`} onClick={() => {
            if (!Array.isArray(value)) return;
            const next = value.filter((item) => !dashboardValuesEqual(scalarType, item, candidate));
            onChange(next.length === 0 ? (nullable ? null : UNSET) : next);
          }}>Remove</button>
        </div>)}
      </div> : null}
      {choiceValueOutsideOptions ? <p className="mt-1 break-words type-status text-warning">The current value {valueText} is not in this control's choices.</p> : null}
      {controlInvalid && !choiceValueOutsideOptions && outsideSetValues.length === 0 ? <p className="mt-1 break-words type-status text-warning">The current value {valueText} is outside this control's allowed range.</p> : null}
      {shapeInvalid ? <p className="mt-1 type-status text-error">Enter a valid value.</p> : null}
    </fieldset>
  );
}

export function unsetDashboardValue(): DashboardEditorValue {
  return UNSET;
}

export function isDashboardValueUnset(value: DashboardEditorValue): boolean {
  return value === UNSET;
}
