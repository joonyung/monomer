interface MonomerLogoProps {
  className?: string;
  compact?: boolean;
}

export function MonomerLogo({ className, compact = false }: MonomerLogoProps) {
  return (
    <img
      src={compact ? "/brand/monomer-lockup-small.svg" : "/brand/monomer-lockup.svg"}
      alt="Monomer"
      className={className}
      draggable={false}
    />
  );
}
