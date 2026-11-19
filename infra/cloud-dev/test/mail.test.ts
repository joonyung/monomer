import assert from 'node:assert/strict';
import { test } from 'node:test';
import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { cloudEnvironment } from '../src/environment';
import { MailStack } from '../src/mail-stack';

function mailTemplate(name: 'dev' | 'prod') {
  const environment = cloudEnvironment(name);
  const stack = new MailStack(new App(), `${environment.stackPrefix}Mail`, environment);
  assert.equal(stack.account, environment.account);
  assert.equal(stack.terminationProtection, true);
  return Template.fromStack(stack);
}

test('each environment owns a DKIM-signed sender domain with an aligned MAIL FROM', () => {
  for (const [name, domain] of [['dev', 'notify.dev.monomer.cc'], ['prod', 'notify.monomer.cc']] as const) {
    const template = mailTemplate(name);
    template.resourceCountIs('AWS::SES::EmailIdentity', 1);
    const identity = Object.values(template.findResources('AWS::SES::EmailIdentity'))[0];
    assert.notEqual(identity.Properties.DkimAttributes?.SigningEnabled, false);
    template.hasResourceProperties('AWS::SES::EmailIdentity', {
      EmailIdentity: domain,
      DkimSigningAttributes: { NextSigningKeyLength: 'RSA_2048_BIT' },
      MailFromAttributes: { MailFromDomain: `bounce.${domain}`, BehaviorOnMxFailure: 'REJECT_MESSAGE' },
    });
    const outputs = template.findOutputs('*');
    for (const index of [1, 2, 3]) {
      assert.ok(outputs[`DkimCnameName${index}`]);
      assert.ok(outputs[`DkimCnameValue${index}`]);
    }
    assert.equal(outputs.SenderDomain.Value, domain);
    assert.equal(outputs.MailFromDomain.Value, `bounce.${domain}`);
    assert.equal(outputs.MailFromMxValue.Value, '10 feedback-smtp.us-east-1.amazonses.com');
    assert.equal(outputs.MailFromSpfValue.Value, 'v=spf1 include:amazonses.com -all');
  }
});
