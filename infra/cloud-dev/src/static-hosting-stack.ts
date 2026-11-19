import { CfnOutput, CfnParameter, Duration, RemovalPolicy, Stack, Tags } from 'aws-cdk-lib';
import { ICertificate } from 'aws-cdk-lib/aws-certificatemanager';
import { CachePolicy, CfnDistribution, CfnFunction, CfnOriginAccessControl, ResponseHeadersPolicy } from 'aws-cdk-lib/aws-cloudfront';
import { Effect, PolicyStatement, ServicePrincipal } from 'aws-cdk-lib/aws-iam';
import { BlockPublicAccess, Bucket, BucketEncryption, ObjectOwnership } from 'aws-cdk-lib/aws-s3';
import { Construct } from 'constructs';
import { CloudEnvironment } from './environment';
import { APP_CSP, WORKER_CSP, pageHtmlRoutingCode, routingCode } from './static-routing';

export class StaticHostingStack extends Stack {
  constructor(scope: Construct, id: string, appCertificate: ICertificate, publicCertificate: ICertificate, environment: CloudEnvironment) {
    super(scope, id, { env: environment, terminationProtection: true });
    Tags.of(this).add('Project', 'Monomer');
    Tags.of(this).add('Environment', environment.name);
    const release = new CfnParameter(this, 'StaticRelease', {
      type: 'String', allowedPattern: '[a-f0-9]{64}',
      description: 'Verified static bundle SHA-256. Upload all objects before switching an existing distribution.',
    });
    const bucket = new Bucket(this, 'StaticBucket', {
      bucketName: `${environment.resourcePrefix}-static-${this.account}-${this.region}`,
      blockPublicAccess: BlockPublicAccess.BLOCK_ALL,
      objectOwnership: ObjectOwnership.BUCKET_OWNER_ENFORCED,
      encryption: BucketEncryption.S3_MANAGED, enforceSSL: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const access = new CfnOriginAccessControl(this, 'OriginAccess', {
      originAccessControlConfig: { name: `${environment.resourcePrefix}-static`, originAccessControlOriginType: 's3', signingBehavior: 'always', signingProtocol: 'sigv4' },
    });
    const assetCache = new CachePolicy(this, 'AssetCache', {
      minTtl: Duration.seconds(0), defaultTtl: Duration.days(365), maxTtl: Duration.days(365),
      enableAcceptEncodingBrotli: true, enableAcceptEncodingGzip: true,
    });
    function policy(scope: Construct, name: string, csp: string | undefined, robots: string) {
      return new ResponseHeadersPolicy(scope, name, {
        customHeadersBehavior: { customHeaders: [
          { header: 'X-Robots-Tag', value: robots, override: true },
          ...(name === 'WorkerHeaders' ? [{ header: 'X-Monomer-Csp-Evidence', value: 'chart-widget-worker', override: true }] : []),
        ] },
        securityHeadersBehavior: csp ? { contentTypeOptions: { override: true }, contentSecurityPolicy: { contentSecurityPolicy: csp, override: true } } : undefined,
      });
    }
    const appHeaders = policy(this, 'AppHeaders', APP_CSP, 'noindex, nofollow');
    const workerHeaders = policy(this, 'WorkerHeaders', WORKER_CSP, 'noindex, nofollow');
    const assetHeaders = policy(this, 'AssetHeaders', undefined, 'noindex, nofollow');
    const publicHeaders = policy(this, 'PublicHeaders', undefined, environment.name === 'prod' ? 'index, follow' : 'noindex, nofollow');
    const pageRouting = new CfnFunction(this, 'PageHtmlRouting', {
      name: `${environment.resourcePrefix}-page-html-routing`, autoPublish: true,
      functionConfig: { comment: 'Exact public Page HTML entry routing', runtime: 'cloudfront-js-2.0' },
      functionCode: pageHtmlRoutingCode(new URL(environment.appOrigin).hostname),
    });
    for (const site of ['app', 'public'] as const) {
      const label = site === 'app' ? 'App' : 'Public';
      const host = site === 'app' ? new URL(environment.appOrigin).hostname : new URL(environment.publicOrigin).hostname;
      const certificate = site === 'app' ? appCertificate : publicCertificate;
      const headers = site === 'app' ? appHeaders : publicHeaders;
      const routing = new CfnFunction(this, `${label}Routing`, {
        name: `${environment.resourcePrefix}-${site === 'public' ? 'public-root' : site}-routing`, autoPublish: true,
        functionConfig: { comment: `${host} static routing`, runtime: 'cloudfront-js-2.0' },
        functionCode: routingCode(site, host),
      });
      const common = {
        viewerProtocolPolicy: 'redirect-to-https', allowedMethods: ['GET', 'HEAD'], cachedMethods: ['GET', 'HEAD'], compress: true,
        responseHeadersPolicyId: headers.responseHeadersPolicyId,
        functionAssociations: [{ eventType: 'viewer-request', functionArn: routing.attrFunctionArn }],
      };
      const sharedBehavior = { ...common, responseHeadersPolicyId: (site === 'app' ? assetHeaders : publicHeaders).responseHeadersPolicyId, targetOriginId: 'SharedAssets', cachePolicyId: assetCache.cachePolicyId };
      const distribution = new CfnDistribution(this, `${label}Distribution`, {
        distributionConfig: {
          enabled: true, aliases: [host], comment: `Monomer ${environment.name} ${site} pay-as-you-go static hosting`,
          httpVersion: 'http2and3', ipv6Enabled: true, priceClass: 'PriceClass_All',
          viewerCertificate: { acmCertificateArn: certificate.certificateArn, sslSupportMethod: 'sni-only', minimumProtocolVersion: 'TLSv1.2_2021' },
          origins: [
            { id: 'Release', domainName: bucket.bucketRegionalDomainName, originPath: `/${site}/releases/${release.valueAsString}`, originAccessControlId: access.attrId, s3OriginConfig: { originAccessIdentity: '' } },
            { id: 'SharedAssets', domainName: bucket.bucketRegionalDomainName, originPath: `/${site}`, originAccessControlId: access.attrId, s3OriginConfig: { originAccessIdentity: '' } },
            ...(site === 'app' ? [{ id: 'ControlPageHtml', domainName: new URL(environment.apiOrigin).hostname,
              customOriginConfig: { originProtocolPolicy: 'https-only', originSslProtocols: ['TLSv1.2'], httpsPort: 443 },
            }] : []),
          ],
          defaultCacheBehavior: { ...common, targetOriginId: 'Release', cachePolicyId: CachePolicy.CACHING_DISABLED.cachePolicyId },
          cacheBehaviors: site === 'app' ? [
            { ...sharedBehavior, pathPattern: '/assets/chart-widget-worker-*.js', responseHeadersPolicyId: workerHeaders.responseHeadersPolicyId },
            { ...sharedBehavior, pathPattern: '/assets/*' },
            { ...common, pathPattern: '/workspaces/*/pages/*', targetOriginId: 'ControlPageHtml',
              cachePolicyId: CachePolicy.CACHING_DISABLED.cachePolicyId,
              functionAssociations: [{ eventType: 'viewer-request', functionArn: pageRouting.attrFunctionArn }],
            },
          ] : [{ ...sharedBehavior, pathPattern: '/_astro/*' }],
          customErrorResponses: [
            ...[403, 404].map(code => ({ errorCode: code, responseCode: 404, responsePagePath: '/404.html', errorCachingMinTtl: 0 })),
            ...(site === 'app' ? [500, 502, 503, 504].map(code => ({ errorCode: code, errorCachingMinTtl: 0 })) : []),
          ],
        },
      });
      bucket.addToResourcePolicy(new PolicyStatement({
        effect: Effect.ALLOW, principals: [new ServicePrincipal('cloudfront.amazonaws.com')], actions: ['s3:GetObject'],
        resources: [bucket.arnForObjects(`${site}/*`)],
        conditions: { StringEquals: { 'AWS:SourceArn': `arn:aws:cloudfront::${this.account}:distribution/${distribution.ref}` } },
      }));
      new CfnOutput(this, `${label}DistributionId`, { value: distribution.ref });
      new CfnOutput(this, `${label}DistributionDomainName`, { value: distribution.attrDomainName });
    }
    new CfnOutput(this, 'StaticBucketName', { value: bucket.bucketName });
    new CfnOutput(this, 'StaticReleaseDigest', { value: release.valueAsString });
  }
}
