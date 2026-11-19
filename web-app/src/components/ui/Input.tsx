import React, { forwardRef, useId } from "react";

const FIELD_BASE_STYLES =
  "min-w-0 rounded-[4px] border text-[13px] text-foreground caret-focus-ring placeholder:text-foreground-secondary transition-colors duration-150 disabled:cursor-not-allowed disabled:bg-surface disabled:text-foreground-secondary";

export function fieldClassName(invalid: boolean): string {
  return `${FIELD_BASE_STYLES} w-full bg-background px-3 ${invalid ? "border-error" : "border-border"}`;
}

/**
 * A text field inside a table cell that reads as the cell's value until it is hovered or focused. It extends 8px
 * left of the cell so its text starts on the column heading's edge.
 */
function inlineFieldClassName(invalid: boolean): string {
  return `${FIELD_BASE_STYLES} -ml-2 w-[calc(100%+0.5rem)] bg-transparent px-2 ${invalid ? "border-error" : "border-transparent hover:border-border focus:border-border focus:bg-background"}`;
}

export const FIELD_LABEL_CLASS = "mb-1.5 block text-xs text-label";

interface FieldProps {
  label?: string;
  error?: string;
  helperText?: string;
}

function FieldFrame({
  controlId,
  label,
  required,
  error,
  helperText,
  children,
}: FieldProps & { controlId: string; required: boolean | undefined; children: React.ReactElement }) {
  const message = error ? (
    <p id={`${controlId}-message`} className="mt-1.5 text-xs text-error">{error}</p>
  ) : helperText ? (
    <p id={`${controlId}-message`} className="mt-1.5 text-xs text-foreground-secondary">{helperText}</p>
  ) : null;
  // Without a label the control keeps its position whether or not a message appears, so it never remounts.
  if (!label) return <>{children}{message}</>;
  return (
    <div className="w-full">
      <label htmlFor={controlId} className={FIELD_LABEL_CLASS}>
        {label}
        {required && <span className="ml-0.5 text-error" aria-hidden="true">*</span>}
      </label>
      {children}
      {message}
    </div>
  );
}

function fieldAccessibility(controlId: string, { error, helperText }: FieldProps, own: { "aria-invalid"?: React.AriaAttributes["aria-invalid"]; "aria-describedby"?: string }) {
  const message = error || helperText ? `${controlId}-message` : undefined;
  return {
    id: controlId,
    "aria-invalid": error ? true : own["aria-invalid"],
    "aria-describedby": [own["aria-describedby"], message].filter(Boolean).join(" ") || undefined,
  };
}

export const Input = forwardRef<HTMLInputElement, FieldProps & { inline?: boolean } & React.InputHTMLAttributes<HTMLInputElement>>(
  ({ label, error, helperText, className = "", id, inline = false, ...props }, ref) => {
    const generatedId = useId();
    const controlId = id ?? generatedId;
    return (
      <FieldFrame controlId={controlId} label={label} required={props.required} error={error} helperText={helperText}>
        <input
          ref={ref}
          {...props}
          {...fieldAccessibility(controlId, { error, helperText }, props)}
          className={`${(inline ? inlineFieldClassName : fieldClassName)(Boolean(error) || props["aria-invalid"] === true)} h-8 ${className}`}
        />
      </FieldFrame>
    );
  },
);

Input.displayName = "Input";

export const TextArea = forwardRef<HTMLTextAreaElement, FieldProps & { inline?: boolean } & React.TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ label, error, helperText, className = "", id, inline = false, ...props }, ref) => {
    const generatedId = useId();
    const controlId = id ?? generatedId;
    return (
      <FieldFrame controlId={controlId} label={label} required={props.required} error={error} helperText={helperText}>
        <textarea
          ref={ref}
          {...props}
          {...fieldAccessibility(controlId, { error, helperText }, props)}
          className={`${inline ? `${inlineFieldClassName(Boolean(error) || props["aria-invalid"] === true)} py-1` : `${fieldClassName(Boolean(error) || props["aria-invalid"] === true)} resize-y py-2`} leading-5 ${className}`}
        />
      </FieldFrame>
    );
  },
);

TextArea.displayName = "TextArea";

export const NativeSelect = forwardRef<HTMLSelectElement, FieldProps & React.SelectHTMLAttributes<HTMLSelectElement>>(
  ({ label, error, helperText, className = "", id, ...props }, ref) => {
    const generatedId = useId();
    const controlId = id ?? generatedId;
    return (
      <FieldFrame controlId={controlId} label={label} required={props.required} error={error} helperText={helperText}>
        <select
          ref={ref}
          {...props}
          {...fieldAccessibility(controlId, { error, helperText }, props)}
          className={`${fieldClassName(Boolean(error) || props["aria-invalid"] === true)} select-chevron h-8 cursor-pointer pr-8 ${className}`}
        />
      </FieldFrame>
    );
  },
);

NativeSelect.displayName = "NativeSelect";
