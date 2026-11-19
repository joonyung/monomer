# Monomer Release Contracts

This directory defines pinned external artifact contracts and repository-wide structural verifiers:

- [`contracts/metricflow-artifact-set.json`](contracts/metricflow-artifact-set.json) pins the exact version, source commit, SHA-256 digests, provenance, and CycloneDX SBOM metadata for the external `metricflow-kotlin` artifact bundle.
- [`schemas/metricflow-artifact-set.schema.json`](schemas/metricflow-artifact-set.schema.json) validates the artifact-set contract format.
- [`scripts/verify_contracts.py`](scripts/verify_contracts.py) verifies `metricflow-kotlin` integrity, Control's module-ownership dependency graph, the Execution gRPC wire contract, and identity security contracts.

## Verification

Run from the repository root:

```bash
python3 release/scripts/verify_contracts.py --repository-root .
python3 -m unittest discover -s release/scripts/tests -p 'test_*.py'
```

Cloud infrastructure and deployment stacks are managed under [`../infra/cloud-dev/`](../infra/cloud-dev/README.md).
