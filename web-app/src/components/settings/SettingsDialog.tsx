import { Dialog, type DialogSize } from "@/components/ui/Dialog";

export function SettingsDialog({
  title,
  description,
  onClose,
  busy = false,
  size = "md",
  role = "dialog",
  children,
  footer,
}: {
  title: string;
  description?: string;
  onClose: () => void;
  busy?: boolean;
  size?: DialogSize;
  role?: "dialog" | "alertdialog";
  children: React.ReactNode;
  footer: React.ReactNode;
}) {
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={title}
      description={description}
      size={size}
      role={role}
      busy={busy}
      footer={footer}
    >
      {children}
    </Dialog>
  );
}
