import type { DashboardDefinition, DashboardLayoutNode } from "@/api/dashboard";
import { changeFingerprint, type ChangeHighlightItem } from "@/lib/change-highlight";

export function dashboardWidgetChanges(definition: DashboardDefinition): ChangeHighlightItem[] {
  const placements = new Map<string, unknown[]>();
  const visit = (node: DashboardLayoutNode, path: unknown[]) => {
    if (node.type === "WIDGET") {
      placements.set(node.widgetId, [...(placements.get(node.widgetId) ?? []), path]);
    } else if (node.type === "STACK") {
      node.children.forEach((child, index) => visit(child.node, [...path, {
        direction: node.direction, separation: node.separation ?? "GAP", index,
        weights: node.children.map((entry) => entry.weight ?? null),
      }]));
    }
  };
  definition.layout.sections.forEach((section, index) => visit(section.root, [{ index, height: section.height }]));
  return [...placements].map(([id, placement]) => ({
    id, fingerprint: changeFingerprint({ widget: definition.widgets[id], placement }),
  }));
}
