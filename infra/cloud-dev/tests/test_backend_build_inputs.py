from pathlib import Path
import re
import shlex
import unittest


ROOT = Path(__file__).resolve().parents[3]


def local_copies(service, *, build_only):
    dockerfile = (ROOT / f'{service}-service/Dockerfile').read_text()
    instructions = dockerfile.replace('\\\n', ' ').splitlines()
    stages = 0
    sources = []
    for instruction in instructions:
        fields = shlex.split(instruction)
        if not fields:
            continue
        if fields[0] == 'FROM':
            stages += 1
        if build_only and stages > 1:
            break
        if fields[0] == 'COPY' and not any(field.startswith('--from=') for field in fields):
            sources.extend(field.rstrip('/') for field in fields[1:-1] if not field.startswith('--'))
    return sources


def covers(sources, path):
    return any(source == '.' or path == source or path.startswith(source + '/') for source in sources)


def gradle_runtime_resources(service):
    resources = set()
    for build_file in (ROOT / f'{service}-service').rglob('build.gradle.kts'):
        source = build_file.read_text()
        for match in re.finditer(r'from\(rootProject\.file\("(\.\./[^"]+)"\)\)\s*\{', source):
            relative = match.group(1)[3:]
            block = source[match.end():].split('}', 1)[0]
            includes = re.search(r'include\((.*?)\)', block, re.DOTALL)
            if includes:
                resources.update(f'{relative}/{name}' for name in re.findall(r'"([^"]+)"', includes.group(1)))
            else:
                resources.add(relative)
    return resources


class BackendBuildInputsTests(unittest.TestCase):
    def test_build_context_covers_compiled_sources_and_gradle_configuration(self):
        for service in ('control', 'execution'):
            with self.subTest(service=service):
                sources = local_copies(service, build_only=True)
                service_root = ROOT / f'{service}-service'
                required = {
                    path.relative_to(ROOT).as_posix()
                    for path in service_root.rglob('*')
                    if path.is_file() and 'build' not in path.relative_to(service_root).parts
                    and (
                        '/src/main/' in path.as_posix()
                        or path.name.endswith('.gradle.kts')
                        or path.name in {'gradle.properties', 'gradlew', 'gradle.lockfile'}
                        or '/gradle/wrapper/' in path.as_posix()
                    )
                }
                required.add('protos/execution_plane.proto')
                required.update(gradle_runtime_resources(service))
                if service == 'control':
                    required.update({
                        'contracts/architecture/module-ownership.json',
                        'release/contracts/metricflow-artifact-set.json',
                    })
                self.assertTrue(required)
                for path in sorted(required):
                    self.assertTrue((ROOT / path).is_file(), path)
                    self.assertTrue(covers(sources, path), f'{service} omits runtime/build input {path}')

    def test_frontend_documentation_and_infrastructure_changes_do_not_invalidate_compilation(self):
        unrelated = (
            'web-app/src/main.tsx',
            'web-app/public/monomer.svg',
            'public-site/src/pages/index.astro',
            'docs/infra/cloud-dev-readiness.md',
            'scripts/bootstrap-metricflow-bundle.py',
            'infra/cloud-dev/deploy.py',
            'infra/cloud-dev/database-bootstrap/us-east-1-bundle.pem',
        )
        for service in ('control', 'execution'):
            with self.subTest(service=service):
                sources = local_copies(service, build_only=True)
                for path in unrelated:
                    self.assertFalse(covers(sources, path), f'{service} compiles again after {path} changes')
                sibling = 'execution' if service == 'control' else 'control'
                self.assertFalse(covers(sources, f'{sibling}-service/build.gradle.kts'))

    def test_control_ca_is_still_a_runtime_image_input(self):
        self.assertTrue(covers(local_copies('control', build_only=False),
                               'infra/cloud-dev/database-bootstrap/us-east-1-bundle.pem'))


if __name__ == '__main__':
    unittest.main()
