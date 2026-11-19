import type { Page } from "@playwright/test";
import { json } from "./session";

// Authoring tests observe the real focus request without needing unrelated Dashboard fixtures.
export async function mockSemanticLineage(page: Page): Promise<void> {
  await page.route("**/v2/graphql", async (route) => {
    const body = route.request().postDataJSON() as {query:string;variables:{workspaceId:string;focus:{kind:string;id:string}}};
    if (!body.query.includes("query LineageFocusCone")) return route.fallback();
    const focus = body.variables.focus;
    const resource = {resource:focus,label:focus.id,address:focus.id,description:null,connector:null,missing:false};
    await json(route,{data:{lineageFocusCone:{workspaceId:body.variables.workspaceId,focus:resource,ancestors:[],descendants:[],siblings:[],missingDefinitions:[],edges:[],siblingEdges:[],entityPeers:[],dashboardUsages:[],detail:{relation:null,semanticModel:focus.kind==="SEMANTIC_MODEL"?{entities:[],dimensions:[],measures:[]}:null,metric:focus.kind==="METRIC"?{type:"SIMPLE",inputs:[],timeGranularity:null}:null}}}});
  });
}
