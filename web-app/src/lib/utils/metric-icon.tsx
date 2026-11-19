import {
  BarChart3,
  Percent,
  Calculator,
  TrendingUp,
  Target,
  type LucideIcon,
} from "lucide-react";
import type { MetricType } from "@/lib/types/metric";

const METRIC_ICON_MAP: Record<MetricType, LucideIcon> = {
  simple: BarChart3,
  ratio: Percent,
  derived: Calculator,
  cumulative: TrendingUp,
  conversion: Target,
};

export function getMetricIcon(type: MetricType): LucideIcon {
  return METRIC_ICON_MAP[type];
}

interface MetricIconProps {
  type: MetricType;
  className?: string;
}

export function MetricIcon({ type, className = "w-5 h-5" }: MetricIconProps) {
  const Icon = getMetricIcon(type);
  return <Icon className={className} />;
}
