#!/usr/bin/env python3
"""Immutable environment-selected static bundles. AWS writes require applicable authorization and --apply."""
import argparse
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import hashlib
import importlib.util
import json
from itertools import islice
import mimetypes
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

spec = importlib.util.spec_from_file_location('dev_deploy', Path(__file__).with_name('deploy.py'))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
S3_CLIENT = None


def configure(environment, profile=None):
    global STACK, BUCKET, INPUTS, S3_CLIENT
    if S3_CLIENT is not None:
        S3_CLIENT.close()
        S3_CLIENT = None
    deploy.configure(environment, profile)
    selected = deploy.ENVIRONMENT
    STACK = selected['stackPrefix'] + 'StaticHosting'
    BUCKET = f"{selected['resourcePrefix']}-static-{deploy.ACCOUNT}-{deploy.REGION}"
    INPUTS = {'APP_ENV': 'production', 'VITE_MONOMER_CONTROL_API_URL': selected['apiOrigin'],
              'VITE_GOOGLE_OAUTH_ENABLED': 'true', 'VITE_LOCAL_LOGIN_ENABLED': 'false',
              'MONOMER_PUBLIC_SITE_ORIGIN': selected['publicOrigin']}


require = deploy.require
DEMO_PATH = 'public/media/dashboard-demo.mp4'
APP_METADATA_START = b'<!-- monomer:page-metadata:start -->'
APP_METADATA_END = b'<!-- monomer:page-metadata:end -->'
CACHING_DISABLED = '4135ea2d-6df8-44a3-9df3-4b5a84be39ad'


def initialize_s3():
    global S3_CLIENT
    if S3_CLIENT is not None:
        return
    try:
        import boto3
        from botocore.config import Config
        from botocore.exceptions import BotoCoreError
    except ImportError as error:
        raise RuntimeError('S3 dependencies are missing; install infra/cloud-dev/requirements.txt in a Python virtual environment.') from error
    try:
        session = boto3.Session(profile_name=deploy.PROFILE, region_name=deploy.REGION)
        S3_CLIENT = session.client('s3', config=Config(max_pool_connections=8))
    except BotoCoreError as error:
        raise RuntimeError(f'S3 client initialization failed ({type(error).__name__}).') from error


def validate_app_shell(data):
    require(len(data) <= 256 * 1024, 'App shell exceeds 256 KiB.')
    require(data.count(APP_METADATA_START) == 1 and data.count(APP_METADATA_END) == 1,
            'App shell requires one metadata slot.')
    require(0 <= data.find(b'<head>') < data.find(APP_METADATA_START)
            < data.find(APP_METADATA_END) < data.find(b'</head>'), 'App metadata slot must be inside head.')
    try:
        data.decode('utf-8')
    except UnicodeDecodeError:
        require(False, 'App shell must be UTF-8.')


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n').encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def release_check(value):
    require(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value), 'Invalid release digest.')


def asset(path):
    return path.startswith(('app/assets/', 'public/_astro/'))


def metadata(path, data):
    mime = {'.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml',
            '.mp4': 'video/mp4', '.md': 'text/markdown', '.json': 'application/json', '.txt': 'text/plain', '.xml': 'application/xml'}.get(
                PurePosixPath(path).suffix, mimetypes.guess_type(path)[0] or 'application/octet-stream')
    return {'path': path, 'sha256': digest(data), 'size': len(data), 'contentType': mime,
            'cacheControl': 'public,max-age=31536000,immutable' if asset(path) else 'no-store'}


def validate(manifest):
    require(set(manifest) == {'version', 'provenance', 'files'} and manifest['version'] == 1, 'Invalid manifest.')
    provenance = manifest['provenance']
    require(provenance['kind'] in ('built-source', 'imported-image'), 'Invalid provenance.')
    source = provenance['sourceCommit']
    require(source is None or re.fullmatch('[0-9a-f]{40}', source), 'Invalid source commit.')
    if provenance['kind'] == 'built-source':
        require(source and provenance['buildInputs'] == INPUTS, 'Invalid build inputs.')
    else:
        deploy.image_uri_check(provenance['imageUri'])
        require(provenance['buildInputs'] is None, 'Imported build inputs must not be invented.')
    paths = []
    for entry in manifest['files']:
        path = entry['path']
        require(path.startswith(('app/', 'public/')) and str(PurePosixPath(path)) == path
                and '..' not in PurePosixPath(path).parts and '\\' not in path, 'Unsafe file path.')
        require(re.fullmatch('[0-9a-f]{64}', entry['sha256']) and type(entry['size']) is int
                and entry['size'] >= 0, 'Invalid file integrity metadata.')
        expected = metadata(path, b'')
        require(set(entry) == set(expected) and all(entry[k] == expected[k] for k in ('contentType', 'cacheControl')),
                'Invalid MIME/cache policy.')
        paths.append(path)
    require(paths == sorted(set(paths)) and {'app/index.html', 'public/index.html', 'app/404.html', 'public/404.html'} <= set(paths),
            'Manifest needs unique sorted files and both indexes and 404 documents.')
    return digest(canonical(manifest))


def require_demo(manifest):
    require(any(entry['path'] == DEMO_PATH and entry['size'] > 0
                and entry['contentType'] == 'video/mp4' for entry in manifest['files']),
            'Required dashboard demo MP4 is missing.')


@deploy.timed('static.build')
def prepare(output, demo_mp4, demo_sha256):
    source = deploy.source_check()
    require(re.fullmatch('[0-9a-f]{64}', demo_sha256), 'Invalid demo SHA-256.')
    demo_path = Path(demo_mp4).expanduser()
    require(demo_path.is_file(), 'Demo MP4 input must be a readable file.')
    demo_bytes = demo_path.read_bytes()
    require(len(demo_bytes) >= 12 and demo_bytes[4:8] == b'ftyp', 'Demo input is not an MP4 file.')
    require(digest(demo_bytes) == demo_sha256, 'Demo MP4 SHA-256 mismatch.')
    output = Path(output).resolve()
    require(not output.exists(), 'Output must be a new directory.')
    command = ['docker', 'build', '--platform', 'linux/amd64', '-f', 'web-app/Dockerfile',
               '--target', 'artifacts', '--output', f'type=local,dest={output}']
    for name, value in INPUTS.items():
        command += ['--build-arg', f'{name}={value}']
    with deploy.phase('static.artifact-build'):
        deploy.run(command + ['.'], capture=False)
    provenance = {'kind': 'built-source', 'sourceCommit': source, 'buildInputs': INPUTS}
    require(deploy.source_check() == source, 'Source changed during preparation.')
    demo_output = output / DEMO_PATH
    require(not any(path.is_symlink() for path in [demo_output, *demo_output.parents]
                    if path.is_relative_to(output)), 'Demo output must not contain symlinks.')
    demo_output.parent.mkdir(parents=True, exist_ok=True)
    demo_output.write_bytes(demo_bytes)
    files = []
    for path in sorted(output.rglob('*'), key=lambda entry: entry.relative_to(output).as_posix()):
        require(not path.is_symlink(), 'Bundle must not contain symlinks.')
        if path.is_file():
            files.append(metadata(path.relative_to(output).as_posix(), path.read_bytes()))
    manifest = {'version': 1, 'provenance': provenance, 'files': files}
    release = validate(manifest)
    (output / 'manifest.json').write_bytes(canonical(manifest))
    local_bundle(output)
    return {'release': release, 'bundle': str(output), 'provenance': provenance}


@deploy.timed('static.local-integrity')
def local_bundle(directory):
    directory = Path(directory).resolve()
    manifest = json.loads((directory / 'manifest.json').read_bytes())
    release = validate(manifest)
    require((directory / 'manifest.json').read_bytes() == canonical(manifest), 'Manifest is not canonical.')
    expected = {'manifest.json'} | {entry['path'] for entry in manifest['files']}
    actual = {p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file()}
    require(actual == expected, 'Bundle file set changed.')
    for entry in manifest['files']:
        path = directory / entry['path']
        require(not any(p.is_symlink() for p in [path, *path.parents] if p.is_relative_to(directory)), 'Bundle symlink rejected.')
        require(metadata(entry['path'], path.read_bytes()) == entry, 'Bundle bytes changed.')
        if entry['path'] == 'app/index.html':
            validate_app_shell(path.read_bytes())
    workers = [e['path'] for e in manifest['files'] if re.fullmatch(r'app/assets/chart-widget-worker-[A-Za-z0-9_-]+\.js', e['path'])]
    require(workers, 'Chart worker with dedicated policy filename is missing.')
    consumers = [directory / e['path'] for e in manifest['files'] if e['path'].startswith('app/assets/')
                 and e['path'].endswith('.js') and e['path'] not in workers]
    require(all(any(PurePosixPath(worker).name.encode() in consumer.read_bytes() for consumer in consumers)
                for worker in workers), 'Chart worker filename is not referenced by application JS.')
    return manifest, release


def key_for(entry, release):
    path = entry['path']
    site, relative = path.split('/', 1)
    return path if asset(path) else f'{site}/releases/{release}/{relative}'


def remote_bytes(key):
    from botocore.exceptions import BotoCoreError, ClientError
    require(S3_CLIENT is not None, 'Initialize S3 before reading release objects.')
    try:
        response = S3_CLIENT.get_object(Bucket=BUCKET, Key=key)
        body = response['Body']
        try:
            return body.read(), response
        finally:
            body.close()
    except (BotoCoreError, ClientError) as error:
        raise RuntimeError(f'S3 read failed ({type(error).__name__}): {key}') from error


def verify_response(key, body, response, entry):
    require(digest(body) == entry['sha256'] and len(body) == entry['size']
            and response.get('ContentLength') == entry['size']
            and response.get('ContentType') == entry['contentType']
            and response.get('CacheControl') == entry['cacheControl']
            and response.get('Metadata') == {'sha256': entry['sha256']}, f'Object collision or drift: {key}')


def verify_object(key, data, entry):
    actual, response = remote_bytes(key)
    verify_response(key, actual, response, entry)
    require(actual == data, f'Object collision or drift: {key}')


def progress(phase, completed, total):
    print(f'{phase}: {completed}/{total}', file=sys.stderr, flush=True)


def parallel_files(phase, entries, operation):
    progress(phase, 0, len(entries))
    remaining = iter(entries)
    executor = ThreadPoolExecutor(max_workers=8)
    pending = set()
    completed = 0
    try:
        pending = {executor.submit(operation, entry) for entry in islice(remaining, 8)}
        while pending:
            finished, pending = wait(pending, return_when=FIRST_COMPLETED)
            for future in finished:
                future.result()
            completed += len(finished)
            if completed == len(entries) or completed // 25 != (completed - len(finished)) // 25:
                progress(phase, completed, len(entries))
            for _ in finished:
                entry = next(remaining, None)
                if entry is not None:
                    pending.add(executor.submit(operation, entry))
    finally:
        executor.shutdown(wait=True, cancel_futures=True)


def put_immutable(key, path, entry):
    from botocore.exceptions import BotoCoreError, ClientError
    require(S3_CLIENT is not None, 'Initialize S3 before publishing release objects.')
    data = path.read_bytes()
    # Conditional creation is the authority; an existing key is reusable only after byte verification.
    try:
        S3_CLIENT.put_object(Bucket=BUCKET, Key=key, Body=data, IfNoneMatch='*',
                             ContentType=entry['contentType'], CacheControl=entry['cacheControl'],
                             Metadata={'sha256': entry['sha256']})
    except ClientError as error:
        if error.response.get('Error', {}).get('Code') != 'PreconditionFailed':
            raise RuntimeError(f'S3 conditional PUT failed ({type(error).__name__}): {key}') from error
    except BotoCoreError as error:
        raise RuntimeError(f'S3 conditional PUT failed ({type(error).__name__}): {key}') from error
    verify_object(key, data, entry)


def manifest_entry(data):
    return {'size': len(data), 'sha256': digest(data), 'contentType': 'application/json', 'cacheControl': 'no-store'}


def read_remote_manifest(release):
    release_check(release)
    initialize_s3()
    key = f'manifests/{release}.json'
    progress('Verify remote manifest', 0, 1)
    data, response = remote_bytes(key)
    manifest = json.loads(data)
    require(validate(manifest) == release and data == canonical(manifest), 'Remote manifest digest mismatch.')
    verify_response(key, data, response, manifest_entry(data))
    progress('Verify remote manifest', 1, 1)
    return manifest


@deploy.timed('static.remote-integrity')
def remote_manifest(release):
    manifest = read_remote_manifest(release)

    def verify_file(entry):
        key = key_for(entry, release)
        body, response = remote_bytes(key)
        verify_response(key, body, response, entry)
        if entry['path'] == 'app/index.html':
            validate_app_shell(body)

    parallel_files('Verify remote files', manifest['files'], verify_file)
    return manifest


def sync_tag():
    deploy.account_check()
    outputs, release = stack()
    manifest = read_remote_manifest(release)
    for site in ('App', 'Public'):
        distribution = deploy.aws('cloudfront', 'get-distribution', '--id', outputs[site + 'DistributionId'])['Distribution']
        require(distribution['Status'] == 'Deployed', 'CloudFront propagation is incomplete; no tag was updated.')
        configuration = distribution['DistributionConfig']
        require(configuration.get('Enabled') is True
                and configuration['DefaultCacheBehavior']['TargetOriginId'] == 'Release',
                'Distribution is disabled or default routing differs; no tag was updated.')
        origins = configuration['Origins']['Items']
        origins = [origin for origin in origins if origin['Id'] == 'Release']
        require(len(origins) == 1 and origins[0].get('OriginPath') == f'/{site.lower()}/releases/{release}'
                and origins[0]['DomainName'] == f'{BUCKET}.s3.{deploy.REGION}.amazonaws.com',
                'Active distribution release differs; no tag was updated.')
    require(stack()[1] == release, 'Static release changed during lookup; no tag was updated.')
    return {'release': release,
            'deploymentTag': deploy.update_deployment_tag('static', manifest['provenance']['sourceCommit'])}


@deploy.timed('static.publish')
def publish(directory):
    deploy.account_check()
    source = deploy.source_check()
    manifest, release = local_bundle(directory)
    require(manifest['provenance']['kind'] == 'built-source', 'Only source-built bundles can be published.')
    require(manifest['provenance']['sourceCommit'] == source, 'Stale source bundle.')
    require_demo(manifest)
    initialize_s3()
    def publish_file(entry):
        put_immutable(key_for(entry, release), Path(directory) / entry['path'], entry)

    for is_asset, phase in ((True, 'Publish shared assets'), (False, 'Publish release files')):
        entries = [entry for entry in manifest['files'] if asset(entry['path']) == is_asset]
        with deploy.phase('static.publish-shared-assets' if is_asset else 'static.publish-release-files'):
            parallel_files(phase, entries, publish_file)
    data = canonical(manifest)
    progress('Publish manifest', 0, 1)
    with deploy.phase('static.publish-manifest'):
        put_immutable(f'manifests/{release}.json', Path(directory) / 'manifest.json', manifest_entry(data))
    progress('Publish manifest', 1, 1)
    return {'release': release, 'uploaded': True, 'activated': False}


def stack():
    result = deploy.aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]
    require(result['StackStatus'] in ('CREATE_COMPLETE', 'UPDATE_COMPLETE', 'UPDATE_ROLLBACK_COMPLETE'),
            'Static stack is not stable.')
    outputs = {e['OutputKey']: e['OutputValue'] for e in result['Outputs']}
    require(outputs['StaticBucketName'] == BUCKET, 'Unexpected static bucket.')
    release = next(e['ParameterValue'] for e in result['Parameters'] if e['ParameterKey'] == 'StaticRelease')
    release_check(release)
    return outputs, release


@deploy.timed('static.check')
def check(expected=None):
    deploy.account_check()
    outputs, release = stack()
    require(expected is None or expected == release, 'Static release parameter mismatch.')
    remote_manifest(release)
    for site in ('App', 'Public'):
        distribution = deploy.aws('cloudfront', 'get-distribution', '--id', outputs[site + 'DistributionId'])['Distribution']
        require(distribution['Status'] == 'Deployed', 'CloudFront propagation is incomplete.')
        require(distribution['DomainName'] == outputs[site + 'DistributionDomainName'], 'Distribution domain mismatch.')
        configuration = distribution['DistributionConfig']
        host = deploy.ENVIRONMENT['appOrigin' if site == 'App' else 'publicOrigin'].removeprefix('https://')
        require(configuration.get('Enabled') is True and configuration.get('Aliases', {}).get('Items') == [host],
                'Distribution enabled state or aliases differ.')
        origins = configuration['Origins']['Items']
        by_id = {origin['Id']: origin for origin in origins}
        expected_origins = {'Release', 'SharedAssets', 'ControlPageHtml'} if site == 'App' else {'Release', 'SharedAssets'}
        require(len(origins) == len(expected_origins) and set(by_id) == expected_origins
                and by_id['Release'].get('OriginPath') == f'/{site.lower()}/releases/{release}'
                and by_id['SharedAssets'].get('OriginPath') == f'/{site.lower()}', 'Distribution origin paths differ.')
        require(all(by_id[name]['DomainName'] == f'{BUCKET}.s3.{deploy.REGION}.amazonaws.com'
                    and by_id[name].get('OriginAccessControlId') for name in ('Release', 'SharedAssets')), 'Distribution bucket or OAC differs.')
        if site == 'App':
            control = by_id['ControlPageHtml']
            config = control.get('CustomOriginConfig', {})
            require(control['DomainName'] == deploy.ENVIRONMENT['apiOrigin'].removeprefix('https://')
                    and not control.get('OriginPath') and not control.get('OriginAccessControlId')
                    and not control.get('S3OriginConfig') and not control.get('CustomHeaders', {}).get('Items')
                    and config.get('OriginProtocolPolicy') == 'https-only' and config.get('HTTPSPort') == 443
                    and config.get('OriginSslProtocols', {}).get('Items') == ['TLSv1.2'], 'Control Page HTML origin differs.')
        default = configuration['DefaultCacheBehavior']
        require(default['TargetOriginId'] == 'Release', 'Default behavior must target Release.')
        behaviors = configuration.get('CacheBehaviors', {}).get('Items', [])
        patterns = ['/assets/chart-widget-worker-*.js', '/assets/*', '/workspaces/*/pages/*'] if site == 'App' else ['/_astro/*']
        require([behavior['PathPattern'] for behavior in behaviors] == patterns
                and all(behavior['TargetOriginId'] == ('ControlPageHtml' if behavior['PathPattern'] == '/workspaces/*/pages/*' else 'SharedAssets') for behavior in behaviors),
                'Asset behavior routing differs.')
        require(all(behavior.get('ViewerProtocolPolicy') == 'redirect-to-https'
                    and behavior.get('CachePolicyId') and behavior.get('ResponseHeadersPolicyId')
                    and any(item.get('EventType') == 'viewer-request' and item.get('FunctionARN')
                            for item in behavior.get('FunctionAssociations', {}).get('Items', []))
                    for behavior in [default, *behaviors]), 'Distribution routing or security policy is missing.')
        if site == 'App':
            page = behaviors[-1]
            require(page.get('CachePolicyId') == CACHING_DISABLED
                    and sorted(page.get('AllowedMethods', {}).get('Items', [])) == ['GET', 'HEAD']
                    and not page.get('OriginRequestPolicyId') and not page.get('ForwardedValues')
                    and page.get('ResponseHeadersPolicyId') == default.get('ResponseHeadersPolicyId'),
                    'Page HTML must disable caching and credential forwarding and retain App security headers.')
            errors = {item['ErrorCode']: item for item in configuration.get('CustomErrorResponses', {}).get('Items', [])}
            require(all(code in errors and errors[code].get('ErrorCachingMinTTL') == 0
                        and not errors[code].get('ResponsePagePath') and not errors[code].get('ResponseCode')
                        for code in (500, 502, 503, 504)), 'Page HTML errors must not be cached or rewritten.')
    return {'release': release, 'infrastructureVerified': True, 'productSmoke': 'NOT_RUN', 'outputs': outputs}


@deploy.timed('static.synth')
def synth(release):
    release_check(release)
    deploy.cdk_toolchain_check()
    with deploy.phase('static.cdk-build'):
        deploy.run(['npm', 'run', 'build'], cwd=deploy.INFRA)
    template_path = deploy.INFRA / 'cdk.out' / f'{STACK}.template.json'
    template_path.unlink(missing_ok=True)
    deploy.run(['npx', '--no-install', 'cdk', 'synth', STACK, *deploy.cdk_arguments(), '--exclusively', '--json'], cwd=deploy.INFRA)
    require(template_path.is_file(), 'CDK synth did not generate the static hosting template.')
    return json.loads(template_path.read_text())


@deploy.timed('static.activate')
def activate(release, rollback=False):
    deploy.account_check()
    deploy.cdk_toolchain_check()
    source = deploy.source_check()
    with deploy.phase('static.pre-activation-integrity'):
        manifest = remote_manifest(release)
    if not rollback:
        require(manifest['provenance']['kind'] == 'built-source', 'Imported releases require explicit rollback.')
        require(manifest['provenance']['sourceCommit'] == source, 'Stale source release; use explicit rollback.')
        require_demo(manifest)
    stack()
    current = deploy.aws('cloudformation', 'get-template', '--stack-name', STACK, '--template-stage', 'Original')['TemplateBody']
    if isinstance(current, str):
        current = json.loads(current)
    proposed = synth(release)
    require(current == proposed, 'Infrastructure template differs; review and apply infrastructure separately.')
    require(deploy.source_check() == source, 'Harness source changed before activation.')
    with deploy.phase('static.cdk-activation'):
        deploy.run(['npx', '--no-install', 'cdk', 'deploy', STACK, *deploy.cdk_arguments(), '--exclusively', '--method', 'direct',
                    '--previous-parameters', 'true', '--require-approval', 'never',
                    '--parameters', f'{STACK}:StaticRelease={release}'], cwd=deploy.INFRA, capture=False)
    outputs, _ = stack()
    for site in ('App', 'Public'):
        with deploy.phase('static.cloudfront-app-wait' if site == 'App' else 'static.cloudfront-public-wait'):
            deploy.run(['aws', 'cloudfront', 'wait', 'distribution-deployed', '--id', outputs[site + 'DistributionId'],
                        *deploy.profile_arguments(), '--region', deploy.REGION], capture=False)
    with deploy.phase('static.post-activation-verification'):
        result = check(release)
    return deploy.mark_deployment(result, 'static', manifest['provenance']['sourceCommit'])


def main(argv=None):
    global S3_CLIENT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--environment', choices=('dev', 'prod'), required=True)
    parser.add_argument('--profile')
    sub = parser.add_subparsers(dest='action', required=True)
    build = sub.add_parser('build')
    build.add_argument('directory')
    build.add_argument('--demo-mp4', required=True)
    build.add_argument('--demo-sha256', required=True)
    publish_command = sub.add_parser('publish')
    publish_command.add_argument('directory')
    publish_command.add_argument('--apply', action='store_true')
    for action in ('plan', 'deploy', 'rollback'):
        command = sub.add_parser(action)
        command.add_argument('release')
        if action != 'plan':
            command.add_argument('--apply', action='store_true')
    sub.add_parser('check')
    sub.add_parser('sync-tag', help='Read current AWS provenance and update only the local deployment tag.')
    args = parser.parse_args(argv)
    configure(args.environment, args.profile)
    if args.action in ('publish', 'deploy', 'rollback'):
        require(args.apply, 'No AWS mutation without --apply and applicable user authorization.')
    try:
        if args.action == 'build':
            result = prepare(args.directory, args.demo_mp4, args.demo_sha256)
        elif args.action == 'publish':
            result = publish(args.directory)
        elif args.action == 'plan':
            result = {'release': args.release, 'parameters': {'StaticRelease': args.release}, 'template': synth(args.release)}
        elif args.action == 'check':
            result = check()
        elif args.action == 'sync-tag':
            result = sync_tag()
        else:
            result = activate(args.release, args.action == 'rollback')
    finally:
        if S3_CLIENT is not None:
            S3_CLIENT.close()
            S3_CLIENT = None
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, KeyError, ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f'static release stopped: {error}', file=sys.stderr)
        sys.exit(1)
