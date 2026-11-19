import { useRouter, Link } from "@/lib/navigation";
import { LayoutGrid, LogOut, Settings, SquareTerminal, User as UserIcon } from "lucide-react";
import { useAuthStore } from "@/lib/auth/store";
import { CONTROL_API_URL } from "@/lib/auth/config";
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from "@/components/ui/Menu";
import type { AuthUser } from "@/lib/auth/types";
import { webPaths } from "@/lib/web-paths";

export function useSignOut() {
  const logout = useAuthStore((s) => s.logout);
  const router = useRouter();
  return async () => {
    try {
      await fetch(`${CONTROL_API_URL}/auth/logout`, {
        method: "POST",
        credentials: "include",
      });
    } catch {
      // Sign-out still clears this browser's session when the server call fails.
    }
    logout();
    router.replace(webPaths.login);
  };
}

export function userInitials(displayName: string): string {
  return displayName
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part[0])
    .slice(0, 2)
    .join("")
    .toUpperCase();
}

export function UserAvatar({ user }: { user: AuthUser }) {
  const initials = userInitials(user.displayName ?? "");
  return (
    <span
      className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full bg-accent type-status font-medium text-accent-foreground"
      aria-hidden="true"
    >
      {initials || <UserIcon className="h-3.5 w-3.5" />}
    </span>
  );
}

// The identity header and personal destinations of the user menu. Shells without a workspace
// switcher, such as the Viewer's Pages, add "Switch workspace".
export function UserMenuEntries({
  user,
  testIdPrefix,
  switchWorkspace,
}: {
  user: AuthUser;
  testIdPrefix: string;
  switchWorkspace: boolean;
}) {
  const signOut = useSignOut();
  return (
    <>
      <MenuLabel className="border-b border-border pb-2">
        <span className="block truncate type-control text-foreground">{user.displayName}</span>
        <span className="block truncate type-status text-foreground-secondary">{user.email}</span>
      </MenuLabel>
      <div className="pt-1">
        <MenuItem asChild>
          <Link href={webPaths.userProfile} data-testid={`${testIdPrefix}-settings`}>
            <Settings aria-hidden="true" />
            Settings
          </Link>
        </MenuItem>
        <MenuItem asChild>
          <Link href={webPaths.userAgents} data-testid={`${testIdPrefix}-connect-agent`}>
            <SquareTerminal aria-hidden="true" />
            Connect an agent
          </Link>
        </MenuItem>
        {switchWorkspace ? (
          <MenuItem asChild>
            <Link href={webPaths.workspaces} data-testid={`${testIdPrefix}-switch-workspace`}>
              <LayoutGrid aria-hidden="true" />
              Switch workspace
            </Link>
          </MenuItem>
        ) : null}
      </div>
      <MenuSeparator />
      <MenuItem icon={LogOut} onSelect={() => void signOut()} data-testid={`${testIdPrefix}-logout`}>
        Sign out
      </MenuItem>
    </>
  );
}

// The avatar button that opens the user menu in a top bar.
export function UserMenuButton({
  user,
  testIdPrefix,
  switchWorkspace,
}: {
  user: AuthUser;
  testIdPrefix: string;
  switchWorkspace: boolean;
}) {
  return (
    <Menu modal={false}>
      <MenuTrigger asChild>
        <button
          type="button"
          data-testid={`${testIdPrefix}-trigger`}
          aria-label={`Open user menu for ${user.displayName}`}
          className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-[4px] transition-colors hover:bg-surface-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring focus-visible:outline-offset-2"
        >
          <UserAvatar user={user} />
        </button>
      </MenuTrigger>
      <MenuContent aria-label="User menu" align="end" className="w-[228px]">
        <UserMenuEntries user={user} testIdPrefix={testIdPrefix} switchWorkspace={switchWorkspace} />
      </MenuContent>
    </Menu>
  );
}
