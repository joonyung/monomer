import { cloudEnvironment } from '../src/environment';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { NetworkStack } from '../src/network-stack';
import { StorageStack } from '../src/storage-stack';
import { ComputeStack } from '../src/compute-stack';
import { ServicesStack } from '../src/services-stack';
import { CertificateStack } from '../src/certificate-stack';

const environment = cloudEnvironment('dev');

test('private services use fixed digests, backend rolling overlap, and startup-only secrets', () => {
  const app = new App();
  const network = new NetworkStack(app, 'MonomerDevNetwork', environment);
  const storage = new StorageStack(app, 'MonomerDevStorage', network.vpc, environment);
  const compute = new ComputeStack(app, 'MonomerDevCompute', network.vpc, storage, environment);
  const certificate = new CertificateStack(app, 'MonomerDevCertificate', environment);
  const template = Template.fromStack(new ServicesStack(app, 'MonomerDevServices', network.vpc, compute.cluster, storage, certificate.certificate, environment));
  const allowedEmailsParameter = template.findParameters('ControlAllowedEmails').ControlAllowedEmails;
  assert.equal(allowedEmailsParameter.Type, 'String');
  assert.equal(allowedEmailsParameter.NoEcho, true);
  assert.equal(allowedEmailsParameter.MinLength, 1);
  assert.equal(allowedEmailsParameter.MaxLength, 4096);
  assert.equal(allowedEmailsParameter.Default, undefined);
  const allowedEmailsPattern = new RegExp(`^(?:${allowedEmailsParameter.AllowedPattern})$`);
  assert.ok(allowedEmailsPattern.test('first@example.com, second@example.com'));
  assert.equal(allowedEmailsPattern.test(''), false);
  assert.equal(allowedEmailsPattern.test('*@example.com'), false);
  const operationsParameter = template.findParameters('ControlOperationsAllowedUserIds').ControlOperationsAllowedUserIds;
  assert.equal(operationsParameter.Default, '');
  const operationsPattern = new RegExp(`^(?:${operationsParameter.AllowedPattern})$`);
  for (const value of ['', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001,00000000-0000-0000-0000-000000000002']) assert.ok(operationsPattern.test(value));
  for (const value of ['operator@example.com', '*', 'ADMIN', '00000000-0000-0000-0000-000000000001,']) assert.equal(operationsPattern.test(value), false);
  const invitationFromParameter = template.findParameters('ControlInvitationFromAddress').ControlInvitationFromAddress;
  assert.equal(invitationFromParameter.Default, undefined);
  assert.equal(invitationFromParameter.MaxLength, 254);
  const invitationFromPattern = new RegExp(`^(?:${invitationFromParameter.AllowedPattern})$`);
  assert.ok(invitationFromPattern.test('invites+dev@example.com'));
  for (const invalid of ['', '*@example.com', 'Monomer <invites@example.com>', 'invites@example.com\nBcc:other@example.com', 'invites@*', 'invite?@example.com', 'invites..dev@example.com']) {
    assert.equal(invitationFromPattern.test(invalid), false);
  }
  const invitationRecipientParameter = template.findParameters('ControlInvitationRecipientAddress').ControlInvitationRecipientAddress;
  assert.equal(invitationRecipientParameter.Type, 'String');
  assert.equal(invitationRecipientParameter.MinLength, 3);
  assert.equal(invitationRecipientParameter.Default, undefined);
  assert.equal(invitationRecipientParameter.MaxLength, 254);
  const invitationRecipientPattern = new RegExp(`^(?:${invitationRecipientParameter.AllowedPattern})$`);
  assert.ok(invitationRecipientPattern.test('invites+dev@example.com'));
  for (const invalid of ['', '*@example.com', 'Monomer <invites@example.com>', 'invites@example.com\nBcc:other@example.com', 'invites@*', 'invite?@example.com', 'invites..dev@example.com']) {
    assert.equal(invitationRecipientPattern.test(invalid), false);
  }
  for (const name of ['ControlInvitationReplyToAddress', 'ControlInvitationFeedbackForwardingEmailAddress', 'ControlInvitationSenderIdentity']) {
    const parameter = template.findParameters(name)[name];
    assert.equal(parameter.Default, '');
    assert.equal(parameter.MaxLength, 254);
    const pattern = new RegExp(`^(?:${parameter.AllowedPattern})$`);
    for (const valid of ['', 'mailbox@example.com']) assert.ok(pattern.test(valid));
    assert.equal(pattern.test('example.com'), name === 'ControlInvitationSenderIdentity');
    for (const invalid of ['*', '*.example.com', 'mail?@example.com', 'a@example.com,b@example.com', 'Name <a@example.com>', 'a@example.com\nBcc:b@example.com', 'a@example.com/extra', 'a..b@example.com']) {
      assert.equal(pattern.test(invalid), false, `${name}: ${invalid}`);
    }
  }
  assert.deepEqual(template.toJSON().Conditions.HasInvitationSenderIdentity, {
    'Fn::Not': [{ 'Fn::Equals': [{ Ref: 'ControlInvitationSenderIdentity' }, ''] }],
  });
  assert.deepEqual(template.toJSON().Conditions.HasInvitationFeedbackAddress, {
    'Fn::Not': [{ 'Fn::Equals': [{ Ref: 'ControlInvitationFeedbackForwardingEmailAddress' }, ''] }],
  });
  assert.equal(Object.keys(template.toJSON().Resources).some(id => id.startsWith('Web')), false);
  assert.equal(template.toJSON().Parameters.WebImage, undefined);
  assert.equal(template.toJSON().Outputs.WebServiceName, undefined);
  template.resourceCountIs('AWS::ECS::Service', 2);
  template.resourceCountIs('AWS::ElasticLoadBalancingV2::LoadBalancer', 1);
  template.resourceCountIs('AWS::ElasticLoadBalancingV2::TargetGroup', 1);
  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
    Port: 443, Protocol: 'HTTPS', DefaultActions: [{ Type: 'fixed-response', FixedResponseConfig: { StatusCode: '404', ContentType: 'text/plain', MessageBody: 'Not found' } }],
  });
  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', { Port: 80, DefaultActions: [{ Type: 'redirect', RedirectConfig: { Protocol: 'HTTPS', Port: '443', StatusCode: 'HTTP_301' } }] });
  const groups = Object.values(template.findResources('AWS::ElasticLoadBalancingV2::TargetGroup'));
  assert.deepEqual(groups.map(g => g.Properties.Port).sort(), [11077]);
  for (const group of groups) assert.equal(group.Properties.HealthyThresholdCount, 2);
  Template.fromStack(certificate).hasResourceProperties('AWS::CertificateManager::Certificate', {
    DomainName: 'app.dev.monomer.cc', SubjectAlternativeNames: ['api.dev.monomer.cc'], ValidationMethod: 'DNS',
  });
  Template.fromStack(certificate).hasResourceProperties('AWS::CertificateManager::Certificate', {
    DomainName: 'dev.monomer.cc', ValidationMethod: 'DNS',
  });
  template.resourceCountIs('AWS::ElasticLoadBalancingV2::ListenerCertificate', 0);
  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::ListenerRule', {
    Conditions: [{ Field: 'host-header', HostHeaderConfig: { Values: ['api.dev.monomer.cc'] } }],
  });
  assert.deepEqual(template.toJSON().Parameters.ControlDesiredCount, {
    Type: 'Number', Default: 1, AllowedValues: ['0', '1'],
    Description: 'Keep Control stopped while installing a cutover task definition; restore only after verification.',
  });
  for (const service of Object.values(template.findResources('AWS::ECS::Service'))) {
    assert.equal(service.Properties.NetworkConfiguration.AwsvpcConfiguration.AssignPublicIp, 'DISABLED');
    assert.deepEqual(service.Properties.DesiredCount, JSON.stringify(service.Properties.TaskDefinition).includes('ControlTask') ? { Ref: 'ControlDesiredCount' } : 1);
    assert.equal(service.Properties.DeploymentConfiguration.MaximumPercent, 200);
    assert.equal(service.Properties.DeploymentConfiguration.MinimumHealthyPercent, 100);
    if (service.Properties.LoadBalancers) assert.equal(service.Properties.HealthCheckGracePeriodSeconds, 180);
  }
  const tasks = Object.values(template.findResources('AWS::ECS::TaskDefinition'));
  assert.deepEqual(
    Object.fromEntries(tasks.map(task => [task.Properties.ContainerDefinitions[0].Name, [task.Properties.Cpu, task.Properties.Memory]])),
    { control: ['1024', '2048'], execution: ['512', '2048'] },
  );
  for (const task of tasks) {
    const container = task.Properties.ContainerDefinitions[0];
    const environment = (container.Environment ?? []) as Array<{Name: string; Value: unknown}>;
    assert.equal(environment.find(e => e.Name === 'LOGGING_STRUCTURED_FORMAT_CONSOLE')?.Value, 'ecs');
    if (container.Name !== 'control') assert.equal(environment.some(e => e.Name.startsWith('MONOMER_INVITATION_')), false);
    assert.ok(container.Image.Ref.endsWith('Image'));
    if (container.Name === 'execution') {
      assert.equal(container.Secrets, undefined);
      assert.equal(environment.some(e => e.Name === 'MONOMER_CONTROL_DEVELOPER_ALLOWED_EMAILS'), false);
      assert.equal(environment.some(e => e.Name === 'MONOMER_OPERATIONS_ALLOWED_USER_IDS'), false);
      assert.equal(JSON.stringify(container).includes('ControlAllowedEmails'), false);
    }
    else {
      assert.equal(container.Secrets.length, 5);
      assert.equal(environment.find(e => e.Name === 'MONOMER_CONTROL_AUTH_LOCAL_LOGIN_ENABLED')?.Value, 'false');
      assert.equal(environment.find(e => e.Name === 'MONOMER_CONTROL_AUTH_REFRESH_COOKIE_SECURE')?.Value, 'true');
      assert.equal(environment.find(e => e.Name === 'MONOMER_CONTROL_DOCS_ORIGIN')?.Value, 'https://dev.monomer.cc');
      assert.ok(container.Secrets.some((s: {Name: string}) => s.Name === 'MONOMER_CONTROL_GOOGLE_OAUTH_CLIENT_SECRET'));
      assert.ok(container.Environment.some((e: {Name: string; Value: string}) => e.Name === 'MONOMER_CONTROL_GOOGLE_OAUTH_ENABLED' && e.Value === 'true'));
      assert.ok(container.Environment.some((e: {Name: string; Value: string}) => e.Name === 'MONOMER_CONTROL_GOOGLE_OAUTH_REDIRECT_URI' && e.Value === 'https://api.dev.monomer.cc/login/oauth2/code/google'));
      assert.ok(!JSON.stringify(container.Secrets).includes('AdminSecret'));
      assert.ok(container.Environment.some((e: {Name: string; Value: string}) => e.Name === 'MONOMER_CONTROL_DEVELOPER_ACCESS_MODE' && e.Value === 'RESTRICTED'));
      assert.deepEqual(environment.find(e => e.Name === 'MONOMER_CONTROL_DEVELOPER_ALLOWED_EMAILS')?.Value, { Ref: 'ControlAllowedEmails' });
      assert.deepEqual(environment.find(e => e.Name === 'MONOMER_OPERATIONS_ALLOWED_USER_IDS')?.Value, { Ref: 'ControlOperationsAllowedUserIds' });
      assert.equal(environment.find(e => e.Name === 'MONOMER_INVITATION_DELIVERY_MODE')?.Value, 'ses');
      assert.equal(environment.find(e => e.Name === 'MONOMER_INVITATION_SES_REGION')?.Value, 'us-east-1');
      assert.deepEqual(environment.find(e => e.Name === 'MONOMER_INVITATION_SES_FROM_ADDRESS')?.Value, { Ref: 'ControlInvitationFromAddress' });
      assert.deepEqual(environment.find(e => e.Name === 'MONOMER_INVITATION_SES_REPLY_TO_ADDRESS')?.Value, { Ref: 'ControlInvitationReplyToAddress' });
      assert.deepEqual(environment.find(e => e.Name === 'MONOMER_INVITATION_SES_FEEDBACK_FORWARDING_EMAIL_ADDRESS')?.Value, { Ref: 'ControlInvitationFeedbackForwardingEmailAddress' });
    }
  }
  const permissions = Object.values(template.findResources('AWS::IAM::Policy'));
  assert.ok(!JSON.stringify(permissions).includes('DatabaseAdminSecret'));
  const readers = permissions.filter(p => JSON.stringify(p).includes('secretsmanager:GetSecretValue'));
  assert.equal(readers.length, 1);
  assert.deepEqual(readers.map(p => p.Properties.Roles[0].Ref.replace(/[A-F0-9]{8}$/, '')).sort(), ['ControlStartupRole']);
  const invitationPolicies = Object.entries(template.findResources('AWS::IAM::Policy')).filter(([, p]) => JSON.stringify(p).includes('ses:'));
  assert.equal(invitationPolicies.length, 1);
  const [invitationPolicyId, invitationPolicy] = invitationPolicies[0];
  const controlTask = tasks.find(t => t.Properties.ContainerDefinitions[0].Name === 'control')!;
  assert.equal(invitationPolicy.Properties.Roles.length, 1);
  assert.deepEqual(invitationPolicy.Properties.Roles[0], {
    'Fn::Select': [1, { 'Fn::Split': ['/', { 'Fn::Select': [5, { 'Fn::Split': [':', controlTask.Properties.TaskRoleArn] }] }] }],
  });
  assert.ok(JSON.stringify(controlTask.Properties.TaskRoleArn).includes('ControlTaskRole'));
  assert.deepEqual(invitationPolicy.Properties.PolicyDocument.Statement, [{
    Effect: 'Allow', Action: 'ses:SendEmail',
    Resource: [
      { 'Fn::Join': ['', ['arn:aws:ses:us-east-1:111122223333:identity/', { 'Fn::If': ['HasInvitationSenderIdentity', { Ref: 'ControlInvitationSenderIdentity' }, { Ref: 'ControlInvitationFromAddress' }] }]] },
      { 'Fn::Join': ['', ['arn:aws:ses:us-east-1:111122223333:identity/', { Ref: 'ControlInvitationRecipientAddress' }]] },
      { 'Fn::If': ['HasInvitationFeedbackAddress', { 'Fn::Join': ['', ['arn:aws:ses:us-east-1:111122223333:identity/', { Ref: 'ControlInvitationFeedbackForwardingEmailAddress' }]] }, { Ref: 'AWS::NoValue' }] },
    ],
    Condition: { StringEquals: { 'ses:FromAddress': { Ref: 'ControlInvitationFromAddress' } } },
  }, {
    Effect: 'Allow', Action: 'ses:ApplyTrackingConfigurationOverrides', Resource: '*',
    Condition: { StringEquals: {
      'ses:OpenTrackingEnabled': 'DISABLED',
      'ses:ClickTrackingEnabled': 'DISABLED',
    } },
  }]);
  assert.equal(JSON.stringify(tasks).includes('ControlInvitationRecipientAddress'), false);
  assert.equal(JSON.stringify(Template.fromStack(storage).toJSON()).includes('ControlInvitationRecipientAddress'), false);
  const controlService = Object.values(template.findResources('AWS::ECS::Service')).find(s => JSON.stringify(s.Properties.TaskDefinition).includes('ControlTask'))!;
  assert.ok(controlService.DependsOn.includes(invitationPolicyId));
  assert.equal(JSON.stringify(Template.fromStack(storage).toJSON()).includes('ControlInvitationFromAddress'), false);
  assert.equal(JSON.stringify(Template.fromStack(storage).toJSON()).includes('ExportsOutputRefControlTaskRole'), false);
  app.synth();
});

for (const name of ['dev', 'prod'] as const) {
  test(`${name} Execution OOM email alert is scoped to the exact service and excludes SQL`, () => {
    const environment = cloudEnvironment(name);
    const app = new App();
    const network = new NetworkStack(app, 'OomNetwork', environment);
    const storage = new StorageStack(app, 'OomStorage', network.vpc, environment);
    const compute = new ComputeStack(app, 'OomCompute', network.vpc, storage, environment);
    const certificate = new CertificateStack(app, 'OomCertificate', environment);
    const template = Template.fromStack(new ServicesStack(app, 'OomServices', network.vpc, compute.cluster, storage, certificate.certificate, environment));
    template.resourceCountIs('AWS::Events::Rule', 1);
    template.resourceCountIs('AWS::SNS::Topic', 1);
    template.hasResourceProperties('AWS::SNS::Subscription', { Protocol: 'email', Endpoint: 'alerts@example.com' });
    const [rule] = Object.values(template.findResources('AWS::Events::Rule'));
    assert.deepEqual(rule.Properties.EventPattern.source, ['aws.ecs']);
    assert.deepEqual(rule.Properties.EventPattern['detail-type'], ['ECS Task State Change']);
    const detail = rule.Properties.EventPattern.detail;
    assert.deepEqual(detail.lastStatus, ['STOPPED']);
    const executionService = Object.values(template.findResources('AWS::ECS::Service'))
      .find(service => JSON.stringify(service.Properties.TaskDefinition).includes('ExecutionTask'))!;
    const clusterNameImport = executionService.Properties.Cluster;
    assert.deepEqual(detail.clusterArn, [{
      'Fn::Join': ['', ['arn:', { Ref: 'AWS::Partition' }, `:ecs:${environment.region}:${environment.account}:cluster/`, clusterNameImport]],
    }], 'OOM rule uses the same cluster name import as the running Execution service');
    const computeTemplate = Template.fromStack(compute);
    const [clusterId, cluster] = Object.entries(computeTemplate.findResources('AWS::ECS::Cluster'))[0];
    const computeExports = Object.values(computeTemplate.toJSON().Outputs)
      .filter((output: any) => output.Export) as Array<{ Value: unknown; Export: { Name: string } }>;
    assert.deepEqual(computeExports.map(output => output.Value), [{ Ref: clusterId }],
      'Services does not require a new Compute cluster ARN export');
    assert.deepEqual(clusterNameImport, { 'Fn::ImportValue': computeExports[0].Export.Name });
    const [arnPrefix, partition, clusterResource, importedName] = detail.clusterArn[0]['Fn::Join'][1];
    assert.deepEqual(partition, { Ref: 'AWS::Partition' });
    assert.deepEqual(importedName, clusterNameImport);
    assert.equal(`${arnPrefix}aws${clusterResource}${cluster.Properties.ClusterName}`,
      `arn:aws:ecs:${environment.region}:${environment.account}:cluster/${environment.resourcePrefix}`,
      'composed ARN matches the ECS runtime cluster ARN');
    assert.ok(JSON.stringify(detail.group).includes('ExecutionService'));
    assert.equal(JSON.stringify(detail.group).includes('ControlService'), false);
    assert.deepEqual(detail.$or, [
      { stoppedReason: [{ wildcard: '*OutOfMemory*' }] },
      { containers: { reason: [{ wildcard: '*OutOfMemory*' }] } },
    ]);
    assert.equal(rule.Properties.Targets.length, 1);
    const input = rule.Properties.Targets[0].InputTransformer;
    assert.ok(input.InputTemplate.includes('Inspect container memory limits'));
    const placeholderFor = (path: string) => {
      const entry = Object.entries(input.InputPathsMap).find(([, value]) => value === path);
      assert.ok(entry, `missing OOM email input path ${path}`);
      return `<${entry[0]}>`;
    };
    const lines = input.InputTemplate.split('\n');
    assert.equal(lines.length, 5, 'EventBridge plain text uses individually quoted lines with real line breaks');
    assert.equal(input.InputTemplate.includes('\\n'), false, 'SNS must not receive escaped newline text');
    assert.deepEqual(lines.map((line: string) => JSON.parse(line)), [
      `Monomer ${name} Execution stopped because of an out-of-memory error.`,
      `Event time: ${placeholderFor('$.time')}`,
      `Task: ${placeholderFor('$.detail.taskArn')}`,
      `Stopped reason: ${placeholderFor('$.detail.stoppedReason')}`,
      'Inspect container memory limits and recent execution runs before restarting.',
    ]);
    assert.throws(() => JSON.parse(input.InputTemplate), 'the whole email must not be one quoted JSON string');
    assert.ok(Object.values(input.InputPathsMap).includes('$.detail.taskArn'));
    assert.equal(Object.values(input.InputPathsMap).some(path => String(path).includes('overrides')), false);
    const [policy] = Object.values(template.findResources('AWS::SNS::TopicPolicy'));
    assert.ok(policy.Properties.PolicyDocument.Statement.some((statement: { Principal?: { Service?: string }; Action?: string }) =>
      statement.Principal?.Service === 'events.amazonaws.com' && statement.Action === 'sns:Publish'));
    template.resourceCountIs('AWS::ECS::Service', 2);
  });
  test(`${name} Control and Execution select ECS JSON console logging`, () => {
    const environment = cloudEnvironment(name);
    const app = new App();
    const network = new NetworkStack(app, 'LoggingNetwork', environment);
    const storage = new StorageStack(app, 'LoggingStorage', network.vpc, environment);
    const compute = new ComputeStack(app, 'LoggingCompute', network.vpc, storage, environment);
    const certificate = new CertificateStack(app, 'LoggingCertificate', environment);
    const template = Template.fromStack(new ServicesStack(app, 'LoggingServices', network.vpc, compute.cluster, storage, certificate.certificate, environment));
    const containers = Object.values(template.findResources('AWS::ECS::TaskDefinition'))
      .map(task => task.Properties.ContainerDefinitions[0]);
    assert.deepEqual(containers.map(container => container.Name).sort(), ['control', 'execution']);
    for (const container of containers) {
      assert.equal(container.Environment.find((entry: {Name: string}) => entry.Name === 'LOGGING_STRUCTURED_FORMAT_CONSOLE').Value, 'ecs');
    }
  });
}
