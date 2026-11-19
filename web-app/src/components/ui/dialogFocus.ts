// The element that opened a dialog is captured before the dialog's children mount, because a
// child with autoFocus moves focus during the same commit and would otherwise be mistaken for it.
export function activeElementBeforeDialogOpens(doc: Document | undefined): HTMLElement | null {
  const active = doc?.activeElement;
  return active && active !== doc.body && typeof (active as HTMLElement).focus === "function"
    ? active as HTMLElement
    : null;
}

// When the opener is gone (for example, the dialog deleted its row), focus lands on the page
// heading instead of the document body so keyboard and screen-reader users keep their place.
export function dialogFocusReturnTarget(opener: HTMLElement | null, doc: Document): HTMLElement | null {
  if (opener?.isConnected) return opener;
  const fallback = doc.querySelector<HTMLElement>("main h1") ?? doc.querySelector<HTMLElement>("main");
  if (!fallback) return null;
  if (!fallback.hasAttribute("tabindex")) {
    fallback.setAttribute("tabindex", "-1");
    fallback.classList.add("focus-visible:!outline-none");
  }
  return fallback;
}
