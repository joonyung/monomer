# Monomer Cloud Infrastructure (AWS CDK)

TypeScript AWS CDK infrastructure and Python deployment/bootstrap tooling for Monomer's cloud environments (`dev` and `prod`, selected via `environments.json` with `-c environment=dev|prod`).

---

## Local Verification

Run TypeScript compilation, CDK assertion tests, and offline synth checks (no AWS credentials required):

```bash
cd infra/cloud-dev
npm ci --ignore-scripts
npm run check
python3 -m unittest discover -s tests -v
```

`cdk.context.json` lookups are intentionally disabled; target environment account, region, and availability zones are declared explicitly in `environments.json`.

---

## CDK Stack Architecture

| Stack | Source | Provisioned Resources & Security Boundaries |
|---|---|---|
| **Network** | [`src/network-stack.ts`](src/network-stack.ts) | Two-AZ VPC (`Public`, `Application` private-with-egress, `Database` isolated subnets), single NAT gateway, S3 Gateway VPC Endpoint on Application route tables, default Security Group stripped via custom resource, no internet route on Database subnets |
| **Storage** | [`src/storage-stack.ts`](src/storage-stack.ts) | Private encrypted RDS PostgreSQL (TLS enforced, Control-only Security Group), private S3 result artifact bucket, least-privilege Control and Execution ECS task roles |
| **Compute** | [`src/compute-stack.ts`](src/compute-stack.ts) | ECS Fargate cluster and one-shot private database schema bootstrap task definition (`database-bootstrap/` applies canonical Control DDL manifest transactionally over `verify-full` TLS and revokes bootstrap `CREATE` privileges) |
| **Certificate** | [`src/certificate-stack.ts`](src/certificate-stack.ts) | DNS-validated ACM certificates separated from service deployments |
| **Services** | [`src/services-stack.ts`](src/services-stack.ts) | ECS Fargate services for Control and Execution with Cloud Map private DNS (`execution` port `11099` reachable only from Control SG), public HTTPS ALB forwarding API traffic to Control `11077` (blocking `/actuator` and `/internal` externally), Secrets Manager startup injection (DB password, JWT PKCS#8 PEM, AES-256 vault key, OAuth credentials), scoped SES invitation IAM policy, and EventBridge/SNS OOM alerting |
| **Mail** | [`src/mail-stack.ts`](src/mail-stack.ts) | SES domain identity with Easy DKIM (RSA 2048) and custom `MAIL FROM` domain aligned for SPF/DKIM/DMARC compliance |
| **Static Hosting** | [`src/static-hosting-stack.ts`](src/static-hosting-stack.ts) | Private S3 buckets + CloudFront distributions with custom routing (`src/static-routing.ts`) and tailored CSP headers for the Web SPA and public documentation site |

---

## Deployment & Bootstrap Tooling

- [`database-bootstrap/`](database-bootstrap/): Builds a self-contained DDL bootstrap image directly from Control's canonical schema manifest and validates TLS, least-privilege app login, and idempotent re-run rejection against disposable PostgreSQL (`verify.py`).
- [`initialize-control-secret.py`](initialize-control-secret.py): One-time idempotent initializer populating structured runtime secrets in Secrets Manager without overwriting existing keys.
- [`deploy.py`](deploy.py) & [`static_release.py`](static_release.py): Controlled build, ECR digest publishing, CDK deployment, immutable static-release manifest verification, and rollback CLIs with unit test suites in [`tests/`](tests/).
