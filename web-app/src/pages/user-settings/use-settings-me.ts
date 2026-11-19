import { useQuery } from "@tanstack/react-query";
import { fetchMe } from "@/lib/auth/graphql";
import { useAuthStore } from "@/lib/auth/store";

export const settingsMeKey = (epoch: number) => ["auth", epoch, "me"] as const;
export function useSettingsMe() {
  const epoch = useAuthStore((state) => state.authEpoch);
  const status = useAuthStore((state) => state.status);
  return useQuery({
    queryKey: settingsMeKey(epoch),
    queryFn: async ({ signal }) => {
      const me = await fetchMe(signal, false);
      if (signal.aborted || useAuthStore.getState().authEpoch !== epoch) throw new Error("Your sign-in session changed.");
      useAuthStore.getState().setMe({ user: me.user, organizationMemberships: me.organizationMemberships });
      return me;
    },
    enabled: status === "authenticated", retry: false,
  });
}
