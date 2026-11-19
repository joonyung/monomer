import { defineConfig } from "vitest/config";
import path from "path";

export default defineConfig({
  oxc: {
    jsx: {
      runtime: "automatic",
      importSource: "react",
    },
  },
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
    },
  },
  test: {
    env: { VITE_MONOMER_CONTROL_API_URL: "http://127.0.0.1:11077" },
    environment: "node",
    exclude: [
      "node_modules",
      "tests/e2e/**",
      "dist",
    ],
  },
});
