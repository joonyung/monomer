import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export interface CloudEnvironment {
  readonly name: 'dev' | 'prod';
  readonly account: string;
  readonly region: string;
  readonly stackPrefix: string;
  readonly resourcePrefix: string;
  readonly vpcCidr: string;
  readonly availabilityZones: string[];
  readonly appOrigin: string;
  readonly apiOrigin: string;
  readonly publicOrigin: string;
  readonly internalNamespace: string;
  readonly identityKey: 'dev' | 'public';
  readonly senderDomain: string;
  readonly executionCpu: 512 | 1024;
}

export function cloudEnvironment(name: unknown): CloudEnvironment {
  if (name !== 'dev' && name !== 'prod') throw new Error('Explicit environment context is required: dev or prod.');
  const environments = JSON.parse(readFileSync(join(__dirname, '../../environments.json'), 'utf8'));
  return { ...environments[name], name };
}
