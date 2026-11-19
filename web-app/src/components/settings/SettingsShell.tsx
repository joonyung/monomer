import type { ReactNode } from "react";
import { MainLayout } from "@/components/layout/MainLayout";
import { NativeSelect } from "@/components/ui/Input";
import { useRouter } from "@/lib/navigation";
import { SettingsNavigationContext, type SettingsNavigationModel } from "./settingsNavigation";

interface SettingsShellProps {
  navigation: SettingsNavigationModel;
  kicker: string;
  title: string;
  actions?: ReactNode;
  children: ReactNode;
}

// Every settings page shares one header and the settings sidebar. Below the sidebar breakpoint a
// section select at the top of the content replaces the sidebar's section list.
export function SettingsShell({ navigation, kicker, title, actions, children }: SettingsShellProps) {
  const router = useRouter();
  return (
    <SettingsNavigationContext.Provider value={navigation}>
      <MainLayout>
        <div className="h-full overflow-y-auto">
          <div className="mx-auto max-w-[1080px] px-4 pb-16 pt-6 md:px-8 md:pt-10" style={{ fontFeatureSettings: '"ss01"' }}>
            <div className="mb-6 lg:hidden">
              <NativeSelect
                aria-label={`${navigation.label} section`}
                value={navigation.activeId}
                onChange={(event) => {
                  const item = navigation.items.find((candidate) => candidate.id === event.target.value);
                  if (item) router.push(item.href);
                }}
              >
                {navigation.items.map((item) => (
                  <option key={item.id} value={item.id}>{item.label}</option>
                ))}
              </NativeSelect>
            </div>
            <header className="mb-8 flex flex-wrap items-end justify-between gap-4">
              <div className="min-w-0">
                <p className="truncate type-group text-foreground-secondary">{kicker}</p>
                <h1 className="mt-1.5 type-display text-heading">{title}</h1>
              </div>
              {actions ? <div className="flex flex-wrap items-center gap-3">{actions}</div> : null}
            </header>
            {children}
          </div>
        </div>
      </MainLayout>
    </SettingsNavigationContext.Provider>
  );
}
