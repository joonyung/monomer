from collections import Counter
from fnmatch import fnmatchcase
import importlib.util
import io
import json
from pathlib import Path
import re
import shlex
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

import boto3
from botocore.exceptions import ClientError
from botocore.response import StreamingBody
from botocore.stub import Stubber

spec = importlib.util.spec_from_file_location('static_release', Path(__file__).parents[1] / 'static_release.py')
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
s.configure('dev')
SOURCE = '1' * 40
REAL_TOOLCHAIN_CHECK = s.deploy.cdk_toolchain_check
DEMO = b'\x00\x00\x00\x18ftypmp42' + b'verified demo bytes'
URI = s.deploy.IMAGE_PREFIX + '@sha256:' + 'a' * 64


class StaticBuildInputTests(unittest.TestCase):
    @staticmethod
    def build_sources(repository, stage):
        sources = []
        in_stage = False
        dockerfile = (repository / 'web-app/Dockerfile').read_text().replace('\\\n', ' ')
        for instruction in dockerfile.splitlines():
            fields = shlex.split(instruction)
            if not fields:
                continue
            if fields[0] == 'FROM':
                in_stage = fields[-1] == stage
            if in_stage and fields[0] == 'COPY' and not any(field.startswith('--from=') for field in fields):
                sources.extend(field.rstrip('/') for field in fields[1:-1] if not field.startswith('--'))
        return sources

    def test_public_contract_publications_are_available_in_image_build(self):
        repository = Path(__file__).resolve().parents[3]
        publications = re.findall(r'source: "([^"]+)"',
                                  (repository / 'public-site/scripts/sync-contracts.mjs').read_text())
        sources = self.build_sources(repository, 'public-site-build')
        self.assertTrue(publications)
        for publication in publications:
            with self.subTest(publication=publication):
                self.assertTrue((repository / publication).is_file())
                self.assertTrue(any(fnmatchcase(publication, source) or publication.startswith(source + '/')
                                    for source in sources), f'public-site build omits owner contract {publication}')

    def test_web_external_imports_are_available_in_image_build(self):
        repository = Path(__file__).resolve().parents[3]
        web_root = repository / 'web-app'
        sources = self.build_sources(repository, 'build')
        external_imports = []
        for module in (web_root / 'src').rglob('*'):
            if module.suffix not in {'.ts', '.tsx'}:
                continue
            for imported in re.findall(r'''(?:from\s*|import\s*)["'](\.[^"']+)["']''', module.read_text()):
                imported_file = (module.parent / imported).resolve()
                if not imported_file.is_relative_to(web_root):
                    external_imports.append(imported_file.relative_to(repository).as_posix())
        self.assertTrue(external_imports)
        for imported in external_imports:
            with self.subTest(imported=imported):
                self.assertTrue((repository / imported).is_file())
                self.assertTrue(any(fnmatchcase(imported, source) or imported.startswith(source + '/')
                                    for source in sources), f'Web build omits external import {imported}')


class StaticReleaseTests(unittest.TestCase):
    def setUp(self):
        s.configure('dev')
        toolchain = patch.object(s.deploy, 'cdk_toolchain_check')
        toolchain.start()
        self.addCleanup(toolchain.stop)
        tag = patch.object(s.deploy, 'update_deployment_tag', return_value={'status': 'updated'})
        tag.start()
        self.addCleanup(tag.stop)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.demo = self.root / 'demo.mp4'
        self.demo.write_bytes(DEMO)
        self.objects = {}
        self.puts = []
        self.gets = []
        self.streams = []
        self.s3 = Mock(get_object=Mock(side_effect=self.get_object), put_object=Mock(side_effect=self.put_object))
        client = patch.object(s, 'S3_CLIENT', self.s3)
        client.start()
        self.addCleanup(client.stop)

    def bundle(self, name='one', files=None, provenance=None):
        directory = self.root / name
        directory.mkdir()
        files = files or {'app/index.html': b'<head><!-- monomer:page-metadata:start --><title>Monomer</title><!-- monomer:page-metadata:end --></head>', 'public/index.html': b'public',
                          'app/assets/old.js': b'old', 'public/_astro/old.css': b'old',
                          'public/page/index.html': b'page', 'app/logo.svg': b'old logo'}
        files = files | {s.DEMO_PATH: DEMO, 'app/404.html': b'<!doctype html><h1>Not found</h1>',
                         'public/404.html': b'<!doctype html><h1>Not found</h1>', 'app/assets/chart-widget-worker-test.js': b'worker',
                         'app/assets/consumer.js': b'chart-widget-worker-test.js'}
        entries = []
        for path, data in sorted(files.items()):
            target = directory / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            entries.append(s.metadata(path, data))
        manifest = {'version': 1, 'provenance': provenance or {'kind': 'built-source', 'sourceCommit': SOURCE,
                    'buildInputs': s.INPUTS}, 'files': entries}
        (directory / 'manifest.json').write_bytes(s.canonical(manifest))
        return directory, manifest, s.validate(manifest)

    def get_object(self, **arguments):
        self.assertEqual(set(arguments), {'Bucket', 'Key'})
        self.assertEqual(arguments['Bucket'], s.BUCKET)
        key = arguments['Key']
        self.gets.append(key)
        data, metadata = self.objects[key]
        stream = io.BytesIO(data)
        self.streams.append(stream)
        return metadata | {'Body': stream}

    def put_object(self, **arguments):
        self.assertEqual(set(arguments), {'Bucket', 'Key', 'Body', 'IfNoneMatch',
                                         'ContentType', 'CacheControl', 'Metadata'})
        self.assertEqual(arguments['Bucket'], s.BUCKET)
        self.assertEqual(arguments['IfNoneMatch'], '*')
        key = arguments['Key']
        self.puts.append(key)
        if key in self.objects:
            raise ClientError({'Error': {'Code': 'PreconditionFailed'},
                               'ResponseMetadata': {'HTTPStatusCode': 412}}, 'PutObject')
        data = arguments['Body']
        self.objects[key] = (data, {'ContentLength': len(data),
            'ContentType': arguments['ContentType'], 'CacheControl': arguments['CacheControl'],
            'Metadata': arguments['Metadata']})
        return {}

    def publish(self, directory):
        with patch.object(s.deploy, 'account_check'), patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(s, 'S3_CLIENT', self.s3):
            return s.publish(directory)

    def test_required_environment_and_preserved_v1_environment_fences(self):
        with patch.object(s.deploy, 'run') as run, self.assertRaises(SystemExit):
            s.main(['check'])
        run.assert_not_called()
        self.assertEqual(s.INPUTS['VITE_MONOMER_CONTROL_API_URL'], 'https://api.dev.monomer.cc')
        _, built, built_release = self.bundle('dev-built', provenance={
            'kind': 'built-source', 'sourceCommit': SOURCE, 'buildInputs': {
                'APP_ENV': 'production', 'VITE_MONOMER_CONTROL_API_URL': 'https://api.dev.monomer.cc',
                'VITE_GOOGLE_OAUTH_ENABLED': 'true', 'VITE_LOCAL_LOGIN_ENABLED': 'false',
                'MONOMER_PUBLIC_SITE_ORIGIN': 'https://dev.monomer.cc'}})
        _, imported, imported_release = self.bundle('dev-imported', provenance={
            'kind': 'imported-image', 'imageUri': URI, 'sourceCommit': None, 'buildInputs': None})
        s.configure('prod', 'operator-prod')
        self.assertEqual(s.INPUTS['VITE_MONOMER_CONTROL_API_URL'], 'https://api.monomer.cc')
        self.assertEqual(s.INPUTS['MONOMER_PUBLIC_SITE_ORIGIN'], 'https://monomer.cc')
        self.assertEqual(s.STACK, 'MonomerProdStaticHosting')
        self.assertEqual(s.BUCKET, 'monomer-prod-static-444455556666-us-east-1')
        for manifest in (built, imported):
            with self.assertRaises(RuntimeError):
                s.validate(manifest)
        _, prod, _ = self.bundle('prod')
        s.configure('dev')
        with self.assertRaises(RuntimeError):
            s.validate(prod)
        self.assertEqual(s.validate(built), built_release)
        self.assertEqual(s.validate(imported), imported_release)

    def test_prod_synth_passes_environment_and_profile(self):
        s.configure('prod', 'operator-prod')
        template_path = self.root / 'cdk.out' / (s.STACK + '.template.json')
        template_path.parent.mkdir()
        def run(command, **kwargs):
            if command[0] == 'npx':
                self.assertEqual(command, ['npx', '--no-install', 'cdk', 'synth', 'MonomerProdStaticHosting',
                    '-c', 'environment=prod', '--profile', 'operator-prod', '--exclusively', '--json'])
                template_path.write_text('{"Resources": {}}')
        with patch.object(s.deploy, 'INFRA', self.root), patch.object(s.deploy, 'run', side_effect=run):
            self.assertEqual(s.synth('a' * 64), {'Resources': {}})

    def test_upload_order_and_idempotent_integrity(self):
        directory, manifest, release = self.bundle()
        self.publish(directory)
        self.assertTrue(all('/releases/' not in key for key in self.puts[:4]))
        self.assertIn('public/_astro/old.css', self.puts[:4])
        self.assertEqual(self.puts[-1], f'manifests/{release}.json')
        self.assertFalse(self.publish(directory)['activated'])
        self.assertEqual(len(self.objects), len(manifest['files']) + 1)
        self.assertTrue(all(stream.closed for stream in self.streams))

    def test_sdk_client_uses_selected_profile_region_and_reuses_connections(self):
        with patch.object(boto3, 'Session') as session:
            s.configure('prod', 'operator-prod')
            self.s3.close.assert_called_once()
            s.initialize_s3()
            s.initialize_s3()
            session.assert_called_once_with(profile_name='operator-prod', region_name=s.deploy.REGION)
            session.return_value.client.assert_called_once()
            arguments = session.return_value.client.call_args
            self.assertEqual(arguments.args, ('s3',))
            self.assertEqual(arguments.kwargs['config'].max_pool_connections, 8)
            client = session.return_value.client.return_value
            s.configure('dev', 'example-dev-profile')
            client.close.assert_called_once()
            s.initialize_s3()
            self.assertEqual(session.call_count, 2)
            self.assertEqual(session.call_args.kwargs['profile_name'], 'example-dev-profile')

    def test_missing_sdk_dependency_stops_before_network_with_install_guidance(self):
        original_import = __import__

        def import_without_sdk(name, *args, **kwargs):
            if name == 'boto3':
                raise ModuleNotFoundError('boto3 unavailable')
            return original_import(name, *args, **kwargs)

        with patch.object(s, 'S3_CLIENT', None), patch('builtins.__import__', side_effect=import_without_sdk), \
             self.assertRaisesRegex(RuntimeError, 'install infra/cloud-dev/requirements.txt'):
            s.initialize_s3()

    def test_sdk_conditional_put_and_download_integrity_for_new_and_existing_keys(self):
        data = b'verified bytes'
        path = self.root / 'object'
        path.write_bytes(data)
        entry = s.metadata('app/assets/object.js', data)
        client = boto3.Session(aws_access_key_id='test', aws_secret_access_key='test',
                               region_name=s.deploy.REGION).client('s3')
        self.addCleanup(client.close)
        put_arguments = {'Bucket': s.BUCKET, 'Key': entry['path'], 'Body': data, 'IfNoneMatch': '*',
                         'ContentType': entry['contentType'], 'CacheControl': entry['cacheControl'],
                         'Metadata': {'sha256': entry['sha256']}}
        for existing in (False, True):
            with self.subTest(existing=existing), Stubber(client) as stubber, patch.object(s, 'S3_CLIENT', client):
                if existing:
                    stubber.add_client_error('put_object', service_error_code='PreconditionFailed',
                                             http_status_code=412, expected_params=put_arguments)
                else:
                    stubber.add_response('put_object', {}, put_arguments)
                stream = io.BytesIO(data)
                stubber.add_response('get_object', {'Body': StreamingBody(stream, len(data)),
                    'ContentLength': len(data), 'ContentType': entry['contentType'],
                    'CacheControl': entry['cacheControl'], 'Metadata': {'sha256': entry['sha256']}},
                    {'Bucket': s.BUCKET, 'Key': entry['path']})
                s.put_immutable(entry['path'], path, entry)
                self.assertTrue(stream.closed)
                stubber.assert_no_pending_responses()

    def test_only_precondition_failure_permits_existing_object_reuse(self):
        directory, _, release = self.bundle()
        self.publish(directory)
        self.puts.clear()
        self.gets.clear()
        for code, status in (('AccessDenied', 403), ('ConditionalRequestConflict', 409),
                             ('InternalError', 500)):
            failure = ClientError({'Error': {'Code': code, 'Message': 'must not appear in phase logs'},
                                   'ResponseMetadata': {'HTTPStatusCode': status}}, 'PutObject')
            with self.subTest(code=code), patch.object(self.s3, 'put_object', side_effect=failure), \
                 patch('sys.stderr', new_callable=io.StringIO) as stderr, \
                 self.assertRaisesRegex(RuntimeError, 'S3 conditional PUT failed'):
                self.publish(directory)
            self.assertEqual(self.gets, [])
            self.assertNotIn(f'manifests/{release}.json', self.puts)
            events = [json.loads(line) for line in stderr.getvalue().splitlines() if line.startswith('{')]
            self.assertEqual(events[-1]['phase'], 'static.publish')
            self.assertEqual(events[-1]['status'], 'failed')
            self.assertNotIn('must not appear', stderr.getvalue())

    def test_download_body_closes_on_read_failure_and_integrity_rejection(self):
        stream = io.BytesIO(b'short')
        body = StreamingBody(stream, 100)
        with patch.object(self.s3, 'get_object', return_value={'Body': body}), \
             self.assertRaisesRegex(RuntimeError, 'S3 read failed'):
            s.remote_bytes('key')
        self.assertTrue(stream.closed)
        data = b'object'
        stream = io.BytesIO(data)
        entry = s.metadata('app/assets/object.js', data)
        with patch.object(self.s3, 'get_object', return_value={'Body': StreamingBody(stream, len(data)),
                'ContentLength': len(data), 'ContentType': 'wrong', 'CacheControl': entry['cacheControl'],
                'Metadata': {'sha256': entry['sha256']}}), self.assertRaisesRegex(RuntimeError, 'collision or drift'):
            s.verify_object(entry['path'], data, entry)
        self.assertTrue(stream.closed)

    def test_cli_publish_keeps_timing_on_stderr_and_closes_client(self):
        directory, _, release = self.bundle()
        with patch.object(s.deploy, 'account_check'), patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(boto3, 'Session') as session, \
             patch('sys.stdout', new_callable=io.StringIO) as stdout, \
             patch('sys.stderr', new_callable=io.StringIO) as stderr:
            session.return_value.client.return_value = self.s3
            s.main(['--environment', 'dev', '--profile', 'example-dev-profile', 'publish', str(directory), '--apply'])
        self.assertEqual(json.loads(stdout.getvalue()), {'release': release, 'uploaded': True, 'activated': False})
        events = [json.loads(line) for line in stderr.getvalue().splitlines() if line.startswith('{')]
        completed = [event['phase'] for event in events if event['status'] == 'succeeded']
        self.assertEqual(completed, ['static.local-integrity', 'static.publish-shared-assets',
                                    'static.publish-release-files', 'static.publish-manifest', 'static.publish'])
        self.assertTrue(all(event['elapsedSeconds'] >= 0 for event in events if event['status'] == 'succeeded'))
        self.assertTrue(all(event['at'].endswith('+00:00') for event in events))
        self.assertEqual(self.s3.close.call_count, 2)
        self.assertIsNone(s.S3_CLIENT)

    def test_publish_and_each_remote_pass_get_each_object_once(self):
        directory, manifest, release = self.bundle()
        expected = Counter([s.key_for(entry, release) for entry in manifest['files']]
                           + [f'manifests/{release}.json'])
        for _ in range(2):
            self.gets.clear()
            self.publish(directory)
            self.assertEqual(Counter(self.gets), expected)
        for _ in range(2):
            self.gets.clear()
            with patch.object(s, 'S3_CLIENT', self.s3):
                self.assertEqual(s.remote_manifest(release), manifest)
            self.assertEqual(Counter(self.gets), expected)

    def test_remote_corruption_rejected_from_one_response_before_activation(self):
        directory, _, release = self.bundle()
        self.publish(directory)
        for key in ('app/assets/old.js', f'manifests/{release}.json'):
            original_body, original_response = self.objects[key]
            corruptions = [
                (b'x' + original_body[1:], original_response),
                (original_body + b'x', original_response),
                (original_body, original_response | {'ContentLength': len(original_body) + 1}),
                (original_body, original_response | {'ContentType': 'wrong'}),
                (original_body, original_response | {'CacheControl': 'wrong'}),
                (original_body, original_response | {'Metadata': {'sha256': '0' * 64}}),
                (original_body, original_response | {'Metadata': original_response['Metadata'] | {'extra': 'wrong'}}),
            ]
            for body, response in corruptions:
                with self.subTest(key=key, response=response, length=len(body)):
                    self.objects[key] = body, response
                    self.gets.clear()
                    with patch.object(s.deploy, 'account_check'), \
                         patch.object(s.deploy, 'source_check', return_value=SOURCE), \
                         patch.object(s, 'S3_CLIENT', self.s3), \
                         patch.object(s.deploy, 'run') as run, self.assertRaises((RuntimeError, ValueError)):
                        s.activate(release)
                    run.assert_not_called()
                    self.assertEqual(self.gets.count(key), 1)
            self.objects[key] = original_body, original_response

    def test_response_checks_actual_length_even_with_matching_hash(self):
        body = b'body'
        entry = s.metadata('app/index.html', body) | {'size': 100}
        response = {'ContentLength': 100, 'ContentType': entry['contentType'],
                    'CacheControl': entry['cacheControl'], 'Metadata': {'sha256': entry['sha256']}}
        with self.assertRaisesRegex(RuntimeError, 'collision or drift'):
            s.verify_response('key', body, response, entry)

    def test_parallel_files_limit_and_progress_on_stderr(self):
        barrier = threading.Barrier(8)
        lock = threading.Lock()
        active = maximum = completed = 0

        def operation(entry):
            nonlocal active, maximum, completed
            with lock:
                active += 1
                maximum = max(maximum, active)
            barrier.wait(timeout=5)
            with lock:
                active -= 1
                completed += 1

        with patch('sys.stdout', new_callable=io.StringIO) as stdout, \
             patch('sys.stderr', new_callable=io.StringIO) as stderr:
            s.parallel_files('Verify files', list(range(24)), operation)
        self.assertEqual(maximum, 8)
        self.assertEqual(completed, 24)
        self.assertEqual(stdout.getvalue(), '')
        self.assertIn('Verify files: 0/24', stderr.getvalue())
        self.assertIn('Verify files: 24/24', stderr.getvalue())

    def test_failure_and_interrupt_stop_submission_and_wait_for_inflight(self):
        for error in (RuntimeError('worker failed'), KeyboardInterrupt()):
            with self.subTest(error=type(error).__name__):
                started = threading.Barrier(9)
                release_workers = threading.Event()
                completed = []

                def operation(entry):
                    started.wait(timeout=5)
                    release_workers.wait(timeout=5)
                    completed.append(entry)

                def interrupted_wait(*args, **kwargs):
                    started.wait(timeout=5)
                    release_workers.set()
                    raise error

                with patch.object(s, 'wait', side_effect=interrupted_wait), \
                     self.assertRaises(type(error)):
                    s.parallel_files('Interrupted files', list(range(100)), operation)
                self.assertEqual(sorted(completed), list(range(8)))

    def test_collision_stops_before_manifest_without_activation(self):
        directory, _, release = self.bundle()
        self.publish(directory)
        self.objects['app/assets/old.js'] = (b'wrong', self.objects['app/assets/old.js'][1])
        self.puts.clear()
        with patch.object(s, 'activate') as activate, self.assertRaisesRegex(RuntimeError, 'collision'):
            self.publish(directory)
        activate.assert_not_called()
        self.assertNotIn(f'manifests/{release}.json', self.puts)

    def test_worker_interrupt_prevents_later_group_and_manifest(self):
        directory, _, release = self.bundle()
        original_put = s.put_immutable

        def interrupted_put(key, path, entry):
            if key == 'app/assets/old.js':
                raise KeyboardInterrupt()
            original_put(key, path, entry)

        with patch.object(s, 'put_immutable', side_effect=interrupted_put), self.assertRaises(KeyboardInterrupt):
            self.publish(directory)
        self.assertTrue(all('/releases/' not in key for key in self.puts))
        self.assertNotIn(f'manifests/{release}.json', self.puts)

    def test_old_assets_and_nonhashed_files_survive_and_deleted_page_is_absent(self):
        directory, _, old = self.bundle()
        self.publish(directory)
        directory, _, new = self.bundle('two', {'app/index.html': b'<head><!-- monomer:page-metadata:start --><title>Monomer</title><!-- monomer:page-metadata:end --></head>new', 'public/index.html': b'new',
            'app/assets/new.js': b'new', 'public/_astro/new.css': b'new', 'app/logo.svg': b'new logo'})
        self.publish(directory)
        self.assertIn('app/assets/old.js', self.objects)
        self.assertIn('app/assets/new.js', self.objects)
        self.assertEqual(self.objects[f'app/releases/{old}/logo.svg'][0], b'old logo')
        self.assertEqual(self.objects[f'app/releases/{new}/logo.svg'][0], b'new logo')
        self.assertNotIn(f'public/releases/{new}/page/index.html', self.objects)
        with patch.object(s, 'S3_CLIENT', self.s3):
            s.remote_manifest(old)
            s.remote_manifest(new)

    def test_stale_manifest_and_changed_files_fail_before_upload(self):
        directory, manifest, _ = self.bundle()
        (directory / 'app/index.html').write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError, 'bytes changed'):
            self.publish(directory)
        self.assertEqual(self.puts, [])
        directory, _, _ = self.bundle('stale', provenance={'kind': 'built-source', 'sourceCommit': '2' * 40,
                                                         'buildInputs': s.INPUTS})
        with self.assertRaisesRegex(RuntimeError, 'Stale'):
            self.publish(directory)
        self.assertEqual(self.puts, [])

    def test_halfway_upload_keeps_previous_release_and_does_not_publish_manifest(self):
        directory, _, old = self.bundle()
        self.publish(directory)
        preserved = dict(self.objects)
        directory, _, new = self.bundle('failed', {'app/index.html': b'<head><!-- monomer:page-metadata:start --><title>Monomer</title><!-- monomer:page-metadata:end --></head>new', 'public/index.html': b'new',
            'app/assets/new.js': b'new asset', 'app/logo.svg': b'new logo'})
        def interrupted_put(**arguments):
            if arguments['Key'] == f'app/releases/{new}/logo.svg':
                raise RuntimeError('Upload interrupted')
            return self.put_object(**arguments)
        with patch.object(s.deploy, 'account_check'), patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(self.s3, 'put_object', side_effect=interrupted_put), patch.object(s, 'activate') as activate:
            with self.assertRaisesRegex(RuntimeError, 'Upload interrupted'):
                s.publish(directory)
            activate.assert_not_called()
        self.assertTrue(all(self.objects[key] == value for key, value in preserved.items()))
        self.assertIn('app/assets/new.js', self.objects)
        self.assertNotIn(f'manifests/{new}.json', self.objects)
        self.assertIn(f'manifests/{old}.json', self.objects)

    def test_required_404_documents_and_fixed_markdown_mime(self):
        directory, manifest, _ = self.bundle()
        self.assertEqual(s.metadata('public/llms.md', b'markdown')['contentType'], 'text/markdown')
        self.assertEqual(s.INPUTS['APP_ENV'], 'production')
        for missing in ('app/404.html', 'public/404.html'):
            invalid = manifest | {'files': [entry for entry in manifest['files'] if entry['path'] != missing]}
            with self.assertRaisesRegex(RuntimeError, '404 documents'):
                s.validate(invalid)
        self.assertNotEqual((directory / 'app/404.html').read_bytes(), (directory / 'app/index.html').read_bytes())

    def test_prepare_sorts_complete_paths_with_hyphenated_sibling_directories(self):
        fixture, _, _ = self.bundle(files={
            'app/index.html': b'<head><!-- monomer:page-metadata:start --><title>Monomer</title><!-- monomer:page-metadata:end --></head>', 'public/index.html': b'public',
            'public/en/agents/claude/index.md': b'Claude',
            'public/en/agents/claude-code/index.md': b'Claude Code',
        })
        output = self.root / 'exported'

        def export(command, **kwargs):
            self.assertEqual(command[:2], ['docker', 'build'])
            for source in fixture.rglob('*'):
                if source.is_file() and source.name != 'manifest.json':
                    target = output / source.relative_to(fixture)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(source.read_bytes())

        with patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(s.deploy, 'run', side_effect=export):
            prepared = s.prepare(output, self.demo, s.digest(DEMO))
        manifest = json.loads((output / 'manifest.json').read_bytes())
        paths = [entry['path'] for entry in manifest['files']]
        self.assertEqual(paths, sorted(paths))
        self.assertEqual(prepared['release'], s.validate(manifest))

    def test_demo_input_is_required_and_verified_before_build(self):
        for demo_path, expected, message in (
                (self.root / 'missing.mp4', s.digest(DEMO), 'readable file'),
                (self.root, s.digest(DEMO), 'readable file'),
                (self.demo, 'invalid', 'Invalid demo SHA'),
                (self.demo, '0' * 64, 'SHA-256 mismatch')):
            with self.subTest(message=message), patch.object(s.deploy, 'source_check', return_value=SOURCE), \
                 patch.object(s.deploy, 'run') as run, self.assertRaisesRegex(RuntimeError, message):
                s.prepare(self.root / 'new', demo_path, expected)
            run.assert_not_called()
        for data in (b'', b'not a video'):
            self.demo.write_bytes(data)
            with patch.object(s.deploy, 'source_check', return_value=SOURCE), \
                 patch.object(s.deploy, 'run') as run, self.assertRaisesRegex(RuntimeError, 'not an MP4'):
                s.prepare(self.root / 'new', self.demo, s.digest(data))
            run.assert_not_called()

    def test_build_rejects_exported_demo_symlink(self):
        output = self.root / 'symlink-build'
        def export(command, **kwargs):
            self.bundle('symlink-build')
            (output / 'manifest.json').unlink()
            (output / s.DEMO_PATH).unlink()
            (output / s.DEMO_PATH).symlink_to(self.demo)
        with patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(s.deploy, 'run', side_effect=export), \
             self.assertRaisesRegex(RuntimeError, 'Demo output must not contain symlinks'):
            s.prepare(output, self.demo, s.digest(DEMO))
        self.assertEqual(self.demo.read_bytes(), DEMO)

    def test_demo_build_cli_requires_and_passes_explicit_inputs(self):
        for options in ([], ['--demo-mp4', str(self.demo)], ['--demo-sha256', s.digest(DEMO)]):
            with patch.object(s, 'prepare') as prepare, self.assertRaises(SystemExit):
                s.main(['--environment', 'dev', 'build', 'output', *options])
            prepare.assert_not_called()
        with patch.object(s, 'prepare', return_value={}) as prepare, patch('sys.stdout', new_callable=io.StringIO):
            s.main(['--environment', 'dev', 'build', 'output', '--demo-mp4', str(self.demo),
                    '--demo-sha256', s.digest(DEMO)])
        prepare.assert_called_once_with('output', str(self.demo), s.digest(DEMO))

    def test_build_materializes_demo_and_publishes_verified_bytes(self):
        output = self.root / 'demo-build'
        link = self.root / 'preview.mp4'
        link.symlink_to(self.demo)
        def export(command, **kwargs):
            self.bundle('demo-build')
            (output / 'manifest.json').unlink()
            (output / s.DEMO_PATH).unlink()
        with patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(s.deploy, 'run', side_effect=export):
            result = s.prepare(output, link, s.digest(DEMO))
        self.assertFalse((output / s.DEMO_PATH).is_symlink())
        self.assertEqual((output / s.DEMO_PATH).read_bytes(), DEMO)
        manifest, release = s.local_bundle(output)
        entry = next(entry for entry in manifest['files'] if entry['path'] == s.DEMO_PATH)
        self.assertEqual(entry, {'path': s.DEMO_PATH, 'sha256': s.digest(DEMO), 'size': len(DEMO),
                                 'contentType': 'video/mp4', 'cacheControl': 'no-store'})
        self.assertEqual(result['release'], release)
        self.publish(output)
        key = f'public/releases/{release}/media/dashboard-demo.mp4'
        self.assertEqual(self.objects[key][0], DEMO)
        self.assertEqual(self.objects[key][1]['ContentType'], 'video/mp4')
        self.objects[key] = (b'corrupt', self.objects[key][1])
        with patch.object(s, 'S3_CLIENT', self.s3), self.assertRaisesRegex(RuntimeError, 'collision or drift'):
            s.remote_manifest(release)
        (output / s.DEMO_PATH).write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError, 'Bundle bytes changed'):
            self.publish(output)

    def test_preserved_manifest_without_demo_is_readable_but_cannot_be_published_or_deployed(self):
        directory, manifest, _ = self.bundle()
        (directory / s.DEMO_PATH).unlink()
        manifest['files'] = [entry for entry in manifest['files'] if entry['path'] != s.DEMO_PATH]
        (directory / 'manifest.json').write_bytes(s.canonical(manifest))
        release = s.validate(manifest)
        self.assertEqual(s.local_bundle(directory), (manifest, release))
        with patch.object(s, 'put_immutable') as put, self.assertRaisesRegex(RuntimeError, 'demo MP4 is missing'):
            self.publish(directory)
        put.assert_not_called()
        with patch.object(s.deploy, 'account_check'), patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(s, 'remote_manifest', return_value=manifest), patch.object(s, 'stack') as stack, \
             self.assertRaisesRegex(RuntimeError, 'demo MP4 is missing'):
            s.activate(release)
        stack.assert_not_called()

    def test_wrong_account_and_main_fences(self):
        directory, _, _ = self.bundle()
        with patch.object(s.deploy, 'aws', return_value={'Account': 'wrong'}), self.assertRaisesRegex(RuntimeError, 'Wrong AWS'):
            s.publish(directory)
        with patch.object(s.deploy, 'account_check'), patch.object(s.deploy, 'run', side_effect=['', SOURCE, 'a', 'b']), \
             self.assertRaisesRegex(RuntimeError, 'match local main'):
            s.publish(directory)

    def test_apply_required(self):
        for action in ('publish', 'deploy', 'rollback'):
            with patch.object(s.deploy, 'aws') as aws, self.assertRaisesRegex(RuntimeError, 'No AWS mutation'):
                s.main(['--environment', 'dev', action, 'a' * 64])
            aws.assert_not_called()

    def test_synth_reads_complete_generated_template_instead_of_stdout(self):
        template = {'Resources': {}, 'Rules': {'CheckBootstrapVersion': {'Assertions': []}}}
        template_path = self.root / 'cdk.out' / f'{s.STACK}.template.json'
        template_path.parent.mkdir()
        template_path.write_text(json.dumps({'stale': True}))

        def synth(command, **kwargs):
            self.assertEqual(kwargs, {'cwd': self.root})
            if command == ['npm', 'run', 'build']:
                return ''
            self.assertEqual(command, ['npx', '--no-install', 'cdk', 'synth', s.STACK, '-c', 'environment=dev', '--exclusively', '--json'])
            self.assertFalse(template_path.exists())
            template_path.write_text(json.dumps(template))
            return json.dumps({'Resources': {}})

        with patch.object(s.deploy, 'INFRA', self.root), patch.object(s.deploy, 'run', side_effect=synth):
            self.assertEqual(s.synth('a' * 64), template)

    def test_synth_rejects_stale_template_when_output_is_not_regenerated(self):
        template_path = self.root / 'cdk.out' / f'{s.STACK}.template.json'
        template_path.parent.mkdir()
        template_path.write_text(json.dumps({'Resources': {}}))
        with patch.object(s.deploy, 'INFRA', self.root), patch.object(s.deploy, 'run', return_value='{}'), \
             self.assertRaisesRegex(RuntimeError, 'did not generate'):
            s.synth('a' * 64)
        self.assertFalse(template_path.exists())

    def test_activation_scope_and_rollback_accept_old_source(self):
        _, manifest, release = self.bundle(provenance={'kind': 'built-source', 'sourceCommit': '2' * 40,
                                                      'buildInputs': s.INPUTS})
        manifest['files'] = [entry for entry in manifest['files'] if entry['path'] != s.DEMO_PATH]
        release = s.validate(manifest)
        template = {'Resources': {}}
        with patch.object(s.deploy, 'account_check'), patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(s, 'remote_manifest', return_value=manifest), patch.object(s, 'stack', return_value=({'AppDistributionId': 'app', 'PublicDistributionId': 'public'}, release)), \
             patch.object(s.deploy, 'aws', return_value={'TemplateBody': template}), \
             patch.object(s, 'synth', return_value=template), patch.object(s, 'check', return_value={}) as check, \
             patch.object(s.deploy, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'Stale'):
                s.activate(release)
            run.assert_not_called()
            s.activate(release, rollback=True)
            command = next(c.args[0] for c in run.call_args_list if c.args[0][:4] == ['npx', '--no-install', 'cdk', 'deploy'])
            self.assertIn('--exclusively', command)
            self.assertEqual(command[-2:], ['--parameters', f'{s.STACK}:StaticRelease={release}'])
            self.assertEqual(command[command.index('--previous-parameters') + 1], 'true')
            check.assert_called_once_with(release)
            s.deploy.update_deployment_tag.assert_called_once_with('static', '2' * 40)

    def test_activation_stops_before_remote_verification_without_cdk_dependencies(self):
        with patch.object(s.deploy, 'INFRA', self.root), patch.object(s.deploy, 'cdk_toolchain_check', REAL_TOOLCHAIN_CHECK), \
             patch.object(s.deploy, 'account_check'), patch.object(s, 'remote_manifest') as remote, \
             patch.object(s.deploy, 'run') as run:
            for rollback in (False, True):
                with self.assertRaisesRegex(RuntimeError, 'npm ci --ignore-scripts --prefix infra/cloud-dev'):
                    s.activate('a' * 64, rollback)
            with self.assertRaisesRegex(RuntimeError, 'CDK dependencies are missing'):
                s.synth('a' * 64)
            remote.assert_not_called()
            run.assert_not_called()

    def test_infrastructure_diff_or_remote_corruption_blocks_activation(self):
        _, manifest, release = self.bundle()
        with patch.object(s.deploy, 'account_check'), patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(s, 'remote_manifest', return_value=manifest), patch.object(s, 'stack', return_value=({'AppDistributionId': 'app', 'PublicDistributionId': 'public'}, release)), \
             patch.object(s.deploy, 'aws', return_value={'TemplateBody': {}}), \
             patch.object(s, 'synth', return_value={'changed': True}), patch.object(s.deploy, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'template differs'):
                s.activate(release)
            run.assert_not_called()
        with patch.object(s.deploy, 'account_check'), patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(s, 'remote_manifest', side_effect=RuntimeError('corrupt')), patch.object(s.deploy, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'corrupt'):
                s.activate(release)
            run.assert_not_called()

    def test_check_without_local_record_validates_distribution_origins(self):
        _, manifest, release = self.bundle()
        outputs = {'StaticBucketName': s.BUCKET}
        distributions = {}
        for site in ('App', 'Public'):
            outputs[site + 'DistributionId'] = site
            outputs[site + 'DistributionDomainName'] = site + '.cloudfront.net'
            distributions[site] = {'Distribution': {'Status': 'Deployed',
                'DomainName': site + '.cloudfront.net', 'DistributionConfig': {'Enabled': True,
                'Aliases': {'Items': ['app.dev.monomer.cc' if site == 'App' else 'dev.monomer.cc']},
                'DefaultCacheBehavior': {'TargetOriginId': 'Release'},
                'CacheBehaviors': {'Items': [{'PathPattern': pattern, 'TargetOriginId': 'SharedAssets'}
                    for pattern in (['/assets/chart-widget-worker-*.js', '/assets/*'] if site == 'App' else ['/_astro/*'])]},
                'Origins': {'Items': [
                    {'Id': 'SharedAssets', 'OriginAccessControlId': 'oac', 'OriginPath': '/' + site.lower(), 'DomainName': f'{s.BUCKET}.s3.{s.deploy.REGION}.amazonaws.com'},
                    {'Id': 'Release', 'OriginAccessControlId': 'oac', 'OriginPath': '/' + site.lower() + '/releases/' + release,
                     'DomainName': f'{s.BUCKET}.s3.{s.deploy.REGION}.amazonaws.com'}]}}}}
        for distribution in distributions.values():
            configuration = distribution['Distribution']['DistributionConfig']
            for behavior in [configuration['DefaultCacheBehavior'], *configuration['CacheBehaviors']['Items']]:
                behavior.update({'ViewerProtocolPolicy': 'redirect-to-https', 'CachePolicyId': 'cache',
                    'ResponseHeadersPolicyId': 'headers', 'FunctionAssociations': {'Items': [
                        {'EventType': 'viewer-request', 'FunctionARN': 'arn:function'}]}})
        app = distributions['App']['Distribution']['DistributionConfig']
        app['Origins']['Items'].append({'Id': 'ControlPageHtml', 'DomainName': 'api.dev.monomer.cc',
            'CustomOriginConfig': {'OriginProtocolPolicy': 'https-only', 'HTTPSPort': 443,
                                  'OriginSslProtocols': {'Items': ['TLSv1.2']}}})
        app['CacheBehaviors']['Items'].append(dict(app['DefaultCacheBehavior'],
            PathPattern='/workspaces/*/pages/*', TargetOriginId='ControlPageHtml', CachePolicyId=s.CACHING_DISABLED,
            AllowedMethods={'Items': ['GET', 'HEAD']}))
        app['CustomErrorResponses'] = {'Items': [{'ErrorCode': code, 'ErrorCachingMinTTL': 0} for code in (500, 502, 503, 504)]}
        with patch.object(s.deploy, 'account_check'), patch.object(s, 'stack', return_value=(outputs, release)), \
             patch.object(s, 'remote_manifest', return_value=manifest), patch.object(s.deploy, 'source_check') as source, \
             patch.object(s.deploy, 'load') as load, patch.object(s.deploy, 'aws', side_effect=lambda *a: distributions[a[-1]]):
            self.assertTrue(s.check()['infrastructureVerified'])
            source.assert_not_called()
            load.assert_not_called()
            configuration = distributions['App']['Distribution']['DistributionConfig']
            for target, field, changed in [(configuration, 'Enabled', False),
                    (configuration['Aliases'], 'Items', ['wrong.example']),
                    (configuration['Origins']['Items'][0], 'OriginAccessControlId', ''),
                    (configuration['DefaultCacheBehavior'], 'TargetOriginId', 'SharedAssets'),
                    (configuration['CacheBehaviors']['Items'][0], 'TargetOriginId', 'Release'),
                    (configuration['DefaultCacheBehavior'], 'ResponseHeadersPolicyId', '')]:
                previous = target[field]
                target[field] = changed
                with self.assertRaises(RuntimeError):
                    s.check()
                target[field] = previous
            page = configuration['CacheBehaviors']['Items'][-1]
            page['AllowedMethods']['Items'] = ['HEAD', 'GET']
            self.assertTrue(s.check()['infrastructureVerified'])
            control = configuration['Origins']['Items'][-1]
            for target, field, changed in [(page, 'CachePolicyId', 'cache'),
                    (page, 'AllowedMethods', {'Items': ['GET', 'HEAD', 'POST']}),
                    (page, 'AllowedMethods', {'Items': ['HEAD']}),
                    (page, 'AllowedMethods', {'Items': ['HEAD', 'GET', 'GET']}),
                    (page, 'ResponseHeadersPolicyId', 'wrong'),
                    (control, 'DomainName', 'evil.example'),
                    (control['CustomOriginConfig'], 'OriginProtocolPolicy', 'http-only'),
                    (configuration['CustomErrorResponses']['Items'][0], 'ErrorCachingMinTTL', 10)]:
                previous = target[field]
                target[field] = changed
                with self.assertRaises(RuntimeError):
                    s.check()
                target[field] = previous
            page['OriginRequestPolicyId'] = 'forward-credentials'
            with self.assertRaises(RuntimeError):
                s.check()
            del page['OriginRequestPolicyId']
            distributions['App']['Distribution']['DistributionConfig']['Origins']['Items'][1]['OriginPath'] = '/app/releases/wrong'
            with self.assertRaisesRegex(RuntimeError, 'origin paths'):
                s.check()

    def test_metadata_slot_is_required_for_local_publish_and_remote_rollback(self):
        for index, body in enumerate((b'<head><title>old shell</title></head>',
                b'<head>' + s.APP_METADATA_START * 2 + s.APP_METADATA_END + b'</head>',
                b'<head></head>' + s.APP_METADATA_START + s.APP_METADATA_END,
                b'<head>' + s.APP_METADATA_START + s.APP_METADATA_END + b'</head>' + b'x' * (256 * 1024))):
            directory, manifest, release = self.bundle('bad-slot-' + str(index), {
                'app/index.html': body, 'public/index.html': b'public'})
            with self.assertRaises(RuntimeError):
                s.local_bundle(directory)
            with self.assertRaises(RuntimeError):
                self.publish(directory)
            for entry in manifest['files']:
                data = (directory / entry['path']).read_bytes()
                self.objects[s.key_for(entry, release)] = (data, {
                    'ContentLength': len(data), 'ContentType': entry['contentType'],
                    'CacheControl': entry['cacheControl'], 'Metadata': {'sha256': entry['sha256']}})
            data = s.canonical(manifest)
            self.objects[f'manifests/{release}.json'] = (data, {
                'ContentLength': len(data), 'ContentType': 'application/json',
                'CacheControl': 'no-store', 'Metadata': {'sha256': s.digest(data)}})
            with patch.object(s, 'S3_CLIENT', self.s3), self.assertRaises(RuntimeError):
                s.remote_manifest(release)

    def test_worker_policy_filename_is_required(self):
        directory, manifest, _ = self.bundle()
        (directory / 'app/assets/chart-widget-worker-test.js').unlink()
        manifest['files'] = [e for e in manifest['files'] if 'chart-widget-worker-' not in e['path']]
        (directory / 'manifest.json').write_bytes(s.canonical(manifest))
        with self.assertRaisesRegex(RuntimeError, 'dedicated policy filename'):
            s.local_bundle(directory)


    def test_build_exports_artifacts_with_fixed_inputs_and_rechecks_source(self):
        output = self.root / 'build'
        def docker(command, **kwargs):
            self.assertEqual(command[:2], ['docker', 'build'])
            self.assertIn('artifacts', command)
            self.assertIn(f'type=local,dest={output.resolve()}', command)
            for name, value in s.INPUTS.items():
                self.assertIn(f'{name}={value}', command)
            self.bundle('build')
            (output / 'manifest.json').unlink()
        with patch.object(s.deploy, 'source_check', return_value=SOURCE) as source, \
             patch.object(s.deploy, 'aws') as aws, patch.object(s.deploy, 'snapshot') as snapshot, \
             patch.object(s.deploy, 'run', side_effect=docker):
            result = s.prepare(output, self.demo, s.digest(DEMO))
            self.assertEqual(source.call_count, 2)
            aws.assert_not_called()
            snapshot.assert_not_called()
        self.assertEqual(result['provenance'], {'kind': 'built-source', 'sourceCommit': SOURCE,
                                               'buildInputs': s.INPUTS})
        s.local_bundle(output)

    def test_build_rejects_source_change_and_missing_error_page(self):
        for changed in (True, False):
            output = self.root / str(changed)
            def docker(command, **kwargs):
                self.bundle(str(changed))
                (output / 'manifest.json').unlink()
                (output / 'app/404.html').unlink()
            with patch.object(s.deploy, 'source_check', side_effect=[SOURCE, '2' * 40 if changed else SOURCE]), \
                 patch.object(s.deploy, 'run', side_effect=docker), \
                 self.assertRaisesRegex(RuntimeError, 'Source changed' if changed else '404 documents'):
                s.prepare(output, self.demo, s.digest(DEMO))

    def test_import_cli_removed_and_preserved_import_requires_rollback(self):
        with self.assertRaises(SystemExit):
            s.main(['import-image', str(self.root / 'import')])
        directory, manifest, release = self.bundle(provenance={'kind': 'imported-image', 'imageUri': URI,
                                                               'sourceCommit': None, 'buildInputs': None})
        self.assertEqual(s.validate(manifest), release)
        with patch.object(s.deploy, 'account_check'), patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(s.deploy, 'snapshot') as snapshot, patch.object(s, 'put_immutable') as put, \
             patch.object(s, 'remote_manifest', return_value=manifest), \
             patch.object(s, 'stack', return_value=({'AppDistributionId': 'app', 'PublicDistributionId': 'public'}, release)), \
             patch.object(s.deploy, 'aws', return_value={'TemplateBody': {}}), \
             patch.object(s, 'synth', return_value={}), patch.object(s, 'check', return_value={}), \
             patch.object(s.deploy, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'source-built'):
                s.publish(directory)
            put.assert_not_called()
            with self.assertRaisesRegex(RuntimeError, 'explicit rollback'):
                s.activate(release)
            run.assert_not_called()
            s.activate(release, rollback=True)
            self.assertTrue(run.called)
            snapshot.assert_not_called()


if __name__ == '__main__':
    unittest.main()
