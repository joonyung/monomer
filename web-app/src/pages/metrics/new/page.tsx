import { MetricEditor, type MetricEditorPrefill } from "@/components/metric/MetricEditor";
import { MainLayout } from "@/components/layout/MainLayout";

export default function NewMetricPage({ prefill = {} }: { prefill?: MetricEditorPrefill }) {
  return (
    <MainLayout>
      <MetricEditor prefill={prefill} />
    </MainLayout>
  );
}
