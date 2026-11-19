import { CfnOutput, Stack, Tags } from 'aws-cdk-lib';
import { DkimIdentity, EasyDkimSigningKeyLength, EmailIdentity, Identity, MailFromBehaviorOnMxFailure } from 'aws-cdk-lib/aws-ses';
import { Construct } from 'constructs';
import { CloudEnvironment } from './environment';

export class MailStack extends Stack {
  readonly senderIdentity: EmailIdentity;

  constructor(scope: Construct, id: string, environment: CloudEnvironment) {
    super(scope, id, { env: environment, terminationProtection: true });
    Tags.of(this).add('Project', 'Monomer'); Tags.of(this).add('Environment', environment.name);
    const mailFromDomain = `bounce.${environment.senderDomain}`;
    this.senderIdentity = new EmailIdentity(this, 'SenderIdentity', {
      identity: Identity.domain(environment.senderDomain),
      dkimIdentity: DkimIdentity.easyDkim(EasyDkimSigningKeyLength.RSA_2048_BIT),
      mailFromDomain,
      mailFromBehaviorOnMxFailure: MailFromBehaviorOnMxFailure.REJECT_MESSAGE,
    });
    new CfnOutput(this, 'SenderDomain', { value: environment.senderDomain });
    this.senderIdentity.dkimRecords.forEach((record, index) => {
      new CfnOutput(this, `DkimCnameName${index + 1}`, { value: record.name });
      new CfnOutput(this, `DkimCnameValue${index + 1}`, { value: record.value });
    });
    new CfnOutput(this, 'MailFromDomain', { value: mailFromDomain });
    new CfnOutput(this, 'MailFromMxValue', { value: `10 feedback-smtp.${this.region}.amazonses.com` });
    new CfnOutput(this, 'MailFromSpfValue', { value: 'v=spf1 include:amazonses.com -all' });
  }
}
