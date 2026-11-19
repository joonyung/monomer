import { cloudEnvironment } from '../src/environment';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { NetworkStack } from '../src/network-stack';
import { StorageStack } from '../src/storage-stack';

const environment = cloudEnvironment('dev');

test('product storage keeps DB private, artifacts upload-only for Execution, and browser origins exact', () => {
  const app = new App();
  const network = new NetworkStack(app, 'MonomerDevNetwork', environment);
  const storage = new StorageStack(app, 'MonomerDevStorage', network.vpc, environment);
  const template = Template.fromStack(storage);
  const resources = template.toJSON().Resources;
  const db = Object.values(template.findResources('AWS::RDS::DBInstance'))[0];
  assert.equal(db.Properties.PubliclyAccessible, false);
  assert.equal(db.Properties.MultiAZ, false);
  assert.equal(db.Properties.StorageEncrypted, true);
  assert.equal(db.Properties.AllocatedStorage, '20');
  assert.equal(db.Properties.DBInstanceClass, 'db.t4g.small');
  assert.equal(db.Properties.BackupRetentionPeriod, 7);
  assert.equal(db.Properties.DeletionProtection, true);
  assert.equal(db.DeletionPolicy, 'Snapshot');
  assert.equal(storage.terminationProtection, true);
  for (const secret of Object.values(template.findResources('AWS::SecretsManager::Secret'))) {
    assert.equal(secret.DeletionPolicy, 'Retain');
    assert.equal(secret.UpdateReplacePolicy, 'Retain');
  }
  const parameters = Object.values(template.findResources('AWS::RDS::DBParameterGroup'))[0];
  assert.equal(parameters.Properties.Parameters['rds.force_ssl'], '1');
  const dbGroupId = Object.keys(resources).find(id => id.startsWith('DatabaseSecurityGroup') && resources[id].Type === 'AWS::EC2::SecurityGroup')!;
  const rules = Object.values(template.findResources('AWS::EC2::SecurityGroupIngress'));
  assert.equal(rules.length, 2);
  const ingress = rules.find(r => r.Properties.FromPort === 5432)!.Properties;
  assert.equal(ingress.GroupId['Fn::GetAtt'][0], dbGroupId);
  assert.equal(ingress.FromPort, 5432);
  assert.equal(ingress.ToPort, 5432);
  assert.ok(ingress.SourceSecurityGroupId['Fn::GetAtt'][0].startsWith('ControlSecurityGroup'));
  assert.equal(ingress.CidrIp, undefined);
  const subnets = Object.values(template.findResources('AWS::RDS::DBSubnetGroup'))[0].Properties.SubnetIds;
  assert.equal(subnets.length, 2);
  assert.ok(subnets.every((s: unknown) => JSON.stringify(s).includes('DatabaseSubnet')));

  const policies = Object.values(template.findResources('AWS::IAM::Policy'));
  const executionPolicy = policies.find(p => p.Properties.Roles.some((r: {Ref: string}) => r.Ref.startsWith('ExecutionTaskRole')))!;
  const executionStatements = executionPolicy.Properties.PolicyDocument.Statement;
  assert.equal(executionStatements.length, 1);
  assert.deepEqual(executionStatements[0].Action.sort(), ['s3:AbortMultipartUpload', 's3:PutObject']);
  assert.ok(JSON.stringify(executionStatements[0].Resource).includes('/artifacts/*'));
  assert.ok(JSON.stringify(executionStatements[0].Resource).includes('ExecutionArtifacts'));
  const controlPolicy = policies.find(p => p.Properties.Roles.some((r: {Ref: string}) => r.Ref.startsWith('ControlTaskRole')))!;
  const controlStatements = controlPolicy.Properties.PolicyDocument.Statement;
  assert.equal(controlStatements.length, 3);
  const pageStatement = controlStatements.find((s: {Resource: unknown}) => JSON.stringify(s.Resource).includes('/page-content/*'));
  assert.deepEqual(pageStatement.Action.sort(), ['s3:AbortMultipartUpload', 's3:DeleteObject', 's3:GetObject', 's3:PutObject']);
  const resultStatement = controlStatements.find((s: {Resource: unknown}) => JSON.stringify(s.Resource).includes('/artifacts/*'));
  assert.deepEqual(resultStatement.Action.sort(), ['s3:DeleteObject', 's3:GetObject']);
  assert.ok(controlStatements.some((s: {Action: string | string[]}) => s.Action === 's3:ListBucket' || s.Action.includes('s3:ListBucket')));
  assert.ok(!JSON.stringify(policies).includes('secretsmanager:GetSecretValue'));
  assert.ok(!JSON.stringify(policies).includes('rds-db:connect'));

  const buckets = Object.values(template.findResources('AWS::S3::Bucket'));
  assert.equal(buckets.length, 2);
  for (const bucket of buckets) {
    assert.equal(bucket.DeletionPolicy, 'Retain');
    assert.ok(Object.values(bucket.Properties.PublicAccessBlockConfiguration).every(v => v === true));
    assert.equal(bucket.Properties.LifecycleConfiguration, undefined);
    assert.deepEqual(bucket.Properties.CorsConfiguration.CorsRules[0].AllowedOrigins, ['https://app.dev.monomer.cc']);
  }
  assert.ok(!JSON.stringify(template.findResources('AWS::S3::BucketPolicy')).includes('aws:SourceVpce'));
  assert.equal(Object.keys(template.findResources('AWS::EC2::VPC')).length, 0);
});
