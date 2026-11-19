import { SemanticFilterFixture } from "./SemanticFilterFixture";
import { notFound } from "@/lib/navigation";

export default function ExploreSemanticFilterFixturePage() {
  if (!import.meta.env.DEV) notFound();
  return <SemanticFilterFixture />;
}
