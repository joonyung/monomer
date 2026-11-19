#!/usr/bin/env python3
"""Verify local sample build inputs and run defaults in a disposable source copy."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
KEY = 'MONOMER_EXECUTION_SAMPLE_DATA_ROOT'
ENVIRONMENT = {key: value for key, value in os.environ.items() if key != KEY}


def main():
    contract = json.loads((ROOT / 'contracts/architecture/module-ownership.json').read_text())
    assert set(contract['executionBuild']['allowedCrossBuildInputs']) == {
        'protos/execution_plane.proto', 'testkit/data/duckdb/acme-cloud/GenerateDataset.java',
        'testkit/data/duckdb/acme-cloud/generate.sql',
        'testkit/data/duckdb/acme-cloud/versions/1/generate.sql',
        'testkit/data/duckdb/acme-cloud/versions/2/generate.sql',
    }
    with tempfile.TemporaryDirectory(prefix='monomer-local-sample-') as directory:
        root = Path(directory)
        service = root / 'execution-service'
        shutil.copytree(ROOT / 'execution-service', service,
                        ignore=shutil.ignore_patterns('build', '.gradle', '.monomer', '__pycache__'))
        for relative in contract['executionBuild']['allowedCrossBuildInputs']:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        native = service / 'driver-duckdb/fixture-jdbc.jar'
        shutil.copy2(ROOT / 'execution-service/application/build/install/application/lib/duckdb_jdbc-1.5.4.0.jar', native)
        driver_build = service / 'driver-duckdb/build.gradle.kts'
        source = driver_build.read_text()
        assert source.count('implementation("org.duckdb:duckdb_jdbc:1.5.4.0")') == 1
        driver_build.write_text(source.replace('implementation("org.duckdb:duckdb_jdbc:1.5.4.0")', 'implementation(files("fixture-jdbc.jar"))'))

        def gradle(*arguments, environment=ENVIRONMENT):
            result = subprocess.run(['./gradlew', '--console=plain', *arguments], cwd=service,
                                    env=environment, capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError(result.stdout + result.stderr)
            return result.stdout

        database = service / 'application/build/sample-data/acme-cloud/3/acme_cloud.duckdb'
        version_two_database = service / 'application/build/sample-data/acme-cloud/2/acme_cloud.duckdb'
        legacy_database = service / 'application/build/sample-data/acme-cloud/1/acme_cloud.duckdb'
        assert not database.exists() and not legacy_database.exists() and not version_two_database.exists()
        output = gradle(':application:prepareLocalSampleData')
        assert ':application:prepareLocalSampleData UP-TO-DATE' not in output and database.is_file() and legacy_database.is_file() and version_two_database.is_file()
        assert database.stat().st_mode & 0o222 == 0 and database.stat().st_size <= 50 * 1024 * 1024
        assert legacy_database.stat().st_mode & 0o222 == 0 and legacy_database.stat().st_size <= 50 * 1024 * 1024
        unchanged = database.stat().st_mtime_ns, database.stat().st_ino
        legacy_unchanged = legacy_database.stat().st_mtime_ns, legacy_database.stat().st_ino
        version_two_unchanged = version_two_database.stat().st_mtime_ns, version_two_database.stat().st_ino
        other_version = database.parent.parent / '4/preserved-fixture.duckdb'
        other_version.parent.mkdir()
        other_version.write_bytes(b'preserved version fixture')
        output = gradle(':application:prepareLocalSampleData')
        assert ':application:prepareLocalSampleData UP-TO-DATE' in output
        assert (database.stat().st_mtime_ns, database.stat().st_ino) == unchanged
        assert (legacy_database.stat().st_mtime_ns, legacy_database.stat().st_ino) == legacy_unchanged
        assert (version_two_database.stat().st_mtime_ns, version_two_database.stat().st_ino) == version_two_unchanged
        assert version_two_database.stat().st_mode & 0o222 == 0 and version_two_database.stat().st_size <= 50 * 1024 * 1024
        print('PASS: three fresh read-only datasets ≤50 MiB; unchanged inputs reuse exact files', flush=True)

        for relative in ('generate.sql', 'GenerateDataset.java'):
            target = root / 'testkit/data/duckdb/acme-cloud' / relative
            target.write_text(target.read_text() + '\n// Changed generator input fixture\n' if relative.endswith('.java')
                              else target.read_text() + '\n-- Changed generator input fixture\n')
            previous = database.stat().st_mtime_ns, database.stat().st_ino
            output = gradle(':application:prepareLocalSampleData')
            assert ':application:prepareLocalSampleData UP-TO-DATE' not in output
            assert (database.stat().st_mtime_ns, database.stat().st_ino) != previous
            if relative == 'generate.sql':
                assert (legacy_database.stat().st_mtime_ns, legacy_database.stat().st_ino) == legacy_unchanged
        previous_current = database.stat().st_mtime_ns, database.stat().st_ino
        previous_two = version_two_database.stat().st_mtime_ns, version_two_database.stat().st_ino
        version_two_sql = root / 'testkit/data/duckdb/acme-cloud/versions/2/generate.sql'
        version_two_sql.write_text(version_two_sql.read_text() + '\n-- Changed version 2 SQL fixture\n')
        output = gradle(':application:prepareLocalSampleData')
        assert ':application:prepareVersionTwoLocalSampleData UP-TO-DATE' not in output
        assert ':application:prepareLocalSampleData UP-TO-DATE' in output
        assert (database.stat().st_mtime_ns, database.stat().st_ino) == previous_current
        assert (version_two_database.stat().st_mtime_ns, version_two_database.stat().st_ino) != previous_two
        previous_legacy = legacy_database.stat().st_mtime_ns, legacy_database.stat().st_ino
        legacy_sql = root / 'testkit/data/duckdb/acme-cloud/versions/1/generate.sql'
        legacy_sql.write_text(legacy_sql.read_text() + '\n-- Changed legacy SQL fixture\n')
        output = gradle(':application:prepareLocalSampleData')
        assert ':application:prepareLegacyLocalSampleData UP-TO-DATE' not in output
        assert ':application:prepareLocalSampleData UP-TO-DATE' in output
        assert (database.stat().st_mtime_ns, database.stat().st_ino) == previous_current
        assert (legacy_database.stat().st_mtime_ns, legacy_database.stat().st_ino) != previous_legacy
        previous = database.stat().st_mtime_ns, database.stat().st_ino
        # Change only a resource in the disposable JDBC JAR; class/native bytes and version stay pinned.
        with zipfile.ZipFile(native, 'a') as archive:
            archive.writestr('monomer-generation-input-probe.txt', 'Changed JDBC input fixture')
        output = gradle(':application:prepareLocalSampleData')
        assert ':application:prepareLocalSampleData UP-TO-DATE' not in output
        assert (database.stat().st_mtime_ns, database.stat().st_ino) != previous
        assert other_version.read_bytes() == b'preserved version fixture'
        assert database.stat().st_mode & 0o222 == 0
        assert legacy_database.stat().st_mode & 0o222 == 0
        print('PASS: version SQL changes refresh only that dataset; Java/JDBC inputs refresh atomically; other versions preserved', flush=True)

        init = root / 'inspect-run.gradle.kts'
        init.write_text('''gradle.projectsEvaluated {
    val run = gradle.rootProject.project(":application").tasks.named("run").get() as org.gradle.api.tasks.JavaExec
    println("LOCAL_SAMPLE_ROOT=" + run.environment["MONOMER_EXECUTION_SAMPLE_DATA_ROOT"])
}
''')
        output = gradle(':application:run', '--dry-run', '--init-script', str(init))
        selected = next(line.split('=', 1)[1] for line in output.splitlines() if line.startswith('LOCAL_SAMPLE_ROOT='))
        assert Path(selected).resolve() == database.parents[2].resolve()
        assert ':application:prepareLocalSampleData SKIPPED' in output
        previous = database.stat().st_mtime_ns, database.stat().st_ino
        previous_legacy = legacy_database.stat().st_mtime_ns, legacy_database.stat().st_ino
        override = root / 'operator-sample-root'
        override.mkdir()
        sentinel = override / 'operator-owned-fixture'
        sentinel.write_bytes(b'preserved operator content')
        for value in (str(override), '', 'relative-operator-root'):
            output = gradle(':application:run', '--dry-run', '--init-script', str(init), environment=dict(ENVIRONMENT, **{KEY: value}))
            assert 'LOCAL_SAMPLE_ROOT=' + value + '\n' in output
            assert ':application:prepareLocalSampleData' not in output
            assert ':application:prepareLegacyLocalSampleData' not in output
            assert ':application:prepareVersionTwoLocalSampleData' not in output
        assert sentinel.read_bytes() == b'preserved operator content'
        assert (database.stat().st_mtime_ns, database.stat().st_ino) == previous
        assert (legacy_database.stat().st_mtime_ns, legacy_database.stat().st_ino) == previous_legacy
        print('PASS: local run selects generated root; explicit/empty/relative overrides preserved without generation; operator content untouched', flush=True)
        gradle(':application:test', '--tests', '*LocalSampleDataStartupTest')
        print('PASS: actual production read-only DuckDB driver opens generated Acme views after refresh', flush=True)


if __name__ == '__main__':
    main()
