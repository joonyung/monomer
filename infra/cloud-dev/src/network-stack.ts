import { CfnOutput, Stack, Tags } from 'aws-cdk-lib';
import { GatewayVpcEndpointAwsService, IpAddresses, SubnetType, Vpc } from 'aws-cdk-lib/aws-ec2';
import { Construct } from 'constructs';

import { CloudEnvironment } from './environment';

export class NetworkStack extends Stack {
  readonly vpc: Vpc;
  private readonly cloudConfig: CloudEnvironment;
  override get availabilityZones(): string[] {
    return this.cloudConfig.availabilityZones;
  }

  constructor(scope: Construct, id: string, environment: CloudEnvironment) {
    super(scope, id, {
      env: environment,
      terminationProtection: true,
      description: `Monomer ${environment.name} network.`,
    });
    this.cloudConfig = environment;
    Tags.of(this).add('Project', 'Monomer');
    Tags.of(this).add('Environment', environment.name);

    const vpc = this.vpc = new Vpc(this, 'Vpc', {
      vpcName: environment.resourcePrefix,
      ipAddresses: IpAddresses.cidr(environment.vpcCidr),
      availabilityZones: this.availabilityZones,
      natGateways: 1,
      enableDnsHostnames: true,
      enableDnsSupport: true,
      restrictDefaultSecurityGroup: true,
      subnetConfiguration: [
        { name: 'Public', subnetType: SubnetType.PUBLIC, cidrMask: 24, mapPublicIpOnLaunch: false },
        { name: 'Application', subnetType: SubnetType.PRIVATE_WITH_EGRESS, cidrMask: 24 },
        { name: 'Database', subnetType: SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
      gatewayEndpoints: {
        S3: {
          service: GatewayVpcEndpointAwsService.S3,
          subnets: [{ subnetType: SubnetType.PRIVATE_WITH_EGRESS }],
        },
      },
    });

    new CfnOutput(this, 'VpcId', { value: vpc.vpcId });
    new CfnOutput(this, 'PublicSubnetIds', { value: vpc.publicSubnets.map(s => s.subnetId).join(',') });
    new CfnOutput(this, 'ApplicationSubnetIds', { value: vpc.privateSubnets.map(s => s.subnetId).join(',') });
    new CfnOutput(this, 'DatabaseSubnetIds', { value: vpc.isolatedSubnets.map(s => s.subnetId).join(',') });
  }
}
