import {
  forwardRef,
  useMemo,
  type AnchorHTMLAttributes,
  type MouseEvent,
} from "react";
import {
  notFound as tanstackNotFound,
  useLocation,
  useNavigate,
  useRouter as useTanStackRouter,
  useRouterState,
} from "@tanstack/react-router";

interface LinkProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, "href"> {
  href: string;
}

export function buildReplaceNavigationOptions(
  href: string,
  options?: { scroll?: boolean },
) {
  return {
    href,
    replace: true,
    resetScroll: options?.scroll !== false,
  } as const;
}

export const Link = forwardRef<HTMLAnchorElement, LinkProps>(function Link(
  { href, onClick, target, ...props },
  ref,
) {
  const navigate = useNavigate();
  const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(event);
    if (
      event.defaultPrevented ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey ||
      target === "_blank"
    ) {
      return;
    }
    const destination = new URL(href, window.location.href);
    if (destination.origin !== window.location.origin) return;
    event.preventDefault();
    void navigate({ href: `${destination.pathname}${destination.search}${destination.hash}` });
  };
  return <a ref={ref} {...props} href={href} target={target} onClick={handleClick} />;
});

export function useRouter() {
  const navigate = useNavigate();
  const router = useTanStackRouter();
  return useMemo(
    () => ({
      push: (href: string) => void navigate({ href }),
      replace: (href: string, options?: { scroll?: boolean }) =>
        void navigate(buildReplaceNavigationOptions(href, options)),
      back: () => window.history.back(),
      refresh: () => void router.invalidate(),
    }),
    [navigate, router],
  );
}

export function usePathname(): string {
  return useLocation({ select: (location) => location.pathname });
}

export function useCurrentSearch<
  TSearch extends Record<string, unknown> = Record<string, unknown>,
>(): TSearch {
  const search = useRouterState({
    select: (state) => state.location.search,
  });
  return search as TSearch;
}

export function useParams<TParams extends Record<string, string> = Record<string, string>>(): TParams {
  const params = useRouterState({
    select: (state) => state.matches.at(-1)?.params ?? {},
  });
  return params as TParams;
}

export function notFound(): never {
  throw tanstackNotFound();
}
