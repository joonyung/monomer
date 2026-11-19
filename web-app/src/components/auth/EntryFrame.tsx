import type { ReactNode, Ref } from "react";
import { CircleAlert, LoaderCircle } from "lucide-react";
import { MonomerLogo } from "@/components/brand/MonomerLogo";

// The one frame for every screen outside a workspace except Login: the small lockup at the
// top left, a centered 420px column, a Display heading, and no shadow card.
export function EntryFrame({ children, label }: { children: ReactNode; label?: string }) {
  return (
    <main className="flex min-h-screen flex-col bg-background px-4 py-6 sm:px-8" aria-label={label}>
      <MonomerLogo compact className="h-6 w-auto self-start" />
      <div className="mx-auto mt-12 w-full max-w-[420px] pb-12 sm:mt-20">{children}</div>
    </main>
  );
}

export function EntryEyebrow({ children }: { children: ReactNode }) {
  return <p className="type-group text-label">{children}</p>;
}

// The page heading takes focus when the screen's state changes so screen readers and keyboard
// users land on the new state.
export function EntryHeading({ children, headingRef, className = "" }: {
  children: ReactNode;
  headingRef?: Ref<HTMLHeadingElement>;
  className?: string;
}) {
  return (
    <h1 ref={headingRef} tabIndex={-1} className={`type-display break-words text-heading outline-none ${className}`}>
      {children}
    </h1>
  );
}

// A StateBlock-shaped state for the entry frame: icon, Display heading, a sentence, and actions.
export function EntryStatus({ kind, title, description, actions, headingRef }: {
  kind: "loading" | "error" | "info";
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  headingRef?: Ref<HTMLHeadingElement>;
}) {
  const role = kind === "error" ? "alert" : kind === "loading" ? "status" : undefined;
  return (
    <div role={role}>
      {kind === "loading" ? (
        <LoaderCircle className="h-6 w-6 animate-spin text-foreground-secondary motion-reduce:animate-none" aria-hidden="true" />
      ) : kind === "error" ? (
        <CircleAlert className="h-6 w-6 text-error" aria-hidden="true" />
      ) : null}
      <EntryHeading headingRef={headingRef} className={kind === "info" ? "" : "mt-4"}>{title}</EntryHeading>
      {description ? <p className="type-body mt-3 text-foreground-secondary">{description}</p> : null}
      {actions ? <div className="mt-6 flex flex-wrap items-center gap-3">{actions}</div> : null}
    </div>
  );
}
