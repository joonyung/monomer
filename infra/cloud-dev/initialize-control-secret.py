#!/usr/bin/env python3
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile

from environment import load_environment


def aws(environment, profile, *arguments):
    return json.loads(subprocess.check_output(
        ['aws', *arguments, '--region', environment['region'], '--output', 'json', '--no-cli-pager',
         *(['--profile', profile] if profile else [])], text=True,
    ))


def initialize(environment, profile):
    def request(*arguments):
        return aws(environment, profile, *arguments)
    if request('sts', 'get-caller-identity')['Account'] != environment['account']:
        raise SystemExit('unexpected AWS account')
    stack = request('cloudformation', 'describe-stacks', '--stack-name', environment['stackPrefix'] + 'Storage')['Stacks'][0]
    if stack['StackStatus'] not in ('CREATE_COMPLETE', 'UPDATE_COMPLETE'):
        raise SystemExit('storage deployment must be complete')
    outputs = {item['OutputKey']: item['OutputValue'] for item in stack['Outputs']}
    secret_arn = outputs['ControlRuntimeSecretArn']
    if not secret_arn.startswith(f"arn:aws:secretsmanager:{environment['region']}:{environment['account']}:secret:"):
        raise SystemExit('unexpected runtime secret account or region')
    current = request('secretsmanager', 'get-secret-value', '--secret-id', secret_arn)['SecretString']
    if len(current) != 32 or '\n' in current:
        raise SystemExit('refusing to replace an initialized or unexpected secret')
    try:
        json.loads(current)
    except json.JSONDecodeError:
        pass
    else:
        raise SystemExit('refusing to replace structured secret data')
    private_key = subprocess.check_output(
        ['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048'],
        stderr=subprocess.DEVNULL, text=True,
    )
    value = {
        'jwtPrivateKeyPem': private_key,
        'credentialVaultKeyBase64': base64.b64encode(os.urandom(32)).decode(),
    }
    with tempfile.TemporaryDirectory(prefix='monomer-secret-') as directory:
        path = Path(directory) / 'value.json'
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as handle:
            json.dump(value, handle)
        request('secretsmanager', 'put-secret-value', '--secret-id', secret_arn, '--secret-string', 'file://' + str(path))
    print('Control runtime keys initialized; values were not printed.')


def main():
    parser = argparse.ArgumentParser(description='Initialize only a fresh environment runtime secret; requires specific approval.')
    parser.add_argument('--environment', required=True, choices=['dev', 'prod'])
    parser.add_argument('--profile')
    parser.add_argument('--apply', required=True, action='store_true')
    args = parser.parse_args()
    initialize(load_environment(args.environment), args.profile)


if __name__ == '__main__':
    main()
