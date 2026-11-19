import fs from "node:fs";
import path from "node:path";
import react from "@vitejs/plugin-react";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import { defineConfig, type Plugin } from "vite";
import { resolveControlApiUrl } from "./src/lib/auth/control-api-origin";
import { resolvePublicSiteOrigin } from "./src/lib/public-site-origin";

const DEVELOPMENT_ROUTE_FILE_PATTERN = "^dev(?:\\.|$)";

function loadAppEnvironment(command: "build" | "serve"): Record<string, string> {
  const appEnvironment = process.env.APP_ENV?.trim() || (command === "build" ? "production" : "local");
  const envFileCandidates = [
    path.resolve(process.cwd(), "config", `${appEnvironment}.env`),
    path.resolve(process.cwd(), "config", "env", `${appEnvironment}.env`),
  ];
  const envFilePath = envFileCandidates.find(fs.existsSync) ?? envFileCandidates[0];

  console.log(`APP_ENV: ${appEnvironment}`);
  console.log(`envFilePath: ${envFilePath}`);

  if (fs.existsSync(envFilePath)) {
    for (const line of fs.readFileSync(envFilePath, "utf8").split(/\r?\n/)) {
      const trimmedLine = line.trim();
      if (!trimmedLine || trimmedLine.startsWith("#")) continue;
      const separatorIndex = trimmedLine.indexOf("=");
      if (separatorIndex < 1) continue;
      const key = trimmedLine.slice(0, separatorIndex).trim();
      const rawValue = trimmedLine.slice(separatorIndex + 1).trim();
      const value = rawValue.replace(/^"|"$/g, "").replace(/^'|'$/g, "");
      if (!(key in process.env)) {
        process.env[key] = value;
      } else if (process.env[key] !== value) {
        console.log(`env override from shell: ${key}`);
      }
    }
  }

  process.env.VITE_MONOMER_CONTROL_API_URL = resolveControlApiUrl(
    process.env.VITE_MONOMER_CONTROL_API_URL,
    appEnvironment === "local",
  );
  process.env.VITE_MONOMER_PUBLIC_SITE_ORIGIN = resolvePublicSiteOrigin(process.env.VITE_MONOMER_PUBLIC_SITE_ORIGIN);

  return Object.fromEntries(
    Object.entries(process.env).flatMap(([key, value]) =>
      key.startsWith("VITE_") && value !== undefined ? [[key, value]] : [],
    ),
  );
}

function healthEndpoint(): Plugin {
  const healthBody = JSON.stringify({ status: "UP" });
  const handleHealth = (
    request: { url?: string },
    response: { statusCode: number; setHeader: (name: string, value: string) => void; end: (body: string) => void },
    next: () => void,
  ) => {
    if (request.url !== "/monitor/health") {
      next();
      return;
    }
    response.statusCode = 200;
    response.setHeader("Content-Type", "application/json; charset=utf-8");
    response.end(healthBody);
  };
  return {
    name: "monomer-health-endpoint",
    configureServer: (server) => {
      server.middlewares.use(handleHealth);
    },
    configurePreviewServer: (server) => {
      server.middlewares.use(handleHealth);
    },
  };
}

function restoreDevelopmentRouteTree(): Plugin {
  return {
    name: "restore-development-route-tree",
    apply: "build",
    async closeBundle() {
      const { Generator, getConfig } = await import("@tanstack/router-generator");
      const root = process.cwd();
      const config = getConfig({ target: "react", autoCodeSplitting: true }, root);
      await new Generator({ config, root }).run();
    },
  };
}

export default defineConfig(({ command }) => {
  const exposedEnvironment = loadAppEnvironment(command);
  const environmentDefinitions = Object.fromEntries(
    Object.entries(exposedEnvironment).map(([key, value]) => [
      `import.meta.env.${key}`,
      JSON.stringify(value),
    ]),
  );

  return {
    plugins: [
      tanstackRouter({
        target: "react",
        autoCodeSplitting: true,
        routeFileIgnorePattern: command === "build"
          ? DEVELOPMENT_ROUTE_FILE_PATTERN
          : undefined,
      }),
      react(),
      healthEndpoint(),
      restoreDevelopmentRouteTree(),
    ],
    worker: {
      rolldownOptions: {
        output: {
          entryFileNames: (chunk) => chunk.facadeModuleId?.replaceAll("\\", "/").endsWith("/features/chart-widget/runtime/worker/entry.ts")
            ? "assets/chart-widget-worker-[hash].js"
            : "assets/[name]-[hash].js",
        },
      },
    },
    // The dependency scanner does not follow Worker imports, so the chart and table Workers'
    // dependencies would be discovered on the first chart page and force a full-page reload.
    optimizeDeps: { include: ["echarts", "ses", "sucrase"] },
    define: environmentDefinitions,
    resolve: { tsconfigPaths: true },
    // The Agents page reads the client list that the public docs own.
    server: { strictPort: true, fs: { allow: [".", "../public-site/src/data/agent-clients.json"] } },
    preview: { strictPort: true },
  };
});
