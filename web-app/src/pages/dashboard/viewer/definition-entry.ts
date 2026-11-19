import type { DashboardDefinition, DashboardJsonObject, DashboardParameterDefinition } from "@/api/dashboard";
import { isDashboardValueShapeValid } from "@/features/dashboard/dashboard-value";

function orderedJson(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(orderedJson);
  if (value !== null && typeof value === "object") return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)).map(([key, item]) => [key, orderedJson(item)]));
  return value;
}

export function dashboardSnapshotsEqual(left: DashboardDefinition, right: DashboardDefinition): boolean {
  return JSON.stringify(orderedJson(left)) === JSON.stringify(orderedJson(right));
}

export function compatibleParameterValues(parameters: Record<string, DashboardParameterDefinition>, bindings: DashboardJsonObject): DashboardJsonObject {
  return Object.fromEntries(Object.entries(parameters).filter(([name, definition]) => Object.hasOwn(bindings, name)
    && (definition.nullable || bindings[name] !== null)
    && isDashboardValueShapeValid(definition.type, bindings[name])).map(([name]) => [name, bindings[name]]));
}
