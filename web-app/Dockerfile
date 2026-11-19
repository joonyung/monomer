FROM --platform=$BUILDPLATFORM node:24.19.0-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 AS build

WORKDIR /workspace/web-app

ARG APP_ENV=production
ENV APP_ENV=${APP_ENV}
ARG VITE_MONOMER_CONTROL_API_URL
ENV VITE_MONOMER_CONTROL_API_URL=${VITE_MONOMER_CONTROL_API_URL}
ARG VITE_LOCAL_LOGIN_ENABLED=false
ARG VITE_GOOGLE_OAUTH_ENABLED=true
ARG MONOMER_PUBLIC_SITE_ORIGIN=https://monomer.cc
ENV VITE_MONOMER_PUBLIC_SITE_ORIGIN=${MONOMER_PUBLIC_SITE_ORIGIN}
ENV VITE_LOCAL_LOGIN_ENABLED=${VITE_LOCAL_LOGIN_ENABLED}
ENV VITE_GOOGLE_OAUTH_ENABLED=${VITE_GOOGLE_OAUTH_ENABLED}

COPY web-app/package.json web-app/package-lock.json ./
COPY web-app/patches ./patches
RUN npm ci

COPY web-app/src ./src
COPY web-app/public ./public
COPY web-app/scripts ./scripts
COPY web-app/config ./config
COPY web-app/index.html web-app/tsconfig.json web-app/vite.config.ts web-app/vitest.config.ts web-app/playwright.config.ts web-app/postcss.config.mjs ./
COPY scripts/generate-logo-assets.mjs scripts/logo-png-manifest.json /workspace/scripts/
COPY assets/brand /workspace/assets/brand
COPY contracts/metric-display.schema.json /workspace/contracts/metric-display.schema.json
COPY contracts/time-zones.json /workspace/contracts/time-zones.json
COPY public-site/public/favicon.svg public-site/public/favicon.png /workspace/public-site/public/
COPY public-site/public/brand/monomer-lockup-small.svg /workspace/public-site/public/brand/monomer-lockup-small.svg
COPY public-site/public/brand/monomer-lockup-small-inverse.svg /workspace/public-site/public/brand/monomer-lockup-small-inverse.svg
COPY public-site/src/data/agent-clients.json /workspace/public-site/src/data/agent-clients.json
RUN npm run build

FROM --platform=$BUILDPLATFORM node:24.19.0-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 AS public-site-build

WORKDIR /workspace/public-site
ARG MONOMER_PUBLIC_SITE_ORIGIN=https://monomer.cc
ENV MONOMER_PUBLIC_SITE_ORIGIN=${MONOMER_PUBLIC_SITE_ORIGIN}
ARG VITE_MONOMER_CONTROL_API_URL=https://api.monomer.cc
ENV MONOMER_PUBLIC_API_ORIGIN=${VITE_MONOMER_CONTROL_API_URL}
COPY public-site/package.json public-site/package-lock.json ./
RUN npm ci
COPY public-site/src ./src
COPY public-site/public ./public
COPY public-site/scripts ./scripts
COPY public-site/astro.config.mjs public-site/tsconfig.json ./
COPY contracts/control-service.openapi.json contracts/problem-details.schema.json contracts/agent-workspace.schema.json contracts/metric-display.schema.json /workspace/contracts/
COPY control-service/modules/connector/src/main/resources/schema/connector.schema.json /workspace/control-service/modules/connector/src/main/resources/schema/connector.schema.json
COPY control-service/modules/dashboard/src/main/resources/schema/dashboard.schema.json /workspace/control-service/modules/dashboard/src/main/resources/schema/dashboard.schema.json
COPY control-service/modules/semantic/src/main/resources/schema/semantic/*.schema.json /workspace/control-service/modules/semantic/src/main/resources/schema/semantic/
RUN npm run build

FROM scratch AS artifacts

COPY --from=build /workspace/web-app/dist /app
COPY --from=public-site-build /workspace/public-site/dist /public
