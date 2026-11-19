export function SidebarTooltip({ id, label }: { id: string; label: string }) {
  return (
    <span
      id={id}
      role="tooltip"
      className="pointer-events-none absolute left-full top-1/2 z-40 ml-2 -translate-y-1/2 translate-x-1 whitespace-nowrap rounded-[6px] bg-accent px-2.5 py-1.5 type-meta text-accent-foreground opacity-0 shadow-ambient transition-[opacity,transform] duration-150 group-hover:translate-x-0 group-hover:opacity-100 group-focus-visible:translate-x-0 group-focus-visible:opacity-100 motion-reduce:transition-none"
    >
      {label}
    </span>
  );
}
