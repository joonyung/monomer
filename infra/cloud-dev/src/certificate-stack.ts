import { CfnOutput, Stack, Tags } from 'aws-cdk-lib';
import { Certificate, CertificateValidation } from 'aws-cdk-lib/aws-certificatemanager';
import { Construct } from 'constructs';
import { CloudEnvironment } from './environment';

export class CertificateStack extends Stack {
  readonly certificate: Certificate;
  readonly publicSiteCertificate: Certificate;

  constructor(scope: Construct, id: string, environment: CloudEnvironment) {
    super(scope, id, { env: environment, terminationProtection: true });
    Tags.of(this).add('Project', 'Monomer'); Tags.of(this).add('Environment', environment.name);
    this.certificate = new Certificate(this, 'Certificate', {
      domainName: new URL(environment.appOrigin).hostname,
      subjectAlternativeNames: [new URL(environment.apiOrigin).hostname],
      validation: CertificateValidation.fromDns(),
    });
    this.publicSiteCertificate = new Certificate(this, 'PublicSiteCertificate', {
      domainName: new URL(environment.publicOrigin).hostname, validation: CertificateValidation.fromDns(),
    });
    new CfnOutput(this, 'PublicSiteCertificateArn', { value: this.publicSiteCertificate.certificateArn });
    new CfnOutput(this, 'CertificateArn', { value: this.certificate.certificateArn });
  }
}
