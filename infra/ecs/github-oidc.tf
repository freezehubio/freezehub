# The ECS half of the deploy role (FZ-159).
#
# Attached to the role ../shared creates, as a second inline policy rather than as part of
# the first one. That is what lets the shared estate exist without this one: on the
# single-box posture (D-35) this module is never applied, and the role simply does not
# carry these statements.

data "aws_iam_policy_document" "github_deploy_ecs" {
  # Register a revision and point the service at it. Deliberately no ecs:CreateService or
  # ecs:DeleteService: deploying replaces an image, it does not reshape infrastructure.
  statement {
    sid       = "RegisterTaskDefinitions"
    actions   = ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition"]
    resources = ["*"] # task definitions are versioned; ARNs are not known ahead of time
  }

  statement {
    sid       = "UpdateTheService"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = [aws_ecs_service.backend.id]
  }

  # A task definition names the execution and task roles, so registering one means passing
  # them. Scoped to exactly those two, because iam:PassRole on "*" is escalation to
  # anything either role could ever do.
  statement {
    sid       = "PassTheTaskRoles"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.execution.arn, aws_iam_role.task.arn]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "github_deploy_ecs" {
  name   = "${local.name}-github-deploy-ecs"
  role   = var.github_deploy_role_name
  policy = data.aws_iam_policy_document.github_deploy_ecs.json
}
