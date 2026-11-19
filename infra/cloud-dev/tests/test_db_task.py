import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('db_task', Path(__file__).parents[1] / 'db_task.py')
t = importlib.util.module_from_spec(spec)
spec.loader.exec_module(t)
READ_ONLY = b'\\set ON_ERROR_STOP on\nBEGIN READ ONLY;\nSELECT count(*) FROM dashboard.dashboards;\nCOMMIT;\n'
CUTOVER = b'BEGIN;\nUPDATE dashboard.dashboards SET definition = definition;\nCOMMIT;\n'
STORAGE = {'DatabaseEndpoint': 'db.internal', 'DatabaseApplicationSecretArn': 'arn:secret', 'ControlSecurityGroupId': 'sg-1'}
BOOTSTRAP = {'taskRoleArn': 'task-role', 'executionRoleArn': 'execution-role', 'networkMode': 'awsvpc',
             'requiresCompatibilities': ['FARGATE'], 'cpu': '256', 'memory': '512',
             'containerDefinitions': [{'image': 'registry/repo@sha256:' + 'a' * 64,
                                       'environment': [{'name': 'PGHOST', 'value': 'db.internal'},
                                                       {'name': 'PGSSLMODE', 'value': 'verify-full'}],
                                       'logConfiguration': {'logDriver': 'awslogs', 'options': {'awslogs-group': 'group'}}}]}
NETWORK = {'awsvpcConfiguration': {'assignPublicIp': 'DISABLED', 'securityGroups': ['sg-1'], 'subnets': ['s']}}


def sha(sql):
    return hashlib.sha256(sql).hexdigest()


class DatabaseTaskTests(unittest.TestCase):
    def setUp(self):
        t.deploy.configure('dev')
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)

    def sql_file(self, sql):
        path = Path(self.temporary.name) / 'task.sql'
        path.write_bytes(sql)
        return str(path)

    def test_sql_must_match_approved_hash_and_preflight_must_be_read_only(self):
        with self.assertRaisesRegex(RuntimeError, 'approved SHA-256'):
            t.sql_check('apply', CUTOVER, sha(READ_ONLY))
        with self.assertRaisesRegex(RuntimeError, 'read-only'):
            t.sql_check('preflight', CUTOVER, sha(CUTOVER))
        for unsafe in (b'BEGIN;\nSELECT 1;\nCOMMIT;\n', b'BEGIN READ ONLY;\nSET TRANSACTION READ WRITE;\nCOMMIT;\n',
                       b'BEGIN READ ONLY;\nSELECT 1 \\gexec\nCOMMIT;\n', b'BEGIN READ ONLY;\n\\! touch x\nCOMMIT;\n'):
            with self.assertRaisesRegex(RuntimeError, 'read-only'):
                t.sql_check('preflight', unsafe, sha(unsafe))
        t.sql_check('preflight', READ_ONLY, sha(READ_ONLY))
        t.sql_check('apply', CUTOVER, sha(CUTOVER))

    def test_existing_cutover_preflights_are_accepted(self):
        for path in (Path(__file__).parents[1] / 'cutovers').glob('*preflight*.sql'):
            sql = path.read_bytes()
            if b'READ ONLY' in sql.upper():
                t.sql_check('preflight', sql, sha(sql))

    def test_preflight_session_is_read_only_at_the_database(self):
        preflight = t.task_definition('preflight', READ_ONLY, BOOTSTRAP, STORAGE)['containerDefinitions'][0]
        apply = t.task_definition('apply', CUTOVER, BOOTSTRAP, STORAGE)['containerDefinitions'][0]
        self.assertIn({'name': 'PGOPTIONS', 'value': '-c default_transaction_read_only=on'}, preflight['environment'])
        self.assertNotIn('PGOPTIONS', {x['name'] for x in apply['environment']})

    def test_task_definition_uses_environment_family_private_tls_database_and_secret_reference(self):
        definition = t.task_definition('apply', CUTOVER, BOOTSTRAP, STORAGE)
        container = definition['containerDefinitions'][0]
        self.assertEqual(definition['family'], 'monomer-dev-db-apply')
        self.assertEqual(container['secrets'], [{'name': 'PGPASSWORD', 'valueFrom': 'arn:secret:password::'}])
        environment = {x['name']: x['value'] for x in container['environment']}
        self.assertEqual((environment['PGUSER'], environment['PGDATABASE'], environment['PGSSLMODE']), ('monomer', 'monomer', 'verify-full'))
        self.assertNotIn('PGPASSWORD', environment)
        other = {**BOOTSTRAP, 'containerDefinitions': [{**BOOTSTRAP['containerDefinitions'][0],
                 'environment': [{'name': 'PGHOST', 'value': 'elsewhere'}, {'name': 'PGSSLMODE', 'value': 'verify-full'}]}]}
        with self.assertRaisesRegex(RuntimeError, 'verified TLS'):
            t.task_definition('apply', CUTOVER, other, STORAGE)

    def run_fixture(self, mode, sql, control, exit_code=0):
        calls = []
        control = {'serviceName': 'control', **control}

        def aws(*args):
            calls.append(args)
            action = args[:2]
            if action == ('ecs', 'list-tasks'):
                return {'taskArns': []}
            if action == ('ecs', 'describe-services'):
                return {'services': [{'networkConfiguration': NETWORK}]}
            if action == ('ecs', 'describe-task-definition'):
                return {'taskDefinition': BOOTSTRAP}
            if action == ('ecs', 'register-task-definition'):
                return {'taskDefinition': {'taskDefinitionArn': 'definition:1'}}
            if action == ('ecs', 'run-task'):
                return {'tasks': [{'taskArn': 'cluster/task-1'}]}
            if action == ('ecs', 'describe-tasks'):
                return {'tasks': [{'lastStatus': 'STOPPED', 'containers': [{'exitCode': exit_code}]}]}
            if action == ('logs', 'get-log-events'):
                return {'events': [{'message': 'COMMIT'}]}
            return {}
        outputs = {'Storage': STORAGE, 'Services': {'ControlServiceName': 'control'}, 'Compute': {'DatabaseBootstrapTaskArn': 'bootstrap'}}
        with patch.object(t.deploy, 'account_check'), patch.object(t, 'outputs', side_effect=outputs.get), \
             patch.object(t.deploy, 'snapshot', return_value={'control': control}), \
             patch.object(t.deploy, 'aws', side_effect=aws), patch.object(t.time, 'sleep'):
            try:
                return t.run_task(mode, self.sql_file(sql), sha(sql)), calls
            except RuntimeError as error:
                return error, calls

    def test_apply_requires_quiesced_control_before_registering_any_task(self):
        running = {'desired': 1, 'running': 1, 'pending': 0, 'tasks': [{}]}
        error, calls = self.run_fixture('apply', CUTOVER, running)
        self.assertRegex(str(error), 'quiesce')
        self.assertFalse(any(call[:2] == ('ecs', 'register-task-definition') for call in calls))

    def test_task_is_tagged_and_its_definition_is_deregistered_even_when_sql_fails(self):
        stopped = {'desired': 0, 'running': 0, 'pending': 0, 'tasks': []}
        result, calls = self.run_fixture('apply', CUTOVER, stopped)
        self.assertEqual(result['exitCode'], 0)
        run = next(call for call in calls if call[:2] == ('ecs', 'run-task'))
        self.assertEqual(run[run.index('--tags') + 1], t.TASK_PURPOSE_TAG)
        self.assertIn(('ecs', 'deregister-task-definition', '--task-definition', 'definition:1'), calls)
        error, calls = self.run_fixture('preflight', READ_ONLY, {'desired': 1, 'running': 1, 'pending': 0, 'tasks': [{}]}, exit_code=3)
        self.assertRegex(str(error), 'SQL failed')
        self.assertIn(('ecs', 'deregister-task-definition', '--task-definition', 'definition:1'), calls)
        error, calls = self.run_fixture('apply', CUTOVER, stopped, exit_code=None)
        self.assertRegex(str(error), 'did not start')
        self.assertFalse(any(call[:2] == ('logs', 'get-log-events') for call in calls))
        self.assertIn(('ecs', 'deregister-task-definition', '--task-definition', 'definition:1'), calls)


if __name__ == '__main__':
    unittest.main()
