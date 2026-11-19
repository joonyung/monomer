import { Dialog, type DialogSize } from "@/components/ui/Dialog";

interface PagesDialogProps {
  pending?: boolean;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  size?: DialogSize;
  children: React.ReactNode;
  footer?: React.ReactNode;
  trigger?: React.ReactNode;
  testId: string;
}

export function PagesDialog({
  pending = false,
  open,
  onOpenChange,
  title,
  description,
  size = "md",
  children,
  footer,
  trigger,
  testId,
}: PagesDialogProps) {
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      trigger={trigger}
      title={title}
      description={description}
      size={size}
      busy={pending}
      footer={footer}
      testId={testId}
    >
      {children}
    </Dialog>
  );
}
