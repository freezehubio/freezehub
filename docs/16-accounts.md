# FreezeHub — AWS Accounts, Users and Roles

## Purpose

How to create the AWS account FreezeHub deploys into, and who is allowed to do what once
it exists.

`FZ-138` says **what** must exist and `OI-32` says why. This is the **how**.

**Nothing here is Terraform.** The estate in `infra/` assumes the account already exists —
it cannot create the account it runs in, and the credential it runs as has to predate it.

---

## 0. Which AWS you are on

**Read this first. It decides everything below, and there are two different AWSes.**

In 2026 AWS split sign-up into two products. They produce different account models, different
identities, and different limits — and most writing about AWS, including earlier revisions of
this guide, describes only the older one.

| | **Sign up for AWS (new)** | **Sign up for AWS (advanced)** |
|---|---|---|
| You sign in with | A Google/GitHub/Apple/Amazon login, via **AWS Builder ID** | An email and password you set |
| Your account is | A **project** inside an Organization **AWS owns and manages** | A standalone account, or one in an Organization you own |
| Human access | **Team members** (workforce identities in an AWS-run Identity Center) | IAM users, or your own Identity Center |
| CLI credentials | **`aws login`** — browser flow, temporary, rotated every 15 minutes | Access keys, or `aws configure sso` |
| Guardrails | SCPs **AWS applies and you cannot modify** | None but your own |

**You are on the new experience if** `https://settings.aws.com` opens, the console has a
*Manage projects* menu, or creating an IAM user shows this:

> Los usuarios de IAM solo se deben utilizar para el acceso mediante programación. Si desea
> conceder acceso a usuarios humanos, puede hacerlo en la página del equipo.

That message is not advice. It is an SCP: **`iam:*LoginProfile*` is denied**, so an IAM user
*cannot* be given a console password on this experience. §3 of the previous revision of this
guide could not be completed, and that is why.

**The rest of this document assumes the new experience**, because that is what FreezeHub's
account is on. `D-36` and `D-37` record the decisions; the old shape survives only as §6.

---

## 1. What the new experience already gives you

This is the part worth understanding before changing anything, because it silently answers
questions `FZ-168` spent a whole story on.

**An AWS Organization already exists.** Your projects are member accounts of an Organization
AWS created and manages. So `D-36`'s central trade-off — *keep the $200 of free credits, or
have an Organization* — **is moot**. You have one. Nothing was forfeited to get it, and there
is nothing left to buy.

**IAM Identity Center already exists**, with AWS Builder ID as its identity source. Team
members are workforce identities in it. You do not create it; you cannot create it
(`sso:CreateInstance` is denied).

**There are no access keys.** `aws login` opens a browser, you sign in the way you sign in to
the console, and the CLI receives role session credentials that it rotates every 15 minutes,
valid up to the role's session duration (12 hours maximum). It needs AWS CLI **2.32.0 or
later** and the `SignInLocalDevelopmentAccess` managed policy on the principal.

That erases the single real residual `D-36` accepted. The guide used to spend two pages
making one long-lived access key survivable; on this experience the key does not exist.

**Every service FreezeHub needs is on the Free Tier list.** Checked individually against
AWS's published list rather than assumed: EC2, RDS, Cognito, CloudFront, Route 53, ACM,
Systems Manager, ECR, S3, SES, ELB, ECS, CloudWatch, CloudTrail, Secrets Manager, KMS, STS,
VPC, EBS, IAM. `D-35`'s one-box posture is buildable here as designed.

**The region is `us-east-2`, and `us-east-1` is good for one thing only.** `RegionFloor`
permits `us-east-1` and `us-west-2` alongside your project's Region — but a *second* policy,
`UsEast1Partitional`, then denies everything in `us-east-1` except a short list of global
services. Cognito, RDS, ECR, SSM, load balancers and `s3:CreateBucket` are all denied there;
`acm`, `cloudfront`, `route53`, `iam`, `sts`, `kms` and `logs` are allowed.

So **CloudFront's certificate works in `us-east-1`, which is the one thing that must be
there**, and everything else runs in `us-east-2`. `D-32` was amended for this (`FZ-171`)
after an earlier revision of this section claimed the opposite from reading `RegionFloor`
alone. Ohio is still the United States, so the residency answer is unchanged.

**Spend limits exist** on the paid plan, per project: a real monthly ceiling enforced by SCP,
which ordinary AWS does not offer. For a pre-revenue solo founder that is worth more than it
sounds.

---

## 2. What it takes away, and the one that stops the deploy

Three restrictions, from SCPs marked **"cannot be modified"** that apply on the Free Tier
*and* the Paid Plan. Only *activating advanced features* removes them.

**`iam:*Provider*` is denied. This blocks the deploy pipeline.**

`infra/shared/github-oidc.tf:7` creates an `aws_iam_openid_connect_provider`, which needs
`iam:CreateOpenIDConnectProvider`. The SCP denies it, so **`terraform apply` on `infra/shared/`
fails**, and with it every workflow: `build-image.yml`, `deploy-frontend.yml` and
`deploy-singlebox.yml` all authenticate by OIDC (`FZ-064`) and have no other path.

This is the decision in §3. Everything else here is manageable; this is not.

**`iam:*LoginProfile*` is denied.** IAM users cannot be given console access — the message in
§0. Human access is team members, which is the better answer anyway.

**`iam:CreateGroup`, `iam:*Alias*`, `iam:*Organizations*` are denied**, and roles under
`/managed/` are protected. Ordinary `iam:CreateRole` is **allowed**, so everything Terraform
creates in §4 is fine.

---

## 3. The decision: activate advanced features, or deploy by hand

**Activating advanced features cannot be reversed.** It hands you the Organization AWS has
been managing, a management account, full IAM, all Regions and all services — and removes
spend limits.

| | **Activate advanced features** | **Stay on the managed experience** |
|---|---|---|
| GitHub Actions OIDC | Works | **Blocked** — deploy by hand |
| `infra/shared/` applies | Yes | No — fails on the OIDC provider |
| Organization | Yours, with a management account | AWS's, opaque |
| Spend limit | **Gone** — budgets and alarms only, which do not stop spend | Kept — a real enforced ceiling |
| SCPs | Yours to write | AWS's, unmodifiable |
| Reversible | **No** | Yes — you can activate later |

### Recommended: activate advanced features, but not yet

**Activate it when you are ready to wire up CI, and not before.** The reasoning:

- It is the only path that preserves the deploy design. The alternative to OIDC is an access
  key in a GitHub secret, which is worse than anything `D-36` contemplated and which
  `FZ-064` exists to avoid.
- **It costs nothing now.** The credits objection in `D-36` died with the discovery that AWS
  already put you in an Organization — controlling it forfeits nothing further. Confirm this
  in **Billing → Credits** before and after; it is the one number worth checking by eye.
- It delivers precisely the separation `OI-32` asked for — a management account holding
  billing and no workloads, production as a member account — which `D-36` deferred only
  because it appeared to cost $200. It no longer does.
- `aws login` continues to work afterwards, so the no-access-key property survives.

**But it is irreversible and it removes your spend limit**, and until the product is
deployed there is nothing for CI to deploy. So the order that loses least:

1. **Now** — build and deploy by hand (§3b). Keep the spend limit while the cost shape is
   unknown.
2. **Before wiring CI** — activate advanced features, then set an AWS Budget with an alarm
   *the same day*, because the enforced ceiling goes away and a budget only emails you.
3. **Then** — `infra/shared/` applies, OIDC works, the workflows run.

### 3b. Deploying by hand, meanwhile

Everything except the OIDC provider applies. The sequence:

```bash
aws --version                      # needs 2.32.0 or later
aws login                          # browser; temporary credentials, rotated every 15 min
aws sts get-caller-identity        # confirm the account and role
```

Then `bootstrap/` and `singlebox/` as `infra/README.md` describes, skipping `shared/`'s
`github-oidc.tf` — and building and pushing the image from the laptop rather than from
`build-image.yml`. `14-operations.md` § *Releasing* names what the workflows would have done.

This is a stopgap and should be named as one: a hand-deploy has no record of what shipped,
which is exactly what `FZ-152` built the workflows to provide.

---

## 4. Team members, and the one you already made

Human access is **team members**, not IAM users. From AWS Settings → **Projects** →
**Actions → Manage team** → **Invite new team member**, by email address. After they accept,
you grant them access to specific accounts in **AWS Account Access Manager**, which assigns
IAM roles in your accounts to Identity Center users.

`freezehubio+admin@gmail.com` is a reasonable second identity: AWS Builder ID is one per
email address, so a separate address is the only way to hold a separate identity. Whether
you need one yet is a different question — with one person, the owner identity already has
the access, and a second identity mostly adds a second MFA device to keep safe. It costs
nothing to keep.

**Put MFA on the Google account behind the Builder ID.** That login can reset everything
below it, and on this experience it *is* the root credential in every sense that matters.

---

## 5. The roles the estate creates, and what each may do

These are **created by Terraform**, not by hand. `iam:CreateRole` is permitted, so all of
these work on the managed experience — except the OIDC provider the first one trusts.

| Role | Assumed by | May do | May **not** do |
|---|---|---|---|
| `freezehub-beta-github-deploy` | GitHub Actions, by OIDC — **needs advanced features** | Push an image, publish the SPA, invalidate the cache, send one SSM document to a tagged instance | Change infrastructure. No `CreateService`, no `DeleteService` |
| `freezehub-beta-instance` | The EC2 box | Pull **one** repository, read **one** environment's SSM parameters, **write** backups | Read the backups back. Reach any other environment's secrets |
| `freezehub-beta-task` | The Fargate task, on ECS | The application's own AWS calls | — |
| `freezehub-beta-execution` | The ECS agent | Pull the image, fetch secrets at start | Anything at runtime |

**No IAM users and no access keys exist anywhere in this design** — and on this experience
that is enforced rather than merely intended. You authenticate with `aws login`, GitHub by
OIDC once available, the instance by its profile. The only long-lived secrets are the
application's own, held as SSM SecureStrings encrypted at rest (`D-3`).

## 6. The classic experience, for reference

If FreezeHub were on **Sign up for AWS (advanced)** instead, the shape `D-36` chose applies:
one standalone account, upgraded to the Paid plan, one IAM user whose only permission is
`sts:AssumeRole` on an MFA-gated administrator role, and an Organization deferred. Five things
that revision got wrong, and that still catch people there:

- **The user must exist before the role.** IAM *"transforms the ARN to the user's unique
  principal ID when you save the policy"*, so a trust policy naming a user that does not yet
  exist returns `MalformedPolicyDocument: Invalid principal in policy`.
- **`mfa_serial` needs a TOTP code**, not a passkey. The CLI *"prompts the user to enter the
  one-time password (OTP) that the MFA device provides"*, and a FIDO2 key cannot produce one.
- **`mfa_serial` names the MFA *device*, not the user.** The console defaults one to the
  other and they do not have to match; read the ARN off the user's Security credentials tab.
- **A role's maximum session duration defaults to 1 hour**, so `duration_seconds = 28800`
  is rejected until it is raised to 8.
- **The billing setting is called Activate IAM Access**, only root can change it, and AWS
  is explicit that it grants nothing on its own — a policy still has to allow the actions.

Neither of the first two applies to the new experience, where there is no IAM user to make.
`FZ-169` found all five by walking the sequence; they are kept because activating advanced
features (§3) restores full IAM, and a reader may end up making roles by hand after all.

## 7. Break-glass

**The Builder ID login is the way back in** — the Google account, with its MFA and its
recovery codes stored offline. There is no account root password to fall back on in the way
the classic experience has one, because you never set one.

After activating advanced features you also gain a management account with its own root, and
`OrganizationAccountAccessRole` into members. At that point write down the management account
id, its root email and its MFA backup somewhere that does not depend on AWS being reachable,
and sign in once deliberately to confirm it works. A recovery path nobody has walked is a
recovery path that does not exist — `FZ-155`'s argument about restores, applied to access.

## 8. Auditing

**CloudTrail Event history is on by default, free, and retains 90 days.** That is what makes
`FZ-152`'s claim true — every SSM session and every deploy command is recorded, with the
identity that made it. Multi-Region and organization trails are not available before advanced
features; Event history in the working Region is.

Ninety days is enough for the beta. A trail delivering to S3 gives longer retention and is
worth adding before a customer's security questionnaire asks, not after.

CloudTrail records the API call that started an SSM session, not what was typed inside it.
Session logging to S3 or CloudWatch is a separate setting — unnecessary while one person has
access, and the first thing to turn on when that stops being true.

---

## What this unblocks, and in what order

With the project in place and `aws login` working, one thing still gates everything:

```
domain → hosted zone → shared/ → Cognito pool → FZ-046 → singlebox/ → deploy → CI
                                                                                ↑
                                                                  needs advanced features (§2)
```

**The domain is first, and not optionally.** `infra/shared/` has two required variables with
no default — `domain_name` and `hosted_zone_id` — and creates an
`aws_acm_certificate_validation` that blocks until DNS resolves. Nothing downstream starts
without it.

`shared/` then creates the Cognito pool, which is what `FZ-046` needs before it can be
written against anything real (`D-4`). `infra/README.md` § *Three modules, and which ones
you apply* has the rest.

**The OIDC restriction bites last, not at `shared/`.** An earlier revision of this section
put "needs advanced features" under `shared/`, which reads as though the whole module is
unreachable. It is not: `github-oidc.tf` is the only part denied, and it is separable —
nothing outside it references its resources except two outputs. As written `shared/` does
still fail, so separating it is a prerequisite; `OI-43` scopes that to two small options.
The decision is due when CI is wired up, which is after a deploy exists.

**`route53domains:*` is permitted**, so the domain can be registered inside the account
rather than delegated from elsewhere. A hosted zone is about $0.50 a month.
