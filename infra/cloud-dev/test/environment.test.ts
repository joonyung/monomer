import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { cloudEnvironment } from '../src/environment';
import { NetworkStack } from '../src/network-stack';
import { StorageStack } from '../src/storage-stack';
import { ComputeStack } from '../src/compute-stack';
import { CertificateStack } from '../src/certificate-stack';
import { ServicesStack } from '../src/services-stack';
import { StaticHostingStack } from '../src/static-hosting-stack';

function templates(name: 'dev' | 'prod') {
  const environment = cloudEnvironment(name);
  const app = new App();
  const network = new NetworkStack(app, `${environment.stackPrefix}Network`, environment);
  const storage = new StorageStack(app, `${environment.stackPrefix}Storage`, network.vpc, environment);
  const compute = new ComputeStack(app, `${environment.stackPrefix}Compute`, network.vpc, storage, environment);
  const certificate = new CertificateStack(app, `${environment.stackPrefix}Certificate`, environment);
  const services = new ServicesStack(app, `${environment.stackPrefix}Services`, network.vpc, compute.cluster, storage, certificate.certificate, environment);
  const hosting = new StaticHostingStack(app, `${environment.stackPrefix}StaticHosting`, certificate.certificate, certificate.publicSiteCertificate, environment);
  return Object.fromEntries(Object.entries({network, storage, compute, certificate, services, hosting}).map(([name, stack]) => {
    assert.equal(stack.account, environment.account);
    assert.equal(stack.terminationProtection, true);
    return [name, Template.fromStack(stack)];
  }));
}

test('environment selection requires an explicit supported target', () => {
  for (const name of [undefined, null, '', 'production', 'DEV', {}, 'constructor']) assert.throws(() => cloudEnvironment(name), /Explicit environment/);
  assert.equal(cloudEnvironment('dev').account, '111122223333');
  assert.equal(cloudEnvironment('prod').account, '444455556666');
});

test('prod isolates resources and public identity with open login admission and least privilege', () => {
  const dev = templates('dev');
  const prod = templates('prod');
  const identity = JSON.parse(readFileSync(join(__dirname, '../../../../contracts/identity/security-identifier-map.json'), 'utf8')).public;
  for (const [name, template] of Object.entries(prod)) {
    const text = JSON.stringify(template.toJSON());
    for (const forbidden of ['111122223333', 'MonomerDev', 'monomer-dev', 'monomer.dev.internal', 'dev.monomer.cc']) assert.equal(text.includes(forbidden), false, `${name}: ${forbidden}`);
    template.resourceCountIs('AWS::ElastiCache::CacheCluster', 0);
    template.resourceCountIs('AWS::ElastiCache::ReplicationGroup', 0);
    for (const type of ['AWS::IAM::Policy', 'AWS::IAM::Role', 'AWS::S3::BucketPolicy']) {
      const normalized = JSON.parse(JSON.stringify(template.findResources(type)).replaceAll('444455556666', '111122223333').replaceAll('MonomerProd', 'MonomerDev').replaceAll('monomer-prod', 'monomer-dev').replaceAll('"Value":"prod"', '"Value":"dev"'));
      assert.deepEqual(normalized, dev[name].findResources(type), `${name} ${type} cannot broaden permissions`);
    }
  }
  prod.network.hasResourceProperties('AWS::EC2::VPC', { CidrBlock: '10.78.0.0/16' });
  prod.network.resourceCountIs('AWS::EC2::NatGateway', 1);
  prod.storage.hasResourceProperties('AWS::RDS::DBInstance', {
    DBInstanceIdentifier: 'monomer-prod', DBInstanceClass: 'db.t4g.small', AllocatedStorage: '20',
    MultiAZ: false, BackupRetentionPeriod: 7, DeletionProtection: true, PubliclyAccessible: false,
  });
  assert.equal(Object.values(prod.storage.findResources('AWS::RDS::DBInstance'))[0].DeletionPolicy, 'Snapshot');
  for (const bucket of Object.values(prod.storage.findResources('AWS::S3::Bucket'))) {
    assert.equal(bucket.Properties.VersioningConfiguration, undefined);
    assert.equal(bucket.Properties.LifecycleConfiguration, undefined);
    assert.deepEqual(bucket.Properties.CorsConfiguration.CorsRules[0].AllowedOrigins, ['https://app.monomer.cc']);
  }
  for (const service of Object.values(prod.services.findResources('AWS::ECS::Service'))) {
    assert.deepEqual(service.Properties.DesiredCount, JSON.stringify(service.Properties.TaskDefinition).includes('ControlTask') ? { Ref: 'ControlDesiredCount' } : 1);
    assert.equal(service.Properties.DeploymentConfiguration.MinimumHealthyPercent, 100);
    assert.equal(service.Properties.DeploymentConfiguration.MaximumPercent, 200);
  }
  for (const task of Object.values(prod.services.findResources('AWS::ECS::TaskDefinition'))) {
    const container = task.Properties.ContainerDefinitions[0];
    assert.equal(task.Properties.Cpu, '1024');
    assert.equal(task.Properties.Memory, '2048');
    if (container.Name !== 'control') continue;
    const environment = Object.fromEntries(container.Environment.map((item: { Name: string; Value: unknown }) => [item.Name, item.Value]));
    for (const [key, value] of Object.entries({
      MONOMER_CONTROL_SECURITY_ISSUER: identity.controlIssuer,
      MONOMER_CONTROL_SECURITY_AGENT_RESOURCE: identity.agentResource,
      MONOMER_CONTROL_OAUTH_ISSUER: identity.oauthIssuer,
      MONOMER_CONTROL_OAUTH_MCP_RESOURCE: identity.mcpResource,
      MONOMER_CONTROL_AUTH_FRONTEND_CALLBACK_URL: identity.oauthRedirect,
      MONOMER_CONTROL_GOOGLE_OAUTH_REDIRECT_URI: 'https://api.monomer.cc/login/oauth2/code/google',
      MONOMER_CONTROL_AUTH_CORS_ALLOWED_ORIGINS: 'https://app.monomer.cc',
      MONOMER_CONTROL_EXECUTION_TARGET: 'http://execution.monomer.prod.internal:11099',
      MONOMER_CONTROL_DEVELOPER_ACCESS_MODE: 'OPEN',
      MONOMER_CONTROL_AUTH_LOCAL_LOGIN_ENABLED: 'false',
      MONOMER_CONTROL_GOOGLE_OAUTH_ENABLED: 'true',
      MONOMER_CONTROL_AUTH_REFRESH_COOKIE_SECURE: 'true',
    })) assert.equal(environment[key], value, key);
    assert.equal(environment.MONOMER_CONTROL_SECURITY_LOGIN_NAMESPACE, undefined);
    assert.equal(environment.MONOMER_CONTROL_SECURITY_LOGIN_SUBJECT_URN_PREFIX, undefined);
    assert.deepEqual(environment.MONOMER_CONTROL_DEVELOPER_ALLOWED_EMAILS, { Ref: 'ControlAllowedEmails' });
  }
  const parameters = prod.services.toJSON().Parameters;
  assert.equal(parameters.ControlDesiredCount.Default, 1);
  assert.deepEqual(parameters.ControlDesiredCount.AllowedValues, ['0', '1']);
  assert.equal(parameters.ControlAllowedEmails.Default, undefined);
  assert.equal(parameters.ControlInvitationRecipientAddress.Default, undefined);
  for (const name of ['ControlImage', 'ExecutionImage']) {
    const pattern = new RegExp(`^${parameters[name].AllowedPattern}$`);
    assert.ok(pattern.test(`444455556666.dkr.ecr.us-east-1.amazonaws.com/cdk-hnb659fds-container-assets-444455556666-us-east-1@sha256:${'a'.repeat(64)}`));
    assert.equal(pattern.test(`111122223333.dkr.ecr.us-east-1.amazonaws.com/cdk-hnb659fds-container-assets-111122223333-us-east-1@sha256:${'a'.repeat(64)}`), false);
  }
  for (const [name, hosting] of [['dev', dev.hosting], ['prod', prod.hosting]] as const) {
    const policies = hosting.findResources('AWS::CloudFront::ResponseHeadersPolicy');
    const environment = cloudEnvironment(name);
    for (const distribution of Object.values(hosting.findResources('AWS::CloudFront::Distribution'))) {
      const config = distribution.Properties.DistributionConfig;
      const host = config.Aliases[0];
      assert.ok([new URL(environment.appOrigin).hostname, new URL(environment.publicOrigin).hostname].includes(host));
      const robots = name === 'prod' && host === 'monomer.cc' ? 'index, follow' : 'noindex, nofollow';
      for (const behavior of [config.DefaultCacheBehavior, ...config.CacheBehaviors]) {
        const policy = policies[behavior.ResponseHeadersPolicyId.Ref].Properties.ResponseHeadersPolicyConfig;
        assert.deepEqual(policy.CustomHeadersConfig.Items.find((item: { Header: string }) => item.Header === 'X-Robots-Tag'), {
          Header: 'X-Robots-Tag', Value: robots, Override: true,
        }, `${host} ${behavior.PathPattern ?? 'default'}`);
      }
    }
  }
  for (const [id, resource] of Object.entries(prod.hosting.findResources('AWS::CloudFront::Function'))) {
    const pageHtml = id.startsWith('PageHtml');
    const host = id.startsWith('App') || pageHtml ? 'app.monomer.cc' : 'monomer.cc';
    const workspace = '019e0000-0000-7000-8000-000000000001';
    const page = '019e0000-0000-7000-8000-000000000002';
    const uri = pageHtml ? `/workspaces/${workspace}/pages/${page}` : '/';
    const route = (value: string) => runInNewContext(`${resource.Properties.FunctionCode}; handler(event)`, { event: { request: { uri, method: 'GET', headers: { host: { value } }, querystring: {} } } });
    assert.equal(route(host).uri, pageHtml ? `/v2/public/workspaces/${workspace}/pages/${page}/html` : '/index.html');
    assert.equal(route('app.dev.monomer.cc').statusCode, 404);
    assert.equal(route('other.cloudfront.net').statusCode, 404);
  }
});
