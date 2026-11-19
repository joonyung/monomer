import copy
from contextlib import redirect_stderr
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('tag_deploy', Path(__file__).parents[1] / 'deploy.py')
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)
spec = importlib.util.spec_from_file_location('tag_static', Path(__file__).parents[1] / 'static_release.py')
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
SOURCE = '1' * 40


class DeploymentTagTests(unittest.TestCase):
    def setUp(self):
        d.configure('dev')
        s.configure('dev')

    def test_tags_move_in_both_directions_without_changing_head_or_other_refs(self):
        with tempfile.TemporaryDirectory() as temporary:
            def run(command, **kwargs):
                return subprocess.run(command, cwd=temporary, text=True, check=True,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()
            run(['git', 'init', '-b', 'main'])
            run(['git', 'config', 'user.name', 'Fixture'])
            run(['git', 'config', 'user.email', 'fixture@example.com'])
            run(['git', 'commit', '--allow-empty', '-m', 'first'])
            first = run(['git', 'rev-parse', 'HEAD'])
            run(['git', 'commit', '--allow-empty', '-m', 'second'])
            second = run(['git', 'rev-parse', 'HEAD'])
            run(['git', 'tag', 'keep', first])
            with patch.object(d, 'run', side_effect=run):
                for environment in ('dev', 'prod'):
                    d.configure(environment)
                    for component in (*d.SERVICES, 'static'):
                        for source in (first, second, first):
                            result = d.update_deployment_tag(component, source)
                            prefix = '🟢' if environment == 'dev' else '🟣'
                            self.assertEqual(result['name'], f'{prefix}deployed/{environment}/{component}')
                            self.assertEqual(run(['git', 'rev-parse', result['name']]), source)
                with self.assertRaises(subprocess.CalledProcessError):
                    d.update_deployment_tag('control', 'f' * 40)
            self.assertEqual(run(['git', 'rev-parse', 'HEAD']), second)
            self.assertEqual(run(['git', 'rev-parse', 'main']), second)
            self.assertEqual(run(['git', 'rev-parse', 'keep']), first)
            self.assertEqual(run(['git', 'status', '--porcelain']), '')
            self.assertEqual(run(['git', 'remote']), '')

    def test_invalid_source_or_component_never_writes_a_ref(self):
        with patch.object(d, 'run') as run:
            for component, source in [('control', None), ('control', 'HEAD'), ('control', 'a' * 12),
                                      ('../main', SOURCE), ('web', SOURCE)]:
                with self.subTest(component=component, source=source), self.assertRaises(RuntimeError):
                    d.update_deployment_tag(component, source)
            run.assert_not_called()

    def test_tag_failure_preserves_successful_deployment_result_and_reports_failure(self):
        stderr = io.StringIO()
        result = {'infrastructureVerified': True, 'productSmoke': 'NOT_RUN'}
        with patch.object(d, 'update_deployment_tag', side_effect=RuntimeError('missing commit')), redirect_stderr(stderr):
            marked = d.mark_deployment(result, 'control', SOURCE)
        self.assertTrue(marked['infrastructureVerified'])
        self.assertEqual(marked['deploymentTag']['status'], 'failed')
        self.assertEqual(marked['deploymentTag']['name'], '🟢deployed/dev/control')
        self.assertIn('sync-tag', stderr.getvalue())

    def test_backend_marks_only_the_verified_record_source_after_success(self):
        image = d.IMAGE_PREFIX + '@sha256:' + 'a' * 64
        record = {'sourceCommit': SOURCE, 'imageUri': image}
        state = {service: {'configuredImage': image} for service in d.SERVICES}
        for succeeds in (True, False):
            with self.subTest(succeeds=succeeds), patch.object(d, 'account_check'), \
                 patch.object(d, 'cdk_toolchain_check'), patch.object(d, 'load', return_value=record.copy()), \
                 patch.object(d, 'source_check', return_value=SOURCE), patch.object(d, 'snapshot', return_value=state), \
                 patch.object(d, 'healthy', return_value=True), patch.object(d, 'save'), patch.object(d, 'run'), \
                 patch.object(d, 'verify', side_effect=None if succeeds else RuntimeError('verification failed'),
                              return_value={'infrastructureVerified': True}), patch.object(d, 'update_deployment_tag') as update:
                if succeeds:
                    d.deploy('control')
                    update.assert_called_once_with('control', SOURCE)
                else:
                    with self.assertRaisesRegex(RuntimeError, 'verification failed'):
                        d.deploy('control')
                    update.assert_not_called()

    def test_backend_sync_uses_running_digest_provenance_and_rejects_unknown_or_conflicting_tags(self):
        image = d.IMAGE_PREFIX + '@sha256:' + 'a' * 64
        state = {service: {'configuredImage': image} for service in d.SERVICES}
        for tags, accepted in [([f'{SOURCE[:12]}-abcdef123456-control'], True),
                               (['bootstrap'], False),
                               ([f'{SOURCE[:12]}-abcdef123456-execution'], False),
                               ([f'{SOURCE[:12]}-abcdef123456-control', '222222222222-abcdef123456-control'], False)]:
            details = {'imageDetails': [{'imageDigest': image.split('@')[1], 'imageTags': tags}]}
            with self.subTest(tags=tags), patch.object(d, 'account_check'), patch.object(d, 'snapshot', return_value=state), \
                 patch.object(d, 'validate_states'), patch.object(d, 'aws', return_value=details) as aws, \
                 patch.object(d, 'run', return_value=SOURCE), patch.object(d, 'update_deployment_tag') as update:
                if accepted:
                    d.sync_tag('control')
                    update.assert_called_once_with('control', SOURCE)
                    self.assertIn('imageDigest=' + image.split('@')[1], aws.call_args.args)
                else:
                    with self.assertRaises(RuntimeError):
                        d.sync_tag('control')
                    update.assert_not_called()

    def test_backend_sync_never_updates_on_failed_validation_or_observed_deployment_change(self):
        image = d.IMAGE_PREFIX + '@sha256:' + 'a' * 64
        state = {service: {'configuredImage': image} for service in d.SERVICES}
        changed = copy.deepcopy(state)
        changed['control']['configuredImage'] = d.IMAGE_PREFIX + '@sha256:' + 'b' * 64
        details = {'imageDetails': [{'imageDigest': image.split('@')[1],
                                    'imageTags': [f'{SOURCE[:12]}-abcdef123456-control']}]}
        for snapshots, validation_error in [([state, changed], None), ([state], RuntimeError('unhealthy'))]:
            with patch.object(d, 'account_check'), patch.object(d, 'snapshot', side_effect=snapshots), \
                 patch.object(d, 'validate_states', side_effect=validation_error), patch.object(d, 'aws', return_value=details), \
                 patch.object(d, 'run', return_value=SOURCE), patch.object(d, 'update_deployment_tag') as update:
                with self.assertRaises(RuntimeError):
                    d.sync_tag('control')
                update.assert_not_called()

    def test_static_sync_requires_both_distributions_to_serve_the_observed_release(self):
        release = 'a' * 64
        outputs = {'AppDistributionId': 'App', 'PublicDistributionId': 'Public'}
        distributions = {site: {'Distribution': {'Status': 'Deployed', 'DistributionConfig': {
            'Enabled': True, 'DefaultCacheBehavior': {'TargetOriginId': 'Release'}, 'Origins': {'Items': [
            {'Id': 'Release', 'OriginPath': f'/{site.lower()}/releases/{release}',
             'DomainName': f'{s.BUCKET}.s3.{s.deploy.REGION}.amazonaws.com'}]}}}} for site in ('App', 'Public')}
        for defect in (None, 'propagation', 'origin', 'changed', 'disabled', 'default-route'):
            current = copy.deepcopy(distributions)
            if defect == 'propagation':
                current['Public']['Distribution']['Status'] = 'InProgress'
            if defect == 'origin':
                current['App']['Distribution']['DistributionConfig']['Origins']['Items'][0]['OriginPath'] = '/old'
            if defect == 'disabled':
                current['Public']['Distribution']['DistributionConfig']['Enabled'] = False
            if defect == 'default-route':
                current['App']['Distribution']['DistributionConfig']['DefaultCacheBehavior']['TargetOriginId'] = 'SharedAssets'
            def aws(*args):
                return current[args[-1]]
            with self.subTest(defect=defect), patch.object(s.deploy, 'account_check'), \
                 patch.object(s, 'stack', side_effect=[(outputs, release), (outputs, 'b' * 64 if defect == 'changed' else release)]), \
                 patch.object(s, 'read_remote_manifest', return_value={'provenance': {'sourceCommit': SOURCE}}), \
                 patch.object(s.deploy, 'aws', side_effect=aws), patch.object(s.deploy, 'update_deployment_tag') as update:
                if defect:
                    with self.assertRaises(RuntimeError):
                        s.sync_tag()
                    update.assert_not_called()
                else:
                    s.sync_tag()
                    update.assert_called_once_with('static', SOURCE)

    def test_failed_activation_verification_never_updates_static_tag(self):
        release = 'a' * 64
        with patch.object(s.deploy, 'account_check'), patch.object(s.deploy, 'cdk_toolchain_check'), \
             patch.object(s.deploy, 'source_check', return_value=SOURCE), \
             patch.object(s, 'remote_manifest', return_value={'provenance': {'kind': 'built-source', 'sourceCommit': SOURCE}}), \
             patch.object(s, 'require_demo'), patch.object(s, 'stack', return_value=({'AppDistributionId': 'App', 'PublicDistributionId': 'Public'}, release)), \
             patch.object(s.deploy, 'aws', return_value={'TemplateBody': {}}), patch.object(s, 'synth', return_value={}), \
             patch.object(s.deploy, 'run'), patch.object(s, 'check', side_effect=RuntimeError('verification failed')), \
             patch.object(s.deploy, 'update_deployment_tag') as update:
            with self.assertRaisesRegex(RuntimeError, 'verification failed'):
                s.activate(release)
            update.assert_not_called()


if __name__ == '__main__':
    unittest.main()
