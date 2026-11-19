import { useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { DashboardVariableInput } from "@/components/dashboard/DashboardVariableInput";
import type { DashboardJsonValue } from "@/api/dashboard";

const label = "Country and city grouping used by the revenue analysis — a long input label";
const options = [
  { value: "KR", label: "South Korea · a long option that compares online and retail orders in Seoul and Busan" },
  { value: "JP", label: "Japan" },
  { value: "US", label: "United States" },
];
export function InputLayoutFixture() {
  const [value, setValue] = useState<DashboardJsonValue>("KR");
  return <section className="space-y-4 py-6" aria-label="Input layout verification">
    <output data-testid="applied-country">{String(value)}</output>
    <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_220px]">
      <div className="h-[104px] overflow-hidden rounded-[6px] border border-border p-4"><DashboardVariableInput name="wide" definition={{ type: "TEXT", nullable: false, initial: "KR", label }} value={value} control={{ options }} onSet={setValue} /></div>
      <div className="overflow-hidden rounded-[6px] border border-border p-4"><DashboardVariableInput name="narrow" definition={{ type: "TEXT", nullable: false, initial: "KR", label: "Country" }} value={value} control={{ options }} onSet={setValue} /></div>
    </div>
    <div className="grid gap-4 md:grid-cols-2">
      <div className="border border-border p-4"><DashboardVariableInput name="disabled" definition={{ type: "TEXT", nullable: false, initial: "KR", label: "Read-only country" }} value="KR" control={{ options }} disabled onSet={setValue} /></div>
      <div className="border border-border p-4"><DashboardVariableInput name="invalid" definition={{ type: "NUMBER", nullable: false, initial: "5", label: "Maximum shown" }} value="5" control={{ min: "1", max: "10" }} onSet={() => undefined} /></div>
    </div>
    <Dialog.Root><Dialog.Trigger className="h-9 rounded-[4px] border border-border px-3">Open input dialog</Dialog.Trigger><Dialog.Portal><Dialog.Overlay className="fixed inset-0 bg-heading/10" /><Dialog.Content className="fixed left-1/2 top-1/2 w-[min(420px,calc(100%-32px))] -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-[8px] border border-border bg-background p-5"><Dialog.Title>Choose input</Dialog.Title><Dialog.Description>Popover and keyboard check</Dialog.Description><DashboardVariableInput name="dialog" definition={{ type: "TEXT", nullable: false, initial: "KR", label: "Dialog country" }} value={value} control={{ options }} onSet={setValue} /><Dialog.Close className="mt-4 h-8 px-3">Close dialog</Dialog.Close></Dialog.Content></Dialog.Portal></Dialog.Root>
  </section>;
}
