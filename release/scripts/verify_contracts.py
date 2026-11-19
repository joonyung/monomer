#!/usr/bin/env python3

import argparse
import hashlib
import sys
from pathlib import Path

REPOSITORY_SCRIPTS = Path(__file__).resolve().parents[2] / "scripts"
sys.path.insert(0, str(REPOSITORY_SCRIPTS))

from canonical_json import load_json, sha256
from schema_validator import validate_file


METRICFLOW_PRODUCT_MODULE_ALLOWLIST = [
    "metricflow-core",
    "metricflow-engine",
    "metricflow-render-bigquery",
    "metricflow-render-databricks",
    "metricflow-render-duckdb",
    "metricflow-render-postgres",
    "metricflow-render-redshift",
    "metricflow-render-snowflake",
    "metricflow-render-trino",
]
METRICFLOW_EXCLUDED_MODULES = ["internal-diff-runner"]


def verify_module_graph(module_map) -> None:
    modules = {entry["id"]: entry for entry in module_map["modules"]}
    for module in modules.values():
        unknown = sorted(set(module["allowedDependencies"]) - set(modules))
        if unknown:
            raise RuntimeError(module["id"] + " has unknown dependencies " + ", ".join(unknown))

    visiting = set()
    visited = set()

    def visit(module_id: str) -> None:
        if module_id in visiting:
            raise RuntimeError("module dependency cycle at " + module_id)
        if module_id in visited:
            return
        visiting.add(module_id)
        for dependency in modules[module_id]["allowedDependencies"]:
            visit(dependency)
        visiting.remove(module_id)
        visited.add(module_id)

    for module_id in sorted(modules):
        visit(module_id)


def verify_protocol(repository_root: Path) -> None:
    proto = (repository_root / "protos/execution_plane.proto").read_text()
    required_fragments = [
        "package monomer.execution.v1;",
        'option java_package = "cc.monomer.execution.protocol.v1";',
        "rpc Run(stream ControlMessage)",
        "ControlAssignment",
        "run_id",
        "ExecutionEvent",
        "credential_document",
        "QueryRequest",
        "InlineStreamTarget",
        "ArtifactUploadTarget",
        "object_key",
        "maximum_artifact_rows",
        "InlineResultBatch",
        "PhysicalOutcome",
        "ArrowResultManifest",
        "PHYSICAL_OUTCOME_KIND_SUCCEEDED",
    ]
    missing = [fragment for fragment in required_fragments if fragment not in proto]
    if missing:
        raise RuntimeError("execution protocol is missing " + ", ".join(missing))

    forbidden_fragments = [
        "CreateConnector",
        "UpdateConnector",
        "ListJobs",
        "WatchJob",
        "GetJob",
        "OpenWorkerControl",
        "OpenAttempt",
        "RunAttempt",
        "PreviewBatch",
        "preview_batch",
        "attempt_id",
        "lease_epoch",
        "staging_handle",
        "manifest_digest",
        "MaterializeCredential",
        "ReadyToEnter",
        "SignedCapabilityEnvelope",
    ]
    present = [fragment for fragment in forbidden_fragments if fragment in proto]
    if present:
        raise RuntimeError("execution protocol exposes product API " + ", ".join(present))


def verify_security_identity(security_identity) -> None:
    if (
        security_identity.get("local", {}).get("controlIssuer")
        != "urn:monomer:control:local"
    ):
        raise RuntimeError("local Control issuer mismatch")
    if (
        security_identity.get("local", {}).get("executionAudience")
        != "urn:monomer:execution:local"
    ):
        raise RuntimeError("local Execution audience mismatch")
    if (
        security_identity.get("public", {}).get("activation")
        != "REQUIRES_PUBLIC_NAMESPACE_APPROVAL"
    ):
        raise RuntimeError("public namespace approval gate is not locked")
    if security_identity["secrets"]["plaintextPersistenceAllowed"]:
        raise RuntimeError("plaintext credential persistence is not allowed")
    if security_identity["secrets"]["plaintextLoggingAllowed"]:
        raise RuntimeError("plaintext credential logging is not allowed")
    if "workerIdentity" in security_identity:
        raise RuntimeError("worker identity surface was retired")
    if "attemptCapability" in security_identity:
        raise RuntimeError("attempt capability surface was retired")


def metricflow_artifact_set_identity(contract) -> dict:
    return {
        "schemaVersion": contract["schemaVersion"],
        "version": contract["version"],
        "source": contract["source"],
        "artifacts": [
            {
                "coordinate": artifact["coordinate"],
                "sha256": artifact["sha256"],
            }
            for artifact in contract["artifacts"]
        ],
    }


def verify_metricflow_distribution(contract: dict) -> None:
    distribution = contract["distribution"]
    if distribution["productModuleAllowlist"] != METRICFLOW_PRODUCT_MODULE_ALLOWLIST:
        raise RuntimeError("MetricFlow distribution module allowlist drift")
    if distribution["excludedModules"] != METRICFLOW_EXCLUDED_MODULES:
        raise RuntimeError("MetricFlow distribution excluded modules drift")
    version = contract["version"]
    release_base = f"{contract['source']['uri']}/releases/download/v{version}/metricflow-monomer-product-{version}"
    if distribution["mavenRepositoryBundle"]["uri"] != release_base + ".zip":
        raise RuntimeError("MetricFlow bundle URI does not match the pinned version")
    for evidence, suffix in (("provenance", ".provenance.json"), ("sbom", ".sbom.cyclonedx.json")):
        if distribution["releaseEvidence"][evidence]["uri"] != release_base + suffix:
            raise RuntimeError(f"MetricFlow {evidence} URI does not match the pinned version")


def verify_metricflow_artifact_set(repository_root: Path) -> dict:
    contract_path = repository_root / "release/contracts/metricflow-artifact-set.json"
    schema_path = repository_root / "release/schemas/metricflow-artifact-set.schema.json"
    validate_file(contract_path, schema_path)
    contract = load_json(contract_path)
    actual_artifacts = {
        artifact["coordinate"]: artifact["sha256"]
        for artifact in contract["artifacts"]
    }
    if len(actual_artifacts) != len(contract["artifacts"]):
        raise RuntimeError("MetricFlow artifact set has duplicate coordinates")
    version = contract["version"]
    expected_coordinates = [f"cc.monomer.metricflow:{module}:{version}" for module in METRICFLOW_PRODUCT_MODULE_ALLOWLIST]
    if list(actual_artifacts) != expected_coordinates:
        raise RuntimeError("MetricFlow artifact coordinates do not match the pinned version and module allowlist")
    for artifact, module in zip(contract["artifacts"], METRICFLOW_PRODUCT_MODULE_ALLOWLIST):
        expected_path = f"cc/monomer/metricflow/{module}/{version}/{module}-{version}.jar"
        if artifact["relativePath"] != expected_path:
            raise RuntimeError("MetricFlow artifact path does not match its coordinate")
    actual_modules = [artifact["coordinate"].split(":")[1] for artifact in contract["artifacts"]]
    if actual_modules != METRICFLOW_PRODUCT_MODULE_ALLOWLIST:
        raise RuntimeError("MetricFlow artifact module order or allowlist drift")
    verify_metricflow_distribution(contract)
    if [artifact["coordinate"] for artifact in contract["artifacts"]] != sorted(actual_artifacts):
        raise RuntimeError("MetricFlow artifact set must be in canonical coordinate order")
    actual_digest = sha256(metricflow_artifact_set_identity(contract))
    if contract["artifactSetDigest"] != actual_digest:
        raise RuntimeError("MetricFlow artifact set identity digest mismatch")
    return contract


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", required=True)
    arguments = parser.parse_args()

    repository_root = Path(arguments.repository_root).resolve()
    module_map = load_json(repository_root / "contracts/architecture/module-ownership.json")
    security_identity = load_json(repository_root / "contracts/identity/security-identifier-map.json")

    verify_module_graph(module_map)
    verify_protocol(repository_root)
    verify_security_identity(security_identity)
    verify_metricflow_artifact_set(repository_root)

    validate_file(
        repository_root / "testkit/environment/environment.contract.json",
        repository_root / "testkit/environment/environment-contract.schema.json",
    )

    for path in sorted((repository_root / "release/schemas").glob("*.json")):
        load_json(path)
    for path in sorted((repository_root / "contracts").rglob("*.json")):
        load_json(path)

    proto_digest = "sha256:" + hashlib.sha256(
        (repository_root / "protos/execution_plane.proto").read_bytes()
    ).hexdigest()
    print("PASS repositorySnapshot=self-contained")
    print("PASS modules=" + str(len(module_map["modules"])))
    print("PASS executionProto=" + proto_digest)
    print("PASS releaseSchemas=" + str(len(list((repository_root / "release/schemas").glob("*.json")))))


if __name__ == "__main__":
    main()
