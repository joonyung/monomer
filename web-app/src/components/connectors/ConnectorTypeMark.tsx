import type { ConnectorType } from "@/lib/types/connector";
import { CONNECTOR_TYPE_META } from "./connectorTypeMeta";
import { ConnectorTypeIcon } from "./ConnectorTypeIcon";

interface ConnectorTypeMarkProps {
  type: ConnectorType;
  size?: "sm" | "md" | "lg";
}

const SIZE_MAP = {
  sm: { slot: "h-7 w-7", icon: "h-5 w-5" },
  md: { slot: "h-9 w-9", icon: "h-[23px] w-[23px]" },
  lg: { slot: "h-11 w-11", icon: "h-7 w-7" },
};

export function ConnectorTypeMark({ type, size = "md" }: ConnectorTypeMarkProps) {
  const meta = CONNECTOR_TYPE_META[type];
  const dims = SIZE_MAP[size];

  return (
    <span
      className={`${dims.slot} flex flex-shrink-0 items-center justify-center`}
      style={{ color: meta.iconColor }}
      data-connector-type-mark={type}
      aria-hidden="true"
    >
      <ConnectorTypeIcon type={type} className={dims.icon} />
    </span>
  );
}
