import { CapacityFixture } from "./CapacityFixture";
import { RuntimeFixtureHost } from "./RuntimeFixtureHost";
import { ScenarioExpressionFixture } from "./scenario-expression-fixture";
import { ThemeFixture } from "./ThemeFixture";

export default function ChartWidgetRuntimeFixturePage() {
  const search = new URLSearchParams(window.location.search);
  if (search.has("raster")) return <CapacityFixture rasterBytes={Number(search.get("raster"))} />;
  if (search.has("capacity")) return <CapacityFixture />;
  if (search.has("expression")) return <ScenarioExpressionFixture />;
  if (search.has("theme")) return <ThemeFixture />;
  return (
    <main className="min-h-screen bg-background" style={{ fontFeatureSettings: '"ss01"' }}>
      <RuntimeFixtureHost />
    </main>
  );
}
