import { SemanticModelEditor, type SemanticModelSourceTable } from "@/components/semantic-model/SemanticModelEditor";
import { MainLayout } from "@/components/layout/MainLayout";

interface NewSemanticModelSource {
  connector?: string;
  catalog?: string;
  schema?: string;
  table?: string;
}

// A Catalog table opens the editor with that table as the source, e.g. from "Create semantic model".
function sourceTable(source: NewSemanticModelSource): SemanticModelSourceTable | null {
  if (!source.connector || !source.schema || !source.table) return null;
  return { connectorId: source.connector, catalog: source.catalog ?? null, schema: source.schema, relation: source.table };
}

export default function NewSemanticModelPage({ source = {} }: { source?: NewSemanticModelSource }) {
  return (
    <MainLayout>
      <SemanticModelEditor initialSource={sourceTable(source)} />
    </MainLayout>
  );
}
