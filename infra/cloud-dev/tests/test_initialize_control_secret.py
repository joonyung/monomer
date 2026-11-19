import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

INFRA = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(INFRA))
spec = importlib.util.spec_from_file_location('initialize_control_secret', INFRA / 'initialize-control-secret.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class InitializeControlSecretTest(unittest.TestCase):
    def environment(self):
        return module.load_environment('prod')

    def responses(self, value='x' * 32, account='444455556666'):
        return [{'Account': '444455556666'}, {'Stacks': [{
            'StackStatus': 'CREATE_COMPLETE', 'Outputs': [{
                'OutputKey': 'ControlRuntimeSecretArn',
                'OutputValue': f'arn:aws:secretsmanager:us-east-1:{account}:secret:runtime-test',
            }],
        }]}, {'SecretString': value}, {}]

    def test_wrong_caller_stops_before_secret_read(self):
        with patch.object(module, 'aws', return_value={'Account': '111122223333'}) as request:
            with self.assertRaises(SystemExit):
                module.initialize(self.environment(), 'prod-view')
            self.assertEqual(request.call_count, 1)

    def test_cross_account_output_stops_before_secret_read(self):
        with patch.object(module, 'aws', side_effect=self.responses(account='111122223333')) as request:
            with self.assertRaises(SystemExit):
                module.initialize(self.environment(), 'prod-admin')
            self.assertEqual(request.call_count, 2)

    def test_initialized_secret_is_never_replaced(self):
        with patch.object(module, 'aws', side_effect=self.responses('{"jwtPrivateKeyPem":"existing"}')) as request:
            with self.assertRaises(SystemExit):
                module.initialize(self.environment(), 'prod-admin')
            self.assertEqual(request.call_count, 3)

    def test_fresh_secret_uses_selected_environment_and_private_temporary_file(self):
        replies = iter(self.responses())
        calls = []
        def request(environment, profile, *arguments):
            calls.append(arguments)
            self.assertEqual(environment['account'], '444455556666')
            self.assertEqual(profile, 'prod-admin')
            if arguments[0:2] == ('secretsmanager', 'put-secret-value'):
                path = Path(arguments[-1].removeprefix('file://'))
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
                self.assertIn('jwtPrivateKeyPem', path.read_text())
            return next(replies)
        with patch.object(module, 'aws', side_effect=request), patch.object(module.subprocess, 'check_output', return_value='test-only-key'), patch('builtins.print'):
            module.initialize(self.environment(), 'prod-admin')
        self.assertIn('MonomerProdStorage', calls[1])
        self.assertFalse(Path(calls[-1][-1].removeprefix('file://')).exists())


if __name__ == '__main__':
    unittest.main()
