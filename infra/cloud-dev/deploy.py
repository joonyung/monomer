#!/usr/bin/env python3
"""Explicit environment-selected build, publish, deploy, and read-only verification commands."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
from functools import wraps
import json
import importlib.util
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
INFRA = ROOT / 'infra/cloud-dev'
spec = importlib.util.spec_from_file_location('deployment_environment', INFRA / 'environment.py')
environment_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(environment_module)
ENVIRONMENT = None
PROFILE = None


def configure(environment, profile=None):
    global ENVIRONMENT, PROFILE, ACCOUNT, REGION, REGISTRY, REPOSITORY, IMAGE_PREFIX, STACK, CLUSTER
    ENVIRONMENT = environment_module.load_environment(environment)
    PROFILE = profile
    ACCOUNT, REGION = ENVIRONMENT['account'], ENVIRONMENT['region']
    REGISTRY = f'{ACCOUNT}.dkr.ecr.{REGION}.amazonaws.com'
    REPOSITORY = f'cdk-hnb659fds-container-assets-{ACCOUNT}-{REGION}'
    IMAGE_PREFIX = f'{REGISTRY}/{REPOSITORY}'
    STACK = ENVIRONMENT['stackPrefix'] + 'Services'
    CLUSTER = ENVIRONMENT['resourcePrefix']


def profile_arguments():
    require(ENVIRONMENT is not None, 'Select an explicit dev or prod environment.')
    return ['--profile', PROFILE] if PROFILE else []


def cdk_arguments():
    return ['-c', f"environment={ENVIRONMENT['name']}", *profile_arguments()]


SERVICES = ('control', 'execution')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


@contextmanager
def phase(name):
    started = time.monotonic()
    def emit(status, elapsed=None):
        event = {'event': 'phase', 'phase': name, 'status': status,
                 'at': datetime.now(timezone.utc).isoformat(timespec='milliseconds')}
        if elapsed is not None:
            event['elapsedSeconds'] = round(elapsed, 3)
        print(json.dumps(event), file=sys.stderr, flush=True)
    emit('started')
    try:
        yield
    except BaseException:
        emit('failed', time.monotonic() - started)
        raise
    else:
        emit('succeeded', time.monotonic() - started)


def timed(name):
    def decorate(operation):
        @wraps(operation)
        def measured(*args, **kwargs):
            with phase(name):
                return operation(*args, **kwargs)
        return measured
    return decorate


def run(args, *, cwd=ROOT, capture=True, input_text=None):
    result = subprocess.run(args, cwd=cwd, text=True, input=input_text,
                            stdout=subprocess.PIPE if capture else None, check=True)
    return result.stdout.strip() if capture else None


def aws(*args):
    return json.loads(run(['aws', *args, *profile_arguments(), '--region', REGION, '--output', 'json', '--no-cli-pager']))


def account_check():
    require(aws('sts', 'get-caller-identity')['Account'] == ACCOUNT, 'Wrong AWS account; stopped.')


def cdk_toolchain_check():
    require(all((INFRA / 'node_modules' / '.bin' / tool).exists() for tool in ('tsc', 'cdk')),
            'CDK dependencies are missing; run npm ci --ignore-scripts --prefix infra/cloud-dev first.')


def source_check():
    require(not run(['git', 'status', '--porcelain']), 'Source must be clean; use an isolated main worktree.')
    require(run(['git', 'rev-parse', 'HEAD^{tree}']) == run(['git', 'rev-parse', 'main^{tree}']),
            'Source tree must match local main.')
    return run(['git', 'rev-parse', 'main'])


def update_deployment_tag(component, source):
    require(component in (*SERVICES, 'static'), 'Unknown deployment component.')
    require(isinstance(source, str) and re.fullmatch(r'[0-9a-f]{40}', source),
            'Deployment source is unknown; no tag was updated.')
    require(run(['git', 'rev-parse', '--verify', f'{source}^{{commit}}']) == source,
            'Deployment source must exist as a local commit.')
    name = f"{'🟢' if ENVIRONMENT['name'] == 'dev' else '🟣'}deployed/{ENVIRONMENT['name']}/{component}"
    run(['git', 'update-ref', '--no-deref', '-m', 'Record observed deployment source',
         f'refs/tags/{name}', source])
    return {'name': name, 'sourceCommit': source, 'status': 'updated'}


def mark_deployment(result, component, source):
    try:
        tag = update_deployment_tag(component, source)
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        tag = {'name': f"{'🟢' if ENVIRONMENT['name'] == 'dev' else '🟣'}deployed/{ENVIRONMENT['name']}/{component}", 'sourceCommit': source,
               'status': 'failed', 'error': str(error)}
        print('Deployment succeeded, but its local Git tag was not updated; use sync-tag.', file=sys.stderr)
    return result | {'deploymentTag': tag}


def sync_tag(service):
    account_check()
    states = snapshot()
    validate_states(states, {name: state['configuredImage'] for name, state in states.items()})
    image = states[service]['configuredImage']
    details = aws('ecr', 'describe-images', '--repository-name', REPOSITORY,
                  '--image-ids', f"imageDigest={image.split('@')[1]}")['imageDetails']
    require(len(details) == 1 and details[0]['imageDigest'] == image.split('@')[1],
            'Deployed image lookup differs.')
    pattern = rf'([0-9a-f]{{12}})-[0-9a-f]{{12}}-{service}'
    prefixes = {match[1] for tag in details[0].get('imageTags', [])
                if (match := re.fullmatch(pattern, tag))}
    require(len(prefixes) == 1, 'Deployed image has no unambiguous source tag; no local tag was updated.')
    source = run(['git', 'rev-parse', '--verify', f'{next(iter(prefixes))}^{{commit}}'])
    current = snapshot()
    validate_states(current, {name: state['configuredImage'] for name, state in current.items()})
    require(current[service]['configuredImage'] == image,
            'Deployed image changed during lookup; no local tag was updated.')
    return {'imageUri': image, 'deploymentTag': update_deployment_tag(service, source)}


def record_path(service):
    return ROOT / f".monomer/{ENVIRONMENT['name']}-deploy" / f'{service}.json'


def save(service, record):
    path = record_path(service)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(record, indent=2) + '\n')


def load(service):
    record = json.loads(record_path(service).read_text())
    require(all(record.get(key) == value for key, value in record_identity().items()),
            'Build record environment/account/region mismatch; rebuild.')
    require(record['service'] == service, 'Build record service mismatch.')
    require(re.fullmatch(r'[0-9a-f]{40}', record['sourceCommit']), 'Invalid source commit.')
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', record['imageId']), 'Invalid local image ID.')
    require(re.fullmatch(re.escape(ENVIRONMENT['resourcePrefix']) + '-' + service + r':[a-z0-9-]+', record['localTag']), 'Invalid local tag.')
    if 'imageUri' in record:
        image_uri_check(record['imageUri'])
    if 'previousImages' in record:
        require(set(record['previousImages']) == set(SERVICES), 'Previous image service scope mismatch.')
        for uri in record['previousImages'].values():
            image_uri_check(uri)
    return record


def image_uri_check(uri):
    require(re.fullmatch(re.escape(IMAGE_PREFIX) + r'@sha256:[0-9a-f]{64}', uri),
            'Image must be digest-pinned in the selected environment repository.')


def inspect_image(tag):
    details = json.loads(run(['docker', 'image', 'inspect', tag]))[0]
    return {'id': details['Id'], 'os': details['Os'], 'architecture': details['Architecture'],
            'digests': details.get('RepoDigests', [])}


def record_identity():
    return {'environment': ENVIRONMENT['name'], 'account': ACCOUNT, 'region': REGION}


@timed('backend build')
def build(service, metricflow_repository):
    source = source_check()
    tag = f"{ENVIRONMENT['resourcePrefix']}-{service}:{source[:12]}-{uuid.uuid4().hex[:12]}"
    dockerfile = f'{service}-service/Dockerfile'
    command = ['docker', 'build', '--platform', 'linux/amd64', '-f', dockerfile, '-t', tag]
    if service == 'control':
        require(metricflow_repository is not None, 'Control requires --metricflow-repository from the bundle bootstrap.')
        path = Path(metricflow_repository).resolve()
        require(path.name == 'repository' and path.is_dir(), 'Expected verified MetricFlow repository directory.')
        verified = run([sys.executable, 'scripts/bootstrap-metricflow-bundle.py',
                        '--contract', str(ROOT / 'release/contracts/metricflow-artifact-set.json'),
                        '--cache-root', str(path.parent.parent)])
        require(Path(verified).resolve() == path, 'MetricFlow repository does not match the activated bundle.')
        command += ['--build-context', f'metricflow-repository={path}']
    with phase(f'{service} Docker build'):
        run(command + ['.'], capture=False)
    require(source_check() == source, 'Source changed during build; rebuild before publishing.')
    image = inspect_image(tag)
    require(image['os'] == 'linux' and image['architecture'] == 'amd64', 'Unexpected image platform.')
    record = record_identity() | {'service': service, 'sourceCommit': source, 'localTag': tag, 'imageId': image['id']}
    save(service, record)
    return record


@timed('backend publish')
def publish(service):
    account_check()
    record = load(service)
    require(source_check() == record['sourceCommit'], 'Build record is stale for this checkout.')
    image = inspect_image(record['localTag'])
    require(image['id'] == record['imageId'], 'Local image tag changed after build.')
    repository = aws('ecr', 'describe-repositories', '--repository-names', REPOSITORY)['repositories'][0]
    require(repository['imageTagMutability'] == 'IMMUTABLE', 'Expected immutable ECR repository.')
    password = run(['aws', 'ecr', 'get-login-password', *profile_arguments(), '--region', REGION])
    run(['docker', 'login', '--username', 'AWS', '--password-stdin', REGISTRY], input_text=password)
    remote_tag = f"{IMAGE_PREFIX}:{record['localTag'].split(':', 1)[1]}-{service}"
    run(['docker', 'tag', record['imageId'], remote_tag])
    with phase(f'{service} ECR push'):
        run(['docker', 'push', remote_tag], capture=False)
    digest = aws('ecr', 'describe-images', '--repository-name', REPOSITORY,
                 '--image-ids', f"imageTag={remote_tag.split(':', 1)[1]}")['imageDetails'][0]['imageDigest']
    uri = f'{IMAGE_PREFIX}@{digest}'
    image_uri_check(uri)
    require(uri in inspect_image(remote_tag)['digests'], 'ECR digest does not match the locally pushed image.')
    record['imageUri'] = uri
    save(service, record)
    return record


def stack_details():
    stack = aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]
    require(stack['StackStatus'] in ('CREATE_COMPLETE', 'UPDATE_COMPLETE', 'UPDATE_ROLLBACK_COMPLETE'),
            'Stack is not stable; inspect Events before continuing.')
    return stack


def snapshot():
    stack = stack_details()
    outputs = {x['OutputKey']: x['OutputValue'] for x in stack['Outputs']}
    parameters = {x['ParameterKey']: x['ParameterValue'] for x in stack['Parameters']}
    names = [outputs[s.title() + 'ServiceName'] for s in SERVICES]
    response = aws('ecs', 'describe-services', '--cluster', CLUSTER, '--services', *names)
    require(not response.get('failures'), 'ECS service lookup failed.')
    by_name = {s['serviceName']: s for s in response['services']}
    result = {}
    for service, name in zip(SERVICES, names):
        current = by_name[name]
        task_ids = aws('ecs', 'list-tasks', '--cluster', CLUSTER, '--service-name', name)['taskArns']
        tasks = aws('ecs', 'describe-tasks', '--cluster', CLUSTER, '--tasks', *task_ids) if task_ids else {'tasks': []}
        require(not tasks.get('failures'), 'ECS task lookup failed.')
        health = []
        for lb in current.get('loadBalancers', []):
            targets = aws('elbv2', 'describe-target-health', '--target-group-arn', lb['targetGroupArn'])['TargetHealthDescriptions']
            health += [t['TargetHealth']['State'] for t in targets]
        result[service] = {'serviceName': name, 'desired': current['desiredCount'],
            'running': current['runningCount'], 'pending': current['pendingCount'],
            'taskDefinition': current['taskDefinition'],
            'deployments': [{'taskDefinition': deployment['taskDefinition'], 'status': deployment['status'],
                             'rolloutState': deployment.get('rolloutState')} for deployment in current['deployments']],
            'rollouts': [d.get('rolloutState') for d in current['deployments']],
            'configuredImage': parameters[service.title() + 'Image'], 'targetHealth': health,
            'tasks': [{'id': t['taskArn'], 'status': t['lastStatus'], 'health': t.get('healthStatus'),
                       'containers': [{'name': c['name'], 'image': c['image'], 'digest': c.get('imageDigest'),
                                       'health': c.get('healthStatus')} for c in t['containers']]} for t in tasks['tasks']]}
    return result


def healthy(state):
    return (state['desired'] == state['running'] == 1 and state['pending'] == 0
            and state['rollouts'] == ['COMPLETED'] and len(state['tasks']) == 1
            and all(t['status'] == 'RUNNING' and t['health'] == 'HEALTHY' for t in state['tasks'])
            and all(c['health'] == 'HEALTHY' for t in state['tasks'] for c in t['containers'])
            and all(h == 'healthy' for h in state['targetHealth']))


def verify_public_site():
    request = urllib.request.Request(ENVIRONMENT['publicOrigin'] + '/', method='GET')
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status, headers = response.status, response.headers
    except urllib.error.HTTPError as response:
        status, headers = response.code, response.headers
        response.close()
    require(status == 200 and not headers.get('WWW-Authenticate'),
            'Public-site must be readable without authentication.')
    expected_robots = {'index', 'follow'} if ENVIRONMENT['name'] == 'prod' else {'noindex', 'nofollow'}
    actual_robots = {directive.strip().lower() for directive in headers.get('X-Robots-Tag', '').split(',')}
    require(actual_robots == expected_robots, 'Public-site robots header does not match the environment policy.')


def logs(service, since, follow, filter_pattern):
    account_check()
    stack = aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]
    outputs = {item['OutputKey']: item['OutputValue'] for item in stack['Outputs']}
    response = aws('ecs', 'describe-services', '--cluster', CLUSTER, '--services',
                   outputs[service.title() + 'ServiceName'])
    require(not response.get('failures') and len(response['services']) == 1, 'ECS service lookup failed.')
    definition = aws('ecs', 'describe-task-definition', '--task-definition',
                     response['services'][0]['taskDefinition'])['taskDefinition']
    containers = [c for c in definition['containerDefinitions'] if c['name'] == service]
    require(len(containers) == 1, 'Expected one service container.')
    configuration = containers[0].get('logConfiguration', {})
    require(configuration.get('logDriver') == 'awslogs', 'Expected CloudWatch logging.')
    options = configuration['options']
    require(options.get('awslogs-region') == REGION, 'Unexpected log region.')
    command = ['aws', 'logs', 'tail', options['awslogs-group'], '--region', REGION,
               *profile_arguments(), '--since', since, '--format', 'short', '--no-cli-pager']
    if follow:
        command.append('--follow')
    if filter_pattern:
        command += ['--filter-pattern', filter_pattern]
    run(command, capture=False)


@timed('backend health check')
def check():
    account_check()
    states = snapshot()
    validate_states(states, {name: state['configuredImage'] for name, state in states.items()})
    return {'infrastructureVerified': True, 'productSmoke': 'NOT_RUN', 'services': states}


@timed('backend deployment verification')
def verify(service, from_quiesced=False):
    require(not from_quiesced or service == 'execution',
            'Verification from quiesced is only valid for Execution while Control remains stopped.')
    account_check()
    record = load(service)
    image_uri_check(record['imageUri'])
    require(not from_quiesced or 'previousImages' in record,
            'Verification from quiesced requires the previous deployment images.')
    states = snapshot()
    expected = record.get('previousImages', {s: states[s]['configuredImage'] for s in SERVICES}) | {service: record['imageUri']}
    validate_states(states, expected, control_quiesced=from_quiesced)
    return {'infrastructureVerified': True, 'productSmoke': 'NOT_RUN', 'sourceCommit': record['sourceCommit'], 'services': states}


def validate_states(states, expected, control_quiesced=False):
    for name, state in states.items():
        image_uri_check(expected[name])
        require(state['configuredImage'] == expected[name], f'{name} image parameter differs from the deployment scope.')
        if control_quiesced and name == 'control':
            require(quiesced(state), 'Expected a fully stopped Control throughout Execution deployment.')
            continue
        require(healthy(state), f'{name} is not stable and healthy.')
        require(name == 'execution' or bool(state['targetHealth']), f'{name} has no ALB targets.')
        for task in state['tasks']:
            require(len(task['containers']) == 1, f'{name} has unexpected containers.')
            container = task['containers'][0]
            require(container['image'] == expected[name] and container['digest'] == expected[name].split('@')[1],
                    f'{name} running image differs from expected digest.')
    verify_public_site()


def quiesced(state):
    """Stopped means no desired, running, or pending tasks and every recently stopped task fully STOPPED,
    because draining tasks leave the RUNNING list while still holding database connections."""
    if state['desired'] or state['running'] or state['pending'] or state['tasks']:
        return False
    stopping = aws('ecs', 'list-tasks', '--cluster', CLUSTER, '--service-name', state['serviceName'],
                   '--desired-status', 'STOPPED')['taskArns']
    response = aws('ecs', 'describe-tasks', '--cluster', CLUSTER, '--tasks', *stopping) if stopping else {'tasks': []}
    require(not response.get('failures') and len(response['tasks']) == len(stopping),
            'Quiesced task lookup failed.')
    return all(task['lastStatus'] == 'STOPPED' for task in response['tasks'])


def quiesce(service, snapshot_id):
    require(service == 'control', 'Only Control is quiesced for database cutovers.')
    database = ENVIRONMENT['resourcePrefix']
    require(snapshot_id.startswith(database + '-'), 'Snapshot name must start with the environment prefix.')
    account_check()
    require(all(healthy(s) for s in snapshot().values()), 'Services must be stable before quiescing Control.')
    aws('rds', 'create-db-snapshot', '--db-instance-identifier', database, '--db-snapshot-identifier', snapshot_id)
    run(['aws', 'rds', 'wait', 'db-snapshot-available', '--db-snapshot-identifier', snapshot_id,
         *profile_arguments(), '--region', REGION], capture=False)
    created = aws('rds', 'describe-db-snapshots', '--db-snapshot-identifier', snapshot_id)['DBSnapshots'][0]
    require(created['Status'] == 'available' and created['DBInstanceIdentifier'] == database,
            'Database snapshot is not available.')
    states = snapshot()
    require(all(healthy(s) for s in states.values()), 'Services changed while the snapshot was created; stopped.')
    aws('ecs', 'update-service', '--cluster', CLUSTER, '--service', states['control']['serviceName'], '--desired-count', '0')
    for _ in range(120):
        control = snapshot()['control']
        if quiesced(control):
            return {'snapshot': snapshot_id, 'control': control}
        time.sleep(5)
    raise RuntimeError('Control did not stop; do not apply database changes.')


@timed('backend deployment')
def deploy(service, from_quiesced=False):
    account_check()
    cdk_toolchain_check()
    record = load(service)
    image_uri_check(record['imageUri'])
    require(source_check() == record['sourceCommit'], 'Checkout changed since build; re-prepare before deploy.')
    states = snapshot()
    if from_quiesced:
        require(service in SERVICES and quiesced(states['control']) and healthy(states['execution']),
                'Deploy from quiesced needs a stopped Control and healthy Execution.')
        validate_states(states, {name: state['configuredImage'] for name, state in states.items()},
                        control_quiesced=True)
    else:
        require(all(healthy(s) for s in states.values()), 'Services must be stable before redeployment.')
    record['previousImages'] = {name: state['configuredImage'] for name, state in states.items()}
    save(service, record)
    with phase('backend CDK build'):
        run(['npm', 'run', 'build'], cwd=INFRA, capture=False)
    def update_stack(image_uri, control_count, no_rollback=False):
        require(source_check() == record['sourceCommit'], 'Source changed before deployment.')
        with phase(f'{service} CDK deploy and ECS stabilization'):
            run(['npx', '--no-install', 'cdk', 'deploy', STACK, *cdk_arguments(), '--exclusively', '--method', 'direct',
                 '--require-approval', 'never', *(['--no-rollback'] if no_rollback else []),
                 '--parameters', f'{STACK}:{service.title()}Image={image_uri}',
                 '--parameters', f'{STACK}:ControlDesiredCount={control_count}'], cwd=INFRA, capture=False, input_text='')

    if from_quiesced:
        update_stack(states[service]['configuredImage'], 0, no_rollback=True)
        stopped = snapshot()
        validate_states(stopped, record['previousImages'], control_quiesced=True)
        update_stack(record['imageUri'], 0)
        if service == 'control':
            installed = snapshot()
            validate_states(installed, record['previousImages'] | {'control': record['imageUri']},
                            control_quiesced=True)
            control = installed['control']
            require(control['deployments'] == [{'taskDefinition': control['taskDefinition'], 'status': 'PRIMARY',
                                               'rolloutState': 'COMPLETED'}],
                    'Stopped Control must have only the installed completed deployment before starting.')
            definition = aws('ecs', 'describe-task-definition', '--task-definition', control['taskDefinition'])['taskDefinition']
            require(definition['taskDefinitionArn'] == control['taskDefinition']
                    and len(definition['containerDefinitions']) == 1
                    and definition['containerDefinitions'][0]['name'] == 'control'
                    and definition['containerDefinitions'][0]['image'] == record['imageUri'],
                    'Stopped Control task definition differs from the selected image; not starting.')
            update_stack(record['imageUri'], 1)
    else:
        update_stack(record['imageUri'], 1)
    result = verify(service, from_quiesced=from_quiesced and service == 'execution')
    return mark_deployment(result, service, record['sourceCommit'])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--environment', choices=('dev', 'prod'), required=True)
    parser.add_argument('--profile')
    sub = parser.add_subparsers(dest='action', required=True)
    sub.add_parser('status')
    sub.add_parser('check')
    log_command = sub.add_parser('logs')
    log_command.add_argument('service', choices=SERVICES)
    log_command.add_argument('--since', default='15m')
    log_command.add_argument('--follow', action='store_true')
    log_command.add_argument('--filter-pattern')
    for action in ('build', 'publish', 'deploy', 'verify', 'sync-tag'):
        command = sub.add_parser(action)
        command.add_argument('service', choices=SERVICES)
        if action in ('publish', 'deploy'):
            command.add_argument('--apply', action='store_true', help='Requires standing dev authorization or explicit user approval; flag alone is not authorization.')
        if action in ('deploy', 'verify'):
            command.add_argument('--from-quiesced', action='store_true',
                                 help='Deploy with fully stopped Control during an approved cutover; verify supports Execution only.')
        if action == 'build':
            command.add_argument('--metricflow-repository')
    quiesce_command = sub.add_parser('quiesce', help='Snapshot the database, then stop Control for an approved database cutover.')
    quiesce_command.add_argument('service', choices=('control',))
    quiesce_command.add_argument('--snapshot', required=True)
    quiesce_command.add_argument('--apply', action='store_true', help='Requires explicit user approval of the cutover.')
    args = parser.parse_args(argv)
    configure(args.environment, args.profile)
    if args.action in ('publish', 'deploy', 'quiesce'):
        require(args.apply, 'No AWS mutation without --apply and applicable user authorization.')
    if args.action == 'status':
        account_check()
        result = snapshot()
    elif args.action == 'logs':
        logs(args.service, args.since, args.follow, args.filter_pattern)
        return
    elif args.action == 'check':
        result = check()
    elif args.action == 'build':
        result = build(args.service, args.metricflow_repository)
    elif args.action == 'quiesce':
        result = quiesce(args.service, args.snapshot)
    elif args.action == 'deploy':
        result = deploy(args.service, args.from_quiesced)
    elif args.action == 'verify':
        result = verify(args.service, args.from_quiesced)
    elif args.action == 'sync-tag':
        result = sync_tag(args.service)
    else:
        result = globals()[args.action](args.service)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f'deployment stopped: {error}', file=sys.stderr)
        sys.exit(1)
