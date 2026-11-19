import { CfnOutput, Duration, RemovalPolicy, Stack, Tags } from 'aws-cdk-lib';
import { InstanceClass, InstanceSize, InstanceType, IVpc, Port, SecurityGroup, SubnetType } from 'aws-cdk-lib/aws-ec2';
import { PolicyStatement, Role, ServicePrincipal } from 'aws-cdk-lib/aws-iam';
import { Credentials, DatabaseInstance, DatabaseInstanceEngine, ParameterGroup, PostgresEngineVersion, StorageType } from 'aws-cdk-lib/aws-rds';
import { BlockPublicAccess, Bucket, BucketEncryption, HttpMethods, ObjectOwnership } from 'aws-cdk-lib/aws-s3';
import { Secret } from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';
import { CloudEnvironment } from './environment';

export class StorageStack extends Stack {
  readonly controlRuntimeSecret: Secret;
  readonly executionGroup: SecurityGroup;
  readonly controlRole: Role;
  readonly executionRole: Role;
  readonly pageBucket: Bucket;
  readonly artifactBucket: Bucket;
  readonly database: DatabaseInstance;
  readonly adminSecret: Secret;
  readonly applicationSecret: Secret;
  readonly controlGroup: SecurityGroup;
  constructor(scope: Construct, id: string, vpc: IVpc, environment: CloudEnvironment) {
    super(scope, id, { env: environment, terminationProtection: true });
    Tags.of(this).add('Project', 'Monomer');
    Tags.of(this).add('Environment', environment.name);
    const controlGroup = this.controlGroup = new SecurityGroup(this, 'ControlSecurityGroup', {
      vpc, description: 'Monomer Control tasks', allowAllOutbound: true,
    });
    const executionGroup = this.executionGroup = new SecurityGroup(this, 'ExecutionSecurityGroup', {
      vpc, description: 'Monomer external DW executor tasks', allowAllOutbound: true,
    });
    const databaseGroup = new SecurityGroup(this, 'DatabaseSecurityGroup', {
      vpc, description: 'Product PostgreSQL accepts only Control', allowAllOutbound: false,
    });
    databaseGroup.addIngressRule(controlGroup, Port.tcp(5432), 'Control to PostgreSQL');
    executionGroup.addIngressRule(controlGroup, Port.tcp(11099), 'Control to Execution');

    const engine = DatabaseInstanceEngine.postgres({ version: PostgresEngineVersion.of('17.10', '17') });
    const parameters = new ParameterGroup(this, 'PostgresParameters', {
      engine, parameters: { 'rds.force_ssl': '1' },
    });
    const adminSecret = this.adminSecret = new Secret(this, 'DatabaseAdminSecret', {
      generateSecretString: { secretStringTemplate: JSON.stringify({ username: 'monomer_admin' }),
        generateStringKey: 'password', excludePunctuation: true, passwordLength: 40 },
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const database = this.database = new DatabaseInstance(this, 'Database', {
      vpc, vpcSubnets: { subnetType: SubnetType.PRIVATE_ISOLATED },
      securityGroups: [databaseGroup],
      engine, parameterGroup: parameters,
      instanceIdentifier: environment.resourcePrefix, databaseName: 'monomer',
      instanceType: InstanceType.of(InstanceClass.T4G, InstanceSize.SMALL),
      credentials: Credentials.fromSecret(adminSecret),
      allocatedStorage: 20, storageType: StorageType.GP3, storageEncrypted: true,
      multiAz: false, publiclyAccessible: false,
      backupRetention: Duration.days(7), deleteAutomatedBackups: false,
      deletionProtection: true, removalPolicy: RemovalPolicy.SNAPSHOT,
      autoMinorVersionUpgrade: true, allowMajorVersionUpgrade: false,
      enablePerformanceInsights: false,
    });

    this.applicationSecret = new Secret(this, 'DatabaseApplicationSecret', {
      generateSecretString: { secretStringTemplate: JSON.stringify({ username: 'monomer' }),
        generateStringKey: 'password', excludePunctuation: true, passwordLength: 40 },
      removalPolicy: RemovalPolicy.RETAIN,
    });

    this.controlRuntimeSecret = new Secret(this, 'ControlRuntimeSecret', { removalPolicy: RemovalPolicy.RETAIN });

    const pageBucket = this.pageBucket = new Bucket(this, 'PageContent', {
      bucketName: `${environment.resourcePrefix}-page-content-${this.account}-${this.region}`,
      encryption: BucketEncryption.S3_MANAGED,
      blockPublicAccess: BlockPublicAccess.BLOCK_ALL, enforceSSL: true,
      objectOwnership: ObjectOwnership.BUCKET_OWNER_ENFORCED,
      removalPolicy: RemovalPolicy.RETAIN,
      cors: [{
        allowedOrigins: [environment.appOrigin],
        allowedMethods: [HttpMethods.GET, HttpMethods.HEAD, HttpMethods.PUT],
        allowedHeaders: ['*'], exposedHeaders: ['ETag'], maxAge: 3600,
      }],
    });
    const artifactBucket = this.artifactBucket = new Bucket(this, 'ExecutionArtifacts', {
      bucketName: `${environment.resourcePrefix}-execution-artifacts-${this.account}-${this.region}`,
      encryption: BucketEncryption.S3_MANAGED,
      blockPublicAccess: BlockPublicAccess.BLOCK_ALL, enforceSSL: true,
      objectOwnership: ObjectOwnership.BUCKET_OWNER_ENFORCED,
      removalPolicy: RemovalPolicy.RETAIN,
      cors: [{
        allowedOrigins: [environment.appOrigin],
        allowedMethods: [HttpMethods.GET, HttpMethods.HEAD],
        allowedHeaders: ['*'], exposedHeaders: ['ETag', 'Content-Range', 'Accept-Ranges'], maxAge: 3600,
      }],
    });

    const taskPrincipal = new ServicePrincipal('ecs-tasks.amazonaws.com', {
      conditions: { StringEquals: { 'aws:SourceAccount': this.account } },
    });
    const controlRole = this.controlRole = new Role(this, 'ControlTaskRole', { assumedBy: taskPrincipal });
    const executionRole = this.executionRole = new Role(this, 'ExecutionTaskRole', { assumedBy: taskPrincipal });
    controlRole.addToPolicy(new PolicyStatement({
      actions: ['s3:GetObject', 's3:PutObject', 's3:DeleteObject', 's3:AbortMultipartUpload'],
      resources: [pageBucket.arnForObjects('page-content/*')],
    }));
    controlRole.addToPolicy(new PolicyStatement({
      actions: ['s3:GetObject', 's3:DeleteObject'],
      resources: [artifactBucket.arnForObjects('artifacts/*')],
    }));
    controlRole.addToPolicy(new PolicyStatement({
      actions: ['s3:ListBucket'], resources: [pageBucket.bucketArn, artifactBucket.bucketArn],
    }));
    executionRole.addToPolicy(new PolicyStatement({
      actions: ['s3:PutObject', 's3:AbortMultipartUpload'],
      resources: [artifactBucket.arnForObjects('artifacts/*')],
    }));

    for (const [name, value] of Object.entries({
      ControlRuntimeSecretArn: this.controlRuntimeSecret.secretArn,
      DatabaseApplicationSecretArn: this.applicationSecret.secretArn,
      DatabaseEndpoint: database.dbInstanceEndpointAddress,
      DatabaseAdminSecretArn: database.secret!.secretArn,
      PageContentBucket: pageBucket.bucketName,
      ExecutionArtifactsBucket: artifactBucket.bucketName,
      ControlTaskRoleArn: controlRole.roleArn,
      ExecutionTaskRoleArn: executionRole.roleArn,
      ControlSecurityGroupId: controlGroup.securityGroupId,
      ExecutionSecurityGroupId: executionGroup.securityGroupId,
      DatabaseSecurityGroupId: databaseGroup.securityGroupId,
    })) new CfnOutput(this, name, { value });
  }
}
