import { useSearch } from "@tanstack/react-router";
import { notFound } from "@/lib/navigation";
import { PagesDemoExperience, type PagesDemoSurface } from "@/components/pages/PagesDemoExperience";
import { isPagesDemoAvailable } from "@/components/pages/demoAccess";

const SURFACES: PagesDemoSurface[] = [
  "root",
  "folder",
  "markdown",
  "notebook",
  "html",
  "empty-editor",
  "empty-member",
  "depth",
  "dense",
  "loading",
  "error",
  "not-found",
];

export default function PagesDemoPage() {
  if (!isPagesDemoAvailable(import.meta.env.DEV)) notFound();
  const params = useSearch({ from: "/dev/pages-demo/" });
  const requestedSurface = params.surface;
  const surface = SURFACES.includes(requestedSurface as PagesDemoSurface)
    ? requestedSurface as PagesDemoSurface
    : "folder";
  return (
    <PagesDemoExperience
      surface={surface}
      showControls={params.controls !== "0"}
    />
  );
}
