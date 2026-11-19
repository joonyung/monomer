import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { SettingsField } from "@/components/settings/SettingsPrimitives";
import { formatSettingsDate } from "@/components/settings/organizationSettingsModel";
import { Button } from "@/components/ui/Button";
import { IconButton } from "@/components/ui/IconButton";
import { Input } from "@/components/ui/Input";
import { useAuthStore } from "@/lib/auth/store";
import { fetchMe, updateMyDisplayName } from "@/lib/auth/graphql";
import { UserSettingsShell } from "./shell";
import { settingsMeKey, useSettingsMe } from "./use-settings-me";
import { sortLoginIdentities } from "./userSettingsModel";

export default function ProfilePage() {
  return (
    <UserSettingsShell section="profile" title="Profile">
      <ProfileFields />
    </UserSettingsShell>
  );
}

function ProfileFields() {
  const user = useAuthStore((state) => state.user)!;
  const epoch = useAuthStore((state) => state.authEpoch);
  const identities = sortLoginIdentities(useSettingsMe().data?.loginIdentities ?? []);
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(user.displayName);
  const editButton = useRef<HTMLButtonElement>(null);
  const finish = () => { setEditing(false); requestAnimationFrame(() => editButton.current?.focus()); };
  const save = useMutation({
    mutationFn: async () => {
      await queryClient.cancelQueries({ queryKey: settingsMeKey(epoch) });
      if (useAuthStore.getState().authEpoch !== epoch) throw new Error("Your sign-in session changed.");
      await updateMyDisplayName(draft.trim());
      if (useAuthStore.getState().authEpoch !== epoch) throw new Error("Your sign-in session changed.");
      const me = await fetchMe(undefined, false);
      if (useAuthStore.getState().authEpoch !== epoch) throw new Error("Your sign-in session changed.");
      useAuthStore.getState().setMe({ user: me.user, organizationMemberships: me.organizationMemberships });
      queryClient.setQueryData(settingsMeKey(epoch), me);
    },
    onSuccess: () => { if (useAuthStore.getState().authEpoch === epoch) finish(); },
  });
  return (
    <section className="max-w-[760px]" aria-label="Profile">
      {save.isSuccess && !editing && <p role="status" className="mb-4 type-body text-success-text">Display name saved.</p>}
      <dl className="divide-y divide-border-light border-y border-border">
        {editing ? (
          <div className="grid gap-x-4 gap-y-2 py-3.5 sm:grid-cols-[200px_minmax(0,1fr)]">
            <dt className="type-meta text-foreground-secondary"><label htmlFor="profile-name">Display name</label></dt>
            <dd>
              <form onSubmit={(event) => { event.preventDefault(); if (draft.trim() && !save.isPending) save.mutate(); }}>
                <Input autoFocus id="profile-name" value={draft} disabled={save.isPending} onChange={(event) => setDraft(event.target.value)} helperText="This is how your name appears to other members." />
                {save.isError && <p role="alert" className="mt-3 type-body text-error">Couldn&apos;t save your display name. Try again.</p>}
                <div className="mt-4 flex gap-2">
                  <Button size="sm" disabled={!draft.trim()} pending={save.isPending} type="submit">{save.isPending ? "Saving…" : "Save"}</Button>
                  <Button size="sm" variant="secondary" disabled={save.isPending} type="button" onClick={finish}>Cancel</Button>
                </div>
              </form>
            </dd>
          </div>
        ) : (
          <SettingsField
            label="Display name"
            action={<IconButton ref={editButton} label="Edit display name" icon={Pencil} onClick={() => { setDraft(user.displayName); save.reset(); setEditing(true); }} />}
          >
            {user.displayName}
          </SettingsField>
        )}
        <SettingsField label="Email" valueClassName="break-all type-body">{user.email}</SettingsField>
        <SettingsField label="Sign-in">
          {identities.length === 0 ? (
            <span className="text-foreground-secondary">—</span>
          ) : (
            <ul className="space-y-1">
              {identities.map((identity) => (
                <li key={`${identity.provider}-${identity.linkedAt}`}>
                  <span className="break-all">{identity.provider} · {user.email}</span>
                  <span className="ml-2 type-meta tabular-nums text-foreground-secondary">
                    Linked <time dateTime={identity.linkedAt}>{formatSettingsDate(identity.linkedAt)}</time>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </SettingsField>
      </dl>
    </section>
  );
}
