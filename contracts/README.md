# Repository contracts

This directory owns repository-wide source contracts that do not belong to one service.

- Root JSON files define the Control HTTP, agent workspace, and CI contracts. Only the agent part of the Control contract is public.
- `architecture/module-ownership.json` defines Control module, package, dependency, and
  PostgreSQL schema ownership.
- `identity/security-identifier-map.json` defines security identifiers and action capabilities.
- `time-zones.json` lists the IANA time zones a Workspace Reporting time zone may use: tzdb `zone.tab`
  names and Zone names, plus UTC.
  Control validation and the Web picker read this one file. Its `source` and `generation` fields
  record the tzdb release and the command that reproduces the list.

Release manifests and evidence live in [`../release/`](../release/README.md).

Public OpenAPI and JSON Schema are published by
`public-site/scripts/sync-contracts.mjs`
from these and module-owned sources. The nine publication inputs under
`public-site/public/openapi/` and `public-site/public/schemas/` are ignored build
outputs. Public-site dev, test, and build commands regenerate them; Git owns only
the source contracts. Only the agent part of `control-service.openapi.json` is public, as
`/openapi/agent.openapi.json`; Web REST, operations, and public Pages viewer paths stay internal.
