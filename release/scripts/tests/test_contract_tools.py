import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


RELEASE_SCRIPTS = Path(__file__).resolve().parents[1]
REPOSITORY_SCRIPTS = Path(__file__).resolve().parents[3] / "scripts"
sys.path.insert(0, str(RELEASE_SCRIPTS))
sys.path.insert(0, str(REPOSITORY_SCRIPTS))

from canonical_json import canonical_bytes, load_json, sha256
from schema_validator import ValidationError, validate
from verify_contracts import (
    metricflow_artifact_set_identity,
    verify_metricflow_artifact_set,
    verify_protocol,
    verify_security_identity,
)


class CanonicalJsonTest(unittest.TestCase):
    def test_canonical_bytes_are_key_order_independent(self):
        left = {"z": 1, "a": ["한글", True, None]}
        right = {"a": ["한글", True, None], "z": 1}

        self.assertEqual(canonical_bytes(left), canonical_bytes(right))
        self.assertEqual(sha256(left), sha256(right))

    def test_duplicate_keys_and_floats_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            duplicate = Path(directory) / "duplicate.json"
            floating_point = Path(directory) / "float.json"
            duplicate.write_text('{"key":1,"key":2}')
            floating_point.write_text('{"value":1.5}')

            with self.assertRaises(ValueError):
                load_json(duplicate)
            with self.assertRaises(ValueError):
                load_json(floating_point)

    def test_security_identity_keeps_runtime_and_activation_boundaries(self):
        identity = load_json(
            Path(__file__).resolve().parents[3]
            / "contracts/identity/security-identifier-map.json"
        )
        verify_security_identity(identity)

        mutations = (
            ("local", "controlIssuer", "urn:changed"),
            ("local", "executionAudience", "urn:changed"),
            ("public", "activation", "ACTIVE"),
        )
        for section, field, value in mutations:
            with self.subTest(field=field):
                changed = json.loads(json.dumps(identity))
                changed[section][field] = value
                with self.assertRaises(RuntimeError):
                    verify_security_identity(changed)

    def test_execution_protocol_verifier_requires_run_without_preview_or_attempt_terms(self):
        repository_root = Path(__file__).resolve().parents[3]
        verify_protocol(repository_root)

        with tempfile.TemporaryDirectory() as directory:
            temporary_root = Path(directory)
            (temporary_root / "protos").mkdir()
            proto_path = temporary_root / "protos/execution_plane.proto"
            proto_path.write_text(
                (repository_root / "protos/execution_plane.proto")
                .read_text()
                .replace("rpc Run(stream ControlMessage)", "rpc RunAttempt(stream ControlMessage)")
            )
            with self.assertRaisesRegex(RuntimeError, r"rpc Run\(stream ControlMessage\)"):
                verify_protocol(temporary_root)


class SchemaValidatorTest(unittest.TestCase):
    def test_ref_siblings_narrow_the_referenced_schema(self):
        schema = {
            "$defs": {
                "base": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": ["kind"],
                    "properties": {"kind": {"type": "string"}},
                }
            },
            "$ref": "#/$defs/base",
            "properties": {"kind": {"const": "LOCKED"}},
        }

        validate({"kind": "LOCKED"}, schema, Path("schema.json"), schema)
        with self.assertRaises(ValidationError):
            validate({"kind": "OPEN"}, schema, Path("schema.json"), schema)

    def test_one_of_requires_exactly_one_match(self):
        schema = {
            "oneOf": [
                {"type": "string", "pattern": "^a"},
                {"type": "string", "pattern": "z$"},
            ]
        }

        validate("alpha", schema, Path("schema.json"), schema)
        with self.assertRaises(ValidationError):
            validate("abz", schema, Path("schema.json"), schema)
        with self.assertRaises(ValidationError):
            validate("middle", schema, Path("schema.json"), schema)

    def test_formats_reject_date_only_and_whitespace_uri(self):
        date_schema = {"type": "string", "format": "date-time"}
        uri_schema = {"type": "string", "format": "uri"}

        validate(
            "2026-09-15T15:00:00Z",
            date_schema,
            Path("schema.json"),
            date_schema,
        )
        validate(
            "https://monomer.cc/path",
            uri_schema,
            Path("schema.json"),
            uri_schema,
        )
        with self.assertRaises(ValidationError):
            validate(
                "2026-09-15",
                date_schema,
                Path("schema.json"),
                date_schema,
            )
        with self.assertRaises(ValidationError):
            validate(
                "https://monomer.cc/invalid path",
                uri_schema,
                Path("schema.json"),
                uri_schema,
            )

    def test_boolean_is_not_an_integer(self):
        schema = {"type": "integer"}

        with self.assertRaises(ValidationError):
            validate(True, schema, Path("schema.json"), schema)

    def test_metricflow_artifact_set_contract_has_exact_canonical_identity(self):
        repository_root = Path(__file__).resolve().parents[3]

        artifact_set = verify_metricflow_artifact_set(repository_root)

        contract = load_json(repository_root / "release/contracts/metricflow-artifact-set.json")
        self.assertEqual(contract, artifact_set)
        self.assertEqual(9, len(artifact_set["artifacts"]))
        self.assertEqual(sha256(metricflow_artifact_set_identity(contract)), artifact_set["artifactSetDigest"])

    def test_metricflow_release_version_has_one_pin(self):
        repository_root = Path(__file__).resolve().parents[3]
        contract = load_json(repository_root / "release/contracts/metricflow-artifact-set.json")
        major, minor, patch = contract["version"].split(".")
        next_version = f"{major}.{minor}.{int(patch) + 1}"
        updated = json.loads(json.dumps(contract).replace(contract["version"], next_version))
        updated["artifactSetDigest"] = sha256(metricflow_artifact_set_identity(updated))
        with tempfile.TemporaryDirectory() as directory:
            temporary_root = Path(directory)
            (temporary_root / "release/contracts").mkdir(parents=True)
            shutil.copytree(repository_root / "release/schemas", temporary_root / "release/schemas")
            contract_path = temporary_root / "release/contracts/metricflow-artifact-set.json"
            contract_path.write_text(json.dumps(updated))
            self.assertEqual(next_version, verify_metricflow_artifact_set(temporary_root)["version"])
            mutations = (
                ("coordinate", lambda value: value["artifacts"][0].update(coordinate=contract["artifacts"][0]["coordinate"])),
                ("path", lambda value: value["artifacts"][0].update(relativePath=contract["artifacts"][0]["relativePath"])),
                ("bundle URI", lambda value: value["distribution"]["mavenRepositoryBundle"].update(uri=contract["distribution"]["mavenRepositoryBundle"]["uri"])),
                ("evidence URI", lambda value: value["distribution"]["releaseEvidence"]["provenance"].update(uri=contract["distribution"]["releaseEvidence"]["provenance"]["uri"])),
                ("digest", lambda value: value.update(artifactSetDigest="sha256:" + "0" * 64)),
            )
            for label, mutate in mutations:
                with self.subTest(field=label):
                    changed = json.loads(json.dumps(updated))
                    mutate(changed)
                    contract_path.write_text(json.dumps(changed))
                    with self.assertRaises(RuntimeError):
                        verify_metricflow_artifact_set(temporary_root)

    def test_metricflow_artifact_identity_does_not_depend_on_distribution_location(self):
        repository_root = Path(__file__).resolve().parents[3]
        artifact_set = load_json(
            repository_root / "release/contracts/metricflow-artifact-set.json"
        )
        activated = json.loads(json.dumps(artifact_set))
        activated["distribution"] = {
            "status": "ACTIVATED",
            "mavenRepositoryBundle": {
                "uri": "https://example.test/metricflow.zip",
                "archiveSha256": "sha256:" + "a" * 64,
            },
        }

        self.assertEqual(
            metricflow_artifact_set_identity(artifact_set),
            metricflow_artifact_set_identity(activated),
        )

if __name__ == "__main__":
    unittest.main()
