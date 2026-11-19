# zrender 6.1.0 OffscreenCanvas resize

The ChartWidget Worker imports ECharts through its ESM entry. ECharts' canvas
renderer imports `zrender/lib/canvas/Painter.js`, so this patch targets the shipped
JavaScript consumed by both Vite development and production Worker builds.

In zrender 6.1.0, `CanvasPainter.resize` resizes only the root layer when the canvas
has no DOM `style` property. Existing virtual layers retain their initial bitmap
size, and manual compositing stretches them into the resized root. A cold
320×240 heatmap resized to 400×408 consequently has enlarged, clipped content.
The patch uses the same internal layer iteration as the DOM branch to resize
all existing layers, including virtual and hover layers, with their own DPR.
It does not change initial viewport sizing, progressive rendering, or Worker lifetime.

Upstream references:

- [6.1.0 resize implementation](https://github.com/ecomfe/zrender/blob/6.1.0/src/canvas/Painter.ts#L1123-L1135)
- [Manual compositing](https://github.com/ecomfe/zrender/blob/6.1.0/src/canvas/Painter.ts#L470-L480)
- [Upstream master still has the root-only branch](https://github.com/ecomfe/zrender/blob/master/src/canvas/Painter.ts#L1126-L1145)

`npm ci` applies the committed patch through `patch-package` in `postinstall`.
Both patch failures and version mismatch warnings are fatal. CI uses `npm ci`;
the static release Dockerfile copies patches before its install step; the local
`monomer` launcher also permits the install hook. Do not install this app with
`--ignore-scripts`: that skips the required patch. Build-time dependencies are
required to produce the static release; the shipped static assets need no npm install.

`npm run check:patches` verifies every committed `.patch` with a reverse dry run
against `node_modules`, without changing installed files. Playwright global setup
and the local `monomer -fe` dependency check run it before starting. If an existing
installation is missing a patch, the launcher fails with an `npm ci` instruction;
it does not reinstall automatically.

Remove this patch when an exact pinned ECharts/zrender release includes the
upstream correction and `tests/e2e/chart-offscreen-resize.spec.ts` passes without
it. Recheck the ECharts renderer import and production Worker bundle when upgrading.
Keep the real Worker regression test after removing the patch.
