import { ArnFormat, CfnCondition, CfnOutput, CfnParameter, Fn, Duration, RemovalPolicy, Stack, Tags } from 'aws-cdk-lib';
import { IVpc, SecurityGroup, SubnetType } from 'aws-cdk-lib/aws-ec2';
import { Cluster, ContainerImage, CpuArchitecture, FargateService, FargateTaskDefinition, LogDrivers, OperatingSystemFamily, Secret } from 'aws-cdk-lib/aws-ecs';
import { Policy, PolicyStatement, Role, ServicePrincipal } from 'aws-cdk-lib/aws-iam';
import { LogGroup, RetentionDays } from 'aws-cdk-lib/aws-logs';
import { PrivateDnsNamespace } from 'aws-cdk-lib/aws-servicediscovery';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { Construct } from 'constructs';
import { CloudEnvironment } from './environment';
import { StorageStack } from './storage-stack';
import { ICertificate } from 'aws-cdk-lib/aws-certificatemanager';
import { ApplicationLoadBalancer, ApplicationProtocol, ListenerAction, ListenerCondition, SslPolicy } from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import { EventField, Rule, RuleTargetInput } from 'aws-cdk-lib/aws-events';
import { SnsTopic } from 'aws-cdk-lib/aws-events-targets';
import { Topic } from 'aws-cdk-lib/aws-sns';
import { EmailSubscription } from 'aws-cdk-lib/aws-sns-subscriptions';

export class ServicesStack extends Stack {
  constructor(scope: Construct, id: string, vpc: IVpc, cluster: Cluster, storage: StorageStack, certificate: ICertificate, environment: CloudEnvironment) {
    super(scope, id, { env: environment, terminationProtection: true });
    Tags.of(this).add('Project', 'Monomer'); Tags.of(this).add('Environment', environment.name);
    const identity = JSON.parse(readFileSync(join(__dirname, '../../../../contracts/identity/security-identifier-map.json'), 'utf8'))[environment.identityKey] as Record<string, string>;
    const namespace = new PrivateDnsNamespace(this, 'PrivateDns', { name: environment.internalNamespace, vpc });
    const controlGroup = SecurityGroup.fromSecurityGroupId(this, 'ControlIngressGroup', storage.controlGroup.securityGroupId);
    const loadBalancerGroup = new SecurityGroup(this, 'LoadBalancerSecurityGroup', { vpc, allowAllOutbound: false });
    const loadBalancer = new ApplicationLoadBalancer(this, 'LoadBalancer', {
      vpc, internetFacing: true, vpcSubnets: { subnetType: SubnetType.PUBLIC },
      securityGroup: loadBalancerGroup, dropInvalidHeaderFields: true,
      idleTimeout: Duration.seconds(300), deletionProtection: true,
    });
    loadBalancer.addListener('Http', { port: 80, defaultAction: ListenerAction.redirect({ protocol: 'HTTPS', port: '443', permanent: true }) });
    const https = loadBalancer.addListener('Https', {
      port: 443, certificates: [certificate], sslPolicy: SslPolicy.TLS13_RES,
      defaultAction: ListenerAction.fixedResponse(404, { contentType: 'text/plain', messageBody: 'Not found' }),
    });
    https.addAction('InternalPaths', {
      priority: 10, conditions: [ListenerCondition.pathPatterns(['/actuator', '/actuator/*', '/internal', '/internal/*'])],
      action: ListenerAction.fixedResponse(404, { contentType: 'text/plain', messageBody: 'Not found' }),
    });
    new CfnOutput(this, 'LoadBalancerDnsName', { value: loadBalancer.loadBalancerDnsName });
    const source = new ServicePrincipal('ecs-tasks.amazonaws.com', { conditions: { StringEquals: { 'aws:SourceAccount': this.account } } });
    const controlDesiredCount = new CfnParameter(this, 'ControlDesiredCount', {
      type: 'Number', default: 1, allowedValues: ['0', '1'],
      description: 'Keep Control stopped while installing a cutover task definition; restore only after verification.',
    });
    const controlAllowedEmails = new CfnParameter(this, 'ControlAllowedEmails', {
      type: 'String', noEcho: true, minLength: 1, maxLength: 4096,
      allowedPattern: '[^\\s@*,]+@[^\\s@*,]+\\.[^\\s@*,]+(?:\\s*,\\s*[^\\s@*,]+@[^\\s@*,]+\\.[^\\s@*,]+)*',
    });
    const operationsAllowedUserIds = new CfnParameter(this, 'ControlOperationsAllowedUserIds', {
      type: 'String', default: '', maxLength: 4096,
      allowedPattern: '(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:,[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})*)?',
      description: 'Environment-local operator User IDs. Empty denies all operations access.',
    });
    const invitationEmailPattern = "[A-Za-z0-9!#$%&'+/=^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'+/=^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+";
    const invitationFromAddress = new CfnParameter(this, 'ControlInvitationFromAddress', {
      type: 'String', minLength: 3, maxLength: 254,
      allowedPattern: invitationEmailPattern,
      description: 'Approved From address covered by the verified SES sender identity in us-east-1.',
    });
    const invitationRecipientAddress = new CfnParameter(this, 'ControlInvitationRecipientAddress', {
      type: 'String', minLength: 3, maxLength: 254,
      allowedPattern: invitationEmailPattern,
      description: 'Approved QA recipient email address verified as an SES email identity in us-east-1.',
    });
    const invitationSenderIdentity = new CfnParameter(this, 'ControlInvitationSenderIdentity', {
      type: 'String', default: '', maxLength: 254,
      allowedPattern: `(?:${invitationEmailPattern}|[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+)?`,
      description: 'Verified SES domain or email identity for the sender; empty uses the From address identity.',
    });
    const invitationReplyToAddress = new CfnParameter(this, 'ControlInvitationReplyToAddress', {
      type: 'String', default: '', maxLength: 254, allowedPattern: `(?:${invitationEmailPattern})?`,
      description: 'Optional monitored mailbox for replies to invitation emails.',
    });
    const invitationFeedbackAddress = new CfnParameter(this, 'ControlInvitationFeedbackForwardingEmailAddress', {
      type: 'String', default: '', maxLength: 254, allowedPattern: `(?:${invitationEmailPattern})?`,
      description: 'Optional verified SES email identity for bounce and complaint email forwarding.',
    });
    const hasInvitationSenderIdentity = new CfnCondition(this, 'HasInvitationSenderIdentity', {
      expression: Fn.conditionNot(Fn.conditionEquals(invitationSenderIdentity.valueAsString, '')),
    });
    const hasInvitationFeedbackAddress = new CfnCondition(this, 'HasInvitationFeedbackAddress', {
      expression: Fn.conditionNot(Fn.conditionEquals(invitationFeedbackAddress.valueAsString, '')),
    });
    const invitationPolicy = new Policy(this, 'ControlInvitationDeliveryPolicy', {
      roles: [Role.fromRoleArn(this, 'InvitationControlTaskRole', storage.controlRole.roleArn)],
      statements: [new PolicyStatement({
        actions: ['ses:SendEmail'],
        resources: [
          `arn:aws:ses:${this.region}:${this.account}:identity/${Fn.conditionIf(hasInvitationSenderIdentity.logicalId, invitationSenderIdentity.valueAsString, invitationFromAddress.valueAsString).toString()}`,
          `arn:aws:ses:${this.region}:${this.account}:identity/${invitationRecipientAddress.valueAsString}`,
          Fn.conditionIf(hasInvitationFeedbackAddress.logicalId,
            `arn:aws:ses:${this.region}:${this.account}:identity/${invitationFeedbackAddress.valueAsString}`, { Ref: 'AWS::NoValue' }).toString(),
        ],
        conditions: { StringEquals: { 'ses:FromAddress': invitationFromAddress.valueAsString } },
      }), new PolicyStatement({
        actions: ['ses:ApplyTrackingConfigurationOverrides'],
        resources: ['*'],
        conditions: { StringEquals: {
          'ses:OpenTrackingEnabled': 'DISABLED',
          'ses:ClickTrackingEnabled': 'DISABLED',
        } },
      })],
    });
    const configs = [
      { name: 'Execution', cpu: environment.executionCpu, memory: 2048, role: storage.executionRole, group: storage.executionGroup,
        health: 'curl --fail --silent http://127.0.0.1:11100/healthz',
        environment: {
          LOGGING_STRUCTURED_FORMAT_CONSOLE: 'ecs',
          JAVA_TOOL_OPTIONS: '-XX:MaxRAMPercentage=65 -XX:MaxDirectMemorySize=256m',
          MONOMER_EXECUTION_LISTEN_ADDRESS: '0.0.0.0', MONOMER_EXECUTION_HEALTH_BIND_ADDRESS: '127.0.0.1',
          MONOMER_EXECUTION_ARTIFACT_STORE_ENDPOINT: `https://s3.${environment.region}.amazonaws.com`,
          MONOMER_EXECUTION_ARTIFACT_STORE_REGION: environment.region,
          MONOMER_EXECUTION_ARTIFACT_STORE_BUCKET: storage.artifactBucket.bucketName,
          MONOMER_EXECUTION_ARTIFACT_STORE_PREFIX: 'artifacts/', MONOMER_EXECUTION_ARTIFACT_STORE_CREDENTIAL_MODE: 'WORKLOAD',
        } as Record<string, string> },
      { name: 'Control', cpu: 1024, memory: 2048, role: storage.controlRole, group: controlGroup,
        health: 'curl --fail --silent http://127.0.0.1:11077/actuator/health',
        environment: {
          LOGGING_STRUCTURED_FORMAT_CONSOLE: 'ecs',
          JAVA_TOOL_OPTIONS: '-XX:MaxRAMPercentage=65 -XX:MaxDirectMemorySize=256m',
          MONOMER_CONTROL_HTTP_ADDRESS: '0.0.0.0', MONOMER_CONTROL_EXECUTION_TARGET: `http://execution.${environment.internalNamespace}:11099`,
          MONOMER_CONTROL_POSTGRES_JDBC_URL: `jdbc:postgresql://${storage.database.dbInstanceEndpointAddress}:5432/monomer?sslmode=verify-full&sslrootcert=/opt/monomer/rds-ca.pem`,
          MONOMER_CONTROL_POSTGRES_USERNAME: 'monomer', MONOMER_CONTROL_POSTGRES_MAXIMUM_POOL_SIZE: '8',
          MONOMER_CONTROL_OBJECT_STORAGE_ENDPOINT: `https://s3.${environment.region}.amazonaws.com`,
          MONOMER_CONTROL_OBJECT_STORAGE_REGION: environment.region, MONOMER_CONTROL_OBJECT_STORAGE_CREDENTIAL_MODE: 'WORKLOAD',
          MONOMER_CONTROL_PAGE_CONTENT_BUCKET: storage.pageBucket.bucketName,
          MONOMER_CONTROL_EXECUTION_ARTIFACT_STORE_BUCKET: storage.artifactBucket.bucketName,
          MONOMER_CONTROL_EXECUTION_ARTIFACT_STORE_PATH_STYLE_ACCESS_ENABLED: 'false',
          MONOMER_CONTROL_DEVELOPER_ACCESS_MODE: environment.name === 'prod' ? 'OPEN' : 'RESTRICTED',
          MONOMER_CONTROL_DEVELOPER_ALLOWED_EMAILS: controlAllowedEmails.valueAsString,
          MONOMER_OPERATIONS_ALLOWED_USER_IDS: operationsAllowedUserIds.valueAsString,
          MONOMER_CONTROL_AUTH_LOCAL_LOGIN_ENABLED: 'false', MONOMER_CONTROL_GOOGLE_OAUTH_ENABLED: 'true',
          MONOMER_CONTROL_GOOGLE_OAUTH_REDIRECT_URI: `${environment.apiOrigin}/login/oauth2/code/google`,
          MONOMER_CONTROL_WEB_ORIGIN: environment.appOrigin,
          MONOMER_CONTROL_DOCS_ORIGIN: environment.publicOrigin,
          MONOMER_CONTROL_AUTH_CORS_ALLOWED_ORIGINS: environment.appOrigin,
          MONOMER_CONTROL_AUTH_FRONTEND_CALLBACK_URL: identity.oauthRedirect,
          MONOMER_INVITATION_DELIVERY_MODE: 'ses',
          MONOMER_INVITATION_SES_REGION: this.region,
          MONOMER_INVITATION_SES_FROM_ADDRESS: invitationFromAddress.valueAsString,
          MONOMER_INVITATION_SES_REPLY_TO_ADDRESS: invitationReplyToAddress.valueAsString,
          MONOMER_INVITATION_SES_FEEDBACK_FORWARDING_EMAIL_ADDRESS: invitationFeedbackAddress.valueAsString,
          MONOMER_CONTROL_AUTH_REFRESH_COOKIE_SECURE: 'true',
          MONOMER_CONTROL_SECURITY_ISSUER: identity.controlIssuer,
          MONOMER_CONTROL_SECURITY_AGENT_RESOURCE: identity.agentResource,
          ...(environment.name === 'dev' ? {
            MONOMER_CONTROL_SECURITY_LOGIN_NAMESPACE: identity.loginNamespace,
            MONOMER_CONTROL_SECURITY_LOGIN_SUBJECT_URN_PREFIX: identity.loginSubjectUrnPrefix,
          } : {}),
          MONOMER_CONTROL_OAUTH_ISSUER: identity.oauthIssuer,
          MONOMER_CONTROL_OAUTH_MCP_RESOURCE: identity.mcpResource,
        } as Record<string, string> },
    ];
    let executionService: FargateService | undefined;
    for (const config of configs) {
      const image = new CfnParameter(this, `${config.name}Image`, {
        type: 'String', allowedPattern: `${environment.account}\\.dkr\\.ecr\\.${environment.region}\\.amazonaws\\.com/cdk-hnb659fds-container-assets-${environment.account}-${environment.region}@sha256:[a-f0-9]{64}`,
      });
      const executionRole = new Role(this, `${config.name}StartupRole`, { assumedBy: source });
      executionRole.addToPolicy(new PolicyStatement({ actions: ['ecr:GetAuthorizationToken'], resources: ['*'] }));
      executionRole.addToPolicy(new PolicyStatement({ actions: ['ecr:BatchCheckLayerAvailability', 'ecr:GetDownloadUrlForLayer', 'ecr:BatchGetImage'], resources: [`arn:aws:ecr:${this.region}:${this.account}:repository/cdk-hnb659fds-container-assets-${this.account}-${this.region}`] }));
      const task = new FargateTaskDefinition(this, `${config.name}Task`, {
        cpu: config.cpu, memoryLimitMiB: config.memory, taskRole: config.role, executionRole,
        runtimePlatform: { cpuArchitecture: CpuArchitecture.X86_64, operatingSystemFamily: OperatingSystemFamily.LINUX },
      });
      const logs = new LogGroup(this, `${config.name}Logs`, { retention: RetentionDays.ONE_WEEK, removalPolicy: RemovalPolicy.RETAIN });
      const secrets: Record<string, Secret> | undefined = config.name === 'Control' ? {
        MONOMER_CONTROL_GOOGLE_OAUTH_CLIENT_ID: Secret.fromSecretsManager(storage.controlRuntimeSecret, 'googleClientId'),
        MONOMER_CONTROL_GOOGLE_OAUTH_CLIENT_SECRET: Secret.fromSecretsManager(storage.controlRuntimeSecret, 'googleClientSecret'),
        MONOMER_CONTROL_POSTGRES_PASSWORD: Secret.fromSecretsManager(storage.applicationSecret, 'password'),
        MONOMER_CONTROL_AUTH_JWT_PRIVATE_KEY_PEM: Secret.fromSecretsManager(storage.controlRuntimeSecret, 'jwtPrivateKeyPem'),
        MONOMER_CONTROL_EXECUTION_CREDENTIAL_VAULT_KEY_BASE64: Secret.fromSecretsManager(storage.controlRuntimeSecret, 'credentialVaultKeyBase64'),
      } : undefined;
      const container = task.addContainer(config.name.toLowerCase(), {
        image: ContainerImage.fromRegistry(image.valueAsString), environment: config.environment, secrets,
        logging: LogDrivers.awsLogs({ logGroup: logs, streamPrefix: config.name.toLowerCase() }),
        healthCheck: { command: ['CMD-SHELL', config.health], interval: Duration.seconds(30), timeout: Duration.seconds(5), retries: 3, startPeriod: Duration.seconds(120) },
        stopTimeout: Duration.seconds(120),
      });
      if (config.name === 'Execution') container.addPortMappings({ containerPort: 11099, name: 'grpc' });
      else container.addPortMappings({ containerPort: 11077, name: 'http' });
      const service = new FargateService(this, `${config.name}Service`, {
        cluster, taskDefinition: task, desiredCount: config.name === 'Control' ? controlDesiredCount.valueAsNumber : 1, assignPublicIp: false,
        vpcSubnets: { subnetType: SubnetType.PRIVATE_WITH_EGRESS }, securityGroups: [config.group],
        minHealthyPercent: 100,
        maxHealthyPercent: 200,
        circuitBreaker: { rollback: true },
        healthCheckGracePeriod: config.name === 'Execution' ? undefined : Duration.seconds(180),
        cloudMapOptions: config.name === 'Execution' ? { cloudMapNamespace: namespace, name: 'execution', dnsTtl: Duration.seconds(10) } : undefined,
      });
      if (config.name === 'Execution') {
        executionService = service;
        const oomTopic = new Topic(this, 'ExecutionOomTopic', {
          displayName: `Monomer ${environment.name} Execution OOM`,
        });
        oomTopic.addSubscription(new EmailSubscription('alerts@example.com'));
        const oomRule = new Rule(this, 'ExecutionOomRule', {
          description: 'Alert when this Execution service stops because of an out-of-memory error.',
          eventPattern: {
            source: ['aws.ecs'], detailType: ['ECS Task State Change'],
            detail: {
              clusterArn: [this.formatArn({ service: 'ecs', resource: 'cluster', resourceName: cluster.clusterName, arnFormat: ArnFormat.SLASH_RESOURCE_NAME })],
              group: [`service:${service.serviceName}`], lastStatus: ['STOPPED'],
              $or: [
                { stoppedReason: [{ wildcard: '*OutOfMemory*' }] },
                { containers: { reason: [{ wildcard: '*OutOfMemory*' }] } },
              ],
            },
          },
        });
        oomRule.addTarget(new SnsTopic(oomTopic, {
          message: RuleTargetInput.fromMultilineText(
            `Monomer ${environment.name} Execution stopped because of an out-of-memory error.\n` +
            `Event time: ${EventField.time}\nTask: ${EventField.fromPath('$.detail.taskArn')}\n` +
            `Stopped reason: ${EventField.fromPath('$.detail.stoppedReason')}\n` +
            'Inspect container memory limits and recent execution runs before restarting.',
          ),
        }));
        new CfnOutput(this, 'ExecutionOomTopicArn', { value: oomTopic.topicArn });
        new CfnOutput(this, 'ExecutionOomRuleName', { value: oomRule.ruleName });
      }
      if (config.name === 'Control' && executionService) service.node.addDependency(executionService);
      if (config.name === 'Control') service.node.addDependency(invitationPolicy);
      if (config.name === 'Control') {
        const port = 11077;
        https.addTargets(`${config.name}Target`, {
          port, protocol: ApplicationProtocol.HTTP, targets: [service],
          priority: 20,
          conditions: [ListenerCondition.hostHeaders([new URL(environment.apiOrigin).hostname])],
          healthCheck: { path: '/actuator/health', healthyHttpCodes: '200', healthyThresholdCount: 2 },
          deregistrationDelay: Duration.seconds(30),
        });
      }
      new CfnOutput(this, `${config.name}ServiceName`, { value: service.serviceName });
    }
  }
}
