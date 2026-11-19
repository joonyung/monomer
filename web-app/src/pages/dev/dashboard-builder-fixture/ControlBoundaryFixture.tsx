import { useState } from "react";
import type { DashboardJsonObject, DashboardJsonValue, DashboardParameterDefinition } from "@/api/dashboard";
import { DashboardParameterToolbar } from "@/components/dashboard/DashboardParameterToolbar";
import { DashboardParameterForm } from "@/components/dashboard/DashboardParameterForm";
import { DashboardVariableInput } from "@/components/dashboard/DashboardVariableInput";

const parameters: Record<string, DashboardParameterDefinition> = {
  region: { type: "TEXT", nullable: false, label: "Region", control: { options: [{ value: "south", label: "South" }, { value: "north", label: "North" }] } },
  regions: { type: "TEXT_SET", nullable: false, label: "Regions", control: { options: [{ value: "south", label: "South" }, { value: "north", label: "North" }] } },
  observedAt: { type: "TIMESTAMP", nullable: false, label: "Observed at", control: { max: "2026-09-01T00:00:00Z" } },
};
const serverParameters: DashboardJsonObject = { region: "west", regions: ["north", "west"], observedAt: "2026-09-06T09:00:00.000000001Z" };

export function ControlBoundaryFixture() {
  const [bindings, setBindings] = useState(serverParameters);
  const [quantity, setQuantity] = useState<DashboardJsonValue>("25");
  const [opened, setOpened] = useState<DashboardJsonObject | null>(null);
  return <main className="min-h-screen bg-background p-5 sm:p-8">
    <h1 className="mb-5 text-[22px] font-light text-heading">Control boundaries</h1>
    <DashboardParameterToolbar definitions={parameters} values={bindings} onSet={(name, value) => setBindings((current) => ({ ...current, [name]: value }))} />
    <div className="mt-5 grid gap-5 sm:grid-cols-2">
      {([{ label: "Quantity 0–10", min: "0", max: "10" }, { label: "Quantity 10–20", min: "10", max: "20" }]).map(({ label, min, max }) => <section key={label} aria-label={label} className="rounded-[6px] border border-border p-4">
        <DashboardVariableInput name="quantity" definition={{ type: "NUMBER", nullable: false, initial: "25", label }} control={{ min, max }} value={quantity} onSet={setQuantity} />
      </section>)}
    </div>
    <section className="mt-5 rounded-[6px] border border-border p-4" aria-label="New session">
      <DashboardParameterForm variant="dialog" parameters={{ regions: parameters.regions }} onOpen={setOpened} />
    </section>
    <div className="mt-5 space-y-2 break-all text-[12px] text-foreground">
      <p>Stored Parameters: <output data-testid="stored-parameters">{JSON.stringify(bindings)}</output></p>
      <p>Stored Variable: <output data-testid="stored-variable">{JSON.stringify(quantity)}</output></p>
      <p>New Session bindings: <output data-testid="opened-bindings">{JSON.stringify(opened)}</output></p>
    </div>
  </main>;
}
