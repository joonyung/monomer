#!/usr/bin/env python3
"""Run one approved SQL file as a temporary ECS task in the environment's private Control network."""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

spec = importlib.util.spec_from_file_location('dev_deploy', Path(__file__).with_name('deploy.py'))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
require = deploy.require
WRITE_STATEMENT = re.compile(r'\b(insert|update|delete|merge|alter|drop|truncate|create|grant|revoke|copy|call'
                             r'|read\s+write|default_transaction_read_only)\b|\\(!|gexec|o\b)', re.I)
TASK_PURPOSE_TAG = 'key=MonomerTaskPurpose,value=database-maintenance'


def outputs(stack):
    result = deploy.aws('cloudformation', 'describe-stacks', '--stack-name', deploy.ENVIRONMENT['stackPrefix'] + stack)
    return {x['OutputKey']: x['OutputValue'] for x in result['Stacks'][0]['Outputs']}


def sql_check(mode, sql, approved_sha256):
    require(hashlib.sha256(sql).hexdigest() == approved_sha256, 'SQL does not match the approved SHA-256.')
    if mode == 'preflight':
        text = sql.decode()
        require(re.search(r'\bBEGIN\s+(TRANSACTION\s+)?READ\s+ONLY\b', text, re.I) and not WRITE_STATEMENT.search(text),
                'Preflight SQL must be a read-only transaction.')


def task_definition(mode, sql, bootstrap, storage):
    container = bootstrap['containerDefinitions'][0]
    image = container['image']
    if '@sha256:' not in image:
        repository, tag = image.split('/', 1)[1].rsplit(':', 1)
        digest = deploy.aws('ecr', 'describe-images', '--repository-name', repository,
                            '--image-ids', 'imageTag=' + tag)['imageDetails'][0]['imageDigest']
        image = image.rsplit(':', 1)[0] + '@' + digest
    environment = {x['name']: x['value'] for x in container['environment']}
    require(environment.get('PGHOST') == storage['DatabaseEndpoint'] and environment.get('PGSSLMODE') == 'verify-full',
            'Bootstrap task must target the environment database with verified TLS.')
    environment.update(PGUSER='monomer', PGDATABASE='monomer', PGCONNECT_TIMEOUT='10',
                       SQL_BASE64=base64.b64encode(sql).decode())
    if mode == 'preflight':
        environment['PGOPTIONS'] = '-c default_transaction_read_only=on'
    name = f"{deploy.ENVIRONMENT['resourcePrefix']}-db-{mode}"
    log = container['logConfiguration']
    log['options']['awslogs-stream-prefix'] = name
    definition = {k: bootstrap[k] for k in ('taskRoleArn', 'executionRoleArn', 'networkMode', 'requiresCompatibilities',
                                           'cpu', 'memory', 'runtimePlatform') if k in bootstrap}
    definition['family'] = name
    definition['containerDefinitions'] = [{
        'name': name, 'image': image, 'essential': True, 'entryPoint': ['/bin/sh', '-c'],
        'command': ['printf %s "$SQL_BASE64" | base64 -d | psql -X -v ON_ERROR_STOP=1 -At'],
        'environment': [{'name': k, 'value': v} for k, v in environment.items()],
        'secrets': [{'name': 'PGPASSWORD', 'valueFrom': storage['DatabaseApplicationSecretArn'] + ':password::'}],
        'logConfiguration': log}]
    return definition


def run_task(mode, sql_path, approved_sha256):
    deploy.account_check()
    sql = Path(sql_path).read_bytes()
    sql_check(mode, sql, approved_sha256)
    storage, services = outputs('Storage'), outputs('Services')
    control = deploy.snapshot()['control']
    if mode == 'apply':
        require(deploy.quiesced(control), 'Apply requires Control stopped by deploy.py quiesce.')
    network = deploy.aws('ecs', 'describe-services', '--cluster', deploy.CLUSTER,
                         '--services', services['ControlServiceName'])['services'][0]['networkConfiguration']
    require(network['awsvpcConfiguration']['assignPublicIp'] == 'DISABLED'
            and network['awsvpcConfiguration']['securityGroups'] == [storage['ControlSecurityGroupId']],
            'Control network must be private with the Control security group.')
    bootstrap = deploy.aws('ecs', 'describe-task-definition',
                           '--task-definition', outputs('Compute')['DatabaseBootstrapTaskArn'])['taskDefinition']
    definition = task_definition(mode, sql, bootstrap, storage)
    with tempfile.NamedTemporaryFile('w', suffix='.json') as file:
        file.write(json.dumps(definition))
        file.flush()
        registered = deploy.aws('ecs', 'register-task-definition', '--cli-input-json', 'file://' + file.name)['taskDefinition']['taskDefinitionArn']
    task = None
    try:
        response = deploy.aws('ecs', 'run-task', '--cluster', deploy.CLUSTER, '--task-definition', registered,
                              '--launch-type', 'FARGATE', '--network-configuration', json.dumps(network),
                              '--tags', TASK_PURPOSE_TAG, '--started-by', definition['family'])
        require(not response.get('failures'), f"RunTask failed: {response.get('failures')}")
        task = response['tasks'][0]['taskArn']
        for _ in range(240):
            state = deploy.aws('ecs', 'describe-tasks', '--cluster', deploy.CLUSTER, '--tasks', task)['tasks'][0]
            if state['lastStatus'] == 'STOPPED':
                break
            time.sleep(5)
        else:
            raise RuntimeError('Database task exceeded its 20-minute deadline; inspect it before any retry.')
        exit_code = state['containers'][0].get('exitCode')
        require(exit_code is not None, f"Database task did not start: {state.get('stoppedReason')}")
        log = definition['containerDefinitions'][0]['logConfiguration']['options']
        stream = f"{log['awslogs-stream-prefix']}/{definition['family']}/{task.rsplit('/', 1)[1]}"
        events = deploy.aws('logs', 'get-log-events', '--log-group-name', log['awslogs-group'],
                            '--log-stream-name', stream, '--start-from-head')['events']
        result = {'mode': mode, 'sha256': approved_sha256, 'task': task, 'definition': registered,
                  'exitCode': exit_code, 'output': [e['message'] for e in events]}
        require(exit_code == 0, 'SQL failed: ' + '\n'.join(result['output'][-20:]))
        return result
    finally:
        try:
            if task and deploy.aws('ecs', 'describe-tasks', '--cluster', deploy.CLUSTER, '--tasks', task)['tasks'][0]['lastStatus'] != 'STOPPED':
                deploy.aws('ecs', 'stop-task', '--cluster', deploy.CLUSTER, '--task', task, '--reason', 'Database task finished')
        finally:
            deploy.aws('ecs', 'deregister-task-definition', '--task-definition', registered)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--environment', choices=('dev', 'prod'), required=True)
    parser.add_argument('--profile')
    parser.add_argument('mode', choices=('preflight', 'apply'))
    parser.add_argument('sql')
    parser.add_argument('--sha256', required=True, help='SHA-256 of the SQL file the user approved.')
    parser.add_argument('--apply', action='store_true', help='Requires applicable user authorization; flag alone is not authorization.')
    args = parser.parse_args(argv)
    deploy.configure(args.environment, args.profile)
    require(args.apply, 'No AWS mutation without --apply and applicable user authorization.')
    print(json.dumps(run_task(args.mode, args.sql, args.sha256), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f'database task stopped: {error}', file=sys.stderr)
        sys.exit(1)
