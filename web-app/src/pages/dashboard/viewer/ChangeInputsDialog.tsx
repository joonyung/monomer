import type { DashboardJsonObject, DashboardParameterDefinition } from "@/api/dashboard";
import { DashboardParameterForm } from "@/components/dashboard/DashboardParameterForm";
import { Dialog } from "@/components/ui/Dialog";

// Changing an input opens new results; the current results stay in History.
export function ChangeInputsDialog({ open, onOpenChange, parameters, currentValues, reviewRequired = false, pending, error, onSubmit }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  parameters: Record<string, DashboardParameterDefinition>;
  currentValues: DashboardJsonObject;
  reviewRequired?: boolean;
  pending: boolean;
  error: string | null;
  onSubmit: (bindings: DashboardJsonObject) => void;
}) {
  return <Dialog closeLabel="Close change inputs"
    open={open}
    onOpenChange={onOpenChange}
    size="lg"
    busy={pending}
    title="Change inputs"
    description="Results for these inputs open as a new entry in History. The current results stay available there."
  >
    <DashboardParameterForm parameters={parameters} initialValues={currentValues} allowUnchanged={reviewRequired} pending={pending} error={error} variant="dialog" onCancel={() => onOpenChange(false)} onOpen={onSubmit} />
  </Dialog>;
}
