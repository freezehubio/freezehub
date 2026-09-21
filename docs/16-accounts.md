# FreezeHub — AWS Accounts, Users and Roles

## Purpose

How to create the AWS account FreezeHub deploys into, and who is allowed to do what once
it exists.

`FZ-138` says **what** must exist and `OI-32` says why. This is the **how**, written as a
sequence somebody can follow once, because it is done once and then relied on for years.

**Nothing here is Terraform.** The estate in `infra/` assumes the account already exists —
it cannot create the account it runs in, and the credential it runs as has to predate it.

## The shape, and why it is one account and not two

Two earlier versions of this guide were wrong in opposite directions. The first said to use
the **existing personal account** as the management account of an Organization. The second
fixed the personal-account half and kept the Organization. AWS does not allow the second
either, not on a new account, and the operator found that out by trying it.

### What AWS actually does

Three rules, each verifiable on AWS's own pages, that together decide the shape:

1. **Joining an Organization destroys the free credits.** *"When your account joins an AWS
   Organization … your Free Tier credits expire immediately, and your account will be
   ineligible to earn more AWS Free Tier credits"* — [Free Tier
   FAQs](https://aws.amazon.com/free/free-tier-faqs/). The same page: the free account plan
   *"will automatically be upgraded to a paid plan"*, which is the message the operator hit.
2. **The credits are not what the Free plan is for.** Both plans grant the same **$100 at
   signup plus up to $100 earned** — [Choosing a
   plan](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html)
   says so in its comparison table. Upgrading to Paid does not forfeit them.
3. **Without an Organization there is no Identity Center access to the account.** A
   standalone account can enable an Identity Center *account instance*, but *"Account
   instances do not support permission sets and therefore do not support access to AWS
   accounts"* — [Account
   instances](https://docs.aws.amazon.com/singlesignon/latest/userguide/account-instances-identity-center.html).
   Account instances do applications, not accounts.

### So the choice is a real one, with a price on it

| | Free credits | The credential on your laptop | Separation |
|---|---|---|---|
| **One standalone account** | **kept — up to $200** | An IAM user's access key, usable only with MFA | None |
| **Two accounts, Organization** | **forfeited on day one** | Identity Center, temporary, no key | Management / production |

At `D-35`'s **$18 a month**, $200 is **roughly eleven months of the box** — paid up front for
account separation that, today, protects one person and zero customers. That is the
"speculative infrastructure" the MVP constraints exclude, bought with the only money the
project has.

**One standalone account. Upgrade it to Paid. Add the Organization later.** `D-36` records
this and §7 is the route back.

**Deferring costs nothing extra, which is the tell.** The credits are destroyed whenever you
create the Organization — this month or next year. Doing it once they are spent is the same
price, minus the eleven months.

**What the personal account has to do with it: nothing, and that was the real defect.**
`OI-32`'s objection was a personal identity in permanent control of production. A fresh
account on `freezehubio@gmail.com` answers that completely. The Organization was a second
layer on top of the answer, not the answer.

**Leave the personal account alone.** Do not invite it, do not use it. It holds $0.007 of S3
(`OI-15`) and stranding it costs nothing.

### What choosing one account actually costs

Named here rather than buried, because §7 is how they come back:

- **A long-lived access key exists** — one, on one laptop, useless without a TOTP (§3).
- **Root is the only break-glass.** There is no `OrganizationAccountAccessRole` to assume
  from somewhere else (§5).
- **No service control policies.** They are an Organization feature; §6's optional guard
  rails are not available.
- **Billing and workloads share an account.** Irrelevant at one account, not at three.

**The one irreversible part is the region, not any of this.** A Cognito user pool is
region-bound and `users.external_subject` stores the `sub` it issues, so `D-32`'s choice of
`us-east-1` has to be made before the first apply, not after (`FZ-135`).

---

## 1. Create the account

Sign up at `aws.amazon.com` with:

| | |
|---|---|
| Root email | `freezehubio@gmail.com` |
| Account name | `freezehub-production` |

`freezehubio+prod@gmail.com` is no longer needed. Keep plus-addressing in reserve: **each AWS
account needs its own unique root email**, so it is what a management account uses in §7.

### Then upgrade to the Paid plan, before anything real depends on it

**The Free account plan closes your account.** Not throttles — closes. *"Your free account
plan ends after six months or when your credits are fully used — whichever occurs first …
After your free account plan expires, your account closes automatically, and you lose access
to your resources and data."* AWS keeps the content 90 days.

For a product whose connector **fails closed**, that is not a billing event. A box that stops
because a six-month timer elapsed blocks every customer's deployments until someone notices.

The Paid plan changes nothing about the credits — they apply to the bill first and you pay
only the excess, which at $18 a month is $0 until they are gone. It removes the closure, and
it removes the Free plan's restriction to *select* services.

Do it at signup if the option is offered, otherwise **Billing → Upgrade plan** immediately.

### Check the credits' expiry date

**Billing and Cost Management → Credits.** The $200 is only eleven months of runway if the
credits live that long; they carry their own expiry and it caps what is actually usable.
Write the date down — it is a cost event with a date, which is the kind that arrives
unnoticed.

## 2. Secure root, then stop using it

Signed in as root:

1. **Enable MFA.** A hardware key if you have one.
2. **Delete any root access keys.** There should be none; if there are, that is the single
   most valuable credential in the account sitting in a file somewhere.
3. Set a strong unique password and put it in a password manager.
4. **Billing → IAM access to billing → activate**, so the admin role below can read costs
   without anyone signing in as root.

Then create the user and role in §3 — **that is the last routine thing root does.** With no
Organization, root is also the only break-glass path (§5), so it matters more here than it
would in the two-account shape.

**And put MFA on `freezehubio@gmail.com` itself.** It can reset the account root *and* the
GitHub organization. It is the strongest credential in the system and the only one with no
recovery path above it.

## 3. One IAM user, which on its own can do nothing

Identity Center cannot grant access to the account without an Organization (rule 3 above), so
this is an IAM user. The design makes the access key worthless by itself: the user's **only**
permission is to assume a role, and the role refuses without MFA.

**As root, once:**

**a. Role `FreezeHubAdmin`** — permissions `AdministratorAccess`, **maximum session duration
8 hours**, trust policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "AWS": "arn:aws:iam::<account-id>:user/<your-user>" },
    "Action": "sts:AssumeRole",
    "Condition": {
      "Bool": { "aws:MultiFactorAuthPresent": "true" },
      "NumericLessThan": { "aws:MultiFactorAuthAge": "28800" }
    }
  }]
}
```

**b. User `<your-user>`** — console access, MFA enabled, and one inline policy. Nothing else,
no managed policies:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "sts:AssumeRole",
    "Resource": "arn:aws:iam::<account-id>:role/FreezeHubAdmin"
  }]
}
```

**c. An access key** for that user, stored in `~/.aws/credentials`.

Then locally:

```ini
# ~/.aws/credentials
[freezehub-cli]
aws_access_key_id     = AKIA...
aws_secret_access_key = ...
```

```ini
# ~/.aws/config
[profile freezehub-cli]
region = us-east-1

[profile freezehub-prod]
region           = us-east-1
role_arn         = arn:aws:iam::<account-id>:role/FreezeHubAdmin
source_profile   = freezehub-cli
mfa_serial       = arn:aws:iam::<account-id>:mfa/<your-user>
duration_seconds = 28800
```

```bash
export AWS_PROFILE=freezehub-prod
aws sts get-caller-identity     # prompts for the TOTP, then prints the FreezeHubAdmin ARN
```

The CLI caches the assumed-role credentials until they expire and re-prompts after that.
Every `terraform` and `aws` command in `infra/README.md` and `14-operations.md` works from
here, including `aws ssm start-session`.

**What this is and is not.** The key cannot read S3, cannot start an instance and cannot
touch IAM — `sts:AssumeRole` is the whole grant, and it fails without a TOTP from a device
that is not the laptop. What remains is real and is the price of no Organization: the key
does not expire on its own, so **rotate it on the credits' expiry date** (§1) and keep the two
events together. Identity Center's temporary credentials are strictly better, and §7 is how
you get them.

**No key goes near CI.** GitHub Actions authenticates by OIDC (`FZ-064`) and the box by its
instance profile. This key exists on exactly one laptop, belonging to the person who holds
root anyway.

### On Terraform running as administrator

`FreezeHubAdmin` is administrator-equivalent and Terraform runs as it. That is a deliberate
compromise and worth naming rather than dressing up: **Terraform creates IAM roles and
policies**, so a genuinely least-privilege Terraform role needs `iam:CreateRole` and
`iam:PutRolePolicy`, which is escalation to anything it can create. Scoping it properly is a
real project and it buys little while one person holds the credential anyway.

What makes it acceptable here is everything around it: the session is temporary, MFA gates
issuing it, and CloudTrail records what it did. Revisit when somebody who should not be an
administrator needs to run an apply — which is the same trigger `D-23` names for building a
provisioning console.

## 4. The roles the estate creates, and what each may do

These are **created by Terraform**, not by hand. Listed so the segregation is legible in one
place — none of them can do another's job, which is the point.

| Role | Assumed by | May do | May **not** do |
|---|---|---|---|
| `freezehub-beta-github-deploy` | GitHub Actions, by OIDC | Push an image, publish the SPA, invalidate the cache, send one SSM document to a tagged instance; on ECS, update the service | Change infrastructure. No `CreateService`, no `DeleteService` |
| `freezehub-beta-instance` | The EC2 box | Pull **one** repository, read **one** environment's SSM parameters, **write** backups | Read the backups back. Reach any other environment's secrets |
| `freezehub-beta-task` | The Fargate task, on ECS | The application's own AWS calls | — |
| `freezehub-beta-execution` | The ECS agent | Pull the image, fetch secrets at start | Anything at runtime |
| `FreezeHubAdmin` | You, from §3, with MFA | Everything | — (which is why the key alone cannot assume it) |

**Exactly one IAM user exists, and it is the one in §3.** Everything automated authenticates
without a stored secret: GitHub by OIDC, the instance by its profile. The only other
long-lived secrets are the ones the application itself holds, and those are SSM
SecureStrings encrypted at rest (`D-3`).

## 5. Break-glass

**Root, with its MFA device, is the only way back in.** That is the sharpest consequence of
one account: the two-account shape has `OrganizationAccountAccessRole` assumable from
somewhere else, and this one has nothing behind root.

So the things that make root work have to be true *now*, while nothing is at stake:

- The root MFA device is not the same physical object as the IAM user's, or one lost phone
  takes both paths.
- Recovery codes for `freezehubio@gmail.com` are stored offline.
- The account id, the root email and the MFA backup live somewhere that does not depend on
  AWS being reachable.

Sign in as root once, deliberately, to confirm it works. A recovery path nobody has walked is
a recovery path that does not exist — the same argument `FZ-155` makes about restores.

## 6. Auditing

**CloudTrail Event history is on by default, free, and retains 90 days.** That is what makes
`FZ-152`'s claim true — every SSM session and every deploy command is recorded, with the
identity that made it.

Ninety days is enough for the beta. A trail delivering to S3 is what gives longer retention
and is worth adding before a customer's security questionnaire asks, not after.

Worth knowing what it does *not* cover: CloudTrail records the API call that started a
session, not what was typed inside it. For that, SSM session logging to S3 or CloudWatch is a
separate setting — unnecessary while one person has access, and the first thing to turn on
when that stops being true.

**Service control policies are not available here.** Denying `cloudtrail:StopLogging` and
`organizations:LeaveOrganization` needs an Organization. It moves to §7's list.

## 7. When to add the Organization, and how

**Any one of these:** the credits are exhausted or expired; a second person needs AWS access;
a customer security review asks whether production is isolated. The first is a date you
already wrote down in §1.

The move, once triggered:

1. **Create a new account** on `freezehubio+mgmt@gmail.com` and create the Organization from
   *it*, choosing **All features**. It stays empty — an account that owns the Organization can
   create and close members and reach into any of them, so nothing should run in it.
2. **Invite `freezehub-production`** into that Organization. This is what expires its
   credits, which by now are gone anyway.
3. **Enable Identity Center** in the management account, `us-east-1`. Create a user, a
   `FreezeHubAdmin` permission set with `AdministratorAccess` and an 8-hour session, and
   assign it to the production account.
4. `aws configure sso --profile freezehub-prod`, replacing the `role_arn`/`mfa_serial`
   profile in §3.
5. **Delete the IAM user and its access key.** This is the step that makes the migration
   worth doing; leaving the key behind keeps the exposure and pays for nothing.
6. Add the service control policies from §6 — deny `cloudtrail:StopLogging` and
   `organizations:LeaveOrganization`.

**Do not create the Organization from the production account.** It would become the
management account, which is the shape §"The shape" rejects: the account running the product
would also be the one that can create and close accounts. A new empty account is the point.

**Nothing in `infra/` changes.** There is no account id anywhere in it (`OI-32`) — `locals.tf`
reads `aws_caller_identity`. The migration is a credentials change, not a redeploy.

---

## What this unblocks

With the account in place and `AWS_PROFILE=freezehub-prod`:

```
bootstrap/ → shared/ → FZ-046 → singlebox/ → deploy → restore drill
```

`shared/` creates the Cognito pool, which is what `FZ-046` needs to exist before it can be
written against anything real (`D-4`). `infra/README.md` § *Three modules, and which ones you
apply* has the rest.

**Also required and not created here: a domain, and a Route 53 hosted zone that already
delegates it.** Both certificates validate through that zone, so an apply hangs without it.
It is the one prerequisite in `infra/README.md` that cannot be satisfied inside AWS alone.
