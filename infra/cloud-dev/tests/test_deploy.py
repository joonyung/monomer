import copy
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('dev_deploy', Path(__file__).parents[1] / 'deploy.py')
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)
d.configure('dev')
URI = d.IMAGE_PREFIX + '@sha256:' + 'a' * 64
OTHER = d.IMAGE_PREFIX + '@sha256:' + 'b' * 64
REAL_TOOLCHAIN_CHECK = d.cdk_toolchain_check
RECORD = {'service': 'control', 'sourceCommit': '1' * 40, 'imageId': 'sha256:' + 'c' * 64,
          'localTag': 'monomer-dev-control:build-1', 'imageUri': URI}


def states():
    return {name: {'desired': 1, 'running': 1, 'pending': 0, 'rollouts': ['COMPLETED'],
        'configuredImage': URI, 'targetHealth': [] if name == 'execution' else ['healthy'],
        'tasks': [{'status': 'RUNNING', 'health': 'HEALTHY',
                   'containers': [{'image': URI, 'digest': 'sha256:' + 'a' * 64, 'health': 'HEALTHY'}]}]}
        for name in d.SERVICES}


def quiesced_states():
    result = states()
    result['control'].update(serviceName='control-service', desired=0, running=0, pending=0, targetHealth=[], tasks=[],
                             taskDefinition='new-definition', deployments=[{'taskDefinition': 'new-definition',
                                 'status': 'PRIMARY', 'rolloutState': 'COMPLETED'}])
    return result


def stopped_task_lookup(last_status):
    def aws(*args):
        if args[:2] == ('ecs', 'list-tasks'):
            return {'taskArns': ['task-1']}
        if args[:2] == ('ecs', 'describe-tasks'):
            return {'tasks': [{'lastStatus': last_status}]}
        if args[:2] == ('ecs', 'describe-task-definition'):
            return {'taskDefinition': {'taskDefinitionArn': args[-1],
                'containerDefinitions': [{'name': 'control', 'image': URI}]}}
        return {}
    return aws


class DeployTests(unittest.TestCase):
    def setUp(self):
        d.configure('dev')
        toolchain = patch.object(d, 'cdk_toolchain_check')
        toolchain.start()
        self.addCleanup(toolchain.stop)
        tag = patch.object(d, 'update_deployment_tag', return_value={'status': 'updated'})
        tag.start()
        self.addCleanup(tag.stop)

    def test_phase_reports_utc_and_monotonic_duration_without_stdout_or_inputs(self):
        stderr, stdout = io.StringIO(), io.StringIO()
        with redirect_stderr(stderr), redirect_stdout(stdout), patch.object(d.time, 'monotonic', side_effect=[12, 14.25]):
            with d.phase('ECR push'):
                pass
        events = [json.loads(line) for line in stderr.getvalue().splitlines()]
        self.assertEqual([event['status'] for event in events], ['started', 'succeeded'])
        self.assertEqual(events[1]['elapsedSeconds'], 2.25)
        self.assertTrue(all(event['at'].endswith('+00:00') for event in events))
        self.assertEqual(stdout.getvalue(), '')

    def test_timed_failure_propagates_without_logging_sensitive_exception_or_arguments(self):
        stderr = io.StringIO()
        @d.timed('publish')
        def failed(password):
            raise RuntimeError(password)
        with redirect_stderr(stderr), self.assertRaisesRegex(RuntimeError, 'secret-fixture'):
            failed('secret-fixture')
        events = [json.loads(line) for line in stderr.getvalue().splitlines()]
        self.assertEqual([event['status'] for event in events], ['started', 'failed'])
        self.assertNotIn('secret-fixture', stderr.getvalue())
        self.assertGreaterEqual(events[1]['elapsedSeconds'], 0)

    def test_quiesced_waits_until_draining_tasks_are_fully_stopped(self):
        control = quiesced_states()['control']
        with patch.object(d, 'aws', side_effect=stopped_task_lookup('DEACTIVATING')):
            self.assertFalse(d.quiesced(control))
        with patch.object(d, 'aws', side_effect=stopped_task_lookup('STOPPED')):
            self.assertTrue(d.quiesced(control))
        with patch.object(d, 'aws') as aws:
            self.assertFalse(d.quiesced(states()['control']))
        aws.assert_not_called()

    def test_quiesce_snapshots_before_stopping_control_and_waits_for_stopped_tasks(self):
        running = states()
        running['control']['serviceName'] = 'control-service'
        draining = quiesced_states()
        calls = []
        lookups = iter(['DEACTIVATING', 'STOPPED'])

        def aws(*args):
            calls.append(args[:2])
            if args[:2] == ('rds', 'describe-db-snapshots'):
                return {'DBSnapshots': [{'Status': 'available', 'DBInstanceIdentifier': 'monomer-dev'}]}
            if args[:2] == ('ecs', 'list-tasks'):
                return {'taskArns': ['task-1']}
            if args[:2] == ('ecs', 'describe-tasks'):
                return {'tasks': [{'lastStatus': next(lookups)}]}
            return {}
        with patch.object(d, 'account_check'), patch.object(d, 'snapshot', side_effect=[running, running, draining, draining]), \
             patch.object(d, 'aws', side_effect=aws), patch.object(d, 'run') as run, patch.object(d.time, 'sleep') as sleep:
            result = d.quiesce('control', 'monomer-dev-before-cutover')
        self.assertEqual(result['snapshot'], 'monomer-dev-before-cutover')
        self.assertEqual(sleep.call_count, 1)
        self.assertLess(calls.index(('rds', 'create-db-snapshot')), calls.index(('ecs', 'update-service')))
        self.assertIn('db-snapshot-available', run.call_args.args[0])

    def test_quiesce_refuses_foreign_snapshot_name_or_unhealthy_services(self):
        with patch.object(d, 'aws') as aws, self.assertRaisesRegex(RuntimeError, 'environment prefix'):
            d.quiesce('control', 'monomer-prod-before-cutover')
        aws.assert_not_called()
        unhealthy = states()
        unhealthy['execution']['running'] = 0
        with patch.object(d, 'account_check'), patch.object(d, 'snapshot', return_value=unhealthy), \
             patch.object(d, 'aws') as aws, self.assertRaisesRegex(RuntimeError, 'stable'):
            d.quiesce('control', 'monomer-dev-before-cutover')
        aws.assert_not_called()

    def test_deploy_from_quiesced_requires_the_explicit_flag_and_a_fully_stopped_control(self):
        common = [patch.object(d, 'verify_public_site'), patch.object(d, 'account_check'), patch.object(d, 'load', return_value=copy.deepcopy(RECORD)),
                  patch.object(d, 'source_check', return_value=RECORD['sourceCommit']), patch.object(d, 'save'),
                  patch.object(d, 'verify', return_value={'infrastructureVerified': True})]
        for item in common:
            item.start()
            self.addCleanup(item.stop)
        stopped = quiesced_states()
        stopped['control']['configuredImage'] = OTHER
        with patch.object(d, 'snapshot', return_value=stopped), patch.object(d, 'run') as run, \
             patch.object(d, 'aws', side_effect=stopped_task_lookup('STOPPED')):
            with self.assertRaisesRegex(RuntimeError, 'stable'):
                d.deploy('control')
            run.assert_not_called()
        partial = quiesced_states()
        partial['control']['running'] = 1
        with patch.object(d, 'snapshot', return_value=partial), patch.object(d, 'run') as run, \
             self.assertRaisesRegex(RuntimeError, 'stopped Control'):
            d.deploy('control', from_quiesced=True)
        run.assert_not_called()


    def test_control_quiesced_deploy_installs_and_verifies_before_starting(self):
        for environment in ('dev', 'prod'):
            d.configure(environment)
            selected = d.IMAGE_PREFIX + '@sha256:' + 'a' * 64
            previous = d.IMAGE_PREFIX + '@sha256:' + 'b' * 64
            for same_image in (False, True):
                before = quiesced_states()
                before['control'].update(configuredImage=selected if same_image else previous, taskDefinition='old-definition')
                before['execution']['configuredImage'] = selected
                before['execution']['tasks'][0]['containers'][0].update(image=selected, digest=selected.split('@')[1])
                installed = copy.deepcopy(before)
                installed['control'].update(configuredImage=selected, taskDefinition='new-definition')
                events = []
                snapshots = iter([before, before, installed])

                def snapshot():
                    state = next(snapshots)
                    events.append(('snapshot', state['control']['configuredImage']))
                    return state

                def run(command, **kwargs):
                    if 'cdk' in command:
                        events.append(('deploy', command))

                def aws(*args):
                    if args[:2] == ('ecs', 'list-tasks'):
                        return {'taskArns': []}
                    if args[:2] == ('ecs', 'describe-task-definition'):
                        events.append(('definition', args[-1]))
                        return {'taskDefinition': {'taskDefinitionArn': 'new-definition',
                            'containerDefinitions': [{'name': 'control', 'image': selected}]}}
                    raise AssertionError(args)

                with self.subTest(environment=environment, same_image=same_image), \
                     patch.object(d, 'account_check'), patch.object(d, 'load', return_value=RECORD | {'imageUri': selected}), \
                     patch.object(d, 'source_check', return_value=RECORD['sourceCommit']), patch.object(d, 'save'), \
                     patch.object(d, 'snapshot', side_effect=snapshot), patch.object(d, 'run', side_effect=run), \
                     patch.object(d, 'aws', side_effect=aws), patch.object(d, 'verify_public_site'), \
                     patch.object(d, 'verify') as verify:
                    d.deploy('control', from_quiesced=True)
                commands = [value for kind, value in events if kind == 'deploy']
                self.assertEqual(len(commands), 3)
                for command, image, count in zip(commands, [before['control']['configuredImage'], selected, selected], [0, 0, 1]):
                    self.assertEqual(command[command.index('--parameters') + 1:],
                        [f'{d.STACK}:ControlImage={image}', '--parameters', f'{d.STACK}:ControlDesiredCount={count}'])
                self.assertIn('--no-rollback', commands[0])
                self.assertNotIn('--no-rollback', commands[1])
                self.assertNotIn('--no-rollback', commands[2])
                self.assertLess(events.index(('definition', 'new-definition')), events.index(('deploy', commands[2])))
                verify.assert_called_once_with('control', from_quiesced=False)

    def test_control_quiesced_deploy_never_starts_after_failed_installation_or_verification(self):
        for defect in ('baseline-update', 'baseline-image', 'install-update', 'installed-image', 'started', 'pending',
                       'old-deployment', 'incomplete-deployment', 'definition-image', 'definition-arn', 'extra-container',
                       'definition-lookup', 'source-drift'):
            before = quiesced_states()
            before['control']['configuredImage'] = OTHER
            baseline = copy.deepcopy(before)
            installed = quiesced_states()
            definition = {'taskDefinitionArn': 'new-definition', 'containerDefinitions': [{'name': 'control', 'image': URI}]}
            if defect == 'baseline-image':
                baseline['control']['configuredImage'] = URI
            elif defect == 'installed-image':
                installed['control']['configuredImage'] = OTHER
            elif defect in ('started', 'pending'):
                installed['control']['running' if defect == 'started' else 'pending'] = 1
            elif defect == 'old-deployment':
                installed['control']['deployments'].append({'taskDefinition': 'old-definition', 'status': 'ACTIVE', 'rolloutState': 'COMPLETED'})
            elif defect == 'incomplete-deployment':
                installed['control']['deployments'][0]['rolloutState'] = 'IN_PROGRESS'
            elif defect == 'definition-image':
                definition['containerDefinitions'][0]['image'] = OTHER
            elif defect == 'definition-arn':
                definition['taskDefinitionArn'] = 'old-definition'
            elif defect == 'extra-container':
                definition['containerDefinitions'].append({'name': 'unexpected', 'image': URI})
            commands = []

            def run(command, **kwargs):
                if 'cdk' in command:
                    commands.append(command)
                    if defect == 'baseline-update' or defect == 'install-update' and len(commands) == 2:
                        raise RuntimeError('deployment failed')

            def aws(*args):
                if args[:2] == ('ecs', 'list-tasks'):
                    return {'taskArns': []}
                if defect == 'definition-lookup':
                    raise RuntimeError('definition lookup failed')
                return {'taskDefinition': definition}

            source_calls = 0
            def source_check():
                nonlocal source_calls
                source_calls += 1
                return '2' * 40 if defect == 'source-drift' and source_calls == 4 else RECORD['sourceCommit']

            with self.subTest(defect=defect), patch.object(d, 'account_check'), \
                 patch.object(d, 'load', return_value=copy.deepcopy(RECORD)), patch.object(d, 'save'), \
                 patch.object(d, 'source_check', side_effect=source_check), \
                 patch.object(d, 'snapshot', side_effect=[before, baseline, installed]), patch.object(d, 'run', side_effect=run), \
                 patch.object(d, 'aws', side_effect=aws), patch.object(d, 'verify_public_site'), \
                 patch.object(d, 'verify') as verify, self.assertRaises(RuntimeError):
                d.deploy('control', from_quiesced=True)
            self.assertTrue(all(f'{d.STACK}:ControlDesiredCount=0' in command for command in commands))
            verify.assert_not_called()

    def test_control_start_failure_does_not_repeat_deployment_or_restore_old_image(self):
        before = quiesced_states()
        before['control']['configuredImage'] = OTHER
        installed = quiesced_states()
        commands = []

        def run(command, **kwargs):
            if 'cdk' in command:
                commands.append(command)
                self.assertEqual(kwargs['input_text'], '')
                if len(commands) == 3:
                    raise RuntimeError('start update failed')

        with patch.object(d, 'account_check'), patch.object(d, 'load', return_value=copy.deepcopy(RECORD)), \
             patch.object(d, 'source_check', return_value=RECORD['sourceCommit']), patch.object(d, 'save'), \
             patch.object(d, 'snapshot', side_effect=[before, before, installed]), patch.object(d, 'run', side_effect=run), \
             patch.object(d, 'aws', side_effect=stopped_task_lookup('STOPPED')), patch.object(d, 'verify_public_site'), \
             patch.object(d, 'verify') as verify, self.assertRaisesRegex(RuntimeError, 'start update failed'):
            d.deploy('control', from_quiesced=True)
        self.assertEqual(len(commands), 3)
        self.assertIn(f'{d.STACK}:ControlImage={URI}', commands[1])
        self.assertIn(f'{d.STACK}:ControlDesiredCount=0', commands[1])
        self.assertIn(f'{d.STACK}:ControlImage={URI}', commands[2])
        self.assertIn(f'{d.STACK}:ControlDesiredCount=1', commands[2])
        verify.assert_not_called()

    def test_execution_deploy_from_quiesced_preserves_control_and_verifies_new_digest(self):
        before = quiesced_states()
        after = quiesced_states()
        after['execution']['configuredImage'] = OTHER
        after['execution']['tasks'][0]['containers'][0].update(image=OTHER, digest=OTHER.split('@')[1])
        record = RECORD | {'service': 'execution', 'imageUri': OTHER}
        with patch.object(d, 'account_check'), patch.object(d, 'load', return_value=record), \
             patch.object(d, 'source_check', return_value=RECORD['sourceCommit']), \
             patch.object(d, 'snapshot', side_effect=[before, before, before, after]), patch.object(d, 'save') as save, \
             patch.object(d, 'aws', side_effect=stopped_task_lookup('STOPPED')), \
             patch.object(d, 'verify_public_site'), patch.object(d, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'stable'):
                d.deploy('execution')
            run.assert_not_called()
            result = d.deploy('execution', from_quiesced=True)
        self.assertTrue(result['infrastructureVerified'])
        self.assertEqual(result['productSmoke'], 'NOT_RUN')
        self.assertEqual(save.call_args.args[1]['previousImages'], {s: URI for s in d.SERVICES})
        command = run.call_args.args[0]
        self.assertIn('--exclusively', command)
        self.assertEqual(command[command.index('--parameters') + 1:], [f'{d.STACK}:ExecutionImage={OTHER}', '--parameters', f'{d.STACK}:ControlDesiredCount=0'])

    def test_quiesced_deploy_rejects_unhealthy_execution_draining_control_and_image_drift(self):
        for service in d.SERVICES:
            for defect in ('unhealthy', 'draining', 'wrong-digest', 'foreign-image', 'stale-source'):
                before = quiesced_states()
                if defect == 'unhealthy':
                    before['execution']['running'] = 0
                elif defect == 'wrong-digest':
                    before['execution']['tasks'][0]['containers'][0]['digest'] = OTHER.split('@')[1]
                elif defect == 'foreign-image':
                    before['control']['configuredImage'] = URI.replace(d.ACCOUNT, '000000000000')
                with self.subTest(service=service, defect=defect), \
                     patch.object(d, 'account_check'), patch.object(d, 'load', return_value=RECORD | {'imageUri': OTHER}), \
                     patch.object(d, 'source_check', return_value='2' * 40 if defect == 'stale-source' else RECORD['sourceCommit']), \
                     patch.object(d, 'snapshot', return_value=before), patch.object(d, 'save') as save, \
                     patch.object(d, 'aws', side_effect=stopped_task_lookup('STOPPING' if defect == 'draining' else 'STOPPED')), \
                     patch.object(d, 'verify_public_site'), patch.object(d, 'run') as run, \
                     self.assertRaises(RuntimeError):
                    d.deploy(service, from_quiesced=True)
                run.assert_not_called()
                save.assert_not_called()

    def test_execution_quiesced_verify_rejects_changed_context_and_rollback(self):
        record = RECORD | {'service': 'execution', 'previousImages': {s: URI for s in d.SERVICES}}
        for defect in ('started', 'pending', 'draining', 'control-image', 'configured-image', 'running-image', 'digest', 'health', 'missing-record'):
            state = quiesced_states()
            if defect == 'started':
                state['control'] = states()['control']
            elif defect == 'pending':
                state['control']['pending'] = 1
            elif defect == 'control-image':
                state['control']['configuredImage'] = OTHER
            elif defect == 'configured-image':
                state['execution']['configuredImage'] = OTHER
            elif defect in ('running-image', 'digest', 'health'):
                field = {'running-image': 'image', 'digest': 'digest', 'health': 'health'}[defect]
                state['execution']['tasks'][0]['containers'][0][field] = OTHER
            with self.subTest(defect=defect), patch.object(d, 'account_check'), \
                 patch.object(d, 'load', return_value=RECORD if defect == 'missing-record' else record), \
                 patch.object(d, 'snapshot', return_value=state), patch.object(d, 'verify_public_site'), \
                 patch.object(d, 'aws', side_effect=stopped_task_lookup('STOPPING' if defect == 'draining' else 'STOPPED')), \
                 self.assertRaises(RuntimeError):
                d.verify('execution', from_quiesced=True)
        with patch.object(d, 'account_check'), patch.object(d, 'load', return_value=record), \
             patch.object(d, 'snapshot', return_value=quiesced_states()), self.assertRaisesRegex(RuntimeError, 'healthy'):
            d.verify('execution')
        with self.assertRaisesRegex(RuntimeError, 'only valid for Execution'):
            d.verify('control', from_quiesced=True)

    def test_quiesced_lookup_fails_closed_on_missing_tasks(self):
        for response in ({'tasks': [], 'failures': [{'reason': 'MISSING'}]}, {'tasks': []}):
            with patch.object(d, 'aws', side_effect=[{'taskArns': ['task-1']}, response]), \
                 self.assertRaisesRegex(RuntimeError, 'lookup failed'):
                d.quiesced(quiesced_states()['control'])

    def test_cli_forwards_explicit_quiesced_verification(self):
        with patch.object(d, 'verify', return_value={}) as verify, patch('builtins.print'):
            d.main(['--environment', 'dev', 'verify', 'execution', '--from-quiesced'])
        verify.assert_called_once_with('execution', True)

    def test_environment_is_required_even_for_read_commands(self):
        with patch.object(d, 'run') as run, self.assertRaises(SystemExit):
            d.main(['status'])
        run.assert_not_called()

    def test_prod_record_and_artifact_fences(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(d, 'ROOT', Path(temporary)):
            d.save('control', RECORD | d.record_identity())
            dev_path = d.record_path('control')
            d.configure('prod')
            self.assertNotEqual(d.record_path('control'), dev_path)
            for account in ('111122223333', '000000000000'):
                with patch.object(d, 'aws', return_value={'Account': account}), self.assertRaises(RuntimeError):
                    d.account_check()
            with self.assertRaises(RuntimeError):
                d.image_uri_check(URI)
            prod = RECORD | d.record_identity() | {'localTag': 'monomer-prod-control:build-1',
                                                     'imageUri': d.IMAGE_PREFIX + '@sha256:' + 'a' * 64}
            d.save('control', prod)
            self.assertEqual(d.load('control')['environment'], 'prod')
            for field, value in [('environment', 'dev'), ('account', '111122223333'), ('region', 'eu-west-1')]:
                d.save('control', prod | {field: value})
                with self.assertRaisesRegex(RuntimeError, 'mismatch; rebuild'):
                    d.load('control')
            for invalid in (prod | {'imageUri': URI}, prod | {'previousImages': {name: URI for name in d.SERVICES}}):
                d.save('control', invalid)
                with self.assertRaisesRegex(RuntimeError, 'selected environment repository'):
                    d.load('control')
            d.save('control', RECORD)
            with self.assertRaisesRegex(RuntimeError, 'mismatch; rebuild'):
                d.load('control')

    def test_profile_propagates_to_aws_login_and_cdk_without_config_writes(self):
        d.configure('prod', 'operator-prod')
        with patch.object(d, 'run', return_value='{}') as run:
            d.aws('sts', 'get-caller-identity')
            self.assertIn('operator-prod', run.call_args.args[0])
        self.assertEqual(d.cdk_arguments(), ['-c', 'environment=prod', '--profile', 'operator-prod'])
        record = RECORD | d.record_identity() | {'localTag': 'monomer-prod-control:build-1'}
        uri = d.IMAGE_PREFIX + '@sha256:' + 'a' * 64
        with patch.object(d, 'account_check'), patch.object(d, 'load', return_value=record), \
             patch.object(d, 'source_check', return_value=record['sourceCommit']), \
             patch.object(d, 'inspect_image', side_effect=[{'id': record['imageId']}, {'digests': [uri]}]), \
             patch.object(d, 'aws', side_effect=[{'repositories': [{'imageTagMutability': 'IMMUTABLE'}]},
                                                {'imageDetails': [{'imageDigest': 'sha256:' + 'a' * 64}]}]), \
             patch.object(d, 'save'), patch.object(d, 'snapshot') as snapshot, \
             patch.object(d, 'run', return_value='secret') as run:
            d.publish('control')
            snapshot.assert_not_called()
            password = next(call.args[0] for call in run.call_args_list if call.args[0][0] == 'aws')
            self.assertEqual(password, ['aws', 'ecr', 'get-login-password', '--profile', 'operator-prod',
                                        '--region', d.REGION])
            self.assertTrue(all('secret' not in call.args[0] for call in run.call_args_list))

    def test_mutations_require_explicit_flag_before_any_command(self):
        for action in ('publish', 'deploy'):
            with patch.object(d, 'aws') as aws, patch.object(d, 'run') as run:
                with self.assertRaisesRegex(RuntimeError, 'No AWS mutation'):
                    d.main(['--environment', 'dev', action, 'control'])
                aws.assert_not_called()
                run.assert_not_called()

    def test_wrong_account_stops(self):
        with patch.object(d, 'aws', return_value={'Account': '000000000000'}):
            with self.assertRaisesRegex(RuntimeError, 'Wrong AWS account'):
                d.account_check()

    def test_dirty_or_non_main_source_stops(self):
        with patch.object(d, 'run', return_value=' M file'):
            with self.assertRaisesRegex(RuntimeError, 'clean'):
                d.source_check()
        with patch.object(d, 'run', side_effect=['', 'tree-a', 'tree-b']):
            with self.assertRaisesRegex(RuntimeError, 'match local main'):
                d.source_check()

    def test_source_records_the_local_main_commit_not_the_task_commit(self):
        with patch.object(d, 'run', side_effect=['', 'tree-a', 'tree-a', '2' * 40]) as run:
            self.assertEqual(d.source_check(), '2' * 40)
        self.assertEqual(run.call_args.args[0], ['git', 'rev-parse', 'main'])

    def test_foreign_or_unpinned_images_are_rejected(self):
        for uri in (d.IMAGE_PREFIX + ':latest', URI.replace(d.ACCOUNT, '000000000000'), URI + 'x'):
            with self.assertRaises(RuntimeError):
                d.image_uri_check(uri)

    def test_retagged_image_cannot_publish(self):
        with patch.object(d, 'account_check'), patch.object(d, 'load', return_value=RECORD), \
             patch.object(d, 'source_check', return_value=RECORD['sourceCommit']), \
             patch.object(d, 'inspect_image', return_value={'id': 'other'}), patch.object(d, 'aws') as aws:
            with self.assertRaisesRegex(RuntimeError, 'tag changed'):
                d.publish('control')
            aws.assert_not_called()

    def test_publish_verifies_pushed_digest_and_passes_password_only_on_stdin(self):
        answers = [{'repositories': [{'imageTagMutability': 'IMMUTABLE'}]},
                   {'imageDetails': [{'imageDigest': 'sha256:' + 'a' * 64}]}]
        with patch.object(d, 'account_check'), patch.object(d, 'load', return_value=copy.deepcopy(RECORD)), \
             patch.object(d, 'source_check', return_value=RECORD['sourceCommit']), \
             patch.object(d, 'inspect_image', side_effect=[{'id': RECORD['imageId']}, {'digests': [URI]}]), \
             patch.object(d, 'aws', side_effect=answers), patch.object(d, 'save') as save, \
             patch.object(d, 'run', return_value='test-password') as run:
            self.assertEqual(d.publish('control')['imageUri'], URI)
            for call in run.call_args_list:
                self.assertNotIn('test-password', call.args[0])
            login = next(c for c in run.call_args_list if c.args[0][:2] == ['docker', 'login'])
            self.assertEqual(login.kwargs['input_text'], 'test-password')
            self.assertEqual(save.call_args.args[1]['imageUri'], URI)

    def test_deploy_changes_only_selected_image_and_records_previous_images(self):
        with patch.object(d, 'account_check'), patch.object(d, 'load', return_value=copy.deepcopy(RECORD)), \
             patch.object(d, 'source_check', return_value=RECORD['sourceCommit']), \
             patch.object(d, 'snapshot', return_value=states()), patch.object(d, 'save') as save, \
             patch.object(d, 'run') as run, patch.object(d, 'verify', return_value={'infrastructureVerified': True}):
            d.deploy('control')
            command = run.call_args.args[0]
            parameters = command[command.index('--parameters') + 1:]
            self.assertEqual(parameters, [f'{d.STACK}:ControlImage={URI}', '--parameters', f'{d.STACK}:ControlDesiredCount=1'])
            self.assertIn('--exclusively', command)
            self.assertEqual(save.call_args.args[1]['previousImages'], {s: URI for s in d.SERVICES})

    def test_deploy_stops_before_any_change_without_cdk_dependencies(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(d, 'INFRA', Path(temporary)), \
             patch.object(d, 'cdk_toolchain_check', REAL_TOOLCHAIN_CHECK), patch.object(d, 'account_check'), \
             patch.object(d, 'load') as load, patch.object(d, 'snapshot') as snapshot, \
             patch.object(d, 'save') as save, patch.object(d, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'npm ci --ignore-scripts --prefix infra/cloud-dev'):
                d.deploy('control')
            for step in (load, snapshot, save, run):
                step.assert_not_called()
            (Path(temporary) / 'node_modules' / '.bin').mkdir(parents=True)
            for tool in ('tsc', 'cdk'):
                (Path(temporary) / 'node_modules' / '.bin' / tool).touch()
            REAL_TOOLCHAIN_CHECK()

    def verify_fixture(self, state, record=None):
        with patch.object(d, 'account_check'), patch.object(d, 'load', return_value=record or RECORD), \
             patch.object(d, 'snapshot', return_value=state), patch.object(d, 'verify_public_site'):
            return d.verify('control')

    def test_verify_rejects_rolled_back_or_wrong_running_image(self):
        for field in ('image', 'digest'):
            state = states()
            state['control']['tasks'][0]['containers'][0][field] = OTHER if field == 'image' else 'sha256:' + 'b' * 64
            with self.assertRaisesRegex(RuntimeError, 'running image'):
                self.verify_fixture(state)

    def test_verify_rejects_unhealthy_targets_and_missing_targets(self):
        for health in (['unhealthy'], []):
            state = states()
            state['control']['targetHealth'] = health
            with self.assertRaises(RuntimeError):
                self.verify_fixture(state)

    def test_verify_rejects_unrelated_service_image_change(self):
        state = states()
        state['execution']['configuredImage'] = OTHER
        record = RECORD | {'previousImages': {s: URI for s in d.SERVICES}}
        with self.assertRaisesRegex(RuntimeError, 'execution image parameter'):
            self.verify_fixture(state, record)

    def test_healthy_does_not_claim_product_smoke(self):
        self.assertEqual(self.verify_fixture(states())['productSmoke'], 'NOT_RUN')

    def test_public_site_must_be_public_and_noindexed(self):
        import urllib.error
        for status, headers, success in [
            (200, {'X-Robots-Tag': 'noindex, nofollow'}, True),
            (200, {}, False),
            (401, {'WWW-Authenticate': 'Basic realm="dev"', 'X-Robots-Tag': 'noindex'}, False),
            (200, {'WWW-Authenticate': 'Basic realm="dev"', 'X-Robots-Tag': 'noindex'}, False),
            (401, {'WWW-Authenticate': 'Basic realm="dev"'}, False),
            (401, {'X-Robots-Tag': 'noindex'}, False),
        ]:
            error = urllib.error.HTTPError('https://dev.monomer.cc/', status, 'fixture', headers, None)
            with patch.object(d.urllib.request, 'urlopen', side_effect=error):
                if success:
                    d.verify_public_site()
                else:
                    with self.assertRaises(RuntimeError):
                        d.verify_public_site()

    def test_public_site_robots_policy_matches_each_environment(self):
        import urllib.error
        for name in ('dev', 'prod'):
            environment = d.environment_module.load_environment(name)
            for value in ('index, follow', 'noindex, nofollow', 'noindex', 'index', '', 'index, follow, noindex'):
                expected = 'index, follow' if name == 'prod' else 'noindex, nofollow'
                error = urllib.error.HTTPError(environment['publicOrigin'] + '/', 200, 'fixture', {'X-Robots-Tag': value}, None)
                with self.subTest(environment=name, robots=value), patch.object(d, 'ENVIRONMENT', environment), \
                     patch.object(d.urllib.request, 'urlopen', side_effect=error) as open_url:
                    if value == expected:
                        d.verify_public_site()
                    else:
                        with self.assertRaisesRegex(RuntimeError, 'robots header'):
                            d.verify_public_site()
                    self.assertEqual(open_url.call_args.args[0].full_url, environment['publicOrigin'] + '/')

    def test_check_needs_no_local_record_and_rejects_drift(self):
        with patch.object(d, 'account_check'), patch.object(d, 'load') as load, \
             patch.object(d, 'snapshot', return_value=states()), patch.object(d, 'verify_public_site'):
            self.assertTrue(d.check()['infrastructureVerified'])
            load.assert_not_called()
        state = states()
        state['execution']['tasks'][0]['containers'][0]['digest'] = 'sha256:' + 'b' * 64
        with patch.object(d, 'account_check'), patch.object(d, 'snapshot', return_value=state):
            with self.assertRaisesRegex(RuntimeError, 'running image'):
                d.check()

    def test_logs_resolves_current_definition_even_during_deployment(self):
        answers = [
            {'Stacks': [{'StackStatus': 'UPDATE_IN_PROGRESS', 'Outputs': [
                {'OutputKey': 'ExecutionServiceName', 'OutputValue': 'execution-service'}]}]},
            {'services': [{'taskDefinition': 'current-definition'}]},
            {'taskDefinition': {'containerDefinitions': [{'name': 'execution', 'logConfiguration': {
                'logDriver': 'awslogs', 'options': {'awslogs-region': d.REGION,
                    'awslogs-group': 'generated-execution-group'}}}]}}
        ]
        with patch.object(d, 'account_check'), patch.object(d, 'aws', side_effect=answers) as aws, \
             patch.object(d, 'run') as run, patch.object(d, 'load') as load:
            d.logs('execution', '15m', False, 'ERROR')
            command = run.call_args.args[0]
            self.assertEqual(command[:4], ['aws', 'logs', 'tail', 'generated-execution-group'])
            self.assertNotIn('--follow', command)
            self.assertEqual(command[-2:], ['--filter-pattern', 'ERROR'])
            self.assertIn('current-definition', aws.call_args.args)
            load.assert_not_called()

    def test_logs_wrong_account_never_reads_logs(self):
        with patch.object(d, 'aws', return_value={'Account': 'wrong'}) as aws, patch.object(d, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'Wrong AWS account'):
                d.logs('execution', '15m', False, None)
            self.assertEqual(aws.call_count, 1)
            run.assert_not_called()

    def test_build_only_runs_local_tools_and_records_platform(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(d, 'ROOT', Path(tmp)), \
             patch.object(d, 'source_check', return_value='1' * 40), patch.object(d, 'run') as run, \
             patch.object(d, 'aws') as aws, patch.object(d, 'inspect_image', return_value={
                 'id': RECORD['imageId'], 'os': 'linux', 'architecture': 'amd64'}):
            record = d.build('execution', None)
            self.assertNotIn('imageUri', record)
            aws.assert_not_called()
            self.assertEqual(run.call_args.args[0][:2], ['docker', 'build'])
            self.assertIn('execution-service/Dockerfile', run.call_args.args[0])
            self.assertTrue(d.record_path('execution').is_file())


if __name__ == '__main__':
    unittest.main()
