import { cloudEnvironment } from '../src/environment';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { NetworkStack } from '../src/network-stack';
import { StorageStack } from '../src/storage-stack';
import { ComputeStack } from '../src/compute-stack';

const environment = cloudEnvironment('dev');

test('bootstrap is an operator-run task, secrets scoped to startup role, with full TLS verification', () => {
  const app = new App();
  const network = new NetworkStack(app, 'MonomerDevNetwork', environment);
  const storage = new StorageStack(app, 'MonomerDevStorage', network.vpc, environment);
  const compute = new ComputeStack(app, 'MonomerDevCompute', network.vpc, storage, environment);
  const template = Template.fromStack(compute);
  template.resourceCountIs('AWS::ECS::Service', 0);
  template.resourceCountIs('AWS::EC2::SecurityGroupIngress', 0);
  const task = Object.values(template.findResources('AWS::ECS::TaskDefinition'))[0].Properties;
  const container = task.ContainerDefinitions[0];
  assert.ok(container.Environment.some((e: { Name: string; Value: string }) => e.Name === 'PGSSLMODE' && e.Value === 'verify-full'));
  assert.equal(container.Secrets.length, 2);
  assert.ok(container.Secrets.every((s: { ValueFrom: unknown }) => JSON.stringify(s.ValueFrom).includes('password')));
  const policies = Object.values(template.findResources('AWS::IAM::Policy'));
  const readers = policies.filter(p => JSON.stringify(p).includes('secretsmanager:GetSecretValue'));
  assert.equal(readers.length, 1);
  assert.ok(readers[0].Properties.Roles[0].Ref.startsWith('DatabaseBootstrapExecutionRole'));
  assert.ok(!JSON.stringify(policies).includes('secretsmanager:PutSecretValue'));
  for (const role of Object.values(template.findResources('AWS::IAM::Role'))) {
    assert.equal(role.Properties.AssumeRolePolicyDocument.Statement[0].Condition.StringEquals['aws:SourceAccount'], '111122223333');
  }
});
