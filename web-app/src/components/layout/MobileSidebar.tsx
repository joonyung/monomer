import { useEffect, useState } from "react";
import { usePathname } from "@/lib/navigation";
import * as Dialog from "@radix-ui/react-dialog";
import { Menu, X } from "lucide-react";
import { SidebarContent } from "@/components/layout/Sidebar";
import { DIALOG_OVERLAY_CLASS } from "@/components/ui/Dialog";
import { useSettingsNavigation } from "@/components/settings/settingsNavigation";
import {
  shellNavigationLabel,
  type ApplicationShellMode,
  type ShellContextTransition,
} from "@/components/layout/shellMode";

export function MobileSidebar({
  mode,
  transition,
  onModeChange,
  onOpenSearch,
}: {
  mode: ApplicationShellMode;
  transition: ShellContextTransition;
  onModeChange: (mode: ApplicationShellMode) => void;
  onOpenSearch: () => void;
}) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const [drawerElement, setDrawerElement] = useState<HTMLDivElement | null>(null);
  const settingsNavigation = useSettingsNavigation();
  const navigationLabel = settingsNavigation?.label ?? shellNavigationLabel(mode);

  useEffect(() => {
    setOpen(false);
  }, [pathname]);

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger asChild>
        <button
          type="button"
          aria-label={`Open ${navigationLabel.toLowerCase()}`}
          className="flex h-11 w-11 items-center justify-center rounded-[4px] text-foreground-secondary transition-colors hover:bg-surface-hover hover:text-foreground focus:outline-none lg:hidden"
        >
          <Menu className="h-4 w-4" aria-hidden="true" />
        </button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className={`${DIALOG_OVERLAY_CLASS} lg:hidden`} />
        <Dialog.Content
          ref={setDrawerElement}
          className="fixed inset-y-0 left-0 z-50 flex w-[min(320px,calc(100vw-48px))] flex-col border-r border-border bg-background shadow-modal focus:outline-none lg:hidden"
          style={{ fontFeatureSettings: '"ss01"' }}
          aria-describedby={undefined}
        >
          <div className="flex h-12 flex-shrink-0 items-center justify-between border-b border-border px-3">
            <Dialog.Title className="type-body text-heading">
              {navigationLabel}
            </Dialog.Title>
            <Dialog.Close
              aria-label={`Close ${navigationLabel.toLowerCase()}`}
              className="flex h-11 w-11 items-center justify-center rounded-[4px] text-foreground-secondary transition-colors hover:bg-surface-hover hover:text-foreground focus:outline-none"
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </Dialog.Close>
          </div>
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
            <SidebarContent
              mode={mode}
              transition={transition}
              onModeChange={onModeChange}
              onWorkspaceContextChange={() => setOpen(false)}
              menuPortalContainer={drawerElement}
              onOpenSearch={() => {
                setOpen(false);
                onOpenSearch();
              }}
            />
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
