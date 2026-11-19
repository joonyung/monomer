import { CfnOutput, RemovalPolicy, Stack, Tags } from 'aws-cdk-lib';
import { IVpc } from 'aws-cdk-lib/aws-ec2';
import { Cluster, ContainerImage, CpuArchitecture, FargateTaskDefinition, LogDrivers, OperatingSystemFamily, Secret } from 'aws-cdk-lib/aws-ecs';
import { Platform } from 'aws-cdk-lib/aws-ecr-assets';
import { LogGroup, RetentionDays } from 'aws-cdk-lib/aws-logs';
import { Role, ServicePrincipal } from 'aws-cdk-lib/aws-iam';
import { Construct } from 'constructs';
import { join } from 'node:path';
import { CloudEnvironment } from './environment';
import { StorageStack } from './storage-stack';

export class ComputeStack extends Stack {
  readonly cluster: Cluster;
  constructor(scope: Construct, id: string, vpc: IVpc, storage: StorageStack, environment: CloudEnvironment) {
    super(scope, id, { env: environment, terminationProtection: true });
    Tags.of(this).add('Project', 'Monomer');
    Tags.of(this).add('Environment', environment.name);
    const cluster = this.cluster = new Cluster(this, 'Cluster', { vpc, clusterName: environment.resourcePrefix });
    const logs = new LogGroup(this, 'DatabaseBootstrapLogs', {
      retention: RetentionDays.ONE_WEEK, removalPolicy: RemovalPolicy.RETAIN,
    });
    const principal = new ServicePrincipal('ecs-tasks.amazonaws.com', {
      conditions: { StringEquals: { 'aws:SourceAccount': this.account } },
    });
    const taskRole = new Role(this, 'DatabaseBootstrapTaskRole', { assumedBy: principal });
    const executionRole = new Role(this, 'DatabaseBootstrapExecutionRole', { assumedBy: principal });
    const task = new FargateTaskDefinition(this, 'DatabaseBootstrapTask', {
      cpu: 256, memoryLimitMiB: 512, taskRole, executionRole,
      runtimePlatform: { cpuArchitecture: CpuArchitecture.X86_64, operatingSystemFamily: OperatingSystemFamily.LINUX },
    });
    task.addContainer('database-bootstrap', {
      image: ContainerImage.fromAsset(join(__dirname, '../database-bootstrap-context'), { platform: Platform.LINUX_AMD64 }),
      environment: {
        PGHOST: storage.database.dbInstanceEndpointAddress,
        PGPORT: '5432', PGDATABASE: 'monomer', PGUSER: 'monomer_admin',
        PGSSLMODE: 'verify-full', PGSSLROOTCERT: '/bootstrap/us-east-1-bundle.pem',
        PGCONNECT_TIMEOUT: '10',
      },
      secrets: {
        PGPASSWORD: Secret.fromSecretsManager(storage.adminSecret, 'password'),
        MONOMER_DATABASE_PASSWORD: Secret.fromSecretsManager(storage.applicationSecret, 'password'),
      },
      logging: LogDrivers.awsLogs({ logGroup: logs, streamPrefix: 'initialize' }),
    });
    new CfnOutput(this, 'ClusterName', { value: cluster.clusterName });
    new CfnOutput(this, 'DatabaseBootstrapTaskArn', { value: task.taskDefinitionArn });
    new CfnOutput(this, 'DatabaseBootstrapLogGroup', { value: logs.logGroupName });
    new CfnOutput(this, 'DatabaseBootstrapSecurityGroup', { value: storage.controlGroup.securityGroupId });
  }
}
