import { useEffect, useRef, useState } from "react";

export function useTransientScrollbar() {
  const [scrolling, setScrolling] = useState(true);
  const hideTimer = useRef<number | null>(null);

  useEffect(() => {
    hideTimer.current = window.setTimeout(() => setScrolling(false), 1200);
    return () => {
      if (hideTimer.current !== null) window.clearTimeout(hideTimer.current);
    };
  }, []);

  const onScroll = () => {
    setScrolling(true);
    if (hideTimer.current !== null) window.clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => setScrolling(false), 700);
  };

  return { onScroll, className: `transient-scrollbar${scrolling ? " is-scrolling" : ""}` };
}
