import { cloudEnvironment } from '../src/environment';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { CertificateStack } from '../src/certificate-stack';
import { StaticHostingStack } from '../src/static-hosting-stack';
import { APP_CSP, WORKER_CSP, pageHtmlRoutingCode, routingCode } from '../src/static-routing';

const environment = cloudEnvironment('dev');

function route(site: 'app' | 'public', uri: string, host?: string, querystring = {}) {
  return runInNewContext(`${routingCode(site, site === 'app' ? 'app.dev.monomer.cc' : 'dev.monomer.cc')}; handler(event)`, { event: { request: {
    uri, method: 'GET', headers: { host: { value: host ?? (site === 'app' ? 'app.dev.monomer.cc' : 'dev.monomer.cc') } }, querystring,
  } } });
}

test('app SPA routes and file requests remain distinct; forbidden paths never return HTML', () => {
  for (const uri of ['/', '/workspaces/abc/pages', '/auth/callback', '/oauth/consent']) assert.equal(route('app', uri).uri, '/index.html');
  for (const uri of ['/assets/old-Abcd1234.js', '/assets/new-Bbcd1234.css', '/assets/chart-widget-worker-X1234567.js', '/missing.js', '/favicon.svg']) assert.equal(route('app', uri).uri, uri);
  for (const uri of ['/assets', '/assets/', '/v2/x', '/mcp', '/auth', '/auth/callback/x', '/oauth/other', '/monitor/health', '/.well-known/foo', '/login/oauth2/code/google', '/internal/x', '/actuator']) assert.equal(route('app', uri).statusCode, 404, uri);
  assert.equal(route('app', '/', 'dev.monomer.cc').statusCode, 404);
  assert.equal(route('app', '/', 'x.cloudfront.net').statusCode, 404);
});

test('public directory redirect preserves repeated query values and uses real index objects', () => {
  const redirect = route('public', '/en/docs', undefined, { a: { multiValue: [{value: '1'}, {value: '2'}] }, q: {value: 'space%20and%26'} });
  assert.equal(redirect.statusCode, 301);
  assert.equal(redirect.headers.location.value, '/en/docs/?a=1&a=2&q=space%20and%26');
  for (const [uri, expected] of [['/', '/index.html'], ['/en/docs/', '/en/docs/index.html'], ['/en/missing/', '/en/missing/index.html'], ['/en/docs/index.md', '/en/docs/index.md'], ['/_astro/old.Abcd.js', '/_astro/old.Abcd.js'], ['/404.html', '/404.html']]) assert.equal(route('public', uri).uri, expected);
  assert.equal(route('public', '/', 'app.dev.monomer.cc').statusCode, 404);
});

test('former English landing permanently redirects to root in one hop without moving docs or machine routes', () => {
  for (const uri of ['/en', '/en/', '/en/index.html']) {
    const redirect = route('public', uri, undefined, { utm_source: {value: 'launch'}, a: {multiValue: [{value: '1'}, {value: '2'}]} });
    assert.equal(redirect.statusCode, 301);
    assert.equal(redirect.headers.location.value, '/?utm_source=launch&a=1&a=2');
    assert.equal(route('public', uri).headers.location.value, '/');
  }
  assert.equal(route('public', '/').uri, '/index.html');
  assert.equal(route('public', '/en/docs/').uri, '/en/docs/index.html');
  assert.equal(route('public', '/en/llms.txt').uri, '/en/llms.txt');
  assert.equal(route('app', '/en/').uri, '/index.html');
  assert.equal(route('public', '/en/', 'evil.example').statusCode, 404);
});

test('normalized and encoded traversal cannot cross the selected origin prefix', () => {
  for (const site of ['app', 'public'] as const) {
    for (const uri of ['/../public/x', '/assets/../x', '/assets/%2e%2e/x', '/%2fpublic/x', '/%5cx', '/%252e/x', '//evil.example/x', '/./x', '/x\\y', '/x\u0000y', '/x\u001fy', '/x y']) assert.equal(route(site, uri).statusCode, 404, uri);
  }
});

test('static stack fences origins, permissions, policies and release switch without ECS mutations', () => {
  const app = new App();
  const certificate = new CertificateStack(app, 'MonomerDevCertificate', environment);
  const template = Template.fromStack(new StaticHostingStack(app, 'MonomerDevStaticHosting', certificate.certificate, certificate.publicSiteCertificate, environment));
  template.resourceCountIs('AWS::S3::Bucket', 1);
  template.resourceCountIs('AWS::CloudFront::Distribution', 2);
  template.resourceCountIs('AWS::CloudFront::OriginAccessControl', 1);
  template.resourceCountIs('AWS::CloudFront::Function', 3);
  template.resourceCountIs('AWS::ECS::Service', 0);
  template.resourceCountIs('AWS::IAM::Role', 0);
  for (const resource of Object.values(template.findResources('AWS::CloudFront::Function'))) {
    const code = resource.Properties.FunctionCode as string;
    assert.ok([...code].every(character => character.charCodeAt(0) >= 32 || '\n\r\t'.includes(character)), 'CloudFormation FunctionCode cannot contain raw control characters');
  }
  template.hasResourceProperties('AWS::CloudFront::Function', { Name: 'monomer-dev-public-root-routing' });
  template.hasResourceProperties('AWS::CloudFront::Function', { Name: 'monomer-dev-app-routing' });
  const bucket = Object.values(template.findResources('AWS::S3::Bucket'))[0];
  assert.equal(bucket.DeletionPolicy, 'Retain');
  assert.equal(bucket.Properties.WebsiteConfiguration, undefined);
  assert.equal(bucket.Properties.LifecycleConfiguration, undefined);
  assert.equal(bucket.Properties.BucketEncryption.ServerSideEncryptionConfiguration[0].ServerSideEncryptionByDefault.SSEAlgorithm, 'AES256');
  assert.deepEqual(bucket.Properties.PublicAccessBlockConfiguration, { BlockPublicAcls: true, BlockPublicPolicy: true, IgnorePublicAcls: true, RestrictPublicBuckets: true });
  assert.equal(bucket.Properties.OwnershipControls.Rules[0].ObjectOwnership, 'BucketOwnerEnforced');
  template.hasResourceProperties('AWS::CloudFront::OriginAccessControl', { OriginAccessControlConfig: { SigningBehavior: 'always', SigningProtocol: 'sigv4', OriginAccessControlOriginType: 's3' } });
  const param = template.findParameters('StaticRelease').StaticRelease;
  assert.equal(param.AllowedPattern, '[a-f0-9]{64}');
  assert.equal(param.Default, undefined);
  const policies = Object.values(template.findResources('AWS::S3::BucketPolicy'))[0].Properties.PolicyDocument.Statement;
  const grants = policies.filter((statement: {Effect: string}) => statement.Effect === 'Allow');
  assert.equal(grants.length, 2);
  grants.forEach((statement: Record<string, any>, index: number) => {
    assert.deepEqual(statement.Action, 's3:GetObject');
    assert.deepEqual(statement.Principal, {Service: 'cloudfront.amazonaws.com'});
    assert.ok(JSON.stringify(statement.Resource).includes(index === 0 ? '/app/*' : '/public/*'));
    assert.ok(JSON.stringify(statement.Condition.StringEquals['AWS:SourceArn']).includes(index === 0 ? 'AppDistribution' : 'PublicDistribution'));
  });
  for (const distribution of Object.values(template.findResources('AWS::CloudFront::Distribution'))) {
    const config = distribution.Properties.DistributionConfig;
    assert.equal(config.ViewerCertificate.MinimumProtocolVersion, 'TLSv1.2_2021');
    assert.equal(config.ViewerCertificate.SslSupportMethod, 'sni-only');
    assert.equal(config.DefaultCacheBehavior.TargetOriginId, 'Release');
    assert.equal(config.DefaultCacheBehavior.CachePolicyId, '4135ea2d-6df8-44a3-9df3-4b5a84be39ad');
    assert.equal(config.Origins.length, config.Aliases[0].startsWith('app.') ? 3 : 2);
    assert.ok(JSON.stringify(config.Origins[0].OriginPath).includes('StaticRelease'));
    assert.ok(!JSON.stringify(config.Origins[1].OriginPath).includes('StaticRelease'));
    for (const behavior of [config.DefaultCacheBehavior, ...config.CacheBehaviors]) {
      assert.deepEqual(behavior.AllowedMethods, ['GET', 'HEAD']);
      assert.equal(behavior.ViewerProtocolPolicy, 'redirect-to-https');
      assert.equal(behavior.OriginRequestPolicyId, undefined);
      assert.equal(behavior.FunctionAssociations[0].EventType, 'viewer-request');
    }
    config.CacheBehaviors.forEach((behavior: Record<string, any>) => assert.equal(behavior.TargetOriginId, behavior.PathPattern === '/workspaces/*/pages/*' ? 'ControlPageHtml' : 'SharedAssets'));
    for (const error of config.CustomErrorResponses) {
      assert.equal(error.ResponseCode, error.ErrorCode < 500 ? 404 : undefined);
      assert.equal(error.ResponsePagePath, error.ErrorCode < 500 ? '/404.html' : undefined);
      assert.equal(error.ErrorCachingMinTTL, 0);
    }
    if (config.Aliases[0].startsWith('app.')) {
      assert.equal(config.CacheBehaviors[0].PathPattern, '/assets/chart-widget-worker-*.js');
      assert.deepEqual(config.Origins[2], {Id: 'ControlPageHtml', DomainName: 'api.dev.monomer.cc', CustomOriginConfig: {
        OriginProtocolPolicy: 'https-only', OriginSSLProtocols: ['TLSv1.2'], HTTPSPort: 443,
      }});
      const page = config.CacheBehaviors[2];
      assert.equal(page.PathPattern, '/workspaces/*/pages/*');
      assert.equal(page.CachePolicyId, config.DefaultCacheBehavior.CachePolicyId);
      assert.deepEqual(page.ResponseHeadersPolicyId, config.DefaultCacheBehavior.ResponseHeadersPolicyId);
      assert.notDeepEqual(page.FunctionAssociations, config.DefaultCacheBehavior.FunctionAssociations);
      assert.deepEqual(config.CustomErrorResponses.filter((e: any) => e.ErrorCode >= 500).map((e: any) => e.ErrorCode), [500, 502, 503, 504]);
      assert.notDeepEqual(config.CacheBehaviors[1].ResponseHeadersPolicyId, config.DefaultCacheBehavior.ResponseHeadersPolicyId);
      assert.notDeepEqual(config.CacheBehaviors[1].ResponseHeadersPolicyId, config.CacheBehaviors[0].ResponseHeadersPolicyId);
    }
  }
  const headerPolicies = template.findResources('AWS::CloudFront::ResponseHeadersPolicy');
  for (const distribution of Object.values(template.findResources('AWS::CloudFront::Distribution'))) {
    const config = distribution.Properties.DistributionConfig;
    for (const behavior of [config.DefaultCacheBehavior, ...config.CacheBehaviors]) {
      const response = headerPolicies[behavior.ResponseHeadersPolicyId.Ref].Properties.ResponseHeadersPolicyConfig;
      assert.deepEqual(response.CustomHeadersConfig.Items.find((header: { Header: string }) => header.Header === 'X-Robots-Tag'), {
        Header: 'X-Robots-Tag', Value: 'noindex, nofollow', Override: true,
      });
      if (behavior.PathPattern === '/assets/*') assert.equal(response.SecurityHeadersConfig, undefined);
      if (behavior.PathPattern === '/assets/chart-widget-worker-*.js') assert.equal(response.SecurityHeadersConfig.ContentSecurityPolicy.ContentSecurityPolicy, WORKER_CSP);
      if (config.Aliases[0].startsWith('app.') && !behavior.PathPattern) assert.equal(response.SecurityHeadersConfig.ContentSecurityPolicy.ContentSecurityPolicy, APP_CSP);
    }
  }
  const responses = Object.values(headerPolicies).map(x => x.Properties.ResponseHeadersPolicyConfig);
  assert.ok(responses.some(x => x.SecurityHeadersConfig?.ContentSecurityPolicy.ContentSecurityPolicy === APP_CSP));
  assert.ok(responses.some(x => x.SecurityHeadersConfig?.ContentSecurityPolicy.ContentSecurityPolicy === WORKER_CSP && x.CustomHeadersConfig.Items.some((h: Record<string,string>) => h.Header === 'X-Monomer-Csp-Evidence' && h.Value === 'chart-widget-worker')));
  for (const response of responses) assert.ok(response.CustomHeadersConfig.Items.some((x: Record<string, any>) => x.Header === 'X-Robots-Tag' && x.Value === 'noindex, nofollow' && x.Override));
});


test('Page HTML behavior only rewrites canonical UUID routes and strips credentials and search', () => {
  const workspace = '019e0000-0000-7000-8000-000000000001';
  const page = '019e0000-0000-7000-8000-000000000002';
  const path = `/workspaces/${workspace}/pages/${page}`;
  function pageRoute(uri: string, method = 'GET', host = 'app.dev.monomer.cc') {
    return runInNewContext(`${pageHtmlRoutingCode('app.dev.monomer.cc')}; handler(event)`, {event: {request: {
      uri, method, headers: {host: {value: host}, authorization: {value: 'Bearer private'}, cookie: {value: 'private'}},
      querystring: {session: {value: 'private'}},
    }}});
  }
  for (const method of ['GET', 'HEAD']) {
    const routed = pageRoute(path, method);
    assert.equal(routed.uri, `/v2/public/workspaces/${workspace}/pages/${page}/html`);
    assert.equal(Object.keys(routed.querystring).length, 0);
    assert.equal(routed.headers.authorization, undefined);
    assert.equal(routed.headers.cookie, undefined);
  }
  for (const invalid of [path + '/', path + '/other', path.replace(workspace, workspace.toUpperCase()),
    path.replace('/pages/', '/%70ages/'), path.replace(page, '../' + page), '/workspaces/abc/pages/def',
    path + '%2f', '/index.html']) assert.equal(pageRoute(invalid).statusCode, 404, invalid);
  assert.equal(pageRoute(path, 'POST').statusCode, 404);
  assert.equal(pageRoute(path, 'GET', 'evil.example').statusCode, 404);
  assert.equal(route('app', '/index.html').uri, '/index.html');
});
