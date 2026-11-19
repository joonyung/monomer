import { cloudEnvironment } from '../src/environment';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { NetworkStack } from '../src/network-stack';

const environment = cloudEnvironment('dev');

test('dev network preserves isolated DB routes and uses one NAT with an S3 bypass', () => {
  const stack = new NetworkStack(new App(), 'MonomerDevNetwork', environment);
  assert.equal(stack.account, environment.account);
  assert.equal(stack.region, 'us-east-1');
  assert.equal(stack.terminationProtection, true);
  const template = Template.fromStack(stack);
  template.resourceCountIs('AWS::EC2::VPC', 1);
  template.resourceCountIs('AWS::EC2::Subnet', 6);
  template.resourceCountIs('AWS::EC2::NatGateway', 1);
  template.resourceCountIs('AWS::EC2::EIP', 1);
  template.resourceCountIs('AWS::EC2::VPCEndpoint', 1);
  template.resourceCountIs('AWS::EC2::InternetGateway', 1);
  const resources = template.toJSON().Resources;
  const subnetIds = Object.keys(template.findResources('AWS::EC2::Subnet'));
  const associations = Object.values(template.findResources('AWS::EC2::SubnetRouteTableAssociation'));
  const routes = Object.values(template.findResources('AWS::EC2::Route'));
  const endpoint = Object.values(template.findResources('AWS::EC2::VPCEndpoint'))[0].Properties;
  assert.equal(endpoint.VpcEndpointType, 'Gateway');
  assert.deepEqual(endpoint.ServiceName, { 'Fn::Join': ['', ['com.amazonaws.', { Ref: 'AWS::Region' }, '.s3']] });
  const natIds = Object.keys(template.findResources('AWS::EC2::NatGateway'));
  for (const subnetId of subnetIds) {
    const subnet = resources[subnetId].Properties;
    assert.equal(subnet.MapPublicIpOnLaunch, false);
    const association = associations.find(a => a.Properties.SubnetId.Ref === subnetId)!;
    const routeTable = association.Properties.RouteTableId;
    const subnetRoutes = routes.filter(r => JSON.stringify(r.Properties.RouteTableId) === JSON.stringify(routeTable));
    const usesS3 = endpoint.RouteTableIds.some((r: unknown) => JSON.stringify(r) === JSON.stringify(routeTable));
    if (subnetId.includes('Database')) {
      assert.equal(subnetRoutes.length, 0, 'database must have no internet default route');
      assert.equal(usesS3, false);
    } else if (subnetId.includes('Application')) {
      assert.equal(subnetRoutes.length, 1);
      assert.equal(subnetRoutes[0].Properties.NatGatewayId.Ref, natIds[0]);
      assert.equal(usesS3, true, 'both application AZs must bypass NAT for S3');
    } else {
      assert.equal(subnetRoutes.length, 1);
      assert.ok(subnetRoutes[0].Properties.GatewayId);
    }
  }
  template.resourceCountIs('Custom::VpcRestrictDefaultSG', 1);
  for (const type of ['AWS::RDS::DBInstance', 'AWS::ECS::Service', 'AWS::ElasticLoadBalancingV2::LoadBalancer']) {
    template.resourceCountIs(type, 0);
  }
});
