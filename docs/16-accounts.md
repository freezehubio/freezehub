# FreezeHub — AWS Accounts, Users and Roles

## Purpose

How to create the AWS accounts FreezeHub deploys into, and who is allowed to do what once
they exist.

`FZ-138` says **what** must exist and `OI-32` says why. This is the **how**, written as a
sequence somebody can follow once, because it is done once and then relied on for years.

**Nothing here is Terraform.** The estate in `infra/` assumes these accounts already exist —
it cannot create the account it runs in, and the role it assumes has to predate it.

## Why two accounts, and why neither is your personal one

**Start from a fresh account, not the one you already have.** An earlier version of this
guide said to make the existing personal account the management account. That is worse than
it sounds: the management account owns the Organization, can create and close member
accounts, and reaches into any of them through `OrganizationAccountAccessRole`. Making it
the personal account leaves a personal identity in permanent, unremovable control of
production — which is `OI-32`'s objection moved up a level rather than answered.

`freezehubio@gmail.com` is the right owner, and creating it was the right instinct.

**Leave the personal account alone.** Do not invite it into the Organization. It holds
$0.007 of S3 (`OI-15`) and nothing that matters; stranding it costs nothing and keeps the
separation total. When a company exists, transferring the product means changing a payment
method and a root email on an account that has only ever held the product — not
disentangling it from someone's personal history.

Two accounts, both free — AWS Organizations costs nothing and member accounts cost nothing;
you pay only for resources:

| | Root email | Holds | Runs |
|---|---|---|---|
| **Management** | `freezehubio@gmail.com` | The Organization, consolidated billing | Nothing. No workloads, ever |
| **Production** | `freezehubio+prod@gmail.com` | Everything in `infra/` | The product |

The management account is deliberately empty. Anything running there is something an
Organization-level credential could reach, and that credential can create accounts.

**Each AWS account needs its own unique root email.** Plus-addressing works and all of it
arrives in one inbox. This is why the plain address goes to management: spend it on
production and management needs a second mailbox.

**The one irreversible part is the region, not the accounts.** A Cognito user pool is
region-bound and `users.external_subject` stores the `sub` it issues, so `D-32`'s choice of
`us-east-1` has to be made before the first apply, not after (`FZ-135`).

---

## 1. The management account

**This is a brand-new AWS account.** Sign up at `aws.amazon.com` with:

| | |
|---|---|
| Root email | `freezehubio@gmail.com` |
| Account name | `freezehub-management` |

It needs a payment method even though it will run nothing — the Organization's consolidated
bill lands here.

Once in: **Organizations → Create an organization**, choosing **All features**, not
consolidated billing only. Identity Center and service control policies both require it, and
switching later is a support ticket rather than a setting.

## 2. The production account

**Organizations → Add an AWS account → Create an AWS account**.

| | |
|---|---|
| Account name | `freezehub-production` |
| Email | `freezehubio+prod@gmail.com` |
| Role name | leave the default `OrganizationAccountAccessRole` |

Created this way it is a member account from birth — no separate signup, no second payment
method, and billing rolls up to management automatically.

`OrganizationAccountAccessRole` is created for you and is assumable from the management
account. It is the break-glass path in §6; do not delete it.

### The free tier changed, and it matters which plan you land on

**AWS replaced the twelve-month free tier with credits.** A new account now gets **$100 in
credits immediately and up to $100 more** as you use services — up to $200 over six months —
alongside the always-free monthly allowances on 30-odd services. `OI-15`'s note about a
"twelve-month window expired in 2023" describes the old model and no longer applies.

Two things to check at creation, because neither is easy to change afterwards:

- **Whether a member account created inside an Organization gets its own credits.** AWS does
  not document this on the free-tier page, and it is worth knowing before you create the
  account rather than after. At ~$18 a month (`D-35`), $200 is most of a year.
- **Whether the production account is on the Free plan or the Paid plan.** They behave
  differently when credits run out, and **production must not sit on a plan that can suspend
  it.** Move it to Paid before anything real depends on it — a box that stops because a
  credit balance hit zero blocks every customer's deployments (`freeze-check.sh` fails
  closed).

## 3. Secure both roots, then stop using them

For **each** account, signed in as root:

1. **Enable MFA.** A hardware key if you have one.
2. **Delete any root access keys.** There should be none; if there are, that is the single
   most valuable credential in the account sitting in a file somewhere.
3. Set a strong unique password and put it in a password manager.
4. In the management account only: **Billing → IAM access to billing → activate**, so the
   admin role below can read costs without anyone signing in as root.

**Then put root away.** Root is for two things: the break-glass path, and the handful of
operations AWS only permits as root (closing an account, changing its email). Neither is
routine.

**And put MFA on `freezehubio@gmail.com` itself.** It can reset both roots *and* the GitHub
organization. It is the strongest credential in the system and the only one with no recovery
path above it.

## 4. Identity Center, not IAM users

**IAM Identity Center**, in the management account, `us-east-1`. It is free.

The alternative is an IAM user with an access key in `~/.aws/credentials`. That is a
long-lived credential on a laptop, which is the same objection `infra/README.md` raises about
root keys — it cannot be scoped down once issued, cannot be rotated without coordination, and
survives the laptop being lost. Identity Center issues **temporary** credentials, expiring on
a schedule, and costs nothing.

It is also the piece that does not need redoing when a second person arrives.

**Identity Center → Enable**, then:

1. **Users →** create one for yourself. Enable MFA on it.
2. **Permission sets →** create `FreezeHubAdmin`, with the AWS managed policy
   `AdministratorAccess` and an **8-hour** session.
3. **AWS accounts →** select `freezehub-production`, assign your user the `FreezeHubAdmin`
   permission set.

Then locally:

```bash
aws configure sso
#   SSO start URL: from the Identity Center dashboard
#   SSO region:    us-east-1
#   profile name:  freezehub-prod

aws sso login --profile freezehub-prod
aws sts get-caller-identity --profile freezehub-prod   # confirms the production account id
export AWS_PROFILE=freezehub-prod
```

Every `terraform` and `aws` command in `infra/README.md` and `14-operations.md` works from
here, including `aws ssm start-session`.

### On Terraform running as administrator

`FreezeHubAdmin` is administrator-equivalent and Terraform runs as it. That is a deliberate
compromise and worth naming rather than dressing up: **Terraform creates IAM roles and
policies**, so a genuinely least-privilege Terraform role needs `iam:CreateRole` and
`iam:PutRolePolicy`, which is escalation to anything it can create. Scoping it properly is a
real project and it buys little while one person holds the credential anyway.

What makes it acceptable here is everything around it: the credential is temporary, MFA
protects issuing it, and CloudTrail records what it did. Revisit when somebody who should not
be an administrator needs to run an apply — which is the same trigger `D-23` names for
building a provisioning console.

## 5. The roles the estate creates, and what each may do

These are **created by Terraform**, not by hand. Listed so the segregation is legible in one
place — none of them can do another's job, which is the point.

| Role | Assumed by | May do | May **not** do |
|---|---|---|---|
| `freezehub-beta-github-deploy` | GitHub Actions, by OIDC | Push an image, publish the SPA, invalidate the cache, send one SSM document to a tagged instance; on ECS, update the service | Change infrastructure. No `CreateService`, no `DeleteService` |
| `freezehub-beta-instance` | The EC2 box | Pull **one** repository, read **one** environment's SSM parameters, **write** backups | Read the backups back. Reach any other environment's secrets |
| `freezehub-beta-task` | The Fargate task, on ECS | The application's own AWS calls | — |
| `freezehub-beta-execution` | The ECS agent | Pull the image, fetch secrets at start | Anything at runtime |
| `OrganizationAccountAccessRole` | The management account | Everything | — (which is why it is break-glass only) |

**No IAM users exist anywhere in this design, and no access keys.** GitHub authenticates by
OIDC (`FZ-064`), the instance by its profile, and you by Identity Center. The only long-lived
secrets in the system are the ones the application itself holds, and those are SSM
SecureStrings encrypted at rest (`D-3`).

## 6. Break-glass

Identity Center can break — a misconfigured permission set, an expired directory, an outage.
There are two ways back in and both should be tested **now**, while nothing is at stake:

1. **From the management account**, assume `OrganizationAccountAccessRole` into production:
   ```bash
   aws sts assume-role \
     --role-arn arn:aws:iam::<production-account-id>:role/OrganizationAccountAccessRole \
     --role-session-name breakglass
   ```
2. **Production account root**, with its MFA device.

Write the production account id, the management account id and the root emails somewhere
that does not depend on AWS being reachable. A recovery path nobody has walked is a recovery
path that does not exist — the same argument `FZ-155` makes about restores.

## 7. Auditing

**CloudTrail Event history is on by default, free, and retains 90 days.** That is what makes
`FZ-152`'s claim true — every SSM session and every deploy command is recorded, with the
identity that made it.

Ninety days is enough for the beta. A trail delivering to S3 is what gives longer retention
and is worth adding before a customer's security questionnaire asks, not after.

Worth knowing what it does *not* cover: CloudTrail records the API call that started a
session, not what was typed inside it. For that, SSM session logging to S3 or CloudWatch is a
separate setting — unnecessary while one person has access, and the first thing to turn on
when that stops being true.

## 8. Optional, and cheap

A **service control policy** on the Organization denying `organizations:LeaveOrganization`
and `cloudtrail:StopLogging` costs nothing and closes two ways an account can be quietly
detached from its own audit trail. Not required for a solo beta; five minutes if you want it.

---

## What this unblocks

With both accounts in place and `AWS_PROFILE` set:

```
bootstrap/ → shared/ → FZ-046 → singlebox/ → deploy → restore drill
```

`shared/` creates the Cognito pool, which is what `FZ-046` needs to exist before it can be
written against anything real (`D-4`). `infra/README.md` § *Three modules, and which ones you
apply* has the rest.

**Also required and not created here: a domain, and a Route 53 hosted zone that already
delegates it.** Both certificates validate through that zone, so an apply hangs without it.
It is the one prerequisite in `infra/README.md` that cannot be satisfied inside AWS alone.
