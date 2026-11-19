import { useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { Check, ChevronLeft, FolderTree, KeyRound, Pencil, Trash2 } from "lucide-react";
import { ConnectorTypeMark } from "@/components/connectors/ConnectorTypeMark";
import { CONNECTOR_TYPE_META } from "@/components/connectors/connectorTypeMeta";
import { Button, buttonClassName } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Input, NativeSelect, TextArea } from "@/components/ui/Input";
import {
  CONNECTOR_TYPE_CONTRACTS,
  CONNECTOR_TYPE_ORDER,
  buildConnectorMutationRequest,
  credentialMethodById,
  initialFieldValues,
  propertiesFromConnector,
  type ConnectorFormField,
  type ConnectorMutationRequest,
} from "@/lib/connectors/mutation-contract";
import { Link } from "@/lib/navigation";
import type { Connector, ExternalConnectorType } from "@/lib/types/connector";

const SECTION_HEADING_CLASS =
  "type-group border-b border-border-light pb-2 text-heading sm:col-span-2";

interface ConnectorMutationDialogProps {
  open: boolean;
  mode: "create" | "detail" | "revise" | "rotate";
  connector: Connector | null;
  canEdit: boolean;
  // Where "Browse in Catalog" leads for the open connector; null hides the action.
  catalogHref: string | null;
  submitting: boolean;
  error: string | null;
  onClose: () => void;
  onEdit: () => void;
  onRotate: () => void;
  onDelete: () => void;
  onCancel: () => void;
  onSubmit: (request: ConnectorMutationRequest) => void;
}

function FieldControl({
  field,
  value,
  onChange,
  disabled,
  error,
}: {
  field: ConnectorFormField;
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
  error: string | undefined;
}) {
  const fieldProps = {
    id: `connector-field-${field.key}`,
    label: `${field.label}${field.required ? "" : " (optional)"}`,
    error,
    helperText: field.helperText,
    value,
    disabled,
    "aria-label": field.label,
    "aria-invalid": Boolean(error),
  } as const;

  if (field.kind === "select") {
    return (
      <NativeSelect
        {...fieldProps}
        onChange={(event) => onChange(event.target.value)}
      >
        <option value="">Select…</option>
        {field.options?.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </NativeSelect>
    );
  }

  if (field.kind === "textarea") {
    return (
      <TextArea
        {...fieldProps}
        autoComplete={field.autocomplete}
        placeholder={field.placeholder}
        onChange={(event) => onChange(event.target.value)}
        rows={6}
        className="font-mono"
      />
    );
  }

  return (
    <Input
      {...fieldProps}
      type={field.kind === "password" ? "password" : "text"}
      autoComplete={field.autocomplete}
      placeholder={field.placeholder}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

function TypeChoice({
  type,
  selected,
  disabled,
  onChange,
  onKeyDown,
  inputRef,
}: {
  type: ExternalConnectorType;
  selected: boolean;
  disabled: boolean;
  onChange: () => void;
  onKeyDown: (event: KeyboardEvent<HTMLInputElement>) => void;
  inputRef: (element: HTMLInputElement | null) => void;
}) {
  return (
    <label
      className={`relative flex min-h-[64px] items-center gap-3 overflow-hidden rounded-[6px] border px-3 py-2.5 text-left transition-colors has-[:focus-visible]:outline-[2px] has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-focus-ring ${disabled ? "cursor-default opacity-60" : "cursor-pointer"}`}
      style={{
        borderColor: "var(--border)",
        backgroundColor: selected ? "var(--selection-tint)" : "var(--background)",
      }}
    >
      {selected ? (
        <span
          aria-hidden="true"
          className="absolute inset-y-0 left-0 w-[3px]"
          style={{ backgroundColor: "var(--accent-marker)" }}
        />
      ) : null}
      <input
        ref={inputRef}
        type="radio"
        name="connector-type"
        value={type}
        checked={selected}
        disabled={disabled}
        onChange={onChange}
        onKeyDown={onKeyDown}
        className="sr-only"
      />
      <ConnectorTypeMark type={type} size="md" />
      <span className="type-body min-w-0 flex-1 text-foreground">{CONNECTOR_TYPE_META[type].label}</span>
      {selected ? (
        <Check
          aria-hidden="true"
          className="h-4 w-4 flex-shrink-0"
          style={{ color: "var(--focus-ring)" }}
        />
      ) : null}
    </label>
  );
}

function formatDetailTime(epochMs: number): string {
  if (!epochMs) return "—";
  return new Date(epochMs).toLocaleString("en-US", { month: "short", day: "numeric", year: "numeric", hour: "numeric", minute: "2-digit" });
}

function ConnectorDetails({ connector }: { connector: Connector }) {
  if (connector.type === "DUCKDB") return null;
  const contract = CONNECTOR_TYPE_CONTRACTS[connector.type];

  return (
    <div className="space-y-6">
        <section aria-labelledby="connector-detail-identity" className="grid grid-cols-1 gap-x-4 gap-y-4 sm:grid-cols-2">
          <h2 id="connector-detail-identity" className={SECTION_HEADING_CLASS}>Identity</h2>
          <dl className="contents">
            <div>
              <dt className="type-meta text-foreground-secondary">Display name</dt>
              <dd className="type-body mt-1 text-foreground">{connector.name}</dd>
            </div>
            <div>
              <dt className="type-meta text-foreground-secondary">Type</dt>
              <dd className="type-body mt-1 text-foreground">{CONNECTOR_TYPE_META[connector.type].label}</dd>
            </div>
            <div>
              <dt className="type-meta text-foreground-secondary">Created</dt>
              <dd className="type-body mt-1 tabular-nums text-foreground">{formatDetailTime(connector.createdAtEpochMs)}</dd>
            </div>
            <div>
              <dt className="type-meta text-foreground-secondary">Settings updated</dt>
              <dd className="type-body mt-1 tabular-nums text-foreground">{formatDetailTime(connector.updatedAtEpochMs)}</dd>
            </div>
          </dl>
        </section>

        <section aria-labelledby="connector-detail-connection" className="grid grid-cols-1 gap-x-4 gap-y-4 sm:grid-cols-2">
          <h2 id="connector-detail-connection" className={SECTION_HEADING_CLASS}>Connection</h2>
          <dl className="contents">
            {contract.properties.map((field) => {
              const value = String(Object.entries(connector.properties).find(([key]) => key === field.key)?.[1] ?? "");
              const displayValue = field.options?.find((option) => option.value === value)?.label ?? value ?? "—";
              return (
                <div key={field.key}>
                  <dt className="type-meta text-foreground-secondary">{field.label}</dt>
                  <dd className="type-body mt-1 break-words text-foreground">{displayValue || "—"}</dd>
                </div>
              );
            })}
          </dl>
        </section>
    </div>
  );
}

function ExternalConnectorMutationDialog({
  open,
  mode,
  connector,
  canEdit,
  catalogHref,
  submitting,
  error,
  onClose,
  onEdit,
  onRotate,
  onDelete,
  onCancel,
  onSubmit,
}: ConnectorMutationDialogProps) {
  const [step, setStep] = useState<"type" | "configure">("type");
  const [type, setType] = useState<ExternalConnectorType>(connector && connector.type !== "DUCKDB" ? connector.type : "POSTGRESQL");
  const [displayName, setDisplayName] = useState(connector?.name ?? "");
  const [properties, setProperties] = useState<Record<string, string>>({});
  const [credentials, setCredentials] = useState<Record<string, string>>({});
  const [credentialMethodId, setCredentialMethodId] = useState(
    CONNECTOR_TYPE_CONTRACTS[connector && connector.type !== "DUCKDB" ? connector.type : "POSTGRESQL"].credentialMethods[0].id,
  );
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const typeInputs = useRef<Partial<Record<ExternalConnectorType, HTMLInputElement | null>>>({});

  useEffect(() => {
    if (!open) return;
    const nextType = connector && connector.type !== "DUCKDB" ? connector.type : "POSTGRESQL";
    const nextContract = CONNECTOR_TYPE_CONTRACTS[nextType];
    setStep(mode === "create" ? "type" : "configure");
    setType(nextType);
    setDisplayName(connector?.name ?? "");
    setProperties(connector ? propertiesFromConnector(connector, nextType) : initialFieldValues(nextContract.properties));
    setCredentialMethodId(nextContract.credentialMethods[0].id);
    setCredentials(initialFieldValues(nextContract.credentialMethods[0].fields));
    setFieldErrors({});
  }, [open, connector, mode]);

  const contract = CONNECTOR_TYPE_CONTRACTS[type];
  const credentialMethod = useMemo(
    () => credentialMethodById(type, credentialMethodId),
    [type, credentialMethodId],
  );
  const hasFieldErrors = Object.keys(fieldErrors).length > 0;
  const isCreateTypeStep = mode === "create" && step === "type";

  const clearValidation = () => setFieldErrors({});
  const clearFieldError = (key: string) => {
    setFieldErrors((current) => {
      const next = { ...current };
      delete next[key];
      return next;
    });
  };

  const applyType = (nextType: ExternalConnectorType) => {
    const nextContract = CONNECTOR_TYPE_CONTRACTS[nextType];
    setType(nextType);
    setProperties(initialFieldValues(nextContract.properties));
    setCredentialMethodId(nextContract.credentialMethods[0].id);
    setCredentials(initialFieldValues(nextContract.credentialMethods[0].fields));
    clearValidation();
  };

  const handleTypeKeyDown = (event: KeyboardEvent<HTMLInputElement>, currentIndex: number) => {
    const forward = event.key === "ArrowRight" || event.key === "ArrowDown";
    const backward = event.key === "ArrowLeft" || event.key === "ArrowUp";
    if (!forward && !backward && event.key !== "Home" && event.key !== "End") return;
    event.preventDefault();
    const nextIndex =
      event.key === "Home"
        ? 0
        : event.key === "End"
          ? CONNECTOR_TYPE_ORDER.length - 1
          : (currentIndex + (forward ? 1 : -1) + CONNECTOR_TYPE_ORDER.length) % CONNECTOR_TYPE_ORDER.length;
    const nextType = CONNECTOR_TYPE_ORDER[nextIndex];
    applyType(nextType);
    requestAnimationFrame(() => typeInputs.current[nextType]?.focus());
  };

  const applyCredentialMethod = (methodId: string) => {
    const method = credentialMethodById(type, methodId);
    setCredentialMethodId(method.id);
    setCredentials(initialFieldValues(method.fields));
    const credentialKeys = new Set(contract.credentialMethods.flatMap((candidate) => candidate.fields.map((field) => field.key)));
    setFieldErrors((current) => Object.fromEntries(Object.entries(current).filter(([key]) => !credentialKeys.has(key))));
  };

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (mode === "detail") return;
    if (isCreateTypeStep) {
      setStep("configure");
      clearValidation();
      return;
    }

    const result = buildConnectorMutationRequest({
      type,
      displayName,
      properties,
      credentials,
      credentialMethodId,
      mode,
    });
    if ("errors" in result) {
      setFieldErrors(result.errors);
      return;
    }
    clearValidation();
    onSubmit(result.request);
  };

  const title = mode === "create"
    ? "Create connector"
    : mode === "detail"
      ? connector?.name ?? "Connector details"
      : mode === "rotate" ? `Replace credentials — ${connector?.name ?? "connector"}` : `Edit ${connector?.name ?? "connector"}`;
  const isDetail = mode === "detail";

  const submitLabel = submitting ? (mode === "create" ? "Creating…" : "Saving…") : isCreateTypeStep ? "Next" : mode === "create" ? "Create" : mode === "rotate" ? "Replace credentials" : "Save changes";

  const detailActions = isDetail && connector && (catalogHref || canEdit) ? (
    <>
      {catalogHref ? (
        <Link href={catalogHref} className={buttonClassName("secondary", "sm", "")}>
          <FolderTree aria-hidden="true" />
          Browse in Catalog
        </Link>
      ) : null}
      {canEdit ? (
        <>
          <Button type="button" variant="secondary" size="sm" onClick={onEdit}><Pencil aria-hidden="true" />Edit settings</Button>
          <Button type="button" variant="secondary" size="sm" onClick={onRotate}><KeyRound aria-hidden="true" />Replace credentials</Button>
        </>
      ) : null}
    </>
  ) : null;

  return (
    <Dialog closeLabel={isDetail ? "Close connector details" : "Close connector form"}
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen) onClose();
      }}
      size="lg"
      testId="connector-mutation-dialog"
      busy={submitting}
      title={
        <span className="flex min-w-0 items-center gap-2">
          {mode !== "create" && connector ? <ConnectorTypeMark type={connector.type} size="sm" /> : null}
          <span className="min-w-0 break-words">{title}</span>
        </span>
      }
      headerActions={detailActions ? (
        <div className="hidden shrink-0 items-center gap-2 md:flex">{detailActions}</div>
      ) : undefined}
      footer={isDetail && connector ? (canEdit ? (
        <Button type="button" variant="ghost" size="sm" onClick={onDelete}><Trash2 aria-hidden="true" />Delete connector</Button>
      ) : undefined) : (
        <>
          {hasFieldErrors || error ? (
            <div className="type-meta w-full text-error" role="alert">
              <p className="font-medium">{hasFieldErrors ? "Check the highlighted fields." : mode === "rotate" ? "Credentials were not replaced." : mode === "create" ? "Connector was not created." : "Changes were not saved."}</p>
              {!hasFieldErrors ? <p className="mt-0.5">{error}</p> : null}
              {!hasFieldErrors ? <p className="mt-0.5 text-foreground-secondary">Your values are still here. Review and retry.</p> : null}
            </div>
          ) : null}
          <div className="flex w-full items-center justify-between gap-2">
            {mode === "create" && step === "configure" ? (
              <Button
                type="button"
                variant="secondary"
                disabled={submitting}
                onClick={() => {
                  setStep("type");
                  clearValidation();
                }}
              >
                <ChevronLeft aria-hidden="true" />
                Back
              </Button>
            ) : <span />}
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="secondary"
                disabled={submitting}
                onClick={onCancel}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                form="connector-mutation-form"
                pending={submitting}
              >
                {submitLabel}
              </Button>
            </div>
          </div>
        </>
      )}
    >
      {isDetail && connector ? (
        <>
          {detailActions ? <div data-testid="connector-detail-actions-narrow" className="mb-5 flex flex-wrap items-center gap-2 md:hidden">{detailActions}</div> : null}
          <ConnectorDetails connector={connector} />
        </>
      ) : (
        <form id="connector-mutation-form" onSubmit={handleSubmit}>
          {isCreateTypeStep ? (
            <section aria-labelledby="connector-type-heading">
              <div className="mb-4">
                <p className="type-status text-foreground-secondary">Step 1 of 2</p>
                <h2 id="connector-type-heading" className="type-item mt-1 text-foreground">
                  Choose a data source type
                </h2>
              </div>
              <div role="radiogroup" aria-label="Connector type" className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
                {CONNECTOR_TYPE_ORDER.map((candidate, index) => (
                  <TypeChoice
                    key={candidate}
                    type={candidate}
                    selected={candidate === type}
                    disabled={submitting}
                    onChange={() => applyType(candidate)}
                    onKeyDown={(event) => handleTypeKeyDown(event, index)}
                    inputRef={(element) => {
                      typeInputs.current[candidate] = element;
                    }}
                  />
                ))}
              </div>
            </section>
          ) : (
            <div className="space-y-6">
              {mode === "create" ? (
                <div className="border-b border-border-light pb-3">
                  <p className="type-status text-foreground-secondary">Step 2 of 2</p>
                  <h2 className="type-item mt-1 flex items-center gap-2 text-foreground">
                    <ConnectorTypeMark type={type} size="sm" />
                    <span>{CONNECTOR_TYPE_META[type].label}</span>
                  </h2>
                </div>
              ) : null}
              <div className="type-control border-l-2 border-warning pl-3 leading-relaxed" role="note">
                <p className="text-foreground">Use a dedicated read-only warehouse account.</p>
                <p className="mt-1 text-foreground-secondary">If this account has write permissions, Explorers and their Agents can modify data. Monomer does not guarantee read-only access.</p>
              </div>

              {mode !== "rotate" ? <>
              <section aria-labelledby="connector-identity-heading" className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <h3 id="connector-identity-heading" className={SECTION_HEADING_CLASS}>
                  Identity
                </h3>
                <div className="sm:col-span-2">
                  <Input
                    id="connector-display-name"
                    label="Display name"
                    error={fieldErrors.displayName}
                    aria-label="Display name"
                    value={displayName}
                    disabled={submitting}
                    onChange={(event) => {
                      setDisplayName(event.target.value);
                      clearFieldError("displayName");
                    }}
                    aria-invalid={Boolean(fieldErrors.displayName)}
                    autoComplete="off"
                  />
                </div>
              </section>

              <section aria-labelledby="connector-connection-heading" className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
                <h3 id="connector-connection-heading" className={SECTION_HEADING_CLASS}>Connection</h3>
                {contract.properties.map((field) => (
                  <FieldControl
                    key={field.key}
                    field={field}
                    value={properties[field.key] ?? ""}
                    disabled={submitting || (mode === "revise" && field.key === "database" && (type === "POSTGRESQL" || type === "REDSHIFT"))}
                    error={fieldErrors[field.key]}
                    onChange={(value) => {
                      setProperties((current) => ({ ...current, [field.key]: value }));
                      clearFieldError(field.key);
                    }}
                  />
                ))}
              </section>

              </> : null}
              {mode !== "revise" ? <section aria-labelledby="connector-credentials-heading" className="grid grid-cols-1 gap-x-4 gap-y-3 sm:grid-cols-2">
                <h3 id="connector-credentials-heading" className={SECTION_HEADING_CLASS}>
                  Credentials
                </h3>
                {contract.credentialMethods.length > 1 ? (
                  <div className="sm:col-span-2">
                    <NativeSelect
                      label="Method"
                      value={credentialMethod.id}
                      disabled={submitting}
                      onChange={(event) => applyCredentialMethod(event.target.value)}
                    >
                      {contract.credentialMethods.map((method) => (
                        <option key={method.id} value={method.id}>
                          {method.label}
                        </option>
                      ))}
                    </NativeSelect>
                  </div>
                ) : null}
                {credentialMethod.fields.map((field) => (
                  <div
                    key={field.key}
                    className={field.kind === "textarea" || credentialMethod.fields.length === 1 ? "sm:col-span-2" : undefined}
                  >
                    <FieldControl
                      field={field}
                      value={credentials[field.key] ?? ""}
                      disabled={submitting}
                      error={fieldErrors[field.key]}
                      onChange={(value) => {
                        setCredentials((current) => ({ ...current, [field.key]: value }));
                        clearFieldError(field.key);
                      }}
                    />
                  </div>
                ))}
              </section> : null}
            </div>
          )}
        </form>
      )}
    </Dialog>
  );
}

export function ConnectorMutationDialog(props: ConnectorMutationDialogProps) {
  if (props.connector?.type === "DUCKDB" || props.connector?.sample) {
    return <Dialog open={props.open} onOpenChange={(open) => { if (!open) props.onClose(); }} closeLabel="Close connector details" size="lg" testId="connector-mutation-dialog" title={props.connector.name}>
      <p className="type-body text-foreground">Sample data · read-only · managed by Monomer</p>
      {props.catalogHref ? <Link href={props.catalogHref} className={buttonClassName("secondary", "sm", "mt-5")}>Browse in Catalog</Link> : null}
    </Dialog>;
  }
  return <ExternalConnectorMutationDialog {...props} />;
}
