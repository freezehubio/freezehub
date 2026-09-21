# infra

Terraform for the AWS deployment target (`FZ-063`). One environment per workspace; `beta` is the default.

## What it creates

```text
                    Route 53
                       │
        ┌──────────────┴───────────────┐
        │                              │
   CloudFront                    ALB (HTTPS)
        │                              │
   S3 (private)                  ECS Fargate ×2        Cognito
   frontend build                 backend image        user pool
                                        │
                                  RDS PostgreSQL
                                   (private subnets)
```

Plus: ECR for backend images, Secrets Manager for the database password and the
application encryption key, and CloudWatch logs.

## Before the first apply

**Which account this applies into is a decision, not a default** (`OI-32`). It is the same
reasoning that governs the region (see Applying, below), pointed at the account: a Cognito
user pool cannot be moved between AWS accounts, and the `sub` it issues is stored in
`users.external_subject`. Before `FZ-046` creates that pool the choice is free; afterwards
it means a new pool, new subjects, and a forced password reset for every customer.

You need, and Terraform will not create for you:

1. **A project on AWS's new sign-up experience** — `freezehubio@gmail.com`, not an account
   also used for anything else, and **not** the operator's personal one. A project *is* an
   AWS account; it sits in an Organization AWS owns and manages. `docs/16-accounts.md` §0
   tells you which experience you are on, and `D-37` is the reasoning.

   **The region is `us-east-2`** (`D-32`, amended by `FZ-171`). A second SCP,
   `UsEast1Partitional`, denies everything in `us-east-1` except global services — Cognito,
   RDS, ECR, SSM, load balancers and `s3:CreateBucket` among them. CloudFront's certificate
   still goes to `us-east-1`, because `acm` is on the allowed list and AWS accepts it from
   nowhere else.

2. **A domain and a Route 53 hosted zone that already delegates it.** Both certificates
   are DNS-validated through that zone, so an apply hangs without it.
3. **Credentials from `aws login`, not an access key.** AWS CLI 2.32.0 or later opens a
   browser and issues role session credentials it rotates every 15 minutes. There is no
   key to scope, rotate or lose, and on this experience there is no IAM user to hold one
   — `iam:*LoginProfile*` is denied.

   **Terraform cannot read that session.** `aws login` writes to the CLI's own store and
   the AWS SDK for Go does not understand it, so Terraform falls through to `[default]` —
   silently, if `[default]` happens to hold credentials for another account. Bridge it
   with a `credential_process` profile and use that for Terraform; `16-accounts.md` §1 has
   the four lines. Every provider also asserts `var.aws_account_id`, so the silent case
   fails before creating anything.

   **`shared/` will not apply as written** (`OI-43`): `github-oidc.tf` creates an
   `aws_iam_openid_connect_provider` and `iam:*Provider*` is denied by an SCP that cannot
   be modified. That file is separable — nothing outside it references its resources
   except two outputs — so the rest of `shared/` is reachable once it is split out or
   toggled off. `bootstrap/` and `singlebox/` are unaffected.
4. **A verified SES identity**, if email notifications are wanted. The task role can send;
   SES still has to be out of the sandbox to send anywhere.

## Three modules, and which ones you apply

`FZ-159` split this into root modules that can each be applied whole, because the previous
single module could not be applied in part — and the single-box posture (`D-35`) needs the
shared half without the ECS half.

| | What it holds | Applied on |
|---|---|---|
| `shared/` | Cognito, the SPA bucket and its distribution, ECR, the GitHub deploy role | **both postures, always first** |
| `singlebox/` | One EC2 instance running Caddy, the backend and PostgreSQL | the beta (`D-35`) |
| `ecs/` | ALB, Fargate, RDS, and the VPC they sit in | after graduating (`FZ-157`) |

**Apply `shared/` and exactly one compute module.** Applying both compute estates is not
forbidden and is what `FZ-157` does during a migration, but it costs both bills at once and
is a deliberate, temporary state.

The compute modules take what they need from `shared/` as variables rather than by reading
its state: a remote-state data source couples a module to where that state lives, and these
are four values a person pastes from `terraform output`.

## Applying

**Decide the region first, and write it down.** `region` has no default (`FZ-135`): it is
the one variable that cannot be changed afterwards without moving the database *and*
re-creating every identity, because a Cognito user pool is region-bound and the `sub` it
issues is stored in `users.external_subject`. It is also the answer to "where does customer
data live". Only the CloudFront certificate sits elsewhere, in `us-east-1`, because AWS
accepts it from nowhere else; it holds no customer data. `D-32` records the decision.

```bash
# Once per account: the bucket that holds state, which contains secrets.
cd bootstrap && terraform init && terraform apply -var region=<the same region>
# Note the bucket name it prints, then fill in the backend block in each module's
# versions.tf. Each uses a different `key`, so the three states stay separate.

# 1. The shared estate. Always first; both postures need it.
cd ../shared
cp terraform.tfvars.example terraform.tfvars   # region, domain_name, hosted_zone_id
terraform init && terraform plan && terraform apply

# 2. The compute estate. For the beta, this is the box.
cd ../singlebox
cp terraform.tfvars.example terraform.tfvars
#    ecr_repository_arn comes from: terraform -chdir=../shared output -raw ecr_repository_arn
terraform init && terraform plan && terraform apply
```

For the ECS posture instead, `cd ../ecs` and pass the four values
`terraform -chdir=../shared output` prints. `FZ-157` describes the migration in order.

distribution. Certificate validation blocks until the DNS records propagate.

## Deploying the application

`FZ-064` automates this in `.github/workflows/deploy.yml`, which assumes a role by OIDC rather than any stored key. After the first apply, set these as repository variables in GitHub — every one is a `terraform output`:

```text
AWS_DEPLOY_ROLE_ARN        github_deploy_role_arn
AWS_REGION                 (your region)
ECR_REPOSITORY             (the repository name from ecr_repository_url)
ECS_CLUSTER                ecs_cluster_name
ECS_SERVICE                ecs_service_name
ECS_TASK_FAMILY            ecs_task_family
FRONTEND_BUCKET            frontend_bucket
CLOUDFRONT_DISTRIBUTION_ID cloudfront_distribution_id
```

Set `github_repository` in `terraform.tfvars` before applying: the OIDC trust policy is scoped to it, and getting it wrong is the difference between only this repository being able to deploy and anyone's being able to.

### The single box needs a different set

That table is the ECS posture's (`deploy.yml`). The beta deploys `singlebox/` through `deploy-singlebox.yml`, which reads:

```text
AWS_DEPLOY_ROLE_ARN        github_deploy_role_arn      (../shared, once OI-43 is lifted)
AWS_REGION                 (your region)
ECR_REPOSITORY_URL         ecr_repository_url          (../shared)
ENVIRONMENT                (beta)
APP_DOMAIN                 domain_name                 the SPA host, e.g. app.example.com
API_DOMAIN                 api_domain_name             the API host, e.g. api.example.com
COGNITO_USER_POOL_ID       cognito_user_pool_id        (../shared)
COGNITO_CLIENT_ID          cognito_user_pool_client_id (../shared)
FRONTEND_BUCKET            frontend_bucket             (../shared)
CLOUDFRONT_DISTRIBUTION_ID cloudfront_distribution_id  (../shared)
API_BASE_URL               https://<api_domain_name>   baked into the bundle at build time
```

**`API_DOMAIN` is set, not derived.** It used to be `api.$APP_DOMAIN` here, which produced `api.app.example.com` once the SPA moved to a subdomain — and that hostname is the one in every customer's CI configuration (`FZ-174`, `FZ-176`).

**`COGNITO_USER_POOL_ID` is not optional.** Without it the backend has no issuer URI and no pool, and the container will not start — deliberately (`FZ-046`). The instance role carries the matching `InviteUsers` grant, scoped to that pool.

By hand:

```bash
# backend — the image must be linux/arm64, which is what the task definition runs
aws ecr get-login-password | docker login --username AWS --password-stdin "$(terraform output -raw ecr_repository_url)"
docker buildx build --platform linux/arm64 -t "$(terraform output -raw ecr_repository_url):$(git rev-parse --short HEAD)" backend/   # backend/Dockerfile
docker push "$(terraform output -raw ecr_repository_url):$(git rev-parse --short HEAD)"
# then register a revision naming that image and update the service. NOT
# `terraform apply`: the service ignores task_definition changes so CI and Terraform
# do not fight over the image tag.

# frontend
(cd frontend && npm run build)
aws s3 sync frontend/dist "s3://$(terraform output -raw frontend_bucket)" --delete
aws cloudfront create-invalidation --distribution-id "$(terraform output -raw cloudfront_distribution_id)" --paths '/*'
```

Image tags are **immutable** and never `latest`, so a rollback is a tag change rather than
a rebuild and hope.

## Things worth knowing before you rely on this

- **`FREEZEHUB_SECRETS_ENCRYPTION_KEY` is generated once and never rotated.** Losing or
  replacing it makes every stored channel credential and webhook signing secret
  permanently unreadable (`FZ-049`, decision `D-3`). Both it and its Secrets Manager entry
  are marked `prevent_destroy`; do not work around that.
- **`FZ-046` is not built yet.** The backend has no real `IdentityProvider`, so it will
  refuse to start outside the `local` profile — meaning this infrastructure can be created
  but the service will not come up until that ships. This is deliberate sequencing
  (decision `D-4`), not an oversight.
- **The state bucket holds secrets.** Treat access to it as access to the database.
- **Tasks in a public subnet and single-AZ RDS**, both deliberate cost choices for beta
  (`D-28`). The task's boundary is its security group rather than its subnet, and neither
  choice is appropriate behind an availability commitment.
- **Nothing here has been applied.** The configuration validates and is formatted; it has
  never been run against an AWS account, so plan-time and apply-time errors are still
  possible. Read the first plan carefully.

## Rough monthly cost

Order of magnitude, beta scale, before data transfer. This is the posture `D-28`
decided, not the one `FZ-063` wrote: **two tasks in a public subnet, and no NAT gateway.**

| | |
|---|---|
| ECS Fargate, 2 × 0.5 vCPU / 1 GB, ARM | ~$29 |
| ALB | ~$16 |
| RDS `db.t4g.micro`, single-AZ, 20 GB | ~$14 |
| Route 53, Secrets Manager, ECR, CloudWatch, S3 | ~$5 |
| CloudFront | free tier: 1 TB out, 10M requests |
| Cognito Lite | free to 10,000 MAU |
| **Total** | **~$64/month** |

**The NAT gateway is gone, and it was the largest line.** It cost about $32 a month plus data
processing, and it existed only so tasks in *private* subnets could reach the internet. A
Fargate task in a **public** subnet with a public IP reaches it through the internet gateway
and needs no NAT at all. The database stays private and is reached over the VPC either way,
and the task's port stays closed to everything but the load balancer's security group — so
the boundary moves from the subnet to the security group rather than disappearing.

For a pre-customer beta that is the right trade, and it is worth revisiting when there is
something to protect. A security review asking for tasks off public addressing is exactly
that moment, and the NAT going back in is priced as the last rung of the ladder.

**It is not free, and the cost is easy to miss.** AWS charges for public IPv4 addresses in
use — roughly $0.005 an hour, about $3.65 a month each — so two tasks add about $7 that the
private-subnet posture did not pay. Whether the load balancer's own addresses are billed the
same way is worth confirming before the first invoice; at this scale it is the difference
between ~$64 and ~$71.

**Every figure here is list-price arithmetic.** `OI-15`'s original $96 baseline came from AWS
Cost Explorer over four months and reconciles exactly, including the ARM64 discount that makes
two Fargate tasks $29 rather than $36. Nothing else in this table has been invoiced: `FZ-123`
records the first real bill against it, which is the only number that settles it.
