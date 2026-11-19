# Monomer Web Application (`web-app`)

Authenticated workspace SPA for Monomer, supporting interactive semantic modeling, Explore SQL/semantic querying, reactive dashboard authoring with live agent drafts over SSE, and Pages browsing.

## Tech Stack

- React 19 + TypeScript + Vite
- TanStack Router v1 (file-based routing with Zod search schemas)
- TanStack Query (REST list/detail caching and invalidation)
- Zustand + Zod (session, dashboard draft/editor, and transient client state)
- Tailwind CSS 4 + Radix UI
- Sandboxed Worker / SES ECharts widget runtime + OpenAPI-TypeScript generated client + SSE

## Development & Verification

```bash
npm ci
npm run dev
npm run lint
npm run test
VITE_MONOMER_CONTROL_API_URL=https://api.example.com npm run build
npm run test:e2e
```

## Directory Structure & State Ownership

```text
src/routes/          TanStack file-based routes and Zod search validation
src/pages/           Workspace views (Overview, Catalog, Semantics, Explore, Dashboards, Pages, Settings)
src/components/      Reusable UI primitives and domain surfaces
src/api/             Generated OpenAPI client, REST helpers, and SSE transport
src/lib/query/       QueryClient configuration and query key factories
src/lib/stores/      Client and editor Zustand stores
```

Finite REST resources are owned by TanStack Query and invalidated on mutations. Authentication sessions, collaborative dashboard drafts, and transient UI states are owned by Zustand or local component state. SSE streams remain dedicated transport hooks that reconcile with Query caches at explicit snapshot/event boundaries.

## Build Configuration & Same-Site Origin Contract

- `APP_ENV` selects environment defaults (`local` for dev server, `production` for builds); shell `VITE_*` variables always override file defaults.
- `VITE_MONOMER_CONTROL_API_URL` is required at build time and must be a valid HTTPS origin (loopback HTTP is permitted only when `APP_ENV=local`).
- Refresh cookies use host-only `SameSite=Lax`, so production Web (`app.example.com`) and Control (`api.example.com`) share a parent site over HTTPS, with matching Control CORS, frontend callback URL, and Google OAuth redirect URI settings.
- In production, static assets are served from private S3 behind CloudFront, rewriting extensionless SPA routes to `index.html` while keeping missing assets as errors and applying dedicated Worker CSP rules for sandboxed chart widgets (`assets/chart-widget-worker-[hash].js`).
