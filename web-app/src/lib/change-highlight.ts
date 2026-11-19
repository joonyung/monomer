import { useLayoutEffect, useRef } from "react";
import "./change-highlight.css";

export function changeFingerprint(value: unknown): string {
  return JSON.stringify(value, (_key, item: unknown) => item && typeof item === "object" && !Array.isArray(item)
    ? Object.fromEntries(Object.entries(item).sort(([left], [right]) => left.localeCompare(right)))
    : item);
}

export interface ChangeHighlightItem {
  id: string;
  fingerprint: string;
  highlightArrival?: boolean;
}

export function useChangeHighlight(scope: string, items: ChangeHighlightItem[] | null,
  { duration = 1_000, animateArrival = false, arrivalStyle = "expand" }: { duration?: number; animateArrival?: boolean; arrivalStyle?: "expand" | "fade-slide" } = {},
) {
  const container = useRef<HTMLDivElement>(null);
  const previous = useRef<{ scope: string; items: Map<string, string> } | null>(null);
  const arrivals = useRef(new Map<HTMLElement, Animation>());
  const animations = useRef(new Map<HTMLElement, Animation>());

  useLayoutEffect(() => {
    const active = animations.current;
    const entering = arrivals.current;
    const clear = () => {
      for (const [element, animation] of active) {
        animation.cancel();
        element.removeAttribute("data-change-active");
      }
      active.clear();
      for (const animation of entering.values()) animation.cancel();
      entering.clear();
    };
    const visibility = () => {
      if (document.hidden) { previous.current = null; clear(); }
    };
    document.addEventListener("visibilitychange", visibility);
    return () => { document.removeEventListener("visibilitychange", visibility); clear(); };
  }, []);

  useLayoutEffect(() => {
    const prior = previous.current;
    if (!items) { previous.current = null; return; }
    previous.current = document.hidden ? null : { scope, items: new Map(items.map((item) => [item.id, item.fingerprint])) };
    if (!prior || prior.scope !== scope || document.hidden) {
      for (const [element, animation] of animations.current) {
        animation.cancel(); element.removeAttribute("data-change-active");
      }
      animations.current.clear();
      for (const animation of arrivals.current.values()) animation.cancel();
      arrivals.current.clear();
      return;
    }
    const changed = new Set(items.filter((item) => prior.items.has(item.id)
      ? prior.items.get(item.id) !== item.fingerprint
      : item.highlightArrival !== false).map((item) => item.id));
    for (const element of container.current?.querySelectorAll<HTMLElement>("[data-change-id]") ?? []) {
      if (!changed.has(element.dataset.changeId!)) continue;
      const overlay = element.querySelector<HTMLElement>(":scope > .change-highlight-overlay");
      if (!overlay) continue;
      const old = animations.current.get(element);
      const startingOpacity = old ? getComputedStyle(overlay).opacity : "0";
      old?.cancel();
      const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      if (animateArrival && !prior.items.has(element.dataset.changeId!) && !reduced) {
        const frames: Keyframe[] = arrivalStyle === "fade-slide" ? [
          { transform: "translateY(8px)", opacity: 0 },
          { transform: "translateY(0)", opacity: 1 },
        ] : [
          { height: "0px", opacity: 0, overflow: "clip" },
          { height: `${element.getBoundingClientRect().height}px`, opacity: 1, overflow: "clip" },
        ];
        const arrival = element.animate(frames, { duration: 280, easing: "cubic-bezier(0.2, 0, 0, 1)" });
        arrivals.current.set(element, arrival);
        arrival.onfinish = () => { arrivals.current.delete(element); };
      }
      const animation = overlay.animate(reduced ? [{ opacity: 1 }, { opacity: 1 }] : [
        { opacity: startingOpacity, offset: 0, easing: "ease-out" }, { opacity: 1, offset: 0.15, easing: "ease-out" }, { opacity: 0, offset: 1 },
      ], { duration: reduced ? 500 : duration });
      element.setAttribute("data-change-active", "true");
      animations.current.set(element, animation);
      animation.onfinish = () => {
        if (animations.current.get(element) !== animation) return;
        animations.current.delete(element);
        element.removeAttribute("data-change-active");
      };
    }
  }, [items, scope, duration, animateArrival, arrivalStyle]);
  return container;
}
