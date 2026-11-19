import { NativeSelect } from "@/components/ui/Input";
import { Select } from "@/components/ui/Select";
import { formatMetricNumber, metricNumberFormat, type MetricDisplay } from "@/lib/metric-display";
import displaySchema from "../../../../contracts/metric-display.schema.json";

const UNITS = ["percent", "second", "millisecond", "minute", "hour", "day", "byte", "kilobyte", "megabyte", "gigabyte"] as const;

export function MetricDisplayFields({ value, disabled, onChange }: { value: MetricDisplay | null; disabled: boolean; onChange: (display: MetricDisplay | null) => void }) {
  const currencyNames = new Intl.DisplayNames(["en"], { type: "currency" });
  const currencies = displaySchema.$defs.currency.enum.map((code) => ({ value: code, label: `${code} · ${currencyNames.of(code) ?? code}` }));
  const numberFormat = metricNumberFormat(value);
  const changeStyle = (style: string) => {
    const decimals = value?.decimals ?? 2;
    onChange(style === "DEFAULT" ? null : style === "CURRENCY" ? { style, decimals, currency: "USD" } : style === "PERCENT" ? { style, decimals } : { style: "NUMBER", decimals });
  };
  return <div className="flex flex-wrap items-end gap-4">
    <div className="w-36"><NativeSelect id="metric-display-style" label="Style" value={value?.style ?? "DEFAULT"} disabled={disabled} onChange={(event) => changeStyle(event.target.value)}>
      <option value="DEFAULT">Default</option><option value="NUMBER">Number</option><option value="CURRENCY">Currency</option><option value="PERCENT">Percent</option>
    </NativeSelect></div>
    {value ? <div className="w-24"><NativeSelect id="metric-display-decimals" label="Decimals" value={String(value.decimals)} onChange={(event) => onChange({ ...value, decimals: Number(event.target.value) })}>{Array.from({ length: 7 }, (_, decimals) => <option key={decimals} value={decimals}>{decimals}</option>)}</NativeSelect></div> : null}
    {value?.style === "CURRENCY" ? <div className="min-w-0 flex-1 basis-64"><label htmlFor="metric-display-currency" className="type-meta mb-1.5 block text-label">Currency</label><Select id="metric-display-currency" label="Currency" value={value.currency} options={currencies} disabled={false} invalid={false} onChange={(currency) => onChange({ ...value, currency: currency as typeof value.currency })} /><p className="type-meta mt-1 text-foreground-secondary">Type a currency code to search.</p></div> : null}
    {value?.style === "NUMBER" ? <div className="w-40"><NativeSelect id="metric-display-unit" label="Unit" value={value.unit ?? ""} onChange={(event) => { const { unit: _unit, ...number } = value; onChange(event.target.value ? { ...number, unit: event.target.value as typeof UNITS[number] } : number); }}><option value="">None</option>{UNITS.map((unit) => <option key={unit} value={unit}>{unit.charAt(0).toUpperCase() + unit.slice(1)}</option>)}</NativeSelect></div> : null}
    <p className="type-body pb-1 tabular-nums text-foreground-secondary" aria-label="Display preview">1234.5 → {numberFormat ? formatMetricNumber("1234.5", numberFormat, "en-US") : "1,234.5"}</p>
    {disabled ? <p className="type-meta w-full text-foreground-secondary">Save the new aggregate, then choose its display format.</p> : null}
  </div>;
}
