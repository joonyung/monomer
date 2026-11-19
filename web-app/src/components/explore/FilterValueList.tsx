import { useState } from "react";
import { X } from "lucide-react";

/** Values for "is one of": each value is a chip, added with Enter; pasting several lines adds each line. */
export function FilterValueList({ values, onChange, placeholder }: { values: string[]; onChange: (values: string[]) => void; placeholder: string }) {
  const [draft, setDraft] = useState("");
  const add = (entries: string[]) => {
    const next = [...values];
    for (const entry of entries.map((value) => value.trim()).filter(Boolean)) if (!next.includes(entry)) next.push(entry);
    onChange(next);
  };
  return (
    <div className="flex min-h-8 flex-wrap items-center gap-1 rounded-[4px] border border-border bg-background px-1.5 py-1 focus-within:border-label">
      {values.map((value) => (
        <span key={value} className="type-status inline-flex max-w-full items-center gap-0.5 rounded-[4px] bg-surface py-0.5 pl-1.5 pr-0.5 text-foreground">
          <span className="truncate">{value}</span>
          <button type="button" aria-label={`Remove ${value}`} onClick={() => onChange(values.filter((candidate) => candidate !== value))} className="grid h-4 w-4 place-items-center rounded-[3px] text-foreground-secondary hover:bg-surface-hover hover:text-foreground">
            <X className="h-3 w-3" aria-hidden="true" />
          </button>
        </span>
      ))}
      <input
        aria-label="Filter value"
        value={draft}
        placeholder={values.length === 0 ? placeholder : "Add another"}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter" && draft.trim()) { event.preventDefault(); event.stopPropagation(); add([draft]); setDraft(""); }
          if (event.key === "Backspace" && !draft && values.length > 0) onChange(values.slice(0, -1));
        }}
        onBlur={() => { if (draft.trim()) { add([draft]); setDraft(""); } }}
        onPaste={(event) => {
          const text = event.clipboardData.getData("text");
          if (!/[\n\t]/.test(text)) return;
          event.preventDefault();
          add(text.split(/[\n\t]/));
        }}
        className="type-control min-w-[96px] flex-1 bg-transparent px-1 py-0.5 text-foreground outline-none placeholder:text-foreground-secondary"
      />
    </div>
  );
}
