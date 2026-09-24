# What the instance may do, and nothing more (FZ-152).
#
# Three grants, each on a named resource. No wildcards on resources anywhere: the point of
# a scoped instance profile is that a process that escapes the container still cannot read
# another environment's secrets or push an image.

resource "aws_iam_role" "instance" {
  name = "${local.name}-instance"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# Session Manager and Run Command. This is what replaces SSH entirely — shell access and
# deploys both arrive as IAM-authorised API calls recorded in CloudTrail, rather than as a
# connection authenticated by a key somebody holds.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "instance" {
  name = "${local.name}-instance"
  role = aws_iam_role.instance.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # GetAuthorizationToken has no resource to scope to — it is account-level by
        # design. The pull itself is scoped to the one repository.
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "EcrPullThisRepositoryOnly"
        Effect = "Allow"
        Action = [
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchCheckLayerAvailability",
        ]
        Resource = var.ecr_repository_arn
      },
      {
        Sid    = "ReadThisEnvironmentsSecrets"
        Effect = "Allow"
        Action = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
        Resource = [
          "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${local.name}",
          "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${local.name}/*",
        ]
      },
      {
        Sid      = "DecryptThoseParameters"
        Effect   = "Allow"
        Action   = "kms:Decrypt"
        Resource = "*"
        Condition = {
          StringEquals = { "kms:ViaService" = "ssm.${var.region}.amazonaws.com" }
        }
      },
      {
        # Write-only, deliberately. A backup the instance can read back is a backup an
        # attacker on the instance can read back -- and the restore is a human operation
        # anyway (FZ-155), performed with different credentials.
        Sid      = "WriteBackupsNeverReadThem"
        Effect   = "Allow"
        Action   = "s3:PutObject"
        Resource = "${aws_s3_bucket.backups.arn}/*"
      },
      {
        # The identities the backend manages (FZ-046, FZ-082, FZ-211). Scoped to the one
        # pool and to the calls CognitoIdentityProvider makes; it still cannot disable or
        # list the pool, so a foothold here cannot enumerate every customer.
        #
        # DELETE WAS DENIED ON PURPOSE UNTIL FZ-211, and the reason was that a foothold
        # could "quietly remove an administrator". It is granted now because that
        # protection was not real: this box holds the database password, and a foothold
        # that can delete a users row has already locked that person out -- the identity
        # without its row answers 401. Denying the call protected nothing and cost
        # something: FZ-082's compensating delete and its seven-day purge of unverified
        # signups both failed silently, so every abandoned signup held its email address
        # against anyone ever signing up with it again.
        #
        # ../ecs must grant the same list. .github/test/check-cognito-permissions.js fails
        # the build when the backend calls a Cognito operation either posture refuses --
        # which is how FZ-082 shipped a call this file denied.
        Sid      = "ManageUserIdentities"
        Effect   = "Allow"
        Action   = ["cognito-idp:AdminCreateUser", "cognito-idp:AdminGetUser", "cognito-idp:AdminDeleteUser"]
        Resource = var.cognito_user_pool_arn
      },
    ]
  })
}

resource "aws_iam_instance_profile" "main" {
  name = local.name
  role = aws_iam_role.instance.name
}
