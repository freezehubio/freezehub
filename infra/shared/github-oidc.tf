# Lets GitHub Actions deploy without any stored AWS credentials (FZ-064).
#
# **Off by default, because this account cannot create it** (OI-43). AWS's new sign-up
# experience denies `iam:*Provider*` through a service control policy that cannot be
# modified, so an apply with this enabled fails on the first resource. Activating advanced
# features (D-37) lifts the denial; then set github_oidc_enabled = true and apply again.
#
# A toggle rather than a separate root module (FZ-174): these resources share the shared
# estate's lifecycle and are absent only because of an account capability we intend to
# remove. A module boundary would assert a difference that is not there.
#
# The runner exchanges a short-lived OIDC token for this role. Nothing long-lived exists
# to leak, nothing has to be rotated, and revoking access is deleting a role rather than
# hunting for a key somebody pasted into a secret four months ago.

resource "aws_iam_openid_connect_provider" "github" {
  count = var.github_oidc_enabled ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # AWS validates this provider against its own trust store and no longer relies on the
  # thumbprint, but the argument is still required. These are GitHub's published values.
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd",
  ]
}

data "aws_iam_policy_document" "github_assume_role" {
  count = var.github_oidc_enabled ? 1 : 0

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github[0].arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # THE line that matters. Scoped to this repository and this branch, so only a workflow
    # running here can assume the role. A `sub` left open — `repo:*`, or even this repo
    # with any ref — would let a pull request from a fork deploy to production, or let
    # somebody else's repository assume it outright. This is the whole security of OIDC.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/master"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  count = var.github_oidc_enabled ? 1 : 0

  name               = "${local.name}-github-deploy"
  description        = "Assumed by GitHub Actions to deploy. Cannot change infrastructure."
  assume_role_policy = data.aws_iam_policy_document.github_assume_role[0].json
}

# Everything the deploy role needs that does not depend on a compute posture: push an
# image, publish the SPA, invalidate the cache, and drive the single box through SSM
# (FZ-154). The ECS statements are attached separately by ../ecs, because that estate may
# not exist -- which is the whole point of the split (FZ-159).
data "aws_iam_policy_document" "github_deploy" {
  # Push an image.
  statement {
    sid       = "AuthenticateToEcr"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # this one action genuinely has no resource to scope to
  }

  statement {
    sid    = "PushImages"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [aws_ecr_repository.backend.arn]
  }

  # arbitrary files — a far larger grant than "restart the stack".
  statement {
    sid       = "DeployToTheSingleBox"
    actions   = ["ssm:SendCommand"]
    resources = ["arn:aws:ec2:${var.region}:${data.aws_caller_identity.current.account_id}:instance/*"]

    condition {
      test     = "StringEquals"
      variable = "ssm:ResourceTag/Project"
      values   = ["freezehub"]
    }
  }

  statement {
    sid       = "OnlyTheShellDocument"
    actions   = ["ssm:SendCommand"]
    resources = ["arn:aws:ssm:${var.region}::document/AWS-RunShellScript"]
  }

  # Without this the workflow can only prove the API accepted the command, which is not
  # the same as the deploy having worked (FZ-154).
  statement {
    sid       = "ReadTheOutcome"
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
    resources = ["*"] # invocation ids are not known ahead of time
  }


  # Publish the frontend.
  statement {
    sid       = "PublishTheFrontend"
    actions   = ["s3:PutObject", "s3:DeleteObject", "s3:ListBucket", "s3:GetObject"]
    resources = [aws_s3_bucket.frontend.arn, "${aws_s3_bucket.frontend.arn}/*"]
  }

  statement {
    sid       = "InvalidateTheCache"
    actions   = ["cloudfront:CreateInvalidation"]
    resources = [aws_cloudfront_distribution.frontend.arn]
  }
}
resource "aws_iam_role_policy" "github_deploy" {
  count = var.github_oidc_enabled ? 1 : 0

  name   = "${local.name}-github-deploy"
  role   = aws_iam_role.github_deploy[0].id
  policy = data.aws_iam_policy_document.github_deploy.json
}
