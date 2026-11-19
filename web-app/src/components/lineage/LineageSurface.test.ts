import { describe, expect, it, vi } from "vitest";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { LineageSurface, resourceKey, resourceOwnerLink } from "./LineageSurface";
import { visibleLineageKeys, selectedPathKeys } from "./LineageMap";
import { impactDepths } from "@/components/semantics/AffectsList";
import { makeLineageFixtureGraph } from "./LineageAccessFixture";

const graph = () => makeLineageFixtureGraph("ready")!;

describe("Lineage map",()=>{
  it("counts visible Dashboard uses downstream and excludes hidden deeper nodes", () => {
    const current = graph();
    current.descendants = [];
    current.edges = current.edges.filter((edge) => resourceKey(edge.dependent) === resourceKey(current.focus.resource));
    current.dashboardUsages = [{ dashboardId: "used-dashboard", title: "Used dashboard", queryNames: ["orders"], metricIds: [current.focus.resource.id] }];
    const markup = renderToStaticMarkup(createElement(LineageSurface, { workspaceId: current.workspaceId, graph: current, state: "ready", onSetFocus: vi.fn(), canCreateSemanticModel: false }));
    expect(markup).toContain("1 upstream · 1 downstream");
    expect(markup).toContain("Used dashboard");
    expect(markup).not.toContain("built from the same parents");
  });
  it("opens at depth one and expands only neighbours of expanded nodes",()=>{
    const current=graph(); const focus=resourceKey(current.focus.resource);
    const visible=visibleLineageKeys(current,new Set([focus]));
    const direct=current.edges.filter((edge)=>resourceKey(edge.dependent)===focus||resourceKey(edge.parent)===focus);
    expect(visible.size).toBe(new Set([focus,...direct.flatMap((edge)=>[resourceKey(edge.parent),resourceKey(edge.dependent)])]).size);
    const model=current.ancestors.find((resource)=>resource.resource.kind==="SEMANTIC_MODEL")!;
    const expanded=visibleLineageKeys(current,new Set([focus,resourceKey(model.resource)]));
    expect([...visible].every((value)=>expanded.has(value))).toBe(true);
    expect(expanded.size).toBeGreaterThan(visible.size);
  });
  it("expands dashboard usage as one additional step and preserves its selected inputs", () => {
    const current = graph();
    const metric = current.descendants[0].resource;
    current.dashboardUsages = [{ dashboardId: "dashboard", title: "Revenue dashboard", queryNames: ["revenue"], metricIds: [metric.id] }];
    const focus = resourceKey(current.focus.resource);
    expect(visibleLineageKeys(current, new Set([focus])).has("DASHBOARD:dashboard")).toBe(false);
    expect(visibleLineageKeys(current, new Set([focus, resourceKey(metric)])).has("DASHBOARD:dashboard")).toBe(true);
    expect(selectedPathKeys(current, "DASHBOARD:dashboard").has(resourceKey(metric))).toBe(true);
  });
  it("highlights only the selected Dashboard's upstream path, excluding other uses and downstream metrics", () => {
    const current = graph();
    const focus = current.focus.resource;
    current.dashboardUsages = [
      { dashboardId: "selected", title: "Selected", queryNames: ["orders"], metricIds: [focus.id] },
      { dashboardId: "other", title: "Other", queryNames: ["orders"], metricIds: [focus.id] },
    ];
    const path = selectedPathKeys(current, "DASHBOARD:selected");
    expect(path.has(resourceKey(focus))).toBe(true);
    expect(current.ancestors.every((resource) => path.has(resourceKey(resource.resource)))).toBe(true);
    expect(path.has("DASHBOARD:other")).toBe(false);
    expect(current.descendants.every((resource) => !path.has(resourceKey(resource.resource)))).toBe(true);
  });
  it("highlights upstream and downstream paths without treating siblings as dependencies",()=>{
    const current=graph(); const path=selectedPathKeys(current,resourceKey(current.focus.resource));
    expect(path.has(resourceKey(current.focus.resource))).toBe(true);
    expect(current.ancestors.every((resource)=>path.has(resourceKey(resource.resource)))).toBe(true);
    expect(current.siblings.some((resource)=>path.has(resourceKey(resource.resource)))).toBe(false);
  });
  it("terminates cycles and computes the shortest dependency depth",()=>{
    const current=graph();current.edges.push({parent:current.descendants[0].resource,dependent:current.focus.resource});
    expect(selectedPathKeys(current,resourceKey(current.focus.resource)).size).toBeLessThan(20);
    expect(impactDepths(current).get(resourceKey(current.focus.resource))).toBe(0);
    expect(impactDepths(current).get(resourceKey(current.descendants[0].resource))).toBe(1);
  });
  it("always pairs the map with an impact list and retains direct owner navigation",()=>{
    const current=graph();const markup=renderToStaticMarkup(createElement(LineageSurface,{workspaceId:current.workspaceId,graph:current,state:"ready",onSetFocus:vi.fn(),canCreateSemanticModel:false}));
    expect(markup).toContain(">Affects</h2>");expect(markup).toContain("Expand with +");expect(markup).not.toContain("react-flow__minimap");
    expect(resourceOwnerLink("ws",current.focus)?.href).toContain("/metrics/");
  });
  it("missing definitions remain visible as broken references without an owner link",()=>{
    const current=graph(); const missing={resource:{kind:"METRIC" as const,id:"deleted"},label:"deleted_revenue",address:"deleted_revenue",description:null,connector:null,missing:true};
    current.missingDefinitions=[missing];current.edges.push({parent:missing.resource,dependent:current.focus.resource});
    expect(resourceOwnerLink("ws",missing)).toBeNull();
    const markup=renderToStaticMarkup(createElement(LineageSurface,{workspaceId:current.workspaceId,graph:current,state:"ready",onSetFocus:vi.fn(),canCreateSemanticModel:false}));
    expect(markup).toContain("Broken references");expect(markup).toContain("deleted_revenue");
  });
  it("unavailable never claims whether deletion or permission caused it",()=>{
    const markup=renderToStaticMarkup(createElement(LineageSurface,{workspaceId:"ws",graph:null,state:"unavailable",onSetFocus:vi.fn(),canCreateSemanticModel:false}));
    expect(markup).toContain("This resource isn&#x27;t available");expect(markup).toContain("Choose another resource");
  });
});
